package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ReferralGrant;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Same shape of bug as {@code ReferralConcurrentCreditRaceIT}, found in review (not reported) of
 * this feature's own initial implementation: two concurrent redeem requests (a
 * double-click, two open tabs) could both read the same pre-reset counter and both pass a
 * Java-side check, creating two {@code ReferralGrant} rows -- two free months -- for one threshold
 * crossing. {@code ReferralCodeRepository.consumeMilestoneIfAtLeast}'s conditional {@code UPDATE}
 * is what actually closes that race; this proves it does, at the real database, not just via a
 * mocked return value.
 *
 * <p>Same deterministic technique {@code ReferralConcurrentCreditRaceIT} already established: a
 * {@code @MockitoSpyBean} pauses the first caller AFTER its UPDATE is issued (so the row lock is
 * genuinely held) but BEFORE its transaction commits, so the second caller's UPDATE is REALLY
 * blocked at the database. Reissues the identical native SQL directly through the
 * {@code EntityManager}, for the same reason that IT's own comment gives: Mockito cannot call a
 * "real" method through a spy of an interface-backed Spring Data proxy.
 */
class ReferralMilestoneConcurrentRedeemRaceIT extends AbstractIntegrationTest {

    @Autowired private ReferralService referralService;
    @Autowired private UserRepository userRepository;
    @Autowired private ReferralGrantRepository referralGrantRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private ReferralCodeRepository referralCodeRepository;

    private static final String FIRST_THREAD = "redeem-race-first";

    private User newUser() {
        User user = new User();
        user.setEmail("milestone-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Milestone Race IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void twoConcurrentRedeemRequestsBothSurviveButOnlyOneGrantIsCreated() throws Exception {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        // Separate referred users, each reaching SUBSCRIBED, to push the milestone counter to
        // exactly the redeem threshold.
        for (int i = 0; i < ReferralService.MILESTONE_REFERRALS; i++) {
            User referred = newUser();
            referralService.redeemCode(referred.getId(), code);
            referralService.onPlanChanged(referred.getId(), "PLUS");
        }

        CountDownLatch firstHasUpdatedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();

        doAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            Integer required = invocation.getArgument(1);
            int updated = entityManager.createNativeQuery("""
                    UPDATE referral_codes SET premium_milestone_counter = premium_milestone_counter - :required
                    WHERE user_id = :userId AND premium_milestone_counter >= :required
                    """)
                    .setParameter("userId", userId)
                    .setParameter("required", required)
                    .executeUpdate();
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHasUpdatedButNotCommitted.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return updated;
        }).when(referralCodeRepository).consumeMilestoneIfAtLeast(eq(referrer.getId()), anyInt());

        Thread first = new Thread(() -> {
            try {
                referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS);
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, FIRST_THREAD);
        first.start();

        assertThat(firstHasUpdatedButNotCommitted.await(30, TimeUnit.SECONDS))
                .as("the first caller must actually be parked with its UPDATE in place, uncommitted")
                .isTrue();

        Thread second = new Thread(() -> {
            try {
                referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS);
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "redeem-race-second");
        second.start();

        // Same reasoning as ReferralConcurrentCreditRaceIT: give the second caller a moment to
        // actually reach and issue its own UPDATE before releasing the first, so the two genuinely
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
        assertThat(((ApiException) secondFailure.get()).getMessage()).contains("just redeemed by another request");

        // The real invariant: exactly one grant survived, not two -- the loser's redemption never
        // created a second free month for the same threshold crossing.
        assertThat(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(referrer.getId())).hasSize(1);
    }

    /**
     * A friend's subscription landing while the referrer redeems. Before the increment took a row
     * lock, it read the pre-redemption 7 (the redeem's UPDATE was not committed yet), then wrote
     * 7 + 1 = 8 over the redeem's result -- so the 7 just redeemed counted again and a second free
     * month unlocked after one more referral. With the lock, the increment waits for the redeem to
     * commit and reads what it left.
     */
    @Test
    void aReferralLandingMidRedeemIsCountedOnTopOfTheRedemptionNotOverIt() throws Exception {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        for (int i = 0; i < ReferralService.MILESTONE_REFERRALS; i++) {
            User referred = newUser();
            referralService.redeemCode(referred.getId(), code);
            referralService.onPlanChanged(referred.getId(), "PLUS");
        }
        User late = newUser();
        referralService.redeemCode(late.getId(), code);

        CountDownLatch redeemHasUpdatedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseRedeem = new CountDownLatch(1);
        AtomicReference<Throwable> redeemFailure = new AtomicReference<>();
        AtomicReference<Throwable> incrementFailure = new AtomicReference<>();

        doAnswer(invocation -> {
            int updated = entityManager.createNativeQuery("""
                    UPDATE referral_codes SET premium_milestone_counter = premium_milestone_counter - :required
                    WHERE user_id = :userId AND premium_milestone_counter >= :required
                    """)
                    .setParameter("userId", invocation.getArgument(0))
                    .setParameter("required", invocation.getArgument(1))
                    .executeUpdate();
            redeemHasUpdatedButNotCommitted.countDown();
            assertThat(releaseRedeem.await(30, TimeUnit.SECONDS)).isTrue();
            return updated;
        }).when(referralCodeRepository).consumeMilestoneIfAtLeast(eq(referrer.getId()), anyInt());

        Thread redeem = new Thread(() -> {
            try {
                referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS);
            } catch (Throwable t) {
                redeemFailure.set(t);
            }
        }, "redeem-mid-increment");
        redeem.start();
        assertThat(redeemHasUpdatedButNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();

        Thread increment = new Thread(() -> {
            try {
                referralService.onPlanChanged(late.getId(), "PLUS");
            } catch (Throwable t) {
                incrementFailure.set(t);
            }
        }, "increment-mid-redeem");
        increment.start();
        // Let the increment reach its read before the redeem commits, so the two really overlap.
        Thread.sleep(500);

        releaseRedeem.countDown();
        redeem.join(TimeUnit.SECONDS.toMillis(30));
        increment.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(redeem.isAlive()).isFalse();
        assertThat(increment.isAlive()).isFalse();
        assertThat(redeemFailure.get()).isNull();
        assertThat(incrementFailure.get()).isNull();

        Integer counter = jdbcTemplate.queryForObject(
                "SELECT premium_milestone_counter FROM referral_codes WHERE user_id = ?", Integer.class, referrer.getId());
        assertThat(counter).as("7 redeemed, then 1 new referral").isEqualTo(1);
        assertThat(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(referrer.getId())).hasSize(1);
    }
}
