package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Referral;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralRepository;
import com.finora.repository.WalletLedgerRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Same shape of bug as {@code MerchantConcurrentAliasRaceIT} (BH-053) and
 * {@code NotificationConcurrentRequestRaceIT}, found in review rather than reported: two admins
 * (or a double-click, or a retried request) crediting the SAME referral at the same moment could
 * both read {@code SUBSCRIBED} before either committed and both credit the wallet -- see
 * {@code ReferralService.creditReward}'s own doc comment. Uses the identical deterministic
 * technique those two tests already established, for the same reason
 * {@code MerchantConcurrentAliasRaceIT}'s own class comment gives: a {@code CyclicBarrier} would
 * be racing the race, not pinning it. A {@code @MockitoSpyBean} hook pauses the first caller AFTER
 * its wallet insert has been issued (so V168's partial unique index is genuinely holding the row
 * lock) but BEFORE its transaction commits, so the second caller's insert is REALLY blocked at the
 * database, not merely scheduled to run around the same time.
 *
 * <p>What's pinned here: two genuinely-concurrent credit requests for the same referral must both
 * survive without crashing (one succeeds, the other gets a clean 409, never an unhandled
 * exception), and {@code wallet_ledger} must end up with exactly one REFERRAL_REWARD row for that
 * referral -- never two, and never zero.
 */
class ReferralConcurrentCreditRaceIT extends AbstractIntegrationTest {

    @Autowired private ReferralService referralService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    /** Real behaviour by default. Spied only to pause the first writer right after its insert is
     *  issued and before its transaction commits -- see the class comment. Reissues the identical
     *  native SQL {@link WalletLedgerRepository#insertReferralRewardIfAbsent} runs directly through
     *  the {@code EntityManager} (Mockito cannot call a "real" method through a spy of an
     *  interface-backed Spring Data proxy), so the row lock it takes is the real thing. */
    @MockitoSpyBean private WalletLedgerRepository walletLedgerRepository;

    private static final String FIRST_THREAD = "credit-race-first";

    private User newUser() {
        User user = new User();
        user.setEmail("credit-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Credit Race IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void twoConcurrentCreditRequestsForTheSameReferralBothSurviveButOnlyOneCredits() throws Exception {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = newUser();
        referralService.redeemCode(referred.getId(), code);
        referralService.onPlanChanged(referred.getId(), "PLUS");
        Referral referral = referralRepository.findByReferredUserId(referred.getId()).orElseThrow();
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);

        CountDownLatch firstHasInsertedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        doAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            BigDecimal amount = invocation.getArgument(1);
            UUID referenceId = invocation.getArgument(2);
            int inserted = ((Number) entityManager.createNativeQuery("""
                    INSERT INTO wallet_ledger (id, user_id, amount, reason, reference_id, created_at)
                    VALUES (gen_random_uuid(), :userId, :amount, 'REFERRAL_REWARD', :referenceId, now())
                    ON CONFLICT (reference_id) WHERE reason = 'REFERRAL_REWARD' DO NOTHING
                    """)
                    .setParameter("userId", userId)
                    .setParameter("amount", amount)
                    .setParameter("referenceId", referenceId)
                    .executeUpdate()).intValue();
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasInsertedButNotCommitted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inserted;
        }).when(walletLedgerRepository).insertReferralRewardIfAbsent(any(), any(), any());

        Thread first = new Thread(() -> {
            try {
                referralService.creditReward(referral.getId(), new BigDecimal("250.00"), "race test", referrer.getId());
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasInsertedButNotCommitted.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked with its insert in place, uncommitted")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                referralService.creditReward(referral.getId(), new BigDecimal("250.00"), "race test", referrer.getId());
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "credit-race-second");
        second.start();

        // Same reasoning as MerchantConcurrentAliasRaceIT: give the second caller a moment to
        // actually reach and issue its insert before releasing the first, so the two genuinely
        // overlap rather than running sequentially by accident.
        Thread.sleep(500);

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).as("the first caller must have finished, not hung").isFalse();
        assertThat(second.isAlive()).as("the second caller must have finished, not hung").isFalse();

        assertThat(firstFailure.get()).as("the winner must not throw").isNull();
        assertThat(secondFailure.get())
                .as("the loser must get a clean rejection, not a crashed/poisoned transaction")
                .isInstanceOf(ApiException.class);
        assertThat(((ApiException) secondFailure.get()).getMessage()).contains("just credited by another request");

        Referral reloaded = referralRepository.findById(referral.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        // The real invariant: exactly one wallet entry survived, not two -- the loser's insert was
        // a genuine no-op at the database, not a duplicate credit racing ahead of the status check.
        assertThat(walletLedgerRepository.sumAmountByUserId(referrer.getId())).isEqualByComparingTo("250.00");
    }
}
