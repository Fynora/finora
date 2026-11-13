package com.finora.observability.jobs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A plain name and one line on what it does, for every {@code @Scheduled} method in the backend.
 *
 * <p>The admin portal's Background jobs page and the alert emails show these, so an admin reading on
 * a phone sees "Account deletion sweep: deletes accounts customers asked to delete" rather than a
 * class name. Keys are {@code SimpleClassName.methodName}, the same key the run recorder writes.
 *
 * <p>Hand-written on purpose. {@code BackgroundJobCatalogTest} fails when a scheduled method has no
 * entry here, or an entry names a method that no longer exists, so a new job cannot ship unnamed.
 */
public final class BackgroundJobCatalog {

    /** What the page and the emails call a job. */
    public record JobInfo(String name, String purpose) {}

    private static final Map<String, JobInfo> JOBS = new LinkedHashMap<>();

    static {
        // Customers' money, accounts and data
        put("AccountPurgeSweepService.scheduledSweep", "Account deletion sweep",
                "Deletes accounts customers asked to delete, once the waiting period ends.");
        put("SubscriptionReconciliationSweepService.scheduledSweep", "Subscription period check",
                "Moves cancelled Razorpay subscriptions to Free when their paid period ends.");
        put("SubscriptionCancellationDispatchSweepService.scheduledSweep", "Subscription cancellation sender",
                "Sends customers' subscription cancellations to Razorpay.");
        put("LapsedSubscriptionCheckSweepService.scheduledSweep", "Lapsed subscription check",
                "Ends Plus for subscriptions whose provider event never arrived.");
        put("ReferralGrantSweepService.scheduledSweep", "Referral free months",
                "Starts and ends free months customers earned by referring friends.");
        put("WebhookEventRecoverySweepService.scheduledSweep", "Payment webhook recovery",
                "Retries payment webhooks that got stuck partway through.");
        put("NotificationDispatcher.poll", "Notification sender",
                "Sends queued push and email notifications, and recovers stuck ones.");
        put("PushCampaignScheduler.tick", "Push campaign starter",
                "Starts promotional push campaigns when their time slot comes.");
        put("StatementMonthStartNudge.scheduledRun", "Month-start statement reminder",
                "Reminds customers at the start of a month to import last month's statement.");
        put("UnconfirmedImportReminder.scheduledRun", "Unconfirmed import reminder",
                "Reminds customers to confirm an import before it is removed.");
        put("NetWorthSnapshotSweepService.scheduledSweep", "Daily net worth snapshot",
                "Saves each customer's net worth once a day so their history has no gaps.");
        put("HealthScoreSnapshotSweepService.scheduledSweep", "Monthly health score snapshot",
                "Saves each customer's financial health score for the month.");

        // Statements and imports
        put("ImportJobWorker.poll", "Statement import worker",
                "Picks up statement imports a nudge missed, retries, and abandoned ones.");
        put("HoldOverdueEscalationService.scheduled", "Overdue held statement alert",
                "Emails admins when a held statement passes the 48-hour promise.");
        put("FixJobFollowUp.scheduled", "Claude fix follow-up",
                "Checks GitHub for each open Claude fix job: PRs, second attempt or giving up.");
        put("StatementPasswordService.sweepUnusablePasswords", "Statement password cleanup",
                "Drops saved statement passwords no upload can use any more.");
        put("StatementRefreshDryRunService.scheduledRun", "Statement refresh dry run",
                "After a deploy, re-reads older statements and records what a refresh would change.");
        put("StatementRowOrderRepairSweepService.scheduledSweep", "Same-day row order repair",
                "Puts back in order same-day rows of statements that were saved reversed.");
        put("TransactionNarrationCleanupSweepService.scheduledSweep", "Narration cleanup",
                "Cleans narrations older imports saved, so they read like a fresh import.");
        put("StatementStorageSweepService.scheduledSweep", "Statement file cleanup",
                "Deletes stored statement files nothing refers to after 90 days.");
        put("StatementSendCodeRetentionSweep.sweep", "Send-a-statement code cleanup",
                "Deletes send-a-statement codes a day after they were issued.");
        put("ImportSessionService.scheduledSweep", "Expired import session cleanup",
                "Deletes import previews customers never confirmed, once they expire.");

        // Categories and merchants
        put("MerchantLearningEventWorker.poll", "Merchant learning worker",
                "Applies customers' category corrections a nudge missed, and recovers stuck ones.");
        put("CategorySuggestionSweepService.scheduledSweep", "Category suggestion recheck",
                "Re-checks rows waiting for review against the current category rules.");
        put("CounterpartyBackfillSweepService.scheduledSweep", "Counterparty backfill",
                "Types the counterparty on transactions imported before that existed.");
        put("InterestLabelRecheckSweepService.scheduledSweep", "Interest label recheck",
                "Gives stored interest credits the label a fresh import gives them now.");
        put("MerchantNobodyDetachSweepService.scheduledSweep", "Nameless merchant cleanup",
                "Detaches rows that name nobody from engine-made merchants.");
        put("MerchantRailNameSweepService.scheduledSweep", "Merchant name repair",
                "Renames merchants stored under a payment-rail name to their proper name.");
        put("SharedCorpusRetentionSweepService.sweep", "Shared category evidence cleanup",
                "Deletes unconfirmed shared category votes past their retention window.");
        put("SharedCorpusRevalidationSweepService.sweep", "Shared category recheck",
                "Re-decides shared categories whose 90-day recheck period has passed.");

        // Sign-in, security and audit
        put("AuditService.scheduledRedaction", "Audit log redaction",
                "Removes personal details from old audit log entries.");
        put("RefreshTokenService.scheduledCleanup", "Expired sign-in token cleanup",
                "Deletes expired sign-in refresh tokens.");
        put("EmailLoginOtpRetentionSweepService.sweep", "Email sign-in code cleanup",
                "Deletes old email sign-in codes.");
        put("PhoneIdentitySweepService.scheduledSweep", "Phone sign-in record cleanup",
                "Removes Firebase phone records for numbers no Fynora account holds.");
        put("CacheLockExpiry.scheduledRepair", "Cache lock repair",
                "Gives an expiry to any cache lock left in Redis without one.");

        // Integrations (most are switched off until launched)
        put("GmailDiscoveryWorker.scheduledDiscovery", "Gmail discovery",
                "Looks for new bank emails in connected Gmail accounts.");
        put("GmailConnectionService.scheduledStateSweep", "Gmail sign-in cleanup",
                "Deletes unfinished Gmail connection attempts.");
        put("AccountAggregatorLinkSweepService.scheduledSweep", "Account Aggregator link cleanup",
                "Expires Account Aggregator links customers never finished setting up.");
        put("AccountAggregatorLinkLifecycleSweepService.scheduledSweep", "Account Aggregator link check",
                "Pauses or ends Account Aggregator links that should no longer be active.");
        put("AccountAggregatorOutageSweepService.scheduledSweep", "Account Aggregator outage check",
                "Detects and reports Account Aggregator outages.");
        put("AccountAggregatorReconciliationSweepService.scheduledSweep", "Account Aggregator refresh",
                "Fetches fresh data for Account Aggregator links that missed an update.");

        // Diagnostics
        put("BackgroundJobAlertChecker.check", "Background job checker",
                "Emails admins when a background job breaks or recovers; prunes old failure records.");
        put("UserRequestLoad.flushIfMinuteElapsed", "Request load counter",
                "Publishes the past minute's customer request counts.");
        put("UserRequestLoad.logSummary", "Request load summary",
                "Logs customer request counts every 15 minutes.");
    }

    private BackgroundJobCatalog() {}

    private static void put(String key, String name, String purpose) {
        if (JOBS.putIfAbsent(key, new JobInfo(name, purpose)) != null) {
            throw new IllegalStateException("Duplicate background job key " + key);
        }
    }

    public static Optional<JobInfo> lookup(String key) {
        return Optional.ofNullable(JOBS.get(key));
    }

    public static Set<String> keys() {
        return Set.copyOf(JOBS.keySet());
    }
}
