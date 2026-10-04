package com.finora.dto;

import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.Payment;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.entity.ReferralGrant;
import com.finora.entity.StatementImportExcludedRow;
import com.finora.entity.SubscriptionOrder;
import com.finora.entity.TransactionRelationship;
import com.finora.entity.WalletLedgerEntry;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationPreference;
import com.finora.timeline.TimelineEvent;
import com.finora.entity.FeatureViewCount;
import com.finora.entity.HealthScoreSnapshot;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.Merchant;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.RecurringDismissal;
import com.finora.entity.Subscription;
import com.finora.entity.UserMerchantCategoryResolution;
import com.finora.goals.GoalContribution;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.onboarding.UserChecklistEvent;
import com.finora.onboarding.UserFinancialFocus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Download My Data" (Phase C of the account-lifecycle work). One container class holding nested
 * records, same convention as {@code ImportDto}/{@code StatementImportDto}.
 */
public final class DataExportDto {
    private DataExportDto() {}

    /**
     * Full-fidelity net worth snapshot -- {@code NetWorthDto.SnapshotPoint} drops id/totalAssets/
     * totalLiabilities and keeps only date+netWorth, which is the right shape for the Net Worth
     * chart but not for an export that owes the user everything stored about them.
     */
    public record NetWorthSnapshotExportDto(
            UUID id, LocalDate snapshotDate, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal netWorth
    ) {
        public static NetWorthSnapshotExportDto from(NetWorthSnapshot s) {
            return new NetWorthSnapshotExportDto(s.getId(), s.getSnapshotDate(), s.getTotalAssets(),
                    s.getTotalLiabilities(), s.getNetWorth());
        }
    }

    /**
     * Identity only -- deliberately excludes {@code topCategory}/{@code topCategoryConfidence}/
     * {@code distribution}, which {@code MerchantDto} derives from {@code
     * MerchantCategoryLearningRepository}, one of the tables this export's locked-in scope
     * explicitly excludes (derived categorization intelligence, not data the user provided).
     */
    public record MerchantExportDto(UUID id, String canonicalName, String logoUrl, String website, String lifecycleStatus) {
        public static MerchantExportDto from(Merchant m) {
            return new MerchantExportDto(m.getId(), m.getCanonicalName(), m.getLogoUrl(), m.getWebsite(),
                    m.getLifecycleStatus().name());
        }
    }

    /**
     * One {@code GmailConnection} row, any status -- live or historical. Carries the same "no
     * credential material" guarantee {@code GmailConnectionStatusDto}'s own doc states, but
     * without the {@code transactionsFound}/{@code needsReview} cross-table stats that only make
     * sense for a single live connection (and would need an extra query per row here).
     */
    public record GmailConnectionExportDto(
            String status, String googleEmail, List<String> grantedScopes,
            Instant connectedAt, Instant lastSyncedAt, Instant lastDiscoveryAt, Instant createdAt
    ) {
        public static GmailConnectionExportDto from(GmailConnection c) {
            List<String> scopes = c.getGrantedScopes() == null || c.getGrantedScopes().isBlank()
                    ? List.of()
                    : List.of(c.getGrantedScopes().split(" "));
            return new GmailConnectionExportDto(c.getStatus().name(), c.getGoogleEmail(), scopes,
                    c.getConnectedAt(), c.getLastSyncedAt(), c.getLastDiscoveryAt(), c.getCreatedAt());
        }
    }

    /**
     * accounts.json entries -- pairs {@code AccountDto} with the deleted marker that reading via
     * {@code AccountRepository.findByUserIdIncludingDeleted} (to mirror the purge's own scope,
     * which includes soft-deleted accounts) requires: {@code AccountDto.status()} is hardcoded
     * {@code "ACTIVE"} by {@code AccountService.listForUser}'s own design (see that method's doc
     * comment), which would misrepresent a soft-deleted account here.
     */
    public record AccountExportEntry(com.finora.accounts.AccountDto account, boolean deleted, Instant deletedAt) {}

    /** goals.json entries -- pairs {@code GoalDto} with the deleted marker that reading via
     *  {@code GoalRepository.findByUserIdIncludingDeleted} (to mirror the purge's own scope,
     *  which includes soft-deleted goals) requires, the same treatment {@code AccountExportEntry}
     *  already gives accounts. */
    public record GoalExportEntry(com.finora.goals.GoalDto goal, boolean deleted, Instant deletedAt) {}

    /** One {@code goal_contributions} row -- {@code goalId} is left as a raw FK, not resolved to
     *  the goal's name, the same way transactions.json leaves {@code accountId} raw: the goal it
     *  belongs to (name included) is already in goals.json, one file over. Sourced from the same
     *  including-deleted goal IDs as goals.json, so a soft-deleted goal's contribution history is
     *  still included here too, not silently dropped along with its goal. */
    public record GoalContributionExportDto(UUID id, UUID goalId, BigDecimal amount, LocalDate contributedAt) {
        public static GoalContributionExportDto from(GoalContribution c) {
            return new GoalContributionExportDto(c.getId(), c.getGoalId(), c.getAmount(), c.getContributedAt());
        }
    }

    /** D-28 PR4-A. One {@code subscriptions} row -- {@code planId} is resolved to the plan's own
     *  {@code code}/{@code name} rather than left as a raw FK, the same way transactions.json
     *  resolves {@code categoryId} to {@code categoryName}. {@code plan} is null only if the
     *  referenced plan row itself has been removed out from under a historical subscription --
     *  fails soft (null code/name) rather than dropping the subscription row from the export.
     *  {@code deleted}/{@code deletedAt} mirror {@code AccountExportEntry}'s own marker -- read
     *  via {@code SubscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc}, a
     *  soft-deleted subscription must still appear here, explicitly marked, not vanish. */
    public record SubscriptionExportDto(
            UUID id, String planCode, String planName, String status,
            LocalDate startDate, LocalDate endDate, LocalDate renewalDate,
            LocalDate trialStart, LocalDate trialEnd, String paymentProvider, Instant createdAt,
            boolean deleted, Instant deletedAt
    ) {
        public static SubscriptionExportDto from(Subscription s, Plan plan) {
            return new SubscriptionExportDto(s.getId(), plan != null ? plan.getCode() : null,
                    plan != null ? plan.getName() : null, s.getStatus(), s.getStartDate(), s.getEndDate(),
                    s.getRenewalDate(), s.getTrialStart(), s.getTrialEnd(), s.getPaymentProvider(), s.getCreatedAt(),
                    s.getDeletedAt() != null, s.getDeletedAt());
        }
    }

    /** D-28 PR4-A. One {@code plan_changes} row -- your upgrade/downgrade history.
     *  {@code subscriptionId} is left as a raw FK (the subscription it belongs to is one file
     *  over, in subscriptions.json), but {@code fromPlanId}/{@code toPlanId} are resolved to
     *  each plan's own {@code code}/{@code name}, same treatment as {@code
     *  SubscriptionExportDto}'s own {@code planId}. {@code fromPlan} is null both for a
     *  referenced plan row that no longer exists AND for a subscription's very first plan change
     *  (where {@code fromPlanId} itself is null) -- both fail soft rather than dropping the row. */
    public record PlanChangeExportDto(
            UUID id, UUID subscriptionId, String fromPlanCode, String fromPlanName,
            String toPlanCode, String toPlanName, String reason, Instant effectiveAt, Instant createdAt
    ) {
        public static PlanChangeExportDto from(PlanChange pc, Plan fromPlan, Plan toPlan) {
            return new PlanChangeExportDto(pc.getId(), pc.getSubscriptionId(),
                    fromPlan != null ? fromPlan.getCode() : null, fromPlan != null ? fromPlan.getName() : null,
                    toPlan != null ? toPlan.getCode() : null, toPlan != null ? toPlan.getName() : null,
                    pc.getReason(), pc.getEffectiveAt(), pc.getCreatedAt());
        }
    }

    public record Manifest(
            Instant generatedAt, UUID userId, String email,
            List<ManifestEntry> included, List<ManifestEntry> excluded
    ) {}

    public record ManifestEntry(String name, String description, Integer rowCount) {}

    /** Security/privacy audit finding F-03 (2026-09-18): a {@code chat_conversations} thread --
     *  {@code userId} is not repeated here since these are already scoped to one user's own
     *  export. */
    public record ChatConversationExportDto(UUID id, String title, Instant createdAt, Instant updatedAt) {
        public static ChatConversationExportDto from(ChatConversation c) {
            return new ChatConversationExportDto(c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** F-03. One turn in a Fyn chat thread -- {@code conversationId} is left as a raw FK, the
     *  same treatment {@code goal_contributions.json} gives {@code goalId}: the conversation it
     *  belongs to (title included) is one file over, in {@code fyn_chat_conversations.json}.
     *  {@code toolCallsJson} is included, unlike {@code FynChatDtos.ChatTurnDto} (the live {@code
     *  GET /chat/history} shape) which omits it -- checked what it actually holds before deciding
     *  this ({@code FynChatOrchestrationService#finish}, the only writer: {@code Map.of("tools",
     *  toolsUsed)}, a list of internal tool NAMES invoked that turn, never tool arguments or
     *  results, which are never persisted at all). {@code ChatTurnDto} most likely omits it only
     *  because the chat bubble UI has no use for "which tools ran," not for any privacy reason --
     *  and either way this content is safe: it names which of Fyn's own tools fired, not what they
     *  returned, so it carries none of the Tier 2-4 concern the F-04 fix addressed elsewhere. */
    public record ChatMessageExportDto(
            UUID id, UUID conversationId, String role, String content,
            Map<String, Object> toolCallsJson, String feedback, Instant createdAt
    ) {
        public static ChatMessageExportDto from(ChatMessage m) {
            return new ChatMessageExportDto(m.getId(), m.getConversationId(), m.getRole(), m.getContent(),
                    m.getToolCallsJson(), m.getFeedback(), m.getCreatedAt());
        }
    }

    /** F-03. One monthly financial-health-score snapshot -- full history, not the dashboard's own
     *  {@code findTop6ByUserIdOrderByYearMonthDesc} (this export owes the user everything stored
     *  about them, not just what the live Health Score card currently displays). */
    public record HealthScoreSnapshotExportDto(
            UUID id, String yearMonth, int overallScore, String label,
            double savingsRateScore, double debtScore, double emergencyFundScore,
            double spendConsistencyScore, double cashFlowStabilityScore, Instant computedAt
    ) {
        public static HealthScoreSnapshotExportDto from(HealthScoreSnapshot s) {
            return new HealthScoreSnapshotExportDto(s.getId(), s.getYearMonth(), s.getOverallScore(), s.getLabel(),
                    s.getSavingsRateScore(), s.getDebtScore(), s.getEmergencyFundScore(),
                    s.getSpendConsistencyScore(), s.getCashFlowStabilityScore(), s.getComputedAt());
        }
    }

    /** The answer to the required setup question "How do you keep track of your spending today?"
     *  -- a SpendingTrackingMethod name -- and when it was given (V256). */
    public record SpendingTrackingExportDto(String method, Instant answeredAt) {}

    /** F-03. One onboarding financial-focus selection. */
    public record UserFinancialFocusExportDto(UUID id, String focusKey, Instant createdAt) {
        public static UserFinancialFocusExportDto from(UserFinancialFocus f) {
            return new UserFinancialFocusExportDto(f.getId(), f.getFocusKey(), f.getCreatedAt());
        }
    }

    /** F-03. One completed onboarding checklist item -- no {@code id} field: {@link
     *  UserChecklistEvent} exposes no {@code getId()} (same as {@link RecurringDismissal} below),
     *  so {@code itemKey}+{@code completedAt} is all there is to export. */
    public record UserChecklistEventExportDto(String itemKey, Instant completedAt) {
        public static UserChecklistEventExportDto from(UserChecklistEvent e) {
            return new UserChecklistEventExportDto(e.getItemKey(), e.getCompletedAt());
        }
    }

    /** F-03. One dismissed recurring-transaction group -- no {@code id} field, same reason as
     *  {@link UserChecklistEventExportDto} above. */
    public record RecurringDismissalExportDto(String merchant, Instant dismissedAt) {
        public static RecurringDismissalExportDto from(RecurringDismissal d) {
            return new RecurringDismissalExportDto(d.getMerchant(), d.getDismissedAt());
        }
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    /** One {@code payments} row -- a charge for your plan. {@code planId} resolved to the plan's
     *  own code/name, the same treatment {@link SubscriptionExportDto} gives it. */
    public record PaymentExportDto(
            UUID id, UUID subscriptionId, String planCode, String planName, String billingCycle,
            BigDecimal amount, BigDecimal baseAmount, BigDecimal taxAmount, String currency,
            String provider, String providerTransactionId, String status, String invoiceId, String invoiceUrl,
            Instant createdAt, Instant updatedAt
    ) {
        public static PaymentExportDto from(Payment p, Plan plan) {
            return new PaymentExportDto(p.getId(), p.getSubscriptionId(), plan != null ? plan.getCode() : null,
                    plan != null ? plan.getName() : null, p.getBillingCycle(), p.getAmount(), p.getBaseAmount(),
                    p.getTaxAmount(), p.getCurrency(), p.getProvider(), p.getProviderTransactionId(), p.getStatus(),
                    p.getInvoiceId(), p.getInvoiceUrl(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    /** One {@code subscription_orders} row -- a checkout you started. {@code razorpaySubscriptionId}
     *  is left out, the same as {@link SubscriptionExportDto} leaves it out; the manifest's
     *  excluded list says so. */
    public record SubscriptionOrderExportDto(
            UUID id, String planCode, String planName, String billingCycle, String status, BigDecimal amount,
            Instant createdAt, Instant completedAt
    ) {
        public static SubscriptionOrderExportDto from(SubscriptionOrder o, Plan plan) {
            return new SubscriptionOrderExportDto(o.getId(), plan != null ? plan.getCode() : null,
                    plan != null ? plan.getName() : null, o.getBillingCycle(), o.getStatus(), o.getAmount(),
                    o.getCreatedAt(), o.getCompletedAt());
        }
    }

    /** One {@code referrals} row, from this user's side: {@code role} is REFERRER when they
     *  invited someone, REFERRED when someone invited them. The other person's account id is
     *  never exported -- it is their identifier, not this user's data. {@code reward} is the
     *  credit paid to the inviter, so it is kept only on REFERRER rows: the app shows it to the
     *  inviter (ReferralService.myReferrals) and never to the person they invited. */
    public record ReferralExportDto(UUID id, String role, String status, BigDecimal reward,
                                    Instant createdAt, Instant updatedAt) {
        public static final String ROLE_REFERRER = "REFERRER";
        public static final String ROLE_REFERRED = "REFERRED";

        public static ReferralExportDto from(Referral r, String role) {
            return new ReferralExportDto(r.getId(), role, r.getStatus(),
                    ROLE_REFERRER.equals(role) ? r.getReward() : null, r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** This user's own referral code and its milestone progress. */
    public record ReferralCodeExportDto(String code, int plusMilestoneCounter, int premiumMilestoneCounter,
                                        Instant createdAt) {
        public static ReferralCodeExportDto from(ReferralCode c) {
            return new ReferralCodeExportDto(c.getCode(), c.getPlusMilestoneCounter(), c.getPremiumMilestoneCounter(),
                    c.getCreatedAt());
        }
    }

    /** One {@code referral_grants} row -- a plan reward earned from a referral. */
    public record ReferralRewardExportDto(UUID id, String tier, String status, UUID earnedFromReferralId,
                                          Instant activatedAt, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        public static ReferralRewardExportDto from(ReferralGrant g) {
            return new ReferralRewardExportDto(g.getId(), g.getTier(), g.getStatus(), g.getEarnedFromReferralId(),
                    g.getActivatedAt(), g.getExpiresAt(), g.getCreatedAt(), g.getUpdatedAt());
        }
    }

    /** One {@code wallet_ledger} entry -- a credit or debit to this user's wallet. */
    public record WalletEntryExportDto(UUID id, BigDecimal amount, String reason, UUID referenceId, Instant createdAt) {
        public static WalletEntryExportDto from(WalletLedgerEntry e) {
            return new WalletEntryExportDto(e.getId(), e.getAmount(), e.getReason(), e.getReferenceId(), e.getCreatedAt());
        }
    }

    /** One notification this user was sent. Delivery bookkeeping ({@code notificationKey},
     *  {@code attemptCount}, {@code nextAttemptAt}, {@code lastError}) is left out; the manifest's
     *  excluded list says so. */
    public record NotificationExportDto(UUID id, String type, String category, String channel, String priority,
                                        String status, String title, String message, Instant sentAt, Instant readAt,
                                        Instant createdAt) {
        public static NotificationExportDto from(Notification n) {
            return new NotificationExportDto(n.getId(), name(n.getType()), name(n.getCategory()), name(n.getChannel()),
                    name(n.getPriority()), name(n.getStatus()), n.getTitle(), n.getMessage(), n.getSentAt(),
                    n.getReadAt(), n.getCreatedAt());
        }
    }

    /** One notification on/off choice, per category and channel. */
    public record NotificationPreferenceExportDto(String category, String channel, boolean enabled) {
        public static NotificationPreferenceExportDto from(NotificationPreference p) {
            return new NotificationPreferenceExportDto(name(p.getCategory()), name(p.getChannel()), p.isEnabled());
        }
    }

    /** One {@code timeline_events} row -- a milestone on this user's financial timeline. */
    public record TimelineEventExportDto(UUID id, String eventType, String bucket, String importance, boolean permanent,
                                         UUID referenceId, String title, String detail, Instant occurredAt,
                                         Instant createdAt) {
        public static TimelineEventExportDto from(TimelineEvent e) {
            return new TimelineEventExportDto(e.getId(), e.getEventType(), e.getBucket(), e.getImportance(),
                    e.isPermanent(), e.getReferenceId(), e.getTitle(), e.getDetail(), e.getOccurredAt(), e.getCreatedAt());
        }
    }

    /** One {@code transaction_relationships} row -- a link between two of this user's transactions
     *  (a transfer pair, a card payment and the charges it settled, a refund). Both ids are
     *  transaction ids from {@code transactions.json}. */
    public record TransactionLinkExportDto(UUID id, UUID fromTransactionId, UUID toTransactionId, String relationshipType,
                                           BigDecimal matchedAmount, String status, String detectionMethod,
                                           Integer confidence, Integer sourceTrust, UUID supersededBy, Instant createdAt) {
        public static TransactionLinkExportDto from(TransactionRelationship r) {
            return new TransactionLinkExportDto(r.getId(), r.getFromTransactionId(), r.getToTransactionId(),
                    name(r.getRelationshipType()), r.getMatchedAmount(), name(r.getStatus()),
                    name(r.getDetectionMethod()), r.getConfidence(), r.getSourceTrust(), r.getSupersededBy(),
                    r.getCreatedAt());
        }
    }

    /** One statement row this user chose to leave out of their ledger. {@code statementImportId}
     *  is the statement's id in {@code statements.json}. */
    public record StatementExcludedRowExportDto(UUID statementImportId, Integer rowPosition, LocalDate txnDate,
                                                String description, BigDecimal amount, String txnType,
                                                boolean likelyDuplicate, Instant createdAt) {
        public static StatementExcludedRowExportDto from(StatementImportExcludedRow r) {
            return new StatementExcludedRowExportDto(r.getStatementImportId(), r.getRowPosition(), r.getTxnDate(),
                    r.getDescription(), r.getAmount(), r.getTxnType(), r.isLikelyDuplicate(), r.getCreatedAt());
        }
    }

    /** One {@code counterparty_category_observation} row -- a category this user chose for a
     *  merchant's payments. */
    public record MerchantCategoryVoteExportDto(String counterpartyKey, String direction, String category,
                                                String counterpartyType, Instant createdAt) {
        public static MerchantCategoryVoteExportDto from(CounterpartyCategoryObservation o) {
            return new MerchantCategoryVoteExportDto(o.getCounterpartyKey(), name(o.getDirection()), o.getCategory(),
                    name(o.getCounterpartyTypeAtVote()), o.getCreatedAt());
        }
    }

    /** One per-feature view counter (V171) -- the running count behind the Billing page's usage
     *  tile. No {@code id} field: the row's surrogate key means nothing outside this database. */
    public record FeatureViewExportDto(String feature, int viewCount, Instant lastViewedAt) {
        public static FeatureViewExportDto from(FeatureViewCount f) {
            return new FeatureViewExportDto(f.getFeature(), f.getViewCount(), f.getLastViewedAt());
        }
    }

    /** F-03. One Account Aggregator (Setu) consent/link -- {@code consentHandleId}, {@code
     *  linkIdempotencyKey}, and {@code resolutionClaimedAt} are deliberately left out, the same
     *  "internal bookkeeping, not data you provided" reasoning {@code subscription_events} gets in
     *  the excluded list. Found in this class's own bugs-and-gaps review: an earlier version of
     *  this DTO included {@code consentHandleId} on the theory that it was "the user's own consent
     *  identifier," but grepping its actual call sites shows it used ONLY for backend-to-Setu
     *  correlation ({@code SetuConsentGatewayImpl#fetchConsentDetail}, {@code
     *  AccountAggregatorWebhookDispatcher#dispatch}'s webhook-to-link lookup) -- it is even run
     *  through {@code LogSanitizer} before logging, and the live, user-facing {@code
     *  AccountAggregatorLinkDto} (what the app's own linked-accounts screen actually returns)
     *  already excludes it. {@code linkIdempotencyKey} is a client-minted request-deduplication
     *  token with no meaning outside this backend; {@code resolutionClaimedAt} is a re-entrancy
     *  claim marker written only by {@code AccountAggregatorLinkRepository
     *  #claimIdentityResolution}'s own atomic UPDATE, never observed by the user. Contains no
     *  credential material (this table has none -- see the entity's own doc comment), the same
     *  guarantee {@code GmailConnectionExportDto} states explicitly for its table. */
    public record AccountAggregatorLinkExportDto(
            UUID id, UUID accountId, String fiType, String status,
            Instant consentExpiresAt, Instant lastSyncedAt, String lastSyncStatus,
            Instant createdAt, Instant updatedAt, Instant statusChangedAt
    ) {
        public static AccountAggregatorLinkExportDto from(AccountAggregatorLink l) {
            return new AccountAggregatorLinkExportDto(l.getId(), l.getAccountId(),
                    l.getFiType() == null ? null : l.getFiType().name(),
                    l.getStatus() == null ? null : l.getStatus().name(),
                    l.getConsentExpiresAt(), l.getLastSyncedAt(),
                    l.getLastSyncStatus() == null ? null : l.getLastSyncStatus().name(),
                    l.getCreatedAt(), l.getUpdatedAt(), l.getStatusChangedAt());
        }
    }

    /** F-03. One AI/human-resolved merchant-to-category mapping -- {@code categoryId} is resolved
     *  to the category's own {@code categoryName}, the same treatment {@code transactions.json}
     *  already gives its own {@code categoryId} (null only if the referenced category no longer
     *  exists, failing soft rather than dropping the row -- same convention {@code
     *  SubscriptionExportDto.planCode/planName} already uses for a missing {@code Plan}). */
    public record UserMerchantCategoryResolutionExportDto(
            UUID id, String counterpartyKey, String direction, UUID categoryId, String categoryName, Instant resolvedAt
    ) {
        public static UserMerchantCategoryResolutionExportDto from(UserMerchantCategoryResolution r, String categoryName) {
            return new UserMerchantCategoryResolutionExportDto(r.getId(), r.getCounterpartyKey(),
                    r.getDirection() == null ? null : r.getDirection().name(), r.getCategoryId(), categoryName, r.getResolvedAt());
        }
    }

    /** Plan 2: a kind the user can give money coming in, and whether it counts as income. */
    public record InflowKindExportDto(UUID id, String name, boolean countsAsIncome, String builtIn) {
        public static InflowKindExportDto from(com.finora.entity.InflowKind k) {
            return new InflowKindExportDto(k.getId(), k.getName(), k.isCountsAsIncome(),
                    k.getBuiltIn() == null ? null : k.getBuiltIn().name());
        }
    }

    /** Plan 2: "every payment from this sender is <kind>". {@code senderKey} is the key Finora
     *  derives from the payment narration (a UPI handle, or a name fragment). */
    public record SenderInflowRuleExportDto(UUID id, String senderKey, UUID inflowKindId, String kindName,
                                            java.time.Instant updatedAt) {}

    /** Plan 2: a kind the user chose for one payment on its own. */
    public record PaymentInflowChoiceExportDto(UUID transactionId, UUID inflowKindId, String kindName) {}
}
