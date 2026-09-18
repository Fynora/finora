package com.finora.dto;

import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
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
     *  {@code toolCallsJson} is included as-is -- it is this user's own conversation with Fyn, not
     *  data being sent onward to Claude (the never-raw boundary {@link
     *  com.finora.service.FynScreenshotOcrService}'s own doc comment describes governs what
     *  Finora sends TO Anthropic, not what it hands back to the user about their own account). */
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

    /** F-03. One Account Aggregator (Setu) consent/link -- {@code linkIdempotencyKey} and {@code
     *  resolutionClaimedAt} are deliberately left out, the same "internal bookkeeping, not data
     *  you provided" reasoning {@code subscription_events} gets in the excluded list: the first is
     *  a client-minted request-deduplication token with no meaning outside this backend, and the
     *  second is a re-entrancy claim marker written only by {@code
     *  AccountAggregatorLinkRepository#claimIdentityResolution}'s own atomic UPDATE, never
     *  observed by the user. {@code consentHandleId} IS included -- unlike those two, it is Setu's
     *  own external identifier for this user's actual consent, not internal plumbing. Contains no
     *  credential material (this table has none -- see the entity's own doc comment), the same
     *  guarantee {@code GmailConnectionExportDto} states explicitly for its table. */
    public record AccountAggregatorLinkExportDto(
            UUID id, UUID accountId, String consentHandleId, String fiType, String status,
            Instant consentExpiresAt, Instant lastSyncedAt, String lastSyncStatus,
            Instant createdAt, Instant updatedAt, Instant statusChangedAt
    ) {
        public static AccountAggregatorLinkExportDto from(AccountAggregatorLink l) {
            return new AccountAggregatorLinkExportDto(l.getId(), l.getAccountId(), l.getConsentHandleId(),
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
}
