package com.finora.service;

import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.SubscriptionRepository;
import com.finora.util.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * What a suspension does beyond the status itself (privacy workstream 10): stop a website Plus
 * subscription renewing, and tell the customer. Called by {@link AdminUserService} inside its
 * suspend/reactivate transaction; the emails go out only after that transaction commits.
 *
 * <p>Renewal: a Razorpay subscription has its auto-renew switched off, exactly as the customer's own
 * "cancel" does ({@link BillingCheckoutService#cancel}): no gateway call now, the cancellation sweep
 * tells Razorpay near the renewal date, and no refund. An App Store or Google Play subscription
 * cannot be stopped by Fynora; the notice names the store instead.
 *
 * <p>Email only to a verified address, as for the lockout email
 * ({@code AuthService.notifyOwnerOfLockout}): an unverified address may belong to someone else. The
 * customer still sees the notice on the suspended screen when they sign in.
 */
@Service
public class AccountSuspensionNotices {

    private static final Logger log = LoggerFactory.getLogger(AccountSuspensionNotices.class);

    private final SubscriptionRepository subscriptionRepository;
    private final EmailProvider emailProvider;
    private final AuditService auditService;
    private final Executor authEmailExecutor;

    public AccountSuspensionNotices(SubscriptionRepository subscriptionRepository, EmailProvider emailProvider,
                                    AuditService auditService,
                                    @Qualifier("authEmailExecutor") Executor authEmailExecutor) {
        this.subscriptionRepository = subscriptionRepository;
        this.emailProvider = emailProvider;
        this.auditService = auditService;
        this.authEmailExecutor = authEmailExecutor;
    }

    /** Stops website renewal and queues the suspension notice. Safe to call again for an account
     *  that is already suspended: renewal is already off, and the notice is meant to go out again
     *  when an admin records or changes the reason. */
    public void onSuspended(User user, UUID actingAdminId) {
        String storeName = stopRenewalOrNameStore(user.getId(), actingAdminId);
        if (!user.isEmailVerified()) {
            log.info("Not emailing the suspension notice for user {}: the email address is not verified", user.getId());
            return;
        }
        UUID userId = user.getId();
        String email = user.getEmail();
        String reasonText = user.getSuspensionReason().customerText();
        String message = user.getSuspensionMessage();
        AfterCommit.run("account suspended email", () -> authEmailExecutor.execute(() -> {
            EmailResult result = emailProvider.sendAccountSuspendedEmail(email, reasonText, message, storeName);
            auditService.recordEvenOnRollback(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "account_suspended", "provider", result.provider().name(), "success", result.success()));
        }));
    }

    /** Queues the "suspension lifted" email. Renewal is left off: the customer decides whether to
     *  switch it back on. */
    public void onSuspensionLifted(User user) {
        if (!user.isEmailVerified()) return;
        UUID userId = user.getId();
        String email = user.getEmail();
        AfterCommit.run("suspension lifted email", () -> authEmailExecutor.execute(() -> {
            EmailResult result = emailProvider.sendSuspensionLiftedEmail(email);
            auditService.recordEvenOnRollback(userId, "EMAIL_SENT", "User", userId, Map.of(
                    "type", "suspension_lifted", "provider", result.provider().name(), "success", result.success()));
        }));
    }

    /**
     * The store name for a subscription paid through a store, or null. Switches off auto-renew on a
     * website (Razorpay) subscription on the way.
     */
    String stopRenewalOrNameStore(UUID userId, UUID actingAdminId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId).orElse(null);
        if (subscription == null || !subscription.isAutoRenew()) return null;
        if (PROVIDER_REVENUECAT.equals(subscription.getPaymentProvider())) {
            return storeName(subscription.getStorePlatform());
        }
        if (subscription.getRazorpaySubscriptionId() != null) {
            subscription.setAutoRenew(false);
            subscriptionRepository.save(subscription);
            auditService.record(userId, "SUBSCRIPTION_RENEWAL_STOPPED_FOR_SUSPENSION", "Subscription",
                    subscription.getId(), Map.of("actorId", actingAdminId.toString()));
        }
        return null;
    }

    /** A store subscription arrives through RevenueCat (RevenueCatWebhookDispatcher sets this
     *  provider), with the store in {@code store_platform}. */
    static final String PROVIDER_REVENUECAT = "REVENUECAT";

    /** The store's name for a RevenueCat subscription's {@code store_platform}: IOS is the App Store,
     *  anything else Google Play, the same split the web and mobile deletion warnings make. */
    static String storeName(String storePlatform) {
        return "IOS".equals(storePlatform) ? "the App Store" : "Google Play";
    }
}
