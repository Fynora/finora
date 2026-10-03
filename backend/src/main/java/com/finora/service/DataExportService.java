package com.finora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.accounts.AccountDto;
import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import com.finora.dto.CategoryDto;
import com.finora.dto.DataExportDto.AccountAggregatorLinkExportDto;
import com.finora.dto.DataExportDto.AccountExportEntry;
import com.finora.dto.DataExportDto.InflowKindExportDto;
import com.finora.dto.DataExportDto.PaymentInflowChoiceExportDto;
import com.finora.dto.DataExportDto.SenderInflowRuleExportDto;
import com.finora.dto.DataExportDto.ChatConversationExportDto;
import com.finora.dto.DataExportDto.ChatMessageExportDto;
import com.finora.dto.DataExportDto.FeatureViewExportDto;
import com.finora.dto.DataExportDto.PaymentExportDto;
import com.finora.dto.DataExportDto.SubscriptionOrderExportDto;
import com.finora.dto.DataExportDto.ReferralExportDto;
import com.finora.dto.DataExportDto.ReferralCodeExportDto;
import com.finora.dto.DataExportDto.ReferralRewardExportDto;
import com.finora.dto.DataExportDto.WalletEntryExportDto;
import com.finora.dto.DataExportDto.NotificationExportDto;
import com.finora.dto.DataExportDto.NotificationPreferenceExportDto;
import com.finora.dto.DataExportDto.TimelineEventExportDto;
import com.finora.dto.DataExportDto.TransactionLinkExportDto;
import com.finora.dto.DataExportDto.StatementExcludedRowExportDto;
import com.finora.dto.DataExportDto.MerchantCategoryVoteExportDto;
import com.finora.dto.DataExportDto.GmailConnectionExportDto;
import com.finora.dto.DataExportDto.GoalContributionExportDto;
import com.finora.dto.DataExportDto.GoalExportEntry;
import com.finora.dto.DataExportDto.HealthScoreSnapshotExportDto;
import com.finora.dto.DataExportDto.Manifest;
import com.finora.dto.DataExportDto.ManifestEntry;
import com.finora.dto.DataExportDto.MerchantExportDto;
import com.finora.dto.DataExportDto.NetWorthSnapshotExportDto;
import com.finora.dto.DataExportDto.PlanChangeExportDto;
import com.finora.dto.DataExportDto.RecurringDismissalExportDto;
import com.finora.dto.DataExportDto.SubscriptionExportDto;
import com.finora.dto.DataExportDto.UserChecklistEventExportDto;
import com.finora.dto.DataExportDto.UserFinancialFocusExportDto;
import com.finora.dto.DataExportDto.UserMerchantCategoryResolutionExportDto;
import com.finora.dto.ImportDto.ImportSessionSummaryDto;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.RelationshipDto;
import com.finora.dto.StatementImportDto.Summary;
import com.finora.dto.UserSettingsDto;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.ChatConversation;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.Subscription;
import com.finora.entity.SupportTicket;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.Goal;
import com.finora.goals.GoalContributionRepository;
import com.finora.goals.GoalDto;
import com.finora.goals.GoalRepository;
import com.finora.imports.ImportSessionService;
import com.finora.imports.jobs.ImportJobDto;
import com.finora.imports.storage.StatementContentService;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.onboarding.UserChecklistEventRepository;
import com.finora.onboarding.UserFinancialFocusRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ChatConversationRepository;
import com.finora.repository.ChatMessageRepository;
import com.finora.repository.FeatureViewCountRepository;
import com.finora.repository.PaymentRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.WalletLedgerRepository;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.timeline.TimelineEventRepository;
import com.finora.repository.TransactionRelationshipRepository;
import com.finora.repository.StatementImportExcludedRowRepository;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.StatementRefreshRunRepository;
import com.finora.dto.StatementRefreshDtos.RefreshRunDetail;
import com.finora.imports.refresh.StatementRefreshUserService;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.HealthScoreSnapshotRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RecurringDismissalRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketAttachmentRepository.AttachmentMetadata;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.rules.RuleDto;
import com.finora.support.FeedbackDto;
import com.finora.support.SupportTicketDto;
import com.finora.transactions.TransactionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Download My Data" (Phase C of the account-lifecycle work). Scope deliberately mirrors {@link
 * AccountPurgeSweepService}'s own purge scope exactly -- the same tables that get deleted/
 * anonymized there are the ones read here. Excluded, for the same reasons that class's own doc
 * comment excludes them: {@code audit_logs} (the app's own accountability record, not the user's
 * data), {@code statement_analysis_sessions} (layout-intelligence evidence, and already stripped
 * of anything personal for a purged user), {@code refresh_tokens}/device-session history (login
 * bookkeeping), {@code password_history} (never had anything to show), and the merchant-learning
 * tables ({@code merchant_aliases}/{@code merchant_category_map}/{@code
 * merchant_category_learning}/{@code merchant_learning_audit}/{@code merchant_learning_event} --
 * derived categorization intelligence, not data the user provided). {@code subscription_events}
 * (D-28 PR4-A) joins that same excluded set for the same reason as {@code audit_logs}: an
 * internal lifecycle/analytics log, not data the user provided -- the subscription itself, and
 * its upgrade/downgrade history, are still read, into {@code subscriptions.json} and {@code
 * plan_changes.json} respectively.
 *
 * <p>Support module (Phase 7): {@code support_tickets.json} and {@code feedback.json} read
 * {@code SupportTicketDto.Detail}/{@code FeedbackDto.Summary} directly rather than duplicating
 * export-only DTOs -- the same shapes the ticket-detail and feedback-list endpoints already
 * return. Two more exclusions join the set above, both with their own manifest entries: attachment
 * *bytes* (filenames are still in {@code support_tickets.json}; the files themselves were never
 * this class's job to route around {@code com.finora.imports.storage}, and never will be while
 * they stay on {@code SupportTicketAttachment} rather than that storage layer) and {@code
 * support_ticket_internal_notes} (Finora's own operational record on a ticket, not the user's
 * data -- V147's own migration comment states the same exclusion for the same reason).
 *
 * <h2>F-03 fix (security/privacy audit, 2026-09-18)</h2>
 * The "mirrors the purge scope exactly" claim above was false for eight tables until this fix:
 * {@code chat_conversations}/{@code chat_messages}, {@code health_score_snapshot}, {@code
 * user_financial_focus}, {@code user_checklist_events}, {@code recurring_dismissals}, {@code
 * account_aggregator_links}, and {@code user_merchant_category_resolution} were all already in
 * {@link AccountPurgeSweepService}'s purge scope (several added there by the same audit's F-01/F-02
 * fix) but were never read by this class at all -- not exported, and not even listed in the
 * excluded set below, so the manifest itself gave no indication they existed. See {@link
 * #buildBundle}'s own comment on the block that fetches them for what each one now produces.
 *
 * <p>The same drift had left twenty-five more tables the purge deletes directly out of the manifest entirely
 * (payments, referrals, wallet, notifications, timeline, transaction links and others, plus every
 * sign-in token table). Each is now exported or listed as excluded with a reason, and {@code
 * DataExportServiceTest.everyPurgedTableIsExportedOrListedAsExcluded} reads {@link
 * AccountPurgeSweepService}'s source so the next purged table cannot be missed silently.
 *
 * <h2>Two phases, for one specific reason</h2>
 * {@link #buildBundle} runs entirely inside one {@code @Transactional(readOnly = true)} call,
 * synchronously, before the controller returns anything -- if it throws, the caller gets a normal
 * clean error response, no ZIP bytes ever sent. It deliberately does NOT resolve any statement's
 * original file bytes. {@link #writeZip} runs afterward, inside the {@code StreamingResponseBody}
 * callback the controller wires up -- which Spring runs on a separate thread, after the controller
 * method (and its transaction) has already returned. {@link #writeZip} resolves each statement's
 * bytes fresh, once per statement, via {@code StatementImportService#getFile} rather than touching
 * a {@code StatementImport} entity captured back in {@link #buildBundle} -- not because {@code
 * fileContent}'s {@code @Basic(fetch = FetchType.LAZY)} would throw outside its owning transaction
 * (bytecode enhancement, which this build does not configure, is required for Hibernate to honor
 * that annotation on a non-{@code @Lob} field at all -- without it the field loads eagerly
 * regardless), but because {@link #buildBundle} never loads a full {@code StatementImport} entity
 * in the first place: {@code StatementImportRepository.findMetadataByUserIdOrderByImportedAtDesc}
 * projects out every column except {@code fileContent}, so the bulk fetch that produces {@code
 * statementSummaries} below can never pull a user's entire statement history's raw bytes into heap
 * during this transaction, whether or not the LAZY annotation actually does anything.
 *
 * <h2>Uploads with no live statement ({@code imports/})</h2>
 * {@code statements/} only ever covered live {@code statement_imports} rows. An upload that was
 * held for review, is still queued, failed, was cancelled, was staged and never confirmed, or
 * whose statement was later deleted has no such row, yet Finora still holds its file in one of
 * two places, both exported under {@code imports/}:
 * <ul>
 *   <li>an {@code import_jobs} row's object (the asynchronous upload path), read through the same
 *       {@link StatementContentService#read} path the import worker uses -- unless the storage
 *       sweep has released it ({@code object_released_at}, V250);</li>
 *   <li>an {@code import_sessions} row's {@code file_content} (the synchronous stage path, which
 *       never creates a job), read via {@code ImportSessionService.readOwnedFileContent} -- which,
 *       unlike {@code getOwnedSession}, does not refuse a session past its expiry that the TTL
 *       sweep has not removed yet.</li>
 * </ul>
 * Same placeholder-on-failure handling as {@code statements/}. Each document appears once, matched
 * by {@code content_hash} (the SHA-256 of the original bytes, recorded on all three tables): a
 * document already under {@code statements/} is skipped, then the newest job holding it wins, then
 * the newest session. Never matched by {@code object_key} -- a job's object and the confirmed
 * statement's object are separate writes, each encrypted under a fresh random IV, so their keys
 * never agree even for identical bytes. A session from before V79 has no hash and so cannot be
 * matched; it is exported rather than risk leaving it out.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);

    private final UserRepository userRepository;
    private final GoogleReauthVerifier googleReauthVerifier;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetService budgetService;
    private final GoalRepository goalRepository;
    private final GoalContributionRepository goalContributionRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final RelationshipService relationshipService;
    private final NetWorthSnapshotRepository netWorthSnapshotRepository;
    private final MerchantRepository merchantRepository;
    private final ImportJobRepository importJobRepository;
    private final ImportSessionRepository importSessionRepository;
    private final ImportSessionService importSessionService;
    private final StatementImportRepository statementImportRepository;
    private final StatementImportService statementImportService;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final UserSettingsService userSettingsService;
    private final WorkspaceSettingsService workspaceSettingsService;
    private final BankManagementService bankManagementService;
    private final AuditService auditService;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PlanChangeRepository planChangeRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final SupportTicketAttachmentRepository supportTicketAttachmentRepository;
    private final FeedbackEntryRepository feedbackEntryRepository;
    // Security/privacy audit finding F-03 (2026-09-18): none of these eight were read by this
    // class at all, despite every one of them being in AccountPurgeSweepService's own purge scope
    // (several added there by the same audit's F-01/F-02 fix) -- this class's own doc comment
    // claims its scope "mirrors AccountPurgeSweepService's own purge scope exactly," which was
    // false for these until now.
    private final ChatConversationRepository chatConversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final HealthScoreSnapshotRepository healthScoreSnapshotRepository;
    private final UserFinancialFocusRepository userFinancialFocusRepository;
    private final UserChecklistEventRepository userChecklistEventRepository;
    private final RecurringDismissalRepository recurringDismissalRepository;
    private final AccountAggregatorLinkRepository accountAggregatorLinkRepository;
    private final UserMerchantCategoryResolutionRepository userMerchantCategoryResolutionRepository;
    private final com.finora.repository.InflowKindRepository inflowKindRepository;
    private final com.finora.repository.SenderInflowRuleRepository senderInflowRuleRepository;
    private final com.finora.repository.StatementPasswordRepository statementPasswordRepository;
    private final FeatureViewCountRepository featureViewCountRepository;
    // Every one of these is in AccountPurgeSweepService's purge scope but was neither exported nor
    // listed as excluded -- the same silent gap F-03 closed for eight other tables. See
    // DataExportServiceTest.everyPurgedTableIsExportedOrListedAsExcluded, which now fails when the
    // purge gains a table this class does not account for.
    private final PaymentRepository paymentRepository;
    private final SubscriptionOrderRepository subscriptionOrderRepository;
    private final ReferralRepository referralRepository;
    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralGrantRepository referralGrantRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final TimelineEventRepository timelineEventRepository;
    private final TransactionRelationshipRepository transactionRelationshipRepository;
    private final StatementImportExcludedRowRepository statementImportExcludedRowRepository;
    private final CounterpartyCategoryObservationRepository counterpartyCategoryObservationRepository;
    private final StatementRefreshRunRepository statementRefreshRunRepository;
    private final com.finora.repository.UserActivityDayRepository userActivityDayRepository;
    private final StatementContentService statementContentService;
    private final ObjectMapper objectMapper;

    public DataExportService(UserRepository userRepository, GoogleReauthVerifier googleReauthVerifier,
                              AccountRepository accountRepository, TransactionRepository transactionRepository,
                              BudgetService budgetService, GoalRepository goalRepository,
                              GoalContributionRepository goalContributionRepository, CategoryRepository categoryRepository,
                              CategoryRuleRepository categoryRuleRepository, RelationshipService relationshipService,
                              NetWorthSnapshotRepository netWorthSnapshotRepository, MerchantRepository merchantRepository,
                              ImportJobRepository importJobRepository, ImportSessionRepository importSessionRepository,
                              ImportSessionService importSessionService, StatementImportRepository statementImportRepository,
                              StatementImportService statementImportService, GmailConnectionRepository gmailConnectionRepository,
                              UserSettingsService userSettingsService, WorkspaceSettingsService workspaceSettingsService,
                              BankManagementService bankManagementService, AuditService auditService,
                              SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                              PlanChangeRepository planChangeRepository,
                              SupportTicketRepository supportTicketRepository,
                              SupportTicketAttachmentRepository supportTicketAttachmentRepository,
                              FeedbackEntryRepository feedbackEntryRepository,
                              ChatConversationRepository chatConversationRepository,
                              ChatMessageRepository chatMessageRepository,
                              HealthScoreSnapshotRepository healthScoreSnapshotRepository,
                              UserFinancialFocusRepository userFinancialFocusRepository,
                              UserChecklistEventRepository userChecklistEventRepository,
                              RecurringDismissalRepository recurringDismissalRepository,
                              AccountAggregatorLinkRepository accountAggregatorLinkRepository,
                              UserMerchantCategoryResolutionRepository userMerchantCategoryResolutionRepository,
                              com.finora.repository.InflowKindRepository inflowKindRepository,
                              com.finora.repository.SenderInflowRuleRepository senderInflowRuleRepository,
                              ObjectMapper objectMapper,
                              com.finora.repository.StatementPasswordRepository statementPasswordRepository,
                              FeatureViewCountRepository featureViewCountRepository,
                              PaymentRepository paymentRepository,
                              SubscriptionOrderRepository subscriptionOrderRepository,
                              ReferralRepository referralRepository,
                              ReferralCodeRepository referralCodeRepository,
                              ReferralGrantRepository referralGrantRepository,
                              WalletLedgerRepository walletLedgerRepository,
                              NotificationRepository notificationRepository,
                              NotificationPreferenceRepository notificationPreferenceRepository,
                              TimelineEventRepository timelineEventRepository,
                              TransactionRelationshipRepository transactionRelationshipRepository,
                              StatementImportExcludedRowRepository statementImportExcludedRowRepository,
                              CounterpartyCategoryObservationRepository counterpartyCategoryObservationRepository,
                              StatementRefreshRunRepository statementRefreshRunRepository,
                              com.finora.repository.UserActivityDayRepository userActivityDayRepository,
                              StatementContentService statementContentService) {
        this.statementPasswordRepository = statementPasswordRepository;
        this.featureViewCountRepository = featureViewCountRepository;
        this.paymentRepository = paymentRepository;
        this.subscriptionOrderRepository = subscriptionOrderRepository;
        this.referralRepository = referralRepository;
        this.referralCodeRepository = referralCodeRepository;
        this.referralGrantRepository = referralGrantRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.notificationRepository = notificationRepository;
        this.notificationPreferenceRepository = notificationPreferenceRepository;
        this.timelineEventRepository = timelineEventRepository;
        this.transactionRelationshipRepository = transactionRelationshipRepository;
        this.statementImportExcludedRowRepository = statementImportExcludedRowRepository;
        this.counterpartyCategoryObservationRepository = counterpartyCategoryObservationRepository;
        this.statementRefreshRunRepository = statementRefreshRunRepository;
        this.userActivityDayRepository = userActivityDayRepository;
        this.statementContentService = statementContentService;
        this.userRepository = userRepository;
        this.googleReauthVerifier = googleReauthVerifier;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetService = budgetService;
        this.goalRepository = goalRepository;
        this.goalContributionRepository = goalContributionRepository;
        this.categoryRepository = categoryRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.relationshipService = relationshipService;
        this.netWorthSnapshotRepository = netWorthSnapshotRepository;
        this.merchantRepository = merchantRepository;
        this.importJobRepository = importJobRepository;
        this.importSessionRepository = importSessionRepository;
        this.importSessionService = importSessionService;
        this.statementImportRepository = statementImportRepository;
        this.statementImportService = statementImportService;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.userSettingsService = userSettingsService;
        this.workspaceSettingsService = workspaceSettingsService;
        this.bankManagementService = bankManagementService;
        this.auditService = auditService;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.planChangeRepository = planChangeRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.supportTicketAttachmentRepository = supportTicketAttachmentRepository;
        this.feedbackEntryRepository = feedbackEntryRepository;
        this.chatConversationRepository = chatConversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.healthScoreSnapshotRepository = healthScoreSnapshotRepository;
        this.userFinancialFocusRepository = userFinancialFocusRepository;
        this.userChecklistEventRepository = userChecklistEventRepository;
        this.recurringDismissalRepository = recurringDismissalRepository;
        this.accountAggregatorLinkRepository = accountAggregatorLinkRepository;
        this.userMerchantCategoryResolutionRepository = userMerchantCategoryResolutionRepository;
        this.inflowKindRepository = inflowKindRepository;
        this.senderInflowRuleRepository = senderInflowRuleRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Phase 1: proves current-password, then gathers every in-scope table except statement file
     * bytes. Deliberately does NOT block {@code SUSPENDED}/{@code DEACTIVATED}/{@code
     * PENDING_DELETION}: a suspended or deactivated account has no path to a fresh login to even
     * reach this (the same "unreachable in practice" reasoning {@link AccountPurgeSweepService}
     * itself relies on) -- only a still-valid access token issued before the status changed
     * (up to 15 minutes stale) can. {@code PENDING_DELETION} is the same story now that deletion
     * is instant, not a deliberate window: it's either a sub-second state while {@code
     * UserAccountLifecycleService.requestDeletion}'s synchronous purge is actually in flight, or
     * an account stuck there because that purge failed and the crash-recovery sweep hasn't
     * retried it yet. Neither case is a reason to block a best-effort export -- there's no
     * "deliberate window" to protect anymore, and refusing one in the stuck case would only
     * penalize a user whose purge is the one that's broken.
     */
    @Transactional(readOnly = true)
    public ExportBundle buildBundle(UUID userId, String currentPassword, String googleIdToken, String appleIdToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (!googleReauthVerifier.verify(user, currentPassword, googleIdToken, appleIdToken)) {
            // recordEvenOnRollback, not record: this method is @Transactional(readOnly = true),
            // and throwing ApiException right after a plain record() call would roll the audit
            // write back along with the (nonexistent) rest of this transaction -- see that
            // method's own doc comment for how this was actually caught.
            auditService.recordEvenOnRollback(userId, "INVALID_CURRENT_PASSWORD", "User", userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, user.isGoogleAccount()
                    ? "We couldn't verify your Google account. Please try again."
                    : user.isAppleAccount()
                            ? "We couldn't verify your Apple account. Please try again."
                            : "Current password is incorrect.");
        }

        // Mirrors AccountPurgeSweepService.purgeOne()'s own table order.

        // Fetched once, ahead of accounts, and shared by both toAccountExportEntry (Finding 3:
        // per-account statementsCount/transactionsCount/lastImportedAt, computed the same way
        // AccountService.listForUser does it, batched rather than one query per account) and the
        // statement-summaries list below -- see StatementMetadata's own doc comment for why this
        // is a fileContent-free projection, not the entity-returning finder this class used to call.
        List<StatementMetadata> statementMetadata = statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId);
        Map<UUID, StatementMetadata> latestImportByAccount = new HashMap<>();
        Map<UUID, Integer> statementsCountByAccount = new HashMap<>();
        for (StatementMetadata m : statementMetadata) {
            latestImportByAccount.putIfAbsent(m.getAccountId(), m); // already ordered by importedAt desc
            statementsCountByAccount.merge(m.getAccountId(), 1, Integer::sum);
        }
        Map<UUID, Long> transactionsCountByAccount = transactionRepository.countByAccountForUser(userId).stream()
                .collect(Collectors.toMap(
                        TransactionRepository.AccountTransactionCount::getAccountId,
                        TransactionRepository.AccountTransactionCount::getCount));

        List<Account> accountEntities = accountRepository.findByUserIdIncludingDeleted(userId);
        List<AccountExportEntry> accounts = accountEntities.stream()
                .map(a -> toAccountExportEntry(a, latestImportByAccount, statementsCountByAccount, transactionsCountByAccount))
                .toList();

        // Fetched once and reused for both categoryNames (transactions.json's category label) and
        // categories.json itself -- this used to query categoryRepository.findByUserId(userId)
        // twice, a few lines apart, for the same rows.
        List<Category> userCategories = categoryRepository.findByUserId(userId);
        Map<UUID, String> categoryNames = userCategories.stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
        List<Transaction> userTransactions = transactionRepository.findByUserId(userId);
        List<TransactionDto> transactions = userTransactions.stream()
                .map(t -> TransactionDto.from(t, categoryNames.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();

        // Plan 2: the user's own answers about money coming in. TransactionDto carries no kind, so a
        // per-payment choice is exported beside it, keyed by transaction id.
        List<com.finora.entity.InflowKind> kinds = inflowKindRepository.findByUserId(userId);
        Map<UUID, String> kindNames = kinds.stream()
                .collect(Collectors.toMap(com.finora.entity.InflowKind::getId, com.finora.entity.InflowKind::getName));
        List<InflowKindExportDto> inflowKinds = kinds.stream().map(InflowKindExportDto::from).toList();
        List<SenderInflowRuleExportDto> senderInflowRules = senderInflowRuleRepository.findByUserId(userId).stream()
                .map(r -> new SenderInflowRuleExportDto(r.getId(), r.getCounterpartyKey(), r.getInflowKindId(),
                        kindNames.get(r.getInflowKindId()), r.getUpdatedAt()))
                .toList();
        List<PaymentInflowChoiceExportDto> paymentInflowChoices = userTransactions.stream()
                .filter(t -> t.getInflowKindId() != null)
                .map(t -> new PaymentInflowChoiceExportDto(t.getId(), t.getInflowKindId(),
                        kindNames.get(t.getInflowKindId())))
                .toList();

        // Statement refresh, step 4: which statements the user let Finora keep a password for, and
        // when they agreed. The consent record, never the password.
        List<com.finora.dto.SavedStatementPasswordDtos.SavedStatementPassword> savedStatementPasswords =
                statementPasswordRepository.listForUser(userId).stream()
                        .map(r -> new com.finora.dto.SavedStatementPasswordDtos.SavedStatementPassword(
                                r.getStatementImportId(), r.getFileName(), r.getAccountName(),
                                r.getPeriodStart(), r.getPeriodEnd(), r.getConsentedAt()))
                        .toList();

        // V249: the dates this account used the app. In the purge scope, so -- per the F-03 rule
        // above -- it is exported rather than silently left out. ISO strings, not LocalDate, so the
        // file's shape does not depend on the ObjectMapper's date settings.
        List<String> activityDays = userActivityDayRepository.findByUserIdOrderByActivityDateAsc(userId).stream()
                .map(d -> d.getActivityDate().toString())
                .toList();

        List<BudgetDto> budgets = budgetService.listForUser(userId);

        // Mirrors accounts.json's own findByUserIdIncludingDeleted treatment above -- reads
        // directly via GoalRepository rather than GoalService.listForUser, which is also the live
        // Goals page's own data source and stays filtered to non-deleted goals on purpose (see
        // GoalRepository.findByUserIdIncludingDeleted's own doc comment).
        List<Goal> goalEntities = goalRepository.findByUserIdIncludingDeleted(userId);
        List<GoalExportEntry> goals = goalEntities.stream()
                .map(g -> new GoalExportEntry(
                        new GoalDto(g.getId(), g.getName(), g.getTargetAmount(), g.getCurrentAmount(), g.getTargetDate()),
                        g.getDeletedAt() != null, g.getDeletedAt()))
                .toList();

        // Batched across every one of this user's goals (including soft-deleted ones, so a
        // deleted goal's contribution history still exports) rather than one query per goal.
        List<GoalContributionExportDto> goalContributions = goalContributionRepository
                .findByGoalIdInOrderByContributedAtDesc(goalEntities.stream().map(Goal::getId).toList()).stream()
                .map(GoalContributionExportDto::from)
                .toList();

        List<CategoryDto> categories = userCategories.stream()
                .map(c -> new CategoryDto(c.getId(), c.getName(), c.isSystem(), c.getIcon(), c.getColor(), c.getAiCreationReason()))
                .toList();
        List<RuleDto> categoryRules = categoryRuleRepository.findByUserId(userId).stream()
                .map(RuleDto::from)
                .toList();

        List<RelationshipDto> relationships = relationshipService.listForUser(userId);

        List<NetWorthSnapshotExportDto> netWorthSnapshots = netWorthSnapshotRepository
                .findByUserIdOrderBySnapshotDateAsc(userId).stream()
                .map(NetWorthSnapshotExportDto::from)
                .toList();

        List<MerchantExportDto> merchants = merchantRepository.findByUserId(userId).stream()
                .map(MerchantExportDto::from)
                .toList();

        List<ImportJob> importJobEntities = importJobRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged());
        List<ImportJobDto.Progress> importJobs = importJobEntities.stream()
                .map(ImportJobDto.Progress::of)
                .toList();

        // Bug fix (review): used to map every session unguarded -- one historical session whose
        // stagedRowsJson/sectionsJson fails to deserialize against the current record shape (e.g.
        // a future field rename) threw ImportSessionService.readJson's uncaught
        // IllegalStateException straight out of buildBundle, failing the user's entire export over
        // one unrelated, unreadable row. Same "one bad item doesn't sink the batch" discipline the
        // per-statement loop in writeZip already applies -- caught, logged, and that one session
        // dropped from the list rather than aborting everything else.
        List<ImportSession> importSessionEntities = importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<ImportSessionSummaryDto> importSessions = importSessionEntities.stream()
                .flatMap(session -> {
                    try {
                        return java.util.stream.Stream.of(toSessionSummary(session));
                    } catch (Exception e) {
                        log.warn("Data export: failed to summarize import session {} for user {}: {}",
                                session.getId(), userId, e.getMessage(), e);
                        return java.util.stream.Stream.empty();
                    }
                })
                .toList();

        Map<UUID, Integer> duplicateCounts = statementImportService.duplicateCountsByStatementImport(userId);
        List<Summary> statementSummaries = statementMetadata.stream()
                .map(s -> Summary.from(s, duplicateCounts.getOrDefault(s.getId(), 0)))
                .toList();

        // imports/ -- see this class's own doc section on it. Both entity lists are newest first,
        // and a hash is claimed by the first source to add it: statements, then jobs, then sessions.
        Set<String> claimedHashes = new HashSet<>(statementImportRepository.findContentHashesByUserId(userId));
        List<UnimportedUpload> unimportedUploads = new ArrayList<>();
        for (ImportJob job : importJobEntities) {
            // No address means no object to read, and a released one (V250) means the storage sweep
            // deleted it or found a live statement naming it -- either way the job holds nothing to
            // export. Skipped without claiming the hash, so a session still holding the same
            // document is exported instead.
            if (job.getContentHash() == null || job.getObjectKey() == null || job.getObjectReleasedAt() != null) continue;
            if (claimedHashes.add(job.getContentHash())) {
                unimportedUploads.add(new UnimportedUpload(UnimportedUpload.Source.IMPORT_JOB, job.getId(), job.getFileName()));
            }
        }
        for (ImportSession session : importSessionEntities) {
            if (session.getContentHash() == null || claimedHashes.add(session.getContentHash())) {
                unimportedUploads.add(new UnimportedUpload(UnimportedUpload.Source.IMPORT_SESSION, session.getId(), session.getFileName()));
            }
        }

        List<GmailConnectionExportDto> gmailConnections = gmailConnectionRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(GmailConnectionExportDto::from)
                .toList();

        UserSettingsDto userSettings = userSettingsService.get(userId);
        WorkspaceSettingsDto workspaceSettings = workspaceSettingsService.get(userId);

        // D-28 PR4-A: subscriptions and plan_changes are now in AccountPurgeSweepService's purge
        // scope (see SubscriptionRepository.hardDeleteByUserId's own doc comment -- plan_changes
        // cascades from subscriptions via subscription_id), so this class's own "mirrors the
        // purge scope exactly" rule means they belong here too. Reads via
        // findByUserIdIncludingDeletedOrderByCreatedAtDesc, the same soft-delete-aware treatment
        // accounts.json/goals.json already get.
        List<Subscription> subscriptions = subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId);
        List<PlanChange> planChanges = planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(
                subscriptions.stream().map(Subscription::getId).toList());

        List<com.finora.entity.Payment> payments = paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<com.finora.entity.SubscriptionOrder> subscriptionOrders =
                subscriptionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId);

        // One batched Plan lookup shared by every DTO below -- every planId a subscription is
        // currently on, plus every fromPlanId/toPlanId a plan_changes row ever referenced (a
        // downgrade's fromPlanId can be a plan the subscription isn't on anymore), plus the plan
        // each payment and checkout was for.
        Set<UUID> planIdsToResolve = new HashSet<>();
        subscriptions.forEach(s -> planIdsToResolve.add(s.getPlanId()));
        payments.forEach(p -> { if (p.getPlanId() != null) planIdsToResolve.add(p.getPlanId()); });
        subscriptionOrders.forEach(o -> { if (o.getPlanId() != null) planIdsToResolve.add(o.getPlanId()); });
        planChanges.forEach(pc -> {
            if (pc.getFromPlanId() != null) planIdsToResolve.add(pc.getFromPlanId());
            planIdsToResolve.add(pc.getToPlanId());
        });
        Map<UUID, Plan> plansById = planRepository.findAllById(planIdsToResolve.stream().toList()).stream()
                .collect(Collectors.toMap(Plan::getId, p -> p));

        // planId/fromPlanId/toPlanId resolved to the plan's own code/name, not left as raw FKs --
        // same treatment transactions.json already gives categoryId.
        List<SubscriptionExportDto> subscriptionExports = subscriptions.stream()
                .map(s -> SubscriptionExportDto.from(s, plansById.get(s.getPlanId())))
                .toList();
        List<PlanChangeExportDto> planChangeExports = planChanges.stream()
                .map(pc -> PlanChangeExportDto.from(pc, plansById.get(pc.getFromPlanId()), plansById.get(pc.getToPlanId())))
                .toList();
        List<PaymentExportDto> paymentExports = payments.stream()
                .map(p -> PaymentExportDto.from(p, plansById.get(p.getPlanId())))
                .toList();
        List<SubscriptionOrderExportDto> subscriptionOrderExports = subscriptionOrders.stream()
                .map(o -> SubscriptionOrderExportDto.from(o, plansById.get(o.getPlanId())))
                .toList();

        // Support module (Phase 7): support_tickets.json/feedback.json. Attachment BYTES and
        // internal notes are both excluded -- see buildManifest's own entries for why -- so this
        // reads only what SupportTicketDto.Detail/FeedbackDto.Summary already expose, the same
        // shapes the ticket-detail and feedback-list endpoints return, reused rather than
        // duplicated into export-only DTOs.
        List<SupportTicket> supportTickets = supportTicketRepository
                .findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).getContent();
        Map<UUID, List<SupportTicketDto.AttachmentSummary>> attachmentsByTicket = supportTicketAttachmentRepository
                .findMetadataByTicketIdIn(supportTickets.stream().map(SupportTicket::getId).toList()).stream()
                .collect(Collectors.groupingBy(AttachmentMetadata::getTicketId,
                        Collectors.mapping(SupportTicketDto.AttachmentSummary::from, Collectors.toList())));
        List<SupportTicketDto.Detail> supportTicketExports = supportTickets.stream()
                .map(t -> SupportTicketDto.Detail.from(t, attachmentsByTicket.getOrDefault(t.getId(), List.of())))
                .toList();

        List<FeedbackDto.Summary> feedbackExports = feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(FeedbackDto.Summary::from)
                .toList();

        // F-03 fix (audit, 2026-09-18): the eight categories below were never read by this class
        // at all -- see the field comments on chatConversationRepository etc. for the full finding.
        List<ChatConversationExportDto> chatConversations = chatConversationRepository
                .findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ChatConversationExportDto::from)
                .toList();
        // Mirrors goal_contributions.json's own batched-by-parent-id treatment: every message for
        // every one of this user's conversations in one query, not one query per conversation.
        List<ChatMessageExportDto> chatMessages = chatMessageRepository
                .findByConversationIdInOrderByCreatedAtAsc(
                        chatConversations.stream().map(ChatConversationExportDto::id).toList())
                .stream()
                .map(ChatMessageExportDto::from)
                .toList();

        List<HealthScoreSnapshotExportDto> healthScoreHistory = healthScoreSnapshotRepository
                .findByUserIdOrderByYearMonthAsc(userId).stream()
                .map(HealthScoreSnapshotExportDto::from)
                .toList();

        List<UserFinancialFocusExportDto> financialFocus = userFinancialFocusRepository.findByUserId(userId).stream()
                .map(UserFinancialFocusExportDto::from)
                .toList();
        List<UserChecklistEventExportDto> checklistEvents = userChecklistEventRepository.findByUserId(userId).stream()
                .map(UserChecklistEventExportDto::from)
                .toList();

        List<RecurringDismissalExportDto> recurringDismissals = recurringDismissalRepository.findByUserId(userId).stream()
                .map(RecurringDismissalExportDto::from)
                .toList();

        List<AccountAggregatorLinkExportDto> accountAggregatorLinks = accountAggregatorLinkRepository.findByUserId(userId).stream()
                .map(AccountAggregatorLinkExportDto::from)
                .toList();

        // categoryId resolved to categoryName via the same categoryNames map transactions.json
        // already built above -- one more reuse of that batched lookup, not a second
        // categoryRepository query. Null categoryName (never null categoryId) means the
        // referenced category no longer exists -- fails soft, same convention
        // SubscriptionExportDto.planCode/planName already uses for a missing Plan.
        List<UserMerchantCategoryResolutionExportDto> merchantCategoryResolutions = userMerchantCategoryResolutionRepository
                .findAllByUserId(userId).stream()
                .map(r -> UserMerchantCategoryResolutionExportDto.from(r, categoryNames.get(r.getCategoryId())))
                .toList();

        // feature_view_counts (V171) was in AccountPurgeSweepService's purge scope but read nowhere
        // here -- the same silent gap F-03 closed for the eight tables above.
        List<FeatureViewExportDto> featureViews = featureViewCountRepository.findByUserIdOrderByFeatureAsc(userId).stream()
                .map(FeatureViewExportDto::from)
                .toList();

        // Both sides of referrals: rows where this user invited someone, and the one row (if any)
        // where someone invited them. The other person's account id never leaves -- see
        // ReferralExportDto.
        List<ReferralExportDto> referrals = new java.util.ArrayList<>();
        referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(userId)
                .forEach(r -> referrals.add(ReferralExportDto.from(r, ReferralExportDto.ROLE_REFERRER)));
        referralRepository.findByReferredUserId(userId)
                .ifPresent(r -> referrals.add(ReferralExportDto.from(r, ReferralExportDto.ROLE_REFERRED)));
        List<ReferralCodeExportDto> referralCode = referralCodeRepository.findByUserId(userId).stream()
                .map(ReferralCodeExportDto::from)
                .toList();
        List<ReferralRewardExportDto> referralRewards = referralGrantRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ReferralRewardExportDto::from)
                .toList();
        List<WalletEntryExportDto> wallet = walletLedgerRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(WalletEntryExportDto::from)
                .toList();

        List<NotificationExportDto> notifications = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationExportDto::from)
                .toList();
        List<NotificationPreferenceExportDto> notificationPreferences = notificationPreferenceRepository.findByUserId(userId).stream()
                .map(NotificationPreferenceExportDto::from)
                .toList();

        List<TimelineEventExportDto> timeline = timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .map(TimelineEventExportDto::from)
                .toList();
        List<TransactionLinkExportDto> transactionLinks = transactionRelationshipRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(TransactionLinkExportDto::from)
                .toList();
        List<StatementExcludedRowExportDto> statementExcludedRows = statementImportExcludedRowRepository
                .findByUserIdOrderByStatementImportIdAscRowPositionAsc(userId).stream()
                .map(StatementExcludedRowExportDto::from)
                .toList();
        List<MerchantCategoryVoteExportDto> merchantCategoryVotes = counterpartyCategoryObservationRepository
                .findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(MerchantCategoryVoteExportDto::from)
                .toList();

        // The same "what changed" view the app shows for one refresh (StatementRefreshUserService.
        // detail), statement name/period/account resolved from rows already fetched above. A run
        // keeps the description and amount of every transaction it removed; nothing else does.
        Map<UUID, StatementMetadata> statementsById = new HashMap<>();
        statementMetadata.forEach(m -> statementsById.put(m.getId(), m));
        Map<UUID, String> accountNames = new HashMap<>();
        accountEntities.forEach(a -> accountNames.put(a.getId(), a.getName()));
        List<RefreshRunDetail> statementRefreshRuns = statementRefreshRunRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(run -> {
                    StatementMetadata m = statementsById.get(run.getStatementImportId());
                    return StatementRefreshUserService.detail(run, m == null ? null : m.getFileName(),
                            m == null ? null : accountNames.get(m.getAccountId()),
                            m == null ? null : m.getStatementPeriodStart(), m == null ? null : m.getStatementPeriodEnd());
                })
                .toList();

        return new ExportBundle(userId, user.getEmail(), accounts, transactions, budgets, goals, goalContributions,
                categories, categoryRules, relationships, netWorthSnapshots, merchants, importJobs, importSessions,
                statementSummaries, unimportedUploads, gmailConnections, userSettings, workspaceSettings, subscriptionExports, planChangeExports,
                supportTicketExports, feedbackExports, chatConversations, chatMessages, healthScoreHistory,
                financialFocus, checklistEvents, recurringDismissals, accountAggregatorLinks, merchantCategoryResolutions,
                inflowKinds, senderInflowRules, paymentInflowChoices, savedStatementPasswords, featureViews,
                paymentExports, subscriptionOrderExports, referrals, referralCode, referralRewards, wallet,
                notifications, notificationPreferences, timeline, transactionLinks, statementExcludedRows,
                merchantCategoryVotes, statementRefreshRuns, activityDays);
    }

    /**
     * Phase 2: assembles the ZIP and streams it. Every statement's bytes are resolved via {@link
     * StatementImportService#getFile} individually, right here -- see this class's own doc comment
     * on why that has to happen from inside this method, not from data captured in {@link
     * #buildBundle}.
     *
     * <p>One statement's storage failure doesn't abort the export -- caught, logged, replaced with
     * a placeholder entry, and the rest continues. This is the same "one bad row doesn't sink the
     * batch" discipline {@link AccountPurgeSweepService} already established for its own per-
     * statement loop. {@code imports/} entries get the identical treatment, via the same {@link
     * #writeStoredFile}.
     */
    public void writeZip(UUID userId, ExportBundle bundle, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeJsonEntry(zos, "manifest.json", buildManifest(bundle));
            writeTextEntry(zos, "README.txt", buildReadmeText());

            writeJsonEntry(zos, "accounts.json", bundle.accounts());
            writeJsonEntry(zos, "transactions.json", bundle.transactions());
            writeJsonEntry(zos, "budgets.json", bundle.budgets());
            writeJsonEntry(zos, "goals.json", bundle.goals());
            writeJsonEntry(zos, "goal_contributions.json", bundle.goalContributions());
            writeJsonEntry(zos, "categories.json", bundle.categories());
            writeJsonEntry(zos, "category_rules.json", bundle.categoryRules());
            writeJsonEntry(zos, "relationships.json", bundle.relationships());
            writeJsonEntry(zos, "net_worth_history.json", bundle.netWorthSnapshots());
            writeJsonEntry(zos, "merchants.json", bundle.merchants());
            writeJsonEntry(zos, "import_jobs.json", bundle.importJobs());
            writeJsonEntry(zos, "import_sessions.json", bundle.importSessions());
            writeJsonEntry(zos, "statements.json", bundle.statementSummaries());
            writeJsonEntry(zos, "gmail_connection.json", bundle.gmailConnections());
            writeJsonEntry(zos, "account_settings.json", bundle.userSettings());
            writeJsonEntry(zos, "workspace_settings.json", bundle.workspaceSettings());
            writeJsonEntry(zos, "subscriptions.json", bundle.subscriptions());
            writeJsonEntry(zos, "plan_changes.json", bundle.planChanges());
            writeJsonEntry(zos, "support_tickets.json", bundle.supportTickets());
            writeJsonEntry(zos, "feedback.json", bundle.feedback());
            // F-03 fix (audit, 2026-09-18) -- see buildBundle's own comment on this same block.
            writeJsonEntry(zos, "fyn_chat_conversations.json", bundle.chatConversations());
            writeJsonEntry(zos, "fyn_chat_messages.json", bundle.chatMessages());
            writeJsonEntry(zos, "health_score_history.json", bundle.healthScoreHistory());
            writeJsonEntry(zos, "financial_focus.json", bundle.financialFocus());
            writeJsonEntry(zos, "onboarding_checklist.json", bundle.checklistEvents());
            writeJsonEntry(zos, "recurring_dismissals.json", bundle.recurringDismissals());
            writeJsonEntry(zos, "account_aggregator_links.json", bundle.accountAggregatorLinks());
            writeJsonEntry(zos, "merchant_category_corrections.json", bundle.merchantCategoryResolutions());
            writeJsonEntry(zos, "money_kinds.json", bundle.inflowKinds());
            writeJsonEntry(zos, "remembered_senders.json", bundle.senderInflowRules());
            writeJsonEntry(zos, "payment_kind_choices.json", bundle.paymentInflowChoices());
            writeJsonEntry(zos, "saved_statement_passwords.json", bundle.savedStatementPasswords());
            writeJsonEntry(zos, "feature_views.json", bundle.featureViews());
            writeJsonEntry(zos, "payments.json", bundle.payments());
            writeJsonEntry(zos, "subscription_orders.json", bundle.subscriptionOrders());
            writeJsonEntry(zos, "referrals.json", bundle.referrals());
            writeJsonEntry(zos, "referral_code.json", bundle.referralCode());
            writeJsonEntry(zos, "referral_rewards.json", bundle.referralRewards());
            writeJsonEntry(zos, "wallet.json", bundle.wallet());
            writeJsonEntry(zos, "notifications.json", bundle.notifications());
            writeJsonEntry(zos, "notification_preferences.json", bundle.notificationPreferences());
            writeJsonEntry(zos, "timeline.json", bundle.timeline());
            writeJsonEntry(zos, "transaction_links.json", bundle.transactionLinks());
            writeJsonEntry(zos, "statement_excluded_rows.json", bundle.statementExcludedRows());
            writeJsonEntry(zos, "merchant_category_votes.json", bundle.merchantCategoryVotes());
            writeJsonEntry(zos, "statement_refresh_runs.json", bundle.statementRefreshRuns());
            writeJsonEntry(zos, "activity_days.json", bundle.activityDays());

            for (Summary statement : bundle.statementSummaries()) {
                writeStoredFile(zos, "statements/" + statement.id() + "-" + sanitize(statement.fileName()),
                        "statement " + statement.id(), userId,
                        () -> statementImportService.getFile(userId, statement.id()).content());
            }

            for (UnimportedUpload upload : bundle.unimportedUploads()) {
                // Re-fetched here, not carried from buildBundle, for the same reason statements are:
                // resolved fresh at write time, and a row deleted in between (a confirmed or swept
                // session, say) becomes a placeholder.
                String entryName = "imports/" + upload.id() + "-" + sanitize(upload.fileName());
                switch (upload.source()) {
                    case IMPORT_JOB -> writeStoredFile(zos, entryName, "import job " + upload.id(), userId,
                            () -> statementContentService.read(importJobRepository.findByIdAndUserId(upload.id(), userId)
                                    .orElseThrow(() -> new IllegalStateException("import job no longer exists"))));
                    case IMPORT_SESSION -> writeStoredFile(zos, entryName, "import session " + upload.id(), userId,
                            () -> importSessionService.readOwnedFileContent(userId, upload.id()));
                }
            }
        }
    }

    /** Writes one stored file's bytes as {@code entryName}, or a {@code .MISSING.txt} placeholder in
     *  its place if reading them fails. {@code content} runs before the entry is opened, so a read
     *  failure never leaves a half-written entry behind. */
    private void writeStoredFile(ZipOutputStream zos, String entryName, String what, UUID userId,
                                 Supplier<byte[]> content) throws IOException {
        try {
            byte[] bytes = content.get();
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(bytes);
            zos.closeEntry();
        } catch (IOException e) {
            // Bug fix (review): a broken pipe (client disconnect mid-download) surfaces as
            // an ordinary IOException from zos.write/putNextEntry/closeEntry above -- the
            // SAME exception type a genuinely broken stream produces. Writing a recovery
            // placeholder onto that same, now-dead stream would itself throw a second,
            // uncaught IOException, misattributing an ordinary client-side cancel as a
            // generic internal failure once it propagates out of this method. Re-thrown
            // here, not converted to a placeholder: an IOException means the STREAM is
            // unusable, not that this one file's storage read failed, so no further write
            // to it (a placeholder or the next file) can succeed either.
            throw e;
        } catch (Exception e) {
            log.warn("Data export: failed to read {} for user {}: {}", what, userId, e.getMessage(), e);
            writeTextEntry(zos, entryName + ".MISSING.txt",
                    "This file could not be included in your export (" + e.getClass().getSimpleName()
                            + "). Contact support if you need it.");
        }
    }

    private AccountExportEntry toAccountExportEntry(Account a, Map<UUID, StatementMetadata> latestImportByAccount,
                                                      Map<UUID, Integer> statementsCountByAccount,
                                                      Map<UUID, Long> transactionsCountByAccount) {
        // Bug fix (review): used to call AccountDto's 2-arg from(Account, BankDto) overload, which
        // hardcodes statementsCount/transactionsCount/lastImportedAt to 0/0/null -- every exported
        // account misrepresented its own history regardless of how much it actually had, even
        // though transactions.json/statements.json elsewhere in this same archive had the real
        // numbers. Now computed the same way AccountService.listForUser does it: batched maps built
        // once in buildBundle, not a query per account.
        StatementMetadata latestImport = latestImportByAccount.get(a.getId());
        AccountDto dto = AccountDto.from(a, bankManagementService.resolve(a.getBankId()),
                latestImport == null ? null : latestImport.getImportedAt(),
                latestImport == null ? null : latestImport.getStatementPeriodStart(),
                latestImport == null ? null : latestImport.getStatementPeriodEnd(),
                statementsCountByAccount.getOrDefault(a.getId(), 0),
                transactionsCountByAccount.getOrDefault(a.getId(), 0L),
                // aaSyncStale is not resolved here -- a data export isn't the account picker
                // Task 5 exists for, and nothing that reads an exported bundle needs a live
                // staleness signal (it's a point-in-time archive, not the live app). Same
                // deliberate simplification as create()/update() (see AccountDto.aaSyncStale's
                // own doc comment), extended to this fourth call site.
                false);
        return new AccountExportEntry(dto, a.getDeletedAt() != null, a.getDeletedAt());
    }

    /** Branches on session kind -- a MULTI_ACCOUNT session's row count has to come from {@code
     *  readSections}, not {@code readStagedRows}, which throws for anything but a SINGLE_ACCOUNT
     *  session (see this class's own scope doc and ImportSessionService.requireKind). */
    private ImportSessionSummaryDto toSessionSummary(ImportSession session) {
        int rowCount = switch (session.getSessionKind()) {
            case ImportSession.KIND_SINGLE_ACCOUNT -> importSessionService.readStagedRows(session).size();
            case ImportSession.KIND_MULTI_ACCOUNT -> importSessionService.readSections(session).stream()
                    .mapToInt(section -> section.rows().size()).sum();
            default -> 0;
        };
        return new ImportSessionSummaryDto(session.getId(), session.getFileName(), rowCount,
                session.getCreatedAt(), session.getExpiresAt());
    }

    private Manifest buildManifest(ExportBundle bundle) {
        List<ManifestEntry> included = List.of(
                new ManifestEntry("accounts.json", "Your bank/card/investment/loan accounts.", bundle.accounts().size()),
                new ManifestEntry("transactions.json", "Every transaction on your ledger.", bundle.transactions().size()),
                new ManifestEntry("budgets.json", "Your monthly category budgets.", bundle.budgets().size()),
                new ManifestEntry("goals.json", "Your savings goals.", bundle.goals().size()),
                new ManifestEntry("goal_contributions.json", "Every contribution you've made toward a savings goal.", bundle.goalContributions().size()),
                new ManifestEntry("categories.json", "Your custom transaction categories.", bundle.categories().size()),
                new ManifestEntry("category_rules.json", "Your own auto-categorization rules.", bundle.categoryRules().size()),
                new ManifestEntry("relationships.json", "People/accounts you've linked transactions to.", bundle.relationships().size()),
                new ManifestEntry("net_worth_history.json", "Your saved net worth snapshots.", bundle.netWorthSnapshots().size()),
                new ManifestEntry("merchants.json", "Merchants Finora has recognized from your transactions.", bundle.merchants().size()),
                new ManifestEntry("import_jobs.json", "Your statement import job history.", bundle.importJobs().size()),
                new ManifestEntry("import_sessions.json", "Your statement staging session history.", bundle.importSessions().size()),
                new ManifestEntry("statements.json", "Metadata for every statement you've imported.", bundle.statementSummaries().size()),
                new ManifestEntry("statements/", "The original statement files you uploaded, where still retrievable.", bundle.statementSummaries().size()),
                new ManifestEntry("imports/", "Statement files you uploaded that Finora still holds but that are not an imported statement -- held for review, still processing, failed, cancelled, never confirmed, or since deleted -- where still retrievable. Each is named after its entry in import_jobs.json or import_sessions.json.", bundle.unimportedUploads().size()),
                new ManifestEntry("gmail_connection.json", "Your Gmail connection status, if any (no credentials).", bundle.gmailConnections().size()),
                new ManifestEntry("account_settings.json", "Your profile and account preferences.", null),
                new ManifestEntry("workspace_settings.json", "Your categorization workspace preferences.", null),
                new ManifestEntry("subscriptions.json", "Your plan and subscription history.", bundle.subscriptions().size()),
                new ManifestEntry("plan_changes.json", "Your subscription upgrade/downgrade history.", bundle.planChanges().size()),
                new ManifestEntry("support_tickets.json", "Your support requests, including attachment filenames (not the files themselves).", bundle.supportTickets().size()),
                new ManifestEntry("feedback.json", "Feedback you've submitted through the app.", bundle.feedback().size()),
                // F-03 fix (audit, 2026-09-18): these eight were previously missing from both this
                // list and the excluded one below -- not exported and not disclosed as excluded.
                new ManifestEntry("fyn_chat_conversations.json", "Your Fyn AI chat threads.", bundle.chatConversations().size()),
                new ManifestEntry("fyn_chat_messages.json", "Every message across all your Fyn AI chat threads.", bundle.chatMessages().size()),
                new ManifestEntry("health_score_history.json", "Your saved monthly financial health score snapshots.", bundle.healthScoreHistory().size()),
                new ManifestEntry("financial_focus.json", "The financial focus area(s) you selected during onboarding.", bundle.financialFocus().size()),
                new ManifestEntry("onboarding_checklist.json", "Onboarding checklist items you've completed.", bundle.checklistEvents().size()),
                new ManifestEntry("recurring_dismissals.json", "Recurring transaction groups you've dismissed.", bundle.recurringDismissals().size()),
                new ManifestEntry("account_aggregator_links.json", "Your Account Aggregator (Setu) bank-linking consents, past and present (no credentials).", bundle.accountAggregatorLinks().size()),
                new ManifestEntry("merchant_category_corrections.json", "Merchant-to-category mappings Fyn learned or you corrected.", bundle.merchantCategoryResolutions().size()),
                new ManifestEntry("money_kinds.json", "The kinds you give money coming in (built-in and your own), and whether each counts as income.", bundle.inflowKinds().size()),
                new ManifestEntry("remembered_senders.json", "Senders you told Finora how to treat every payment from.", bundle.senderInflowRules().size()),
                new ManifestEntry("payment_kind_choices.json", "Kinds you chose for a single payment.", bundle.paymentInflowChoices().size()),
                new ManifestEntry("saved_statement_passwords.json", "Statements you let Finora keep the password for, and when you agreed -- never the password itself.", bundle.savedStatementPasswords().size()),
                new ManifestEntry("feature_views.json", "How many times you've opened each tracked feature, and when you last did -- the count behind the Billing page's usage tile.", bundle.featureViews().size()),
                new ManifestEntry("payments.json", "Every payment you made for your plan, with its invoice.", bundle.payments().size()),
                new ManifestEntry("subscription_orders.json", "Every plan checkout you started, finished or not.", bundle.subscriptionOrders().size()),
                new ManifestEntry("referrals.json", "People you invited and, if someone invited you, that invitation -- never the other person's account id.", bundle.referrals().size()),
                new ManifestEntry("referral_code.json", "Your own referral code and its reward progress.", bundle.referralCode().size()),
                new ManifestEntry("referral_rewards.json", "Plan rewards you earned through referrals.", bundle.referralRewards().size()),
                new ManifestEntry("wallet.json", "Every credit and debit to your Finora wallet.", bundle.wallet().size()),
                new ManifestEntry("notifications.json", "Notifications Finora sent you, and when you read them.", bundle.notifications().size()),
                new ManifestEntry("notification_preferences.json", "Which notifications you turned on or off, per channel.", bundle.notificationPreferences().size()),
                new ManifestEntry("timeline.json", "Milestones on your financial timeline.", bundle.timeline().size()),
                new ManifestEntry("transaction_links.json", "Links between your transactions -- transfers, card payments and what they settled, refunds.", bundle.transactionLinks().size()),
                new ManifestEntry("statement_excluded_rows.json", "Statement rows you chose to leave out of your ledger.", bundle.statementExcludedRows().size()),
                new ManifestEntry("merchant_category_votes.json", "Categories you chose for merchants' payments.", bundle.merchantCategoryVotes().size()),
                new ManifestEntry("statement_refresh_runs.json", "Every time Finora re-read one of your statements, and exactly what that changed -- including transactions it removed.", bundle.statementRefreshRuns().size()),
                new ManifestEntry("activity_days.json", "The dates you used Fynora -- the date only, never the time or what you did.", bundle.activityDays().size())
        );
        List<ManifestEntry> excluded = List.of(
                new ManifestEntry("audit_logs", "Your own actions are logged for security, not collected as your data.", null),
                new ManifestEntry("merchant_aliases, merchant_category_map, merchant_category_learning, merchant_learning_audit, merchant_learning_event",
                        "Derived categorization intelligence Finora builds from your transactions, not data you provided directly.", null),
                new ManifestEntry("refresh_tokens", "Login session bookkeeping (device/IP history), not your financial data.", null),
                new ManifestEntry("password_history", "Used only to block password reuse; never held anything to show.", null),
                new ManifestEntry("statement_analysis_sessions", "Internal parsing evidence Finora keeps to improve statement recognition, not part of your ledger.", null),
                new ManifestEntry("subscription_events", "An internal lifecycle/analytics log of your subscription, not data you provided -- your plan history itself is in subscriptions.json and plan_changes.json.", null),
                new ManifestEntry("support_ticket_attachments (bytes)", "The files themselves aren't included, only their filenames in support_tickets.json -- contact support if you need one back.", null),
                new ManifestEntry("support_ticket_internal_notes", "Finora's own operational notes on your ticket (e.g. \"reproduced on Android 1.3.7\"), not data you provided.", null),
                new ManifestEntry("account_aggregator_links (consent_handle_id, link_idempotency_key, resolution_claimed_at)",
                        "Internal Setu-correlation, request-deduplication, and concurrency-claim bookkeeping, not data you provided -- your link's own status/consent/sync history is in account_aggregator_links.json.", null),
                new ManifestEntry("password_change_sessions, password_reset_tokens, account_reactivation_tokens, email_verification_tokens, email_login_otps, email_change_sessions, phone_change_sessions",
                        "Short-lived codes and confirmation steps for signing in or changing your details -- security bookkeeping that expires, not your data.", null),
                new ManifestEntry("device_tokens", "The push-notification address of each device you signed in on -- delivery plumbing, not your data.", null),
                new ManifestEntry("reimport_confirmation_claims", "A guard that stops one statement re-import from running twice -- request bookkeeping, not your data.", null),
                new ManifestEntry("statement_refresh_previews",
                        "What a newer statement reader would change if you let it -- an offer Finora recalculates, not a record of anything that happened. Refreshes you ran are in statement_refresh_runs.json.", null),
                new ManifestEntry("ai_audit_log", "Cost, token and timing records for each Fyn AI call -- Finora's own accountability log, like audit_logs. Your chats themselves are in fyn_chat_conversations.json and fyn_chat_messages.json.", null),
                new ManifestEntry("referral_charges", "The payments made by people you referred, kept to count your referral rewards -- those are their payments, not your data. Your rewards are in referral_rewards.json and wallet.json.", null),
                new ManifestEntry("held_statements, held_statement_events", "Finora's own review record for a statement it held back to check -- like support_ticket_internal_notes. The import itself is in import_jobs.json.", null),
                new ManifestEntry("notification_logs, notifications (notification_key, attempt_count, next_attempt_at, last_error)",
                        "Delivery attempts and provider responses for each notification -- delivery plumbing. What you were sent, and when, is in notifications.json.", null),
                new ManifestEntry("subscription_orders (razorpay_subscription_id)", "The payment provider's internal id for a checkout -- correlation bookkeeping, the same id subscriptions.json leaves out.", null)
        );
        return new Manifest(Instant.now(), bundle.userId(), bundle.email(), included, excluded);
    }

    private String buildReadmeText() {
        return """
                This is your data export from Finora, generated on request.

                See manifest.json for the full list of what's included in this archive and what's
                deliberately excluded (with a one-line reason for each).

                If a file under statements/ or imports/ ends in ".MISSING.txt" instead of
                containing your original document, that one file couldn't be retrieved at export
                time -- contact support if you need it.
                """;
    }

    private void writeJsonEntry(ZipOutputStream zos, String entryName, Object value) throws IOException {
        // Serialized to a byte[] first, not written straight to zos: ObjectMapper.writeValue(
        // OutputStream, Object) defaults AUTO_CLOSE_TARGET=true and would close the whole
        // ZipOutputStream after the first entry, silently truncating every entry after it.
        byte[] bytes = objectMapper.writeValueAsBytes(value);
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(bytes);
        zos.closeEntry();
    }

    private void writeTextEntry(ZipOutputStream zos, String entryName, String text) throws IOException {
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** Keeps a user-chosen filename from escaping its intended statements/ directory inside the
     *  archive (a literal ".." or leading "/" in an uploaded filename) or otherwise confusing a
     *  ZIP-reading tool -- strips everything but word characters, dots, dashes and spaces. */
    private static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) return "statement";
        return fileName.replaceAll("[^A-Za-z0-9._ -]", "_");
    }

    /** Internal transport between {@link #buildBundle} and {@link #writeZip} -- never serialized
     *  directly. {@code statementSummaries} alone carries what {@link #writeZip} needs to resolve
     *  each statement's bytes ({@code id()}/{@code fileName()}) -- a separate entity list is no
     *  longer carried alongside it (removed in the same fix that made {@code buildBundle} stop
     *  loading full {@code StatementImport} entities at all; see this class's own doc comment). */
    public record ExportBundle(
            UUID userId, String email,
            List<AccountExportEntry> accounts, List<TransactionDto> transactions, List<BudgetDto> budgets,
            List<GoalExportEntry> goals, List<GoalContributionExportDto> goalContributions,
            List<CategoryDto> categories, List<RuleDto> categoryRules,
            List<RelationshipDto> relationships, List<NetWorthSnapshotExportDto> netWorthSnapshots,
            List<MerchantExportDto> merchants, List<ImportJobDto.Progress> importJobs,
            List<ImportSessionSummaryDto> importSessions,
            List<Summary> statementSummaries, List<UnimportedUpload> unimportedUploads,
            List<GmailConnectionExportDto> gmailConnections,
            UserSettingsDto userSettings, WorkspaceSettingsDto workspaceSettings,
            List<SubscriptionExportDto> subscriptions, List<PlanChangeExportDto> planChanges,
            List<SupportTicketDto.Detail> supportTickets, List<FeedbackDto.Summary> feedback,
            List<ChatConversationExportDto> chatConversations, List<ChatMessageExportDto> chatMessages,
            List<HealthScoreSnapshotExportDto> healthScoreHistory, List<UserFinancialFocusExportDto> financialFocus,
            List<UserChecklistEventExportDto> checklistEvents, List<RecurringDismissalExportDto> recurringDismissals,
            List<AccountAggregatorLinkExportDto> accountAggregatorLinks,
            List<UserMerchantCategoryResolutionExportDto> merchantCategoryResolutions,
            List<InflowKindExportDto> inflowKinds,
            List<SenderInflowRuleExportDto> senderInflowRules,
            List<PaymentInflowChoiceExportDto> paymentInflowChoices,
            List<com.finora.dto.SavedStatementPasswordDtos.SavedStatementPassword> savedStatementPasswords,
            List<FeatureViewExportDto> featureViews,
            List<PaymentExportDto> payments, List<SubscriptionOrderExportDto> subscriptionOrders,
            List<ReferralExportDto> referrals, List<ReferralCodeExportDto> referralCode,
            List<ReferralRewardExportDto> referralRewards, List<WalletEntryExportDto> wallet,
            List<NotificationExportDto> notifications, List<NotificationPreferenceExportDto> notificationPreferences,
            List<TimelineEventExportDto> timeline, List<TransactionLinkExportDto> transactionLinks,
            List<StatementExcludedRowExportDto> statementExcludedRows,
            List<MerchantCategoryVoteExportDto> merchantCategoryVotes,
            List<RefreshRunDetail> statementRefreshRuns,
            List<String> activityDays
    ) {}

    /** An {@code imports/} entry to write: just enough for {@link #writeZip} to re-fetch the row
     *  holding the file and name it. {@code id} is that row's id. */
    public record UnimportedUpload(Source source, UUID id, String fileName) {
        public enum Source { IMPORT_JOB, IMPORT_SESSION }
    }
}
