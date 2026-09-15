package com.finora.service;

import com.finora.entity.BillingPrice;
import com.finora.entity.Payment;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.SubscriptionEvent;
import com.finora.entity.SubscriptionOrder;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PaymentRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionEventRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.AfterCommit;
import com.finora.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * Subscription billing V1 (design spec §5). One method per Razorpay event type this application
 * acts on. Named for what it does, not {@code *Service}: a single-purpose collaborator used only by
 * {@link com.finora.controller.RazorpayWebhookController}.
 */
@Component
public class RazorpayWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final PlanRepository planRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final PaymentRepository paymentRepository;
    private final RazorpaySubscriptionGateway gateway;
    private final UserRepository userRepository;
    private final EmailProvider emailProvider;
    private final ReferralService referralService;
    private final InvoiceService invoiceService;

    public RazorpayWebhookDispatcher(SubscriptionRepository subscriptionRepository,
                                      SubscriptionOrderRepository subscriptionOrderRepository,
                                      SubscriptionEventRepository subscriptionEventRepository,
                                      PlanRepository planRepository,
                                      BillingPriceRepository billingPriceRepository,
                                      PaymentRepository paymentRepository,
                                      RazorpaySubscriptionGateway gateway,
                                      UserRepository userRepository,
                                      EmailProvider emailProvider,
                                      ReferralService referralService,
                                      InvoiceService invoiceService) {
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.subscriptionEventRepository = subscriptionEventRepository;
        this.planRepository = planRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.userRepository = userRepository;
        this.emailProvider = emailProvider;
        this.referralService = referralService;
        this.invoiceService = invoiceService;
    }

    /**
     * {@code @Transactional} lives here, not on the individual {@code handle*} methods below —
     * those are called from inside this same class (self-invocation), which bypasses Spring's
     * proxy-based transaction interception entirely. An {@code @Transactional} on a privately
     * self-invoked method is silently a no-op; confirmed the hard way when
     * {@code handleActivated}'s order/subscription/event writes landed outside any transaction and
     * a missing {@code save()} call on the order was masked until the mutated field never persisted.
     */
    @Transactional
    public void dispatch(String eventType, Map<String, Object> payload) {
        switch (eventType) {
            case "subscription.authenticated", "subscription.activated" -> handleActivated(payload);
            case "subscription.charged" -> handleCharged(payload);
            case "subscription.pending" -> handlePending(payload);
            case "subscription.halted" -> handleHalted(payload);
            case "subscription.cancelled" -> handleCancelled(payload);
            case "subscription.paused" -> handlePaused(payload);
            case "subscription.resumed" -> handleResumed(payload);
            default -> log.info("Razorpay webhook event '{}' received but not handled in V1.",
                    LogSanitizer.sanitize(eventType));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> subscriptionEntity(Map<String, Object> payload) {
        Map<String, Object> subscription = (Map<String, Object>) payload.get("subscription");
        return subscription == null ? Map.of() : (Map<String, Object>) subscription.get("entity");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paymentEntity(Map<String, Object> payload) {
        Map<String, Object> payment = (Map<String, Object>) payload.get("payment");
        return payment == null ? Map.of() : (Map<String, Object>) payment.get("entity");
    }

    /** Payment Method card (Billing page). Razorpay's own {@code subscription.activated}/
     *  {@code subscription.charged} webhook payloads already carry {@code payment.entity.card} --
     *  last4/network/type -- for every card-authorized mandate (confirmed against Razorpay's docs);
     *  absent entirely for a UPI/emandate mandate, which this simply leaves untouched. Also how a
     *  successful "Update Payment Method" checkout (same Standard Checkout flow, re-run against an
     *  already-active subscription's {@code subscription_id}) is picked up: whichever of these two
     *  webhooks fires next for the new authentication carries the new card, overwriting the old one. */
    private void applyCardOnFile(Subscription subscription, Map<String, Object> paymentEntity) {
        Object cardObj = paymentEntity.get("card");
        if (!(cardObj instanceof Map<?, ?> card)) return;
        subscription.setCardLast4(asString(card.get("last4")));
        subscription.setCardNetwork(asString(card.get("network")));
        subscription.setCardType(asString(card.get("type")));
    }

    // Same defensive instanceof-based extraction this class already uses for "amount" above --
    // a blind (String) cast on a Razorpay-controlled leaf value would throw ClassCastException on
    // any unexpected shape and roll back this whole @Transactional dispatch (order completion,
    // activation, payment recording) over what is otherwise a purely cosmetic card-display field.
    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    /** spec §6.1 step 5 / §5. Completes checkout: marks the matching {@link SubscriptionOrder}
     *  COMPLETED and mutates the user's single {@link Subscription} row in place — the same
     *  mutate-in-place model {@code SubscriptionService.changePlan} already uses, never a second
     *  row (see design spec §6.5's DB-constraint discussion).
     *
     *  <p>Idempotent across repeated invocations for the same order, not just across repeated
     *  deliveries of the same webhook event id: {@code subscription.authenticated} and
     *  {@code subscription.activated} both fire, sequentially, for one real checkout (confirmed
     *  against Razorpay's own docs — they are lifecycle stages, not mutually-exclusive
     *  alternatives), each as its own event with its own event id, so the {@code webhook_events}
     *  idempotency ledger does not collapse them. Without the early return below, the second
     *  delivery would re-run this whole method and insert a second {@code SUBSCRIPTION_CREATED}
     *  event for one signup — which matters beyond a duplicate audit row, since design spec §5/§6.7
     *  names this exact event as what fires Plan 2's one-time referral trigger.
     *
     *  <p>Falls back to {@link #recoverOrderFromNotes} when no order matches this
     *  razorpaySubscriptionId — see that method's doc for why the order can legitimately be missing
     *  here (a client-side timeout on {@code BillingCheckoutService}'s create call, after Razorpay
     *  had already created the real subscription). */
    void handleActivated(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        SubscriptionOrder order = subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId)
                .orElseGet(() -> recoverOrderFromNotes(razorpaySubscriptionId, entity));
        if (order == null) {
            log.warn("subscription.activated for unknown razorpaySubscriptionId {}, ignoring.",
                    LogSanitizer.sanitize(razorpaySubscriptionId));
            return;
        }
        // Plan 3 review: was `if (STATUS_COMPLETED.equals(...)) return;`, which only guarded
        // against re-processing an already-activated order (idempotency). Once Plan 3 gave
        // STATUS_ABANDONED a real writer (BillingCheckoutService.cancelPendingOrder -- a user
        // explicitly cancelling a stuck checkout in the Billing Portal), that same narrow guard
        // meant an abandoned order could still be silently activated here if the Razorpay payment
        // completed anyway after the user cancelled (a still-open tab, a delayed webhook) --
        // reactivating a paid plan the user believed they'd backed out of. Requiring PENDING
        // specifically closes that: COMPLETED (idempotency) and ABANDONED (explicit cancel) both
        // now correctly stop this from running again.
        if (!SubscriptionOrder.STATUS_PENDING.equals(order.getStatus())) {
            return;
        }
        order.setStatus(SubscriptionOrder.STATUS_COMPLETED);
        order.setCompletedAt(Instant.now());
        subscriptionOrderRepository.save(order);

        Subscription subscription = subscriptionRepository.findActiveOrTrial(order.getUserId())
                .orElseThrow(() -> new IllegalStateException(
                        "User " + order.getUserId() + " has a pending order but no subscription row " +
                        "-- provisionFreeSubscription should have created one at signup."));
        Plan plan = planRepository.findById(order.getPlanId()).orElseThrow();

        String oldRazorpaySubscriptionId = subscription.getRazorpaySubscriptionId();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(order.getBillingCycle());
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        subscription.setCancellationDispatchedAt(null);
        Object currentEnd = entity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        applyCardOnFile(subscription, paymentEntity(payload));
        subscriptionRepository.save(subscription);

        // design spec §6.5 step 4. A pre-existing, DIFFERENT razorpaySubscriptionId on the row
        // means this activation is completing an upgrade over an old, still live subscription --
        // stop it now that the new one is confirmed active, not before (spec §6.5's whole reason
        // for this ordering: cancelling first would risk leaving the user with no active paid
        // access if they abandoned the new checkout). Deliberately NOT allowed to roll back this
        // transaction: the new subscription is genuinely active and correctly billing regardless of
        // whether stopping the old one succeeds, so a Razorpay error here is caught, logged for
        // manual follow-up, and does not undo the activation that already happened. A brand-new
        // signup has no prior razorpaySubscriptionId (null), so this never fires for that path.
        if (oldRazorpaySubscriptionId != null && !oldRazorpaySubscriptionId.equals(razorpaySubscriptionId)) {
            try {
                gateway.cancelSubscription(oldRazorpaySubscriptionId, false);
            } catch (RuntimeException e) {
                log.error("Upgrade completed for user {} but cancelling the old Razorpay subscription {} " +
                        "failed -- requires manual follow-up to stop it from charging again.",
                        subscription.getUserId(), oldRazorpaySubscriptionId, e);
            }
        }

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CREATED);
        event.setMetadata(Map.of("planCode", plan.getCode(), "billingCycle", order.getBillingCycle(),
                "razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);

        sendActivationEmail(subscription.getUserId(), plan.getName(), order.getBillingCycle());
    }

    /** Ambiguous-timeout recovery. {@code BillingCheckoutService.checkout}/{@code
     *  upgradeToNewSubscription} each call {@code gateway.createSubscription} and only save a
     *  {@link SubscriptionOrder} row after that call returns successfully. If the HTTP round-trip to
     *  Razorpay times out on Fynora's end but Razorpay's server actually received and processed the
     *  request, {@code RazorpaySubscriptionGatewayImpl.createSubscription} throws before ever
     *  returning the new subscription's id to its caller — so no order row is ever created, and this
     *  webhook is the very first time Fynora learns the subscription exists at all. Without recovery
     *  the lookup above finds nothing, {@link #handleActivated} logs and drops the event, and a real,
     *  live, paying Razorpay subscription is left permanently invisible to this application.
     *
     *  <p>Razorpay echoes back the exact {@code notes} this application submitted at creation
     *  ({@code fynoraUserId}/{@code planCode}/{@code billingCycle}, see {@code
     *  BillingCheckoutService.checkout}) on every representation of the subscription entity,
     *  webhooks included — confirmed against Razorpay's {@code subscription.activated}/{@code
     *  subscription.authenticated} webhook payload documentation. That is enough to reconstruct the
     *  missing order without a new Razorpay API call or a periodic reconciliation job: Razorpay does
     *  not document a way to list/search subscriptions by notes content (checked against the {@code
     *  razorpay-java} SDK's {@code SubscriptionClient} — {@code fetchAll} takes only the same generic
     *  query params Razorpay's own "list subscriptions" endpoint documents, none of them notes-based),
     *  so a sweep would have to page through and filter every subscription on the account instead.
     *
     *  <p>This payload has already passed {@code RazorpayWebhookController}'s HMAC signature check
     *  against Fynora's own webhook secret before reaching here, so the notes it carries are trusted
     *  to be exactly what this application itself sent at creation, not attacker-controlled input.
     *
     *  <p>Returns {@code null} — never guesses — when the notes are missing, malformed, or don't
     *  resolve to a real plan or an active price for it; the caller logs and drops the event exactly
     *  as it always has for a genuinely unrecognized subscription id. */
    @SuppressWarnings("unchecked")
    private SubscriptionOrder recoverOrderFromNotes(String razorpaySubscriptionId, Map<String, Object> entity) {
        if (!(entity.get("notes") instanceof Map<?, ?> notes)) return null;

        String fynoraUserId = asString(notes.get("fynoraUserId"));
        String planCode = asString(notes.get("planCode"));
        String billingCycle = asString(notes.get("billingCycle"));
        if (fynoraUserId == null || planCode == null || billingCycle == null) return null;

        java.util.UUID userId;
        try {
            userId = java.util.UUID.fromString(fynoraUserId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        Plan plan = planRepository.findByCode(planCode).orElse(null);
        if (plan == null) return null;
        // Falls back to a deactivated price rather than aborting recovery entirely: the active row
        // at checkout time may since have been superseded by the time a delayed/retried webhook
        // finally arrives (a real possibility -- there is no bound on how late Razorpay's retries
        // can land), and SubscriptionOrder.amount is audit-only, not what actually gets charged
        // (Razorpay's own razorpay_plan_id already decided that at the real, original checkout
        // time) -- so any real historical price for this plan+cycle is a better answer than
        // silently dropping the whole recovery, which is exactly the bug this method exists to fix.
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plan.getId(), billingCycle)
                .or(() -> billingPriceRepository.findByPlanIdAndBillingCycle(plan.getId(), billingCycle).stream()
                        .max(java.util.Comparator.comparing(BillingPrice::getCreatedAt)))
                .orElse(null);
        if (price == null) return null;

        log.warn("Recovering orphaned razorpaySubscriptionId {} for user {} from webhook notes -- no local " +
                "SubscriptionOrder existed for it, most likely a client-side timeout on the original " +
                "createSubscription call that lost the response before the order row could be saved.",
                LogSanitizer.sanitize(razorpaySubscriptionId), userId);

        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(userId);
        order.setPlanId(plan.getId());
        order.setBillingCycle(billingCycle);
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(price.getPrice());
        return order;
    }

    /** Product decision: a confirmation email on every successful subscription purchase (Plan 3),
     *  fires for both a first-time paid signup and an upgrade's new subscription. Deferred via
     *  {@link AfterCommit} for the same two reasons every other post-commit side effect in this
     *  codebase is: this whole method runs inside {@code dispatch()}'s transaction, and sending an
     *  email is a network call that must not hold a pooled DB connection nor fire for an
     *  activation that then rolls back. A missing user row is defensive-only (should be
     *  unreachable -- the subscription lookup above already required one) and simply skips the
     *  email rather than failing the whole webhook over a notification. */
    private void sendActivationEmail(java.util.UUID userId, String planName, String billingCycle) {
        AfterCommit.run("subscription activated email", () ->
                userRepository.findById(userId).ifPresent(user ->
                        emailProvider.sendSubscriptionActivatedEmail(
                                user.getEmail(), user.getFullName(), planName, billingCycle)));
    }

    /** spec §5, §6.4. Renewal is otherwise fully passive — this is also the reconciliation point
     *  that makes a scheduled downgrade (Plan 2) actually take effect: if the charged Razorpay plan
     *  id no longer matches what BillingPrice says the local plan should be billed under, the local
     *  plan_id is corrected to match.
     *
     *  <p>Idempotency guard added for {@code WebhookEventRecoverySweepService}: unlike
     *  {@link #handleActivated}, this method previously had no defense against running twice for the
     *  same charge -- a second run would insert a second {@link Payment} row (and send a second
     *  invoice email, and advance the referral ledger a second time were its own guard not already
     *  internal). A genuine Razorpay retry never reaches here twice ({@code claim()} dedupes it), but
     *  the recovery sweep's whole reason to exist is reprocessing a webhook whose outcome is
     *  genuinely unknown -- it may have already fully committed. Payment's {@code
     *  provider_transaction_id} is exactly Razorpay's own {@code payment.entity.id} for this charge
     *  (set below), so its existence is a direct, verified signal that this exact charge was already
     *  recorded -- not a guess. */
    @SuppressWarnings("unchecked")
    void handleCharged(Map<String, Object> payload) {
        Map<String, Object> subscriptionEntity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) subscriptionEntity.get("id");
        if (razorpaySubscriptionId == null) return;

        String chargePaymentId = (String) paymentEntity(payload).get("id");
        if (chargePaymentId != null && paymentRepository.existsByProviderTransactionId(chargePaymentId)) {
            log.info("subscription.charged for razorpaySubscriptionId {} already recorded as payment {}, skipping.",
                    LogSanitizer.sanitize(razorpaySubscriptionId), LogSanitizer.sanitize(chargePaymentId));
            return;
        }

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) {
            // Razorpay does not guarantee webhook delivery order (confirmed against Razorpay's own
            // docs): a retried subscription.charged can be delayed behind, and so arrive before, the
            // subscription.activated that creates this row -- not necessarily a garbage id. Returning
            // cleanly here (the old behavior) let RazorpayWebhookController mark the webhook_events
            // row PROCESSED, so WebhookEventRecoverySweepService -- which only ever looks at NULL/
            // FAILED rows -- never saw this one, and the charge (renewal date, plan reconciliation,
            // the Payment row, the invoice email) was lost permanently with no error and no retry.
            // Throwing instead marks the row FAILED so the sweep retries it once activation has
            // landed. A genuinely unknown/garbage razorpaySubscriptionId fails the same way on every
            // retry -- the same accepted "retried on every sweep tick indefinitely, mitigated only by
            // the visible FAILED row for manual follow-up" residual risk WebhookEventRepository
            // .findFailed's own doc already describes for any deterministically-failing FAILED row,
            // not a new mechanism introduced here.
            throw new IllegalStateException(
                    "subscription.charged for unknown razorpaySubscriptionId " + LogSanitizer.sanitize(razorpaySubscriptionId));
        }
        Subscription subscription = maybeSubscription.get();

        String chargedRazorpayPlanId = (String) subscriptionEntity.get("plan_id");
        if (chargedRazorpayPlanId != null) {
            billingPriceRepository.findAll().stream()
                    .filter(bp -> chargedRazorpayPlanId.equals(bp.getRazorpayPlanId()))
                    .findFirst()
                    .ifPresent(bp -> {
                        subscription.setPlanId(bp.getPlanId());
                        subscription.setBillingCycle(bp.getBillingCycle());
                    });
        }
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        Object currentEnd = subscriptionEntity.get("current_end");
        if (currentEnd instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
        }
        Map<String, Object> paymentEntity = paymentEntity(payload);
        applyCardOnFile(subscription, paymentEntity);
        subscriptionRepository.save(subscription);

        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_SUCCESS);
        Object amountPaise = paymentEntity.get("amount");
        payment.setAmount(amountPaise instanceof Number n
                ? java.math.BigDecimal.valueOf(n.longValue(), 2)
                : java.math.BigDecimal.ZERO);
        payment.setCurrency("INR");
        payment.setProviderTransactionId((String) paymentEntity.get("id"));
        // Frozen at charge time, not read back later off the (mutated-in-place) subscription --
        // see Payment.planId/billingCycle's own doc for why: subscription.getPlanId()/
        // getBillingCycle() are already the final, reconciled values for THIS charge by this point
        // (the plan-correction block above has already run and saved), so this is the one moment
        // they're guaranteed to describe what was actually charged rather than whatever the
        // subscription has since become.
        payment.setPlanId(subscription.getPlanId());
        payment.setBillingCycle(subscription.getBillingCycle());
        paymentRepository.save(payment);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_RENEWED);
        event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
        subscriptionEventRepository.save(event);

        // design spec §5 (referral reward ledger): subscription.charged is the real-charge signal
        // this is keyed off (not subscription.activated, which can fire with zero funds movement).
        // Fires on every charge including renewals -- onPlanChanged's own REGISTERED-only guard
        // makes repeat calls a no-op, so this needs no idempotency handling of its own.
        Plan chargedPlan = planRepository.findById(subscription.getPlanId()).orElse(null);
        if (chargedPlan != null) {
            referralService.onPlanChanged(subscription.getUserId(), chargedPlan.getCode());
        }

        String planName = chargedPlan != null ? chargedPlan.getName() : "Fynora";
        sendInvoiceEmail(subscription.getUserId(), payment.getId(), planName);
    }

    /** Fires for every successful charge this method creates a Payment row for -- first purchase,
     *  upgrade, and renewal alike (see EmailProvider.sendInvoiceEmail's own doc for why that's
     *  deliberately broader than sendActivationEmail above). Deferred via {@link AfterCommit} for
     *  the same reasons as sendActivationEmail: PDF generation plus a network call must not hold a
     *  pooled DB connection, and must not fire for a charge whose transaction then rolls back.
     *  {@code payment.getId()} is populated before commit despite the missing reassignment at the
     *  {@code paymentRepository.save(payment)} call site above -- {@link Payment} does not extend
     *  {@code BaseEntity}, so it has no {@code @Version} field to prime Spring Data's
     *  {@code isNew()} check false; the null id makes {@code isNew()} true, {@code persist()} runs
     *  (not {@code merge()}), and JPA assigns the generated id onto this same instance. */
    private void sendInvoiceEmail(java.util.UUID userId, java.util.UUID paymentId, String planName) {
        AfterCommit.run("invoice email", () ->
                userRepository.findById(userId).ifPresent(user -> {
                    InvoiceService.GeneratedInvoice invoice = invoiceService.generate(userId, paymentId);
                    EmailAttachment attachment = new EmailAttachment(
                            invoice.fileName(), invoice.pdfBytes(), "application/pdf");
                    emailProvider.sendInvoiceEmail(user.getEmail(), user.getFullName(), planName, attachment);
                }));
    }

    /** spec §5. PAST_DUE, not a revoked state — Razorpay's own retry is in progress and, per its
     *  documented behavior, does not itself affect access (design spec §3). */
    void handlePending(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();
        subscription.setStatus(Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        Payment payment = new Payment();
        payment.setUserId(subscription.getUserId());
        payment.setSubscriptionId(subscription.getId());
        payment.setProvider("RAZORPAY");
        payment.setStatus(Payment.STATUS_PENDING);
        payment.setAmount(java.math.BigDecimal.ZERO); // retry attempt, amount not in this webhook's payload
        payment.setCurrency("INR");
        paymentRepository.save(payment);
    }

    /** spec §5, §9. Retries exhausted — the real access-revoking signal (unlike "pending"). Marks
     *  any outstanding PENDING payment for this subscription FAILED (the retry sequence is over,
     *  it never will succeed now) and downgrades straight to FREE — V1 does not build a "resume a
     *  halted subscription" flow (spec §9); the user re-subscribes via ordinary checkout. */
    void handleHalted(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        Optional<Subscription> maybeSubscription = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId);
        if (maybeSubscription.isEmpty()) return;
        Subscription subscription = maybeSubscription.get();

        paymentRepository.findBySubscriptionIdOrderByCreatedAtDesc(subscription.getId()).stream()
                .filter(p -> Payment.STATUS_PENDING.equals(p.getStatus()))
                .forEach(p -> { p.setStatus(Payment.STATUS_FAILED); paymentRepository.save(p); });

        Plan free = planRepository.findByCode("FREE")
                .orElseThrow(() -> new IllegalStateException("FREE plan missing -- V99 seed data not applied"));
        subscription.setPlanId(free.getId());
        subscription.setBillingCycle(null);
        subscription.setRazorpaySubscriptionId(null);
        subscription.setPaymentProvider(null);
        subscription.setAutoRenew(true);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscriptionRepository.save(subscription);

        SubscriptionEvent event = new SubscriptionEvent();
        event.setSubscriptionId(subscription.getId());
        event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
        event.setMetadata(Map.of("reason", "PAYMENT_FAILURE"));
        subscriptionEventRepository.save(event);
    }

    /** spec §5, §6.3. Does not itself downgrade to Free — that happens at
     * {@code current_period_end}, via {@code SubscriptionReconciliationSweepService} (Task 12), not
     * from this webhook alone (a missed webhook must not leave paid access active forever).
     *
     * <p>Always sets {@code autoRenew=false} here too, not only in {@code BillingCheckoutService
     * .cancel()}: the sweep's own query ({@code findCancelledSubscriptionsPastPeriodEnd}) requires
     * {@code autoRenew=false AND status='CANCELLED'} together. The spec's state-machine table notes
     * "auto_renew already false from the cancel request", true for the only cancellation path V1
     * has today — but this webhook is the one place that hears from Razorpay directly that a
     * subscription is cancelled, regardless of what triggered it (a future admin/Razorpay-dashboard
     * cancellation, or Plan 2's upgrade flow cancelling the old subscription). Without this, any
     * cancellation that didn't go through our own {@code cancel()} first would leave {@code
     * autoRenew=true} forever, and the sweep would never downgrade that user off the paid plan. */
    void handleCancelled(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_CANCELLED);
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.SUBSCRIPTION_CANCELLED);
            event.setMetadata(Map.of("reason", "USER_INITIATED"));
            subscriptionEventRepository.save(event);
        });
    }

    /** Product decision (2026-09-08). {@code BillingCheckoutService.pause} already sets PAUSED
     *  locally for the user-initiated path -- Razorpay's pause is synchronous, so that call's own
     *  API response is already authoritative, unlike checkout's "created" status. This handler is
     *  for the trigger that call can't cover: a pause initiated directly from the Razorpay
     *  dashboard, the same "hears from Razorpay directly, regardless of what triggered it" reasoning
     *  {@link #handleCancelled} already applies to cancellation. */
    void handlePaused(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_PAUSED);
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.SUBSCRIPTION_PAUSED);
            event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
            subscriptionEventRepository.save(event);
        });
    }

    /** Same reasoning as {@link #handlePaused}, mirrored for resume. Also the one place
     *  {@code renewalDate} gets corrected after a resume -- {@code BillingCheckoutService.resume}
     *  deliberately doesn't read it off its own gateway call, matching {@code handleCharged}'s
     *  existing pattern of trusting only the webhook's {@code current_end} for that field. */
    void handleResumed(Map<String, Object> payload) {
        Map<String, Object> entity = subscriptionEntity(payload);
        String razorpaySubscriptionId = (String) entity.get("id");
        if (razorpaySubscriptionId == null) return;

        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(subscription -> {
            subscription.setStatus(Subscription.STATUS_ACTIVE);
            Object currentEnd = entity.get("current_end");
            if (currentEnd instanceof Number n) {
                subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochSecond(n.longValue()), ZoneOffset.UTC));
            }
            subscriptionRepository.save(subscription);

            SubscriptionEvent event = new SubscriptionEvent();
            event.setSubscriptionId(subscription.getId());
            event.setEventType(SubscriptionEvent.SUBSCRIPTION_RESUMED);
            event.setMetadata(Map.of("razorpaySubscriptionId", razorpaySubscriptionId));
            subscriptionEventRepository.save(event);
        });
    }
}
