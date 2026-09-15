package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.EmailVerificationToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.EmailVerificationTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * D-23 bug found in audit (not reported): {@code AuthService.verifyEmail} read
 * {@code evt.getUsedAt()}, checked it was null in Java, and only wrote {@code usedAt} back at the
 * very end -- classic check-then-act. Two concurrent requests for the same raw token (a
 * double-click, or an email client's link-preview bot fetching the verification URL followed by
 * the real user's own click moments later) could both fetch the row while it was still unused and
 * both pass that check before either wrote anything back.
 *
 * <p>Same deterministic technique {@code ReferralMilestoneConcurrentRedeemRaceIT} already
 * establishes: a {@code @MockitoSpyBean} pauses the first caller right after it has read the row
 * but before it acts on it, so the second caller's own read-act-write cycle genuinely completes
 * first, at the real database -- not via a mocked return value. Reissues the equivalent JPQL query
 * directly through the {@code EntityManager}: Mockito cannot call a "real" method through a spy of
 * an interface-backed Spring Data proxy.
 *
 * <p>Unlike that referral test, the first caller isn't blocked on any database row lock here --
 * check-then-act with no atomic guard means the race window is a bare Java {@code if}, not a
 * lock-holding {@code UPDATE}. So the second caller is left completely free to run to full
 * completion (and commit) while the first is parked; only afterward is the first released, to
 * resume acting on its own now-stale, already-fetched copy of the row.
 */
class AuthServiceVerifyEmailConcurrentRaceIT extends AbstractIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private EntityManager entityManager;

    @MockitoSpyBean private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private static final String FIRST_THREAD = "verify-email-race-first";
    private static final String RAW_TOKEN = "verify-email-race-token";

    @Test
    void twoConcurrentVerifyRequestsForTheSameTokenLeaveExactlyOneAuditRow() throws Exception {
        User user = new User();
        user.setEmail("verify-email-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Verify Email Race IT User");
        user.setEmailVerified(false);
        user = userRepository.save(user);
        UUID userId = user.getId();

        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUserId(userId);
        evt.setTokenHash(TokenHasher.sha256(RAW_TOKEN));
        evt.setExpiresAt(Instant.now().plusSeconds(600));
        emailVerificationTokenRepository.save(evt);

        CountDownLatch firstHasReadButNotActed = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        doAnswer(invocation -> {
            String tokenHash = invocation.getArgument(0);
            List<EmailVerificationToken> rows = entityManager.createQuery(
                            "SELECT t FROM EmailVerificationToken t WHERE t.tokenHash = :h",
                            EmailVerificationToken.class)
                    .setParameter("h", tokenHash)
                    .getResultList();
            Optional<EmailVerificationToken> result = rows.stream().findFirst();
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasReadButNotActed.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(emailVerificationTokenRepository).findByTokenHash(anyString());

        Thread first = new Thread(() -> {
            try {
                authService.verifyEmail(RAW_TOKEN);
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasReadButNotActed.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked, holding its own stale read of the row")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                authService.verifyEmail(RAW_TOKEN);
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "verify-email-race-second");
        second.start();
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(second.isAlive()).as("the second caller must have finished, not hung").isFalse();

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).as("the first caller must have finished, not hung").isFalse();

        assertThat(secondFailure.get()).as("the winner (whichever actually completed first) must not throw").isNull();
        assertThat(firstFailure.get())
                .as("the loser must get a clean rejection for an already-used token, not silently succeed a second time")
                .isInstanceOf(ApiException.class);
        assertThat(((ApiException) firstFailure.get()).getMessage()).contains("already been used");

        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isTrue();
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(userId))
                .as("exactly one EMAIL_VERIFIED audit row must survive, not one per racing request")
                .hasSize(1);
    }

    /**
     * Real-database check, not a guess: {@code verifyEmail()} now runs {@code claimIfUnused}
     * (a real {@code UPDATE}) BEFORE the expiry check, and the method is plain
     * {@code @Transactional} -- no {@code noRollbackFor = ApiException.class}, unlike several
     * sibling methods in this same class. Spring's default rule rolls back the whole transaction
     * on any unchecked exception, so when the expiry check throws {@code ApiException} right
     * after a successful claim, that claim's {@code UPDATE} must be rolled back too -- the token
     * must NOT come out of a failed, expired attempt permanently marked used. Confirmed here by
     * reading {@code usedAt} back with a fresh, separate {@code EntityManager} query (not the
     * caller's own possibly-stale object) and by calling {@code verifyEmail} on the same expired
     * token twice, asserting the SECOND call gets the same "expired" message as the first --
     * proof the first failed attempt left no side effect for the second to trip over.
     */
    @Test
    void anExpiredButNeverUsedTokenIsNotPermanentlyBurnedByAFailedVerifyAttempt() {
        User user = new User();
        user.setEmail("verify-email-expired-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Verify Email Expired IT User");
        user.setEmailVerified(false);
        user = userRepository.save(user);
        UUID userId = user.getId();

        String rawToken = "verify-email-expired-token";
        EmailVerificationToken evt = new EmailVerificationToken();
        evt.setUserId(userId);
        evt.setTokenHash(TokenHasher.sha256(rawToken));
        evt.setExpiresAt(Instant.now().minusSeconds(60));
        evt = emailVerificationTokenRepository.save(evt);
        UUID tokenId = evt.getId();

        try {
            authService.verifyEmail(rawToken);
            throw new AssertionError("Expected the first attempt on an expired token to throw");
        } catch (ApiException e) {
            assertThat(e.getMessage()).contains("expired");
        }

        assertThat(emailVerificationTokenRepository.findById(tokenId).orElseThrow().getUsedAt())
                .as("a failed, expired attempt must not leave the token claimed -- the failing "
                        + "transaction's UPDATE must have rolled back with everything else")
                .isNull();

        try {
            authService.verifyEmail(rawToken);
            throw new AssertionError("Expected the second attempt on the same expired token to throw too");
        } catch (ApiException e) {
            assertThat(e.getMessage())
                    .as("a second attempt on the same still-unused, still-expired token must fail "
                            + "the same way as the first -- not flip to \"already been used\" because "
                            + "an earlier failed attempt secretly consumed it")
                    .contains("expired");
        }

        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified()).isFalse();
    }
}
