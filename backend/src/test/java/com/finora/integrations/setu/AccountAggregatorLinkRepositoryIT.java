package com.finora.integrations.setu;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AccountAggregatorLink} carries no {@code @Version} (see its own doc comment --
 * connection/session state, not BaseEntity's optimistically-locked financial data), so
 * {@code claimStatusTransition} is what stands in for it. A mock-based test (see
 * {@code AccountAggregatorIdentityResolutionServiceTest}) can prove the service code calls the
 * right method and reacts correctly to a 0 vs 1 result, but it cannot prove the underlying native
 * UPDATE is actually atomic under real concurrent access -- that needs a real database and real
 * concurrent transactions, the same reasoning {@code RefreshTokenService}'s own doc comment gives
 * for why its analogous reuse-detection bug was invisible to every mocked test and only surfaced
 * against a real Postgres instance.
 */
class AccountAggregatorLinkRepositoryIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountAggregatorLinkRepository links;

    private UUID linkId;
    private UUID consentPendingLinkId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("aa-link-repo-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("AA Link Repository Test User");
        UUID userId = userRepository.save(user).getId();

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        link.setLinkIdempotencyKey("aa-link-repo-it-" + UUID.randomUUID());
        link.setConsentHandleId("handle-" + UUID.randomUUID());
        linkId = links.save(link).getId();

        AccountAggregatorLink consentPendingLink = new AccountAggregatorLink();
        consentPendingLink.setUserId(userId);
        consentPendingLink.setFiType(FiType.DEPOSIT);
        consentPendingLink.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        consentPendingLink.setLinkIdempotencyKey("aa-link-repo-it-" + UUID.randomUUID());
        consentPendingLink.setConsentHandleId("handle-" + UUID.randomUUID());
        consentPendingLinkId = links.save(consentPendingLink).getId();
    }

    @Test
    void firstClaimSucceedsAndASubsequentClaimOfTheSameFromStatusFails() {
        int first = links.claimStatusTransition(linkId,
                AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION.name(),
                AccountAggregatorLinkStatus.ACTIVE.name());
        assertThat(first).isEqualTo(1);
        assertThat(links.findById(linkId).orElseThrow().getStatus())
                .isEqualTo(AccountAggregatorLinkStatus.ACTIVE);

        // Same call again, same fromStatus -- the row is no longer PENDING_ACCOUNT_CONFIRMATION,
        // so this must affect zero rows rather than re-applying (and rather than throwing).
        int second = links.claimStatusTransition(linkId,
                AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION.name(),
                AccountAggregatorLinkStatus.ACTIVE.name());
        assertThat(second).isEqualTo(0);
    }

    @Test
    void claimAgainstTheWrongFromStatusIsANoOp() {
        int claimed = links.claimStatusTransition(linkId,
                AccountAggregatorLinkStatus.CONSENT_PENDING.name(),
                AccountAggregatorLinkStatus.ACTIVE.name());

        assertThat(claimed).isEqualTo(0);
        assertThat(links.findById(linkId).orElseThrow().getStatus())
                .isEqualTo(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
    }

    /**
     * The actual regression test for the race this repository method exists to close: N genuinely
     * concurrent callers claiming the SAME transition on the SAME row. Without an atomic
     * conditional UPDATE (i.e. back on the old check-then-act {@code save()} this replaces), every
     * one of them could observe PENDING_ACCOUNT_CONFIRMATION and every one would "succeed" --
     * exactly the class of bug that produced an orphaned Account plus a duplicate Setu backfill
     * call in {@code AccountAggregatorIdentityResolutionService.attach}. Real threads against the
     * real Testcontainers Postgres instance, not a single-threaded sequential simulation -- a
     * sequential test cannot distinguish "atomic" from "just happens to run in order."
     */
    @Test
    void exactlyOneOfManyConcurrentClaimsWinsAgainstTheSameRow() throws Exception {
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> claims = IntStream.range(0, attempts)
                    .<Callable<Integer>>mapToObj(i -> () -> links.claimStatusTransition(linkId,
                            AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION.name(),
                            AccountAggregatorLinkStatus.ACTIVE.name()))
                    .collect(Collectors.toList());

            List<Future<Integer>> results = pool.invokeAll(claims);
            int totalClaimed = 0;
            for (Future<Integer> result : results) {
                totalClaimed += result.get();
            }

            assertThat(totalClaimed).isEqualTo(1);
            assertThat(links.findById(linkId).orElseThrow().getStatus())
                    .isEqualTo(AccountAggregatorLinkStatus.ACTIVE);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    // -- claimIdentityResolution: the pre-fetchConsentDetail claim that closes the re-entrancy gap
    // claimStatusTransition alone does not (see that method's own doc comment on
    // AccountAggregatorLinkRepository) --

    @Test
    void claimIdentityResolutionSucceedsOnceThenFailsOnANaiveRetryOfTheSameEvent() {
        int first = links.claimIdentityResolution(consentPendingLinkId,
                AccountAggregatorLinkStatus.CONSENT_PENDING.name());
        assertThat(first).isEqualTo(1);
        // The whole point: status is UNCHANGED by this claim -- still CONSENT_PENDING, exactly as a
        // link whose one and only resolution attempt crashed before ever reaching attach() would be.
        assertThat(links.findById(consentPendingLinkId).orElseThrow().getStatus())
                .isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(links.findById(consentPendingLinkId).orElseThrow().getResolutionClaimedAt()).isNotNull();

        // A second delivery of the SAME logical event (redelivery, or a crash-recovery re-dispatch)
        // reaching this same still-CONSENT_PENDING link must not be able to claim again.
        int second = links.claimIdentityResolution(consentPendingLinkId,
                AccountAggregatorLinkStatus.CONSENT_PENDING.name());
        assertThat(second).isEqualTo(0);
    }

    @Test
    void claimIdentityResolutionAgainstTheWrongFromStatusIsANoOp() {
        int claimed = links.claimIdentityResolution(linkId,
                AccountAggregatorLinkStatus.CONSENT_PENDING.name());

        assertThat(claimed).isEqualTo(0);
        assertThat(links.findById(linkId).orElseThrow().getResolutionClaimedAt()).isNull();
    }

    /** Same reasoning as {@code exactlyOneOfManyConcurrentClaimsWinsAgainstTheSameRow} above, for
     *  the ONE-SHOT claim this method performs -- real threads, real Postgres, not a sequential
     *  simulation that can't distinguish "atomic" from "just happened to run in order." */
    @Test
    void exactlyOneOfManyConcurrentIdentityResolutionClaimsWinsAgainstTheSameRow() throws Exception {
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> claims = IntStream.range(0, attempts)
                    .<Callable<Integer>>mapToObj(i -> () -> links.claimIdentityResolution(
                            consentPendingLinkId, AccountAggregatorLinkStatus.CONSENT_PENDING.name()))
                    .collect(Collectors.toList());

            List<Future<Integer>> results = pool.invokeAll(claims);
            int totalClaimed = 0;
            for (Future<Integer> result : results) {
                totalClaimed += result.get();
            }

            assertThat(totalClaimed).isEqualTo(1);
            assertThat(links.findById(consentPendingLinkId).orElseThrow().getStatus())
                    .isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
            assertThat(links.findById(consentPendingLinkId).orElseThrow().getResolutionClaimedAt()).isNotNull();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }
}
