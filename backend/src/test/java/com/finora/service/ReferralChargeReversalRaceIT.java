package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ReferralCharge;
import com.finora.entity.ReferralCode;
import com.finora.entity.ReferralGrant;
import com.finora.entity.User;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * ReferralService.onChargeReversed under real concurrency, at the real database. Same
 * deterministic technique as {@code ReferralMilestoneConcurrentRedeemRaceIT}: a
 * {@code @MockitoSpyBean} parks the first caller while it holds its row lock, uncommitted, the
 * second caller is started against it, and only then is the first released. The spied methods
 * re-issue the same query through the {@code EntityManager}, since a spy of an interface-backed
 * Spring Data proxy cannot call through to the real method.
 */
class ReferralChargeReversalRaceIT extends AbstractIntegrationTest {

    @Autowired private ReferralService referralService;
    @Autowired private UserRepository userRepository;
    @Autowired private ReferralGrantRepository referralGrantRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private ReferralCodeRepository referralCodeRepository;

    private static final String FIRST_THREAD = "reversal-race-first";

    private User newUser() {
        User user = new User();
        user.setEmail("reversal-race-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reversal Race IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private String countedFriend(String code) {
        User referred = newUser();
        referralService.redeemCode(referred.getId(), code);
        String paymentId = "pay_test_" + UUID.randomUUID();
        referralService.onReferredUserCharged(referred.getId(), "PLUS", ReferralCharge.PROVIDER_RAZORPAY, paymentId);
        return paymentId;
    }

    private int counter(User referrer) {
        return jdbcTemplate.queryForObject(
                "SELECT premium_milestone_counter FROM referral_codes WHERE user_id = ?", Integer.class, referrer.getId());
    }

    private Thread start(String name, Runnable body, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, name);
        thread.start();
        return thread;
    }

    /** A refund and a lost chargeback of the same payment landing at once. Without the charge
     *  row's lock both read reversed_at as unset and both take 1 off: 2 would become 0. */
    @Test
    void twoConcurrentReversalsOfOneChargeTakeOnlyOneOff() throws Exception {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String refunded = countedFriend(code);
        countedFriend(code);
        assertThat(counter(referrer)).isEqualTo(2);

        CountDownLatch firstHoldsTheChargeLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        // Reached only after onChargeReversed has locked and updated the charge row.
        doAnswer(invocation -> {
            UUID userId = invocation.getArgument(0);
            Optional<ReferralCode> locked = entityManager
                    .createQuery("SELECT c FROM ReferralCode c WHERE c.userId = :userId", ReferralCode.class)
                    .setParameter("userId", userId)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                    .getResultStream().findFirst();
            if (Thread.currentThread().getName().equals(FIRST_THREAD)) {
                firstHoldsTheChargeLock.countDown();
                assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return locked;
        }).when(referralCodeRepository).findByUserIdForUpdate(any());

        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = start(FIRST_THREAD,
                () -> referralService.onChargeReversed(ReferralCharge.PROVIDER_RAZORPAY, refunded, "REFUND"), firstFailure);
        assertThat(firstHoldsTheChargeLock.await(30, TimeUnit.SECONDS)).isTrue();
        Thread second = start("reversal-race-second",
                () -> referralService.onChargeReversed(ReferralCharge.PROVIDER_RAZORPAY, refunded, "CHARGEBACK_LOST"), secondFailure);
        // Let the second caller reach its own locked read of the charge row before releasing.
        Thread.sleep(500);
        assertThat(second.isAlive()).as("the second reversal must be waiting on the charge row lock").isTrue();

        releaseFirst.countDown();
        first.join(TimeUnit.SECONDS.toMillis(30));
        second.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(firstFailure.get()).isNull();
        assertThat(secondFailure.get()).isNull();

        assertThat(counter(referrer)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reversal_reason FROM referral_charges WHERE charge_ref = ?", String.class, refunded))
                .isEqualTo("REFUND");
    }

    /** A refund landing while the referrer redeems at exactly 7. The refund waits for the
     *  redemption's row lock and then reads what it left (0), so the result is 0 with the month
     *  granted. Reading the pre-redemption 7 instead would write 6 over the redemption and hand
     *  back 6 referrals the month just consumed. */
    @Test
    void aRefundLandingMidRedeemIsAppliedAfterTheRedemptionNotOverIt() throws Exception {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String refunded = countedFriend(code);
        for (int i = 1; i < ReferralService.MILESTONE_REFERRALS; i++) countedFriend(code);
        assertThat(counter(referrer)).isEqualTo(ReferralService.MILESTONE_REFERRALS);

        CountDownLatch redeemHasUpdatedButNotCommitted = new CountDownLatch(1);
        CountDownLatch releaseRedeem = new CountDownLatch(1);
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

        AtomicReference<Throwable> redeemFailure = new AtomicReference<>();
        AtomicReference<Throwable> refundFailure = new AtomicReference<>();
        Thread redeem = start("redeem-mid-refund",
                () -> referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS), redeemFailure);
        assertThat(redeemHasUpdatedButNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();
        Thread refund = start("refund-mid-redeem",
                () -> referralService.onChargeReversed(ReferralCharge.PROVIDER_RAZORPAY, refunded, "REFUND"), refundFailure);
        Thread.sleep(500);
        assertThat(refund.isAlive()).as("the refund must be waiting on the referral_codes row lock").isTrue();

        releaseRedeem.countDown();
        redeem.join(TimeUnit.SECONDS.toMillis(30));
        refund.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(redeem.isAlive()).isFalse();
        assertThat(refund.isAlive()).isFalse();
        assertThat(redeemFailure.get()).isNull();
        assertThat(refundFailure.get()).isNull();

        assertThat(counter(referrer)).as("7 redeemed, then the refund floors at 0").isZero();
        assertThat(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(referrer.getId())).hasSize(1);
    }
}
