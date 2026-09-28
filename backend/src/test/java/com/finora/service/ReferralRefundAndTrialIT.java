package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.Referral;
import com.finora.entity.ReferralGrant;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Referral counting against the real webhook dispatchers and a real Postgres: a referral counts
 * only on a real (non-trial) charge, and a refund or lost chargeback of the charge that counted
 * takes it back. Everything goes through {@code dispatch()} exactly as the webhook controllers call
 * it, so the tests cover the event routing and payload parsing, not just ReferralService.
 *
 * <p>Payload fields follow the providers' own webhook docs: RevenueCat marks a free trial with
 * {@code period_type=TRIAL} and a refund with a CANCELLATION whose {@code cancel_reason} is
 * CUSTOMER_SUPPORT; Razorpay sends {@code refund.processed} with the refunded payment in
 * {@code refund.entity.payment_id}, and {@code payment.dispute.lost} with it in
 * {@code payment.entity.id}.
 */
class ReferralRefundAndTrialIT extends AbstractIntegrationTest {

    @Autowired private RazorpayWebhookDispatcher razorpayDispatcher;
    @Autowired private RevenueCatWebhookDispatcher revenueCatDispatcher;
    @Autowired private ReferralService referralService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private IapProductRepository iapProductRepository;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReferralGrantRepository referralGrantRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private RazorpaySubscriptionGateway gateway;
    @MockitoBean private EmailProvider emailProvider;

    private User newUser() {
        User user = new User();
        user.setEmail("referral-refund-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Referral Refund IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private int counter(User referrer) {
        return jdbcTemplate.queryForObject(
                "SELECT premium_milestone_counter FROM referral_codes WHERE user_id = ?", Integer.class, referrer.getId());
    }

    private String referralStatus(User referred) {
        return referralRepository.findByReferredUserId(referred.getId()).map(Referral::getStatus).orElse(null);
    }

    private User referredBy(String code) {
        User referred = newUser();
        referralService.redeemCode(referred.getId(), code);
        subscriptionService.provisionFreeSubscription(referred.getId());
        return referred;
    }

    // ---- Razorpay ----

    /** Puts the user's subscription on a Razorpay-billed Plus mandate and returns the razorpay plan id. */
    private String razorpaySubscribe(User user, String razorpaySubscriptionId) {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String razorpayPlanId = plusMonthly.getRazorpayPlanId();
        if (razorpayPlanId == null) {
            razorpayPlanId = "plan_test_" + UUID.randomUUID();
            plusMonthly.setRazorpayPlanId(razorpayPlanId);
            billingPriceRepository.save(plusMonthly);
        }
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);
        return razorpayPlanId;
    }

    private void razorpayCharged(String razorpaySubscriptionId, String razorpayPlanId, String paymentId, long amountPaise) {
        razorpayDispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", amountPaise)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second
    }

    private void razorpayRefundProcessed(String paymentId) {
        razorpayDispatcher.dispatch("refund.processed", Map.of(
                "refund", Map.of("entity", Map.of(
                        "id", "rfnd_test_" + UUID.randomUUID(), "payment_id", paymentId,
                        "amount", 79900, "status", "processed")),
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 79900))));
    }

    /** One referred friend who paid through Razorpay, counted. Returns the counting payment id. */
    private String razorpayCountedFriend(String code, User referrer) {
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        String paymentId = "pay_test_" + UUID.randomUUID();
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, paymentId, 79900);
        return paymentId;
    }

    @Test
    void razorpayRefundOfTheCountingChargeTakesTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        String paymentId = "pay_test_" + UUID.randomUUID();

        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, paymentId, 79900);
        assertThat(counter(referrer)).isEqualTo(1);
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_SUBSCRIBED);

        razorpayRefundProcessed(paymentId);

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);
    }

    @Test
    void razorpayLostChargebackOfTheCountingChargeTakesTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String paymentId = razorpayCountedFriend(code, referrer);
        assertThat(counter(referrer)).isEqualTo(1);

        razorpayDispatcher.dispatch("payment.dispute.lost", Map.of(
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 79900)),
                "dispute", Map.of("entity", Map.of("id", "disp_test_" + UUID.randomUUID(), "payment_id", paymentId))));

        assertThat(counter(referrer)).isZero();
    }

    /** A dispute being raised is not yet a loss -- nothing changes until it is lost. */
    @Test
    void razorpayDisputeCreatedDoesNotTakeTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String paymentId = razorpayCountedFriend(code, referrer);

        razorpayDispatcher.dispatch("payment.dispute.created", Map.of(
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 79900)),
                "dispute", Map.of("entity", Map.of("id", "disp_test_" + UUID.randomUUID(), "payment_id", paymentId))));

        assertThat(counter(referrer)).isEqualTo(1);
    }

    /** refund.processed and payment.dispute.lost for the same payment, or one event re-sent under a
     *  new event id: only one of them may take the referral back. Two counted friends so a double
     *  decrement is visible (2 -> 0) instead of hidden by the floor at 0. */
    @Test
    void razorpayRepeatedReversalOfOneChargeTakesOnlyOneReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String refunded = razorpayCountedFriend(code, referrer);
        razorpayCountedFriend(code, referrer);
        assertThat(counter(referrer)).isEqualTo(2);

        razorpayRefundProcessed(refunded);
        razorpayRefundProcessed(refunded);
        razorpayDispatcher.dispatch("payment.dispute.lost", Map.of(
                "payment", Map.of("entity", Map.of("id", refunded, "amount", 79900)),
                "dispute", Map.of("entity", Map.of("id", "disp_test_" + UUID.randomUUID(), "payment_id", refunded))));

        assertThat(counter(referrer)).isEqualTo(1);
    }

    /** A refund of a payment that never counted (the friend's second month) must not take away
     *  the referral that the first month earned. */
    @Test
    void razorpayRefundOfALaterRenewalDoesNotTakeTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, "pay_test_" + UUID.randomUUID(), 79900);
        String renewal = "pay_test_" + UUID.randomUUID();
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, renewal, 79900);

        razorpayRefundProcessed(renewal);

        assertThat(counter(referrer)).isEqualTo(1);
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_SUBSCRIBED);
    }

    /** The farming path: pay, get counted, delete the account, get refunded. The account purge
     *  hard-deletes the referral row and the Payment rows (AccountPurgeSweepService), so the refund
     *  must still find its way back to the referrer without them. Simulated with the same deletes
     *  the purge issues for the referred user's referral, payment and subscription rows. */
    @Test
    void razorpayRefundAfterTheReferredAccountWasPurgedStillTakesTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        String paymentId = "pay_test_" + UUID.randomUUID();
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, paymentId, 79900);
        assertThat(counter(referrer)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM referrals WHERE referred_user_id = ?", referred.getId());
        jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", referred.getId());
        jdbcTemplate.update("DELETE FROM subscriptions WHERE user_id = ?", referred.getId());

        razorpayRefundProcessed(paymentId);

        assertThat(counter(referrer)).isZero();
    }

    /** A month already redeemed is kept (the grant is untouched), but the refunded referral is
     *  owed: the counter goes to -1 and the next friend only repays it. Without that, "redeem at
     *  once, then have every friend refunded" earned a month for free. */
    @Test
    void refundAfterTheMonthWasRedeemedKeepsTheMonthButOwesTheReferral() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String first = null;
        for (int i = 0; i < ReferralService.MILESTONE_REFERRALS; i++) {
            String paymentId = razorpayCountedFriend(code, referrer);
            if (first == null) first = paymentId;
        }
        assertThat(counter(referrer)).isEqualTo(ReferralService.MILESTONE_REFERRALS);
        referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS);
        assertThat(counter(referrer)).isZero();

        razorpayRefundProcessed(first);

        assertThat(counter(referrer)).isEqualTo(-1);
        assertThat(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(referrer.getId())).hasSize(1);
        var mine = referralService.myReferrals(referrer.getId());
        assertThat(mine.premiumMilestoneCounter()).isZero();
        assertThat(mine.referralsOwed()).isEqualTo(1);

        razorpayCountedFriend(code, referrer);
        assertThat(counter(referrer)).isZero();
        assertThat(referralService.myReferrals(referrer.getId()).referralsOwed()).isZero();
    }

    /** The "redeem, then refund everyone" farm: 7 friends counted, a month redeemed, all 7
     *  refunded. The month stays, but the next 7 referrals only repay it -- none of them earns a
     *  second month. */
    @Test
    void redeemThenRefundEveryFriendDoesNotEarnASecondMonth() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        java.util.List<String> payments = new java.util.ArrayList<>();
        for (int i = 0; i < ReferralService.MILESTONE_REFERRALS; i++) payments.add(razorpayCountedFriend(code, referrer));
        referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS);
        payments.forEach(this::razorpayRefundProcessed);
        assertThat(counter(referrer)).isEqualTo(-ReferralService.MILESTONE_REFERRALS);

        for (int i = 0; i < ReferralService.MILESTONE_REFERRALS; i++) razorpayCountedFriend(code, referrer);

        assertThat(counter(referrer)).isZero();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> referralService.redeemMilestone(referrer.getId(), ReferralGrant.TIER_PLUS)))
                .isInstanceOf(com.finora.exception.ApiException.class);
        assertThat(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(referrer.getId())).hasSize(1);
    }

    @Test
    void referrerIsToldWhenAReferralIsTakenBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String paymentId = razorpayCountedFriend(code, referrer);

        razorpayRefundProcessed(paymentId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'REFERRAL_REVERSED'",
                Integer.class, referrer.getId())).isPositive();
    }

    /** A lost chargeback marks the payment Refunded only when the whole payment was deducted. */
    @Test
    void razorpayLostChargebackMarksThePaymentRefundedOnlyWhenFullyDeducted() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String full = razorpayCountedFriend(code, referrer);
        String partial = razorpayCountedFriend(code, referrer);

        razorpayDispatcher.dispatch("payment.dispute.lost", Map.of(
                "payment", Map.of("entity", Map.of("id", full, "amount", 79900)),
                "dispute", Map.of("entity", Map.of("id", "disp_test_" + UUID.randomUUID(), "payment_id", full,
                        "amount", 79900, "amount_deducted", 79900))));
        razorpayDispatcher.dispatch("payment.dispute.lost", Map.of(
                "payment", Map.of("entity", Map.of("id", partial, "amount", 79900)),
                "dispute", Map.of("entity", Map.of("id", "disp_test_" + UUID.randomUUID(), "payment_id", partial,
                        "amount", 10000, "amount_deducted", 10000))));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE provider_transaction_id = ?", String.class, full))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE provider_transaction_id = ?", String.class, partial))
                .isEqualTo("SUCCESS");
    }

    /** Billing history: a full refund marks the payment Refunded; a partial one leaves it paid. */
    @Test
    void razorpayFullRefundMarksThePaymentRefundedAndAPartialOneDoesNot() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        String full = razorpayCountedFriend(code, referrer);
        String partial = razorpayCountedFriend(code, referrer);

        razorpayDispatcher.dispatch("refund.processed", Map.of(
                "refund", Map.of("entity", Map.of("id", "rfnd_test_" + UUID.randomUUID(), "payment_id", full, "amount", 79900)),
                "payment", Map.of("entity", Map.of("id", full, "amount", 79900, "amount_refunded", 79900, "refund_status", "full"))));
        razorpayDispatcher.dispatch("refund.processed", Map.of(
                "refund", Map.of("entity", Map.of("id", "rfnd_test_" + UUID.randomUUID(), "payment_id", partial, "amount", 10000)),
                "payment", Map.of("entity", Map.of("id", partial, "amount", 79900, "amount_refunded", 10000, "refund_status", "partial"))));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE provider_transaction_id = ?", String.class, full))
                .isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM payments WHERE provider_transaction_id = ?", String.class, partial))
                .isEqualTo("SUCCESS");
    }

    /** Taken back, then the friend pays again for real: the new charge counts again. */
    @Test
    void aReferralTakenBackCountsAgainOnTheFriendsNextRealCharge() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        String paymentId = "pay_test_" + UUID.randomUUID();
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, paymentId, 79900);
        razorpayRefundProcessed(paymentId);
        assertThat(counter(referrer)).isZero();

        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, "pay_test_" + UUID.randomUUID(), 79900);

        assertThat(counter(referrer)).isEqualTo(1);
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_SUBSCRIBED);
    }

    @Test
    void razorpayZeroAmountChargeDoesNotCount() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);

        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, "pay_test_" + UUID.randomUUID(), 0);

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);
    }

    /** Neither provider guarantees webhook order, and a subscription.charged that races ahead of
     *  its subscription's activation is only retried by the recovery sweep (every 15 minutes). A
     *  refund processed in that window must still stop the charge from counting when it lands. */
    @Test
    void razorpayRefundThatArrivesBeforeItsChargeStopsTheChargeFromCounting() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        String razorpayPlanId = razorpaySubscribe(referred, razorpaySubscriptionId);
        String paymentId = "pay_test_" + UUID.randomUUID();

        razorpayRefundProcessed(paymentId);
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, paymentId, 79900);

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);

        // ...and the friend's next, real charge still counts.
        razorpayCharged(razorpaySubscriptionId, razorpayPlanId, "pay_test_" + UUID.randomUUID(), 79900);
        assertThat(counter(referrer)).isEqualTo(1);
    }

    // ---- RevenueCat ----

    private String iosPlusProduct() {
        String productId = "fynora_plus_monthly_" + UUID.randomUUID();
        IapProduct product = new IapProduct();
        product.setProviderProductId(productId);
        product.setPlanId(planRepository.findByCode("PLUS").orElseThrow().getId());
        product.setBillingCycle("MONTHLY");
        product.setPlatform("IOS");
        iapProductRepository.save(product);
        return productId;
    }

    private Map<String, Object> revenueCatEvent(User user, String productId, String transactionId,
                                                String originalTransactionId, String periodType, double price) {
        Map<String, Object> event = new HashMap<>();
        event.put("app_user_id", user.getId().toString());
        event.put("product_id", productId);
        event.put("store", "APP_STORE");
        event.put("transaction_id", transactionId);
        event.put("original_transaction_id", originalTransactionId);
        event.put("period_type", periodType);
        event.put("price", price);
        event.put("environment", "PRODUCTION");
        event.put("expiration_at_ms", 1893456000000L); // synthetic-ok: fixture epoch millis
        return event;
    }

    @Test
    void revenueCatTrialStartDoesNotCount() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String txn = "txn_test_" + UUID.randomUUID();

        revenueCatDispatcher.dispatch("INITIAL_PURCHASE",
                revenueCatEvent(referred, iosPlusProduct(), txn, txn, "TRIAL", 0));

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);
    }

    /** App Store sandbox / TestFlight purchases cost nothing. RevenueCat delivers them to the
     *  production webhook with environment=SANDBOX; they must not earn referral months, but must
     *  still unlock the plan (App Review). */
    @Test
    void revenueCatSandboxPurchaseDoesNotCount() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String txn = "txn_test_" + UUID.randomUUID();
        Map<String, Object> event = revenueCatEvent(referred, iosPlusProduct(), txn, txn, "NORMAL", 4.99);
        event.put("environment", "SANDBOX");

        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", event);

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);
        // The plan itself is still unlocked: Apple's App Review buys in the sandbox against the
        // production server, and must see the purchase work.
        Subscription subscription = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(referred.getId()).get(0);
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
        assertThat(planRepository.findById(subscription.getPlanId()).orElseThrow().getCode()).isEqualTo("PLUS");
    }

    /** RevenueCat's documented trial flow: INITIAL_PURCHASE (TRIAL), then a RENEWAL when the trial
     *  converts into the first paid period. That RENEWAL is the real charge. */
    @Test
    void revenueCatTrialConversionCounts() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String productId = iosPlusProduct();
        String original = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE",
                revenueCatEvent(referred, productId, original, original, "TRIAL", 0));

        Map<String, Object> renewal = revenueCatEvent(referred, productId, "txn_test_" + UUID.randomUUID(), original, "NORMAL", 4.99);
        renewal.put("is_trial_conversion", true);
        revenueCatDispatcher.dispatch("RENEWAL", renewal);

        assertThat(counter(referrer)).isEqualTo(1);
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_SUBSCRIBED);
    }

    @Test
    void revenueCatPaidPurchaseCountsAndItsRefundTakesItBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        assertThat(counter(referrer)).isEqualTo(1);

        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        assertThat(counter(referrer)).isZero();
        assertThat(referralStatus(referred)).isEqualTo(Referral.STATUS_REGISTERED);
        // RevenueCat: a refund "doesn't mean that a subscription's autorenewal preference has been
        // deactivated" -- the subscription is left as it was.
        Subscription subscription = subscriptionRepository.findByRevenuecatOriginalTransactionId(txn).orElseThrow();
        assertThat(subscription.isAutoRenew()).isTrue();
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    /** Apple refunds after a subscription has already expired are routine. EXPIRATION clears the
     *  subscription's original_transaction_id, so the refund's CANCELLATION can no longer find the
     *  subscription. It must still take the referral back, and must not throw (a throw rolls the
     *  reversal back and leaves the webhook failing on every retry). */
    @Test
    void revenueCatRefundAfterExpirationStillTakesTheReferralBackWithoutThrowing() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        revenueCatDispatcher.dispatch("EXPIRATION", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));

        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        assertThat(counter(referrer)).isZero();
    }

    /** RevenueCat: price is "negative for refunds", and Apple can send cancel_reason UNKNOWN. */
    @Test
    void revenueCatRefundWithUnknownReasonButNegativePriceStillTakesTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));

        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "UNKNOWN");
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        assertThat(counter(referrer)).isZero();
    }

    private Subscription revenueCatSubscription(String originalTransactionId) {
        return subscriptionRepository.findByRevenuecatOriginalTransactionId(originalTransactionId).orElseThrow();
    }

    private String planCode(Subscription subscription) {
        return planRepository.findById(subscription.getPlanId()).orElseThrow().getCode();
    }

    /** A refund of the latest paid transaction took the current period away: Plus ends now, not
     *  whenever an EXPIRATION might arrive. (The refund's expiry fields are RevenueCat's own sample
     *  refund values, moved to the past.) The mandate stays recorded, so a later EXPIRATION still
     *  finds the row and finishes the downgrade without throwing. */
    @Test
    void revenueCatRefundOfTheCurrentPeriodEndsPlusAtOnceAndALaterExpirationStillWorks() {
        User referred = newUser();
        subscriptionService.provisionFreeSubscription(referred.getId());
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        assertThat(planCode(revenueCatSubscription(txn))).isEqualTo("PLUS");

        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        refund.put("expiration_at_ms", 1601336705000L); // synthetic-ok: RevenueCat sample refund's expiry
        refund.put("event_timestamp_ms", 1601337615995L); // synthetic-ok: ...and its event time, after it
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        Subscription afterRefund = revenueCatSubscription(txn);
        assertThat(planCode(afterRefund)).isEqualTo("FREE");
        assertThat(afterRefund.getPaymentProvider()).isEqualTo("REVENUECAT");
        assertThat(afterRefund.isAutoRenew()).isTrue();

        revenueCatDispatcher.dispatch("EXPIRATION", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        Subscription expired = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(referred.getId()).get(0);
        assertThat(planCode(expired)).isEqualTo("FREE");
        assertThat(expired.getPaymentProvider()).isNull();
    }

    /** Refunded, but the store keeps renewing (a refund does not cancel the subscription): the
     *  next RENEWAL is a new paid period and brings Plus back. */
    @Test
    void revenueCatRenewalAfterARefundBringsPlusBack() {
        User referred = newUser();
        subscriptionService.provisionFreeSubscription(referred.getId());
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        refund.put("expiration_at_ms", 1601336705000L); // synthetic-ok: RevenueCat sample refund's expiry
        refund.put("event_timestamp_ms", 1601337615995L); // synthetic-ok: ...and its event time, after it
        revenueCatDispatcher.dispatch("CANCELLATION", refund);
        assertThat(planCode(revenueCatSubscription(txn))).isEqualTo("FREE");

        revenueCatDispatcher.dispatch("RENEWAL",
                revenueCatEvent(referred, productId, "txn_test_" + UUID.randomUUID(), txn, "NORMAL", 4.99));

        Subscription renewed = revenueCatSubscription(txn);
        assertThat(planCode(renewed)).isEqualTo("PLUS");
        assertThat(renewed.getBillingCycle()).isEqualTo("MONTHLY");
    }

    /** A late refund of an older, already-ended period, arriving after a RENEWAL recorded a newer
     *  paid period: the current period is still paid for, so Plus stays. */
    @Test
    void revenueCatLateRefundOfAnEndedOlderPeriodLeavesTheRenewedPeriodAlone() {
        User referred = newUser();
        subscriptionService.provisionFreeSubscription(referred.getId());
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        String renewalTxn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("RENEWAL", revenueCatEvent(referred, productId, renewalTxn, txn, "NORMAL", 4.99));

        // Refund of the first period: its expiry (2020) is past, but the renewal runs to 2030.
        Map<String, Object> refund = revenueCatEvent(referred, productId, txn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        refund.put("expiration_at_ms", 1601336705000L); // synthetic-ok: RevenueCat sample refund's expiry
        refund.put("event_timestamp_ms", 1601337615995L); // synthetic-ok: ...and its event time, after it
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        assertThat(planCode(revenueCatSubscription(txn))).isEqualTo("PLUS");
    }

    /** The latest paid transaction refunded, even while its expiry still reads as future: the
     *  refunded purchase pays for nothing, so Plus ends. */
    @Test
    void revenueCatRefundOfTheLatestPeriodEndsPlusWhateverItsExpirySays() {
        User referred = newUser();
        subscriptionService.provisionFreeSubscription(referred.getId());
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));
        String renewalTxn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("RENEWAL", revenueCatEvent(referred, productId, renewalTxn, txn, "NORMAL", 4.99));

        Map<String, Object> refund = revenueCatEvent(referred, productId, renewalTxn, txn, "NORMAL", -4.99);
        refund.put("cancel_reason", "CUSTOMER_SUPPORT");
        revenueCatDispatcher.dispatch("CANCELLATION", refund);

        assertThat(planCode(revenueCatSubscription(txn))).isEqualTo("FREE");
    }

    /** A voluntary unsubscribe is not a refund: the friend paid and keeps what they paid for. */
    @Test
    void revenueCatVoluntaryCancellationDoesNotTakeTheReferralBack() {
        User referrer = newUser();
        String code = referralService.myCode(referrer.getId());
        User referred = referredBy(code);
        String productId = iosPlusProduct();
        String txn = "txn_test_" + UUID.randomUUID();
        revenueCatDispatcher.dispatch("INITIAL_PURCHASE", revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99));

        Map<String, Object> cancel = revenueCatEvent(referred, productId, txn, txn, "NORMAL", 4.99);
        cancel.put("cancel_reason", "UNSUBSCRIBE");
        revenueCatDispatcher.dispatch("CANCELLATION", cancel);

        assertThat(counter(referrer)).isEqualTo(1);
    }
}
