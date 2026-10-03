package com.finora.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.ImportSessionSummaryDto;
import com.finora.dto.UserSettingsDto;
import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.entity.HealthScoreSnapshot;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.Merchant;
import com.finora.entity.NetWorthSnapshot;
import com.finora.entity.Plan;
import com.finora.entity.PlanChange;
import com.finora.entity.FeatureViewCount;
import com.finora.entity.RecurringDismissal;
import com.finora.entity.Subscription;
import com.finora.entity.SupportTicket;
import com.finora.entity.SupportTicketAttachment;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.entity.UserMerchantCategoryResolution;
import com.finora.exception.ApiException;
import com.finora.goals.Goal;
import com.finora.goals.GoalContribution;
import com.finora.goals.GoalContributionRepository;
import com.finora.goals.GoalRepository;
import com.finora.budgets.BudgetService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.storage.StatementContentService;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.setu.AccountAggregatorLink;
import com.finora.integrations.setu.AccountAggregatorLinkRepository;
import com.finora.integrations.setu.AccountAggregatorLinkStatus;
import com.finora.integrations.setu.FiType;
import com.finora.onboarding.UserChecklistEvent;
import com.finora.onboarding.UserChecklistEventRepository;
import com.finora.onboarding.UserFinancialFocus;
import com.finora.onboarding.UserFinancialFocusRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.ChatConversationRepository;
import com.finora.repository.ChatMessageRepository;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.HealthScoreSnapshotRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.NetWorthSnapshotRepository;
import com.finora.repository.PlanChangeRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.FeatureViewCountRepository;
import com.finora.repository.StatementRefreshRunRepository;
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
import com.finora.repository.RecurringDismissalRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.SupportTicketAttachmentRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.support.FeedbackDto;
import com.finora.support.SupportTicketDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DataExportService} in isolation -- the password gate, the two locked-in scope
 * decisions this plan's "Findings" section flags as easy to silently regress (soft-deleted
 * accounts, MULTI_ACCOUNT session row counts), and the per-statement best-effort ZIP writing.
 * No real-Postgres concern here (no lazy-loading, no native queries) -- unlike
 * {@link AccountPurgeSweepServiceIT}'s split from its own unit test, this class needed no IT.
 */
class DataExportServiceTest {
    private com.finora.repository.InflowKindRepository inflowKindRepository;
    private com.finora.repository.SenderInflowRuleRepository senderInflowRuleRepository;
    private final com.finora.repository.UserActivityDayRepository userActivityDayRepository =
            mock(com.finora.repository.UserActivityDayRepository.class);


    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private com.finora.integrations.google.login.GoogleIdTokenVerifierService googleIdTokenVerifierService;
    private com.finora.integrations.apple.login.AppleIdTokenVerifierService appleIdTokenVerifierService;
    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private BudgetService budgetService;
    private GoalRepository goalRepository;
    private GoalContributionRepository goalContributionRepository;
    private CategoryRepository categoryRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RelationshipService relationshipService;
    private NetWorthSnapshotRepository netWorthSnapshotRepository;
    private MerchantRepository merchantRepository;
    private ImportJobRepository importJobRepository;
    private StatementContentService statementContentService;
    private ImportSessionRepository importSessionRepository;
    private ImportSessionService importSessionService;
    private StatementImportRepository statementImportRepository;
    private StatementImportService statementImportService;
    private GmailConnectionRepository gmailConnectionRepository;
    private UserSettingsService userSettingsService;
    private WorkspaceSettingsService workspaceSettingsService;
    private BankManagementService bankManagementService;
    private AuditService auditService;
    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private PlanChangeRepository planChangeRepository;
    private SupportTicketRepository supportTicketRepository;
    private SupportTicketAttachmentRepository supportTicketAttachmentRepository;
    private FeedbackEntryRepository feedbackEntryRepository;
    private ChatConversationRepository chatConversationRepository;
    private ChatMessageRepository chatMessageRepository;
    private HealthScoreSnapshotRepository healthScoreSnapshotRepository;
    private UserFinancialFocusRepository userFinancialFocusRepository;
    private UserChecklistEventRepository userChecklistEventRepository;
    private RecurringDismissalRepository recurringDismissalRepository;
    private AccountAggregatorLinkRepository accountAggregatorLinkRepository;
    private UserMerchantCategoryResolutionRepository userMerchantCategoryResolutionRepository;
    private FeatureViewCountRepository featureViewCountRepository;
    private StatementRefreshRunRepository statementRefreshRunRepository;
    private PaymentRepository paymentRepository;
    private SubscriptionOrderRepository subscriptionOrderRepository;
    private ReferralRepository referralRepository;
    private ReferralCodeRepository referralCodeRepository;
    private ReferralGrantRepository referralGrantRepository;
    private WalletLedgerRepository walletLedgerRepository;
    private NotificationRepository notificationRepository;
    private NotificationPreferenceRepository notificationPreferenceRepository;
    private TimelineEventRepository timelineEventRepository;
    private TransactionRelationshipRepository transactionRelationshipRepository;
    private StatementImportExcludedRowRepository statementImportExcludedRowRepository;
    private CounterpartyCategoryObservationRepository counterpartyCategoryObservationRepository;
    private DataExportService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        googleIdTokenVerifierService = mock(com.finora.integrations.google.login.GoogleIdTokenVerifierService.class);
        appleIdTokenVerifierService = mock(com.finora.integrations.apple.login.AppleIdTokenVerifierService.class);
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        budgetService = mock(BudgetService.class);
        goalRepository = mock(GoalRepository.class);
        goalContributionRepository = mock(GoalContributionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        relationshipService = mock(RelationshipService.class);
        netWorthSnapshotRepository = mock(NetWorthSnapshotRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        statementContentService = mock(StatementContentService.class);
        importSessionRepository = mock(ImportSessionRepository.class);
        importSessionService = mock(ImportSessionService.class);
        statementImportRepository = mock(StatementImportRepository.class);
        statementImportService = mock(StatementImportService.class);
        gmailConnectionRepository = mock(GmailConnectionRepository.class);
        userSettingsService = mock(UserSettingsService.class);
        workspaceSettingsService = mock(WorkspaceSettingsService.class);
        bankManagementService = mock(BankManagementService.class);
        auditService = mock(AuditService.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        planChangeRepository = mock(PlanChangeRepository.class);
        supportTicketRepository = mock(SupportTicketRepository.class);
        supportTicketAttachmentRepository = mock(SupportTicketAttachmentRepository.class);
        feedbackEntryRepository = mock(FeedbackEntryRepository.class);
        chatConversationRepository = mock(ChatConversationRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        healthScoreSnapshotRepository = mock(HealthScoreSnapshotRepository.class);
        userFinancialFocusRepository = mock(UserFinancialFocusRepository.class);
        userChecklistEventRepository = mock(UserChecklistEventRepository.class);
        recurringDismissalRepository = mock(RecurringDismissalRepository.class);
        accountAggregatorLinkRepository = mock(AccountAggregatorLinkRepository.class);
        userMerchantCategoryResolutionRepository = mock(UserMerchantCategoryResolutionRepository.class);
        featureViewCountRepository = mock(FeatureViewCountRepository.class);
        statementRefreshRunRepository = mock(StatementRefreshRunRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        subscriptionOrderRepository = mock(SubscriptionOrderRepository.class);
        referralRepository = mock(ReferralRepository.class);
        referralCodeRepository = mock(ReferralCodeRepository.class);
        referralGrantRepository = mock(ReferralGrantRepository.class);
        walletLedgerRepository = mock(WalletLedgerRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationPreferenceRepository = mock(NotificationPreferenceRepository.class);
        timelineEventRepository = mock(TimelineEventRepository.class);
        transactionRelationshipRepository = mock(TransactionRelationshipRepository.class);
        statementImportExcludedRowRepository = mock(StatementImportExcludedRowRepository.class);
        counterpartyCategoryObservationRepository = mock(CounterpartyCategoryObservationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        // Empty-by-default collections, matching AccountPurgeSweepServiceTest's own convention --
        // so a test exercising one table never NPEs on every other one it doesn't care about.
        when(accountRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(transactionRepository.findByUserId(any())).thenReturn(List.of());
        when(transactionRepository.countByAccountForUser(any())).thenReturn(List.of());
        when(budgetService.listForUser(any())).thenReturn(List.of());
        when(goalRepository.findByUserIdIncludingDeleted(any())).thenReturn(List.of());
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(any())).thenReturn(List.of());
        when(categoryRepository.findByUserId(any())).thenReturn(List.of());
        when(categoryRuleRepository.findByUserId(any())).thenReturn(List.of());
        when(relationshipService.listForUser(any())).thenReturn(List.of());
        when(netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(any())).thenReturn(List.of());
        when(merchantRepository.findByUserId(any())).thenReturn(List.of());
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(statementImportService.duplicateCountsByStatementImport(any())).thenReturn(Map.of());
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(any())).thenReturn(List.of());
        when(gmailConnectionRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(userSettingsService.get(any()))
                .thenReturn(new UserSettingsDto("jane@example.com", "Jane Doe", null, "light", "Asia/Kolkata",
                        null, false, Instant.now(), null, User.SIGN_IN_METHOD_PASSWORD, true));
        when(workspaceSettingsService.get(any())).thenReturn(new WorkspaceSettingsDto(90, Instant.now()));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(planRepository.findAllById(any())).thenReturn(List.of());
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(any(), any())).thenReturn(Page.empty());
        when(supportTicketAttachmentRepository.findMetadataByTicketIdIn(any())).thenReturn(List.of());
        when(feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(chatConversationRepository.findByUserIdOrderByUpdatedAtDesc(any())).thenReturn(List.of());
        when(chatMessageRepository.findByConversationIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(healthScoreSnapshotRepository.findByUserIdOrderByYearMonthAsc(any())).thenReturn(List.of());
        when(userFinancialFocusRepository.findByUserId(any())).thenReturn(List.of());
        when(userChecklistEventRepository.findByUserId(any())).thenReturn(List.of());
        when(recurringDismissalRepository.findByUserId(any())).thenReturn(java.util.Set.of());
        when(accountAggregatorLinkRepository.findByUserId(any())).thenReturn(List.of());
        when(userMerchantCategoryResolutionRepository.findAllByUserId(any())).thenReturn(List.of());
        when(featureViewCountRepository.findByUserIdOrderByFeatureAsc(any())).thenReturn(List.of());
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(subscriptionOrderRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(referralRepository.findByReferredUserId(any())).thenReturn(Optional.empty());
        when(referralCodeRepository.findByUserId(any())).thenReturn(Optional.empty());
        when(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(walletLedgerRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(notificationPreferenceRepository.findByUserId(any())).thenReturn(List.of());
        when(timelineEventRepository.findByUserIdOrderByOccurredAtDesc(any())).thenReturn(List.of());
        when(transactionRelationshipRepository.findByUserIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(statementImportExcludedRowRepository.findByUserIdOrderByStatementImportIdAscRowPositionAsc(any())).thenReturn(List.of());
        when(counterpartyCategoryObservationRepository.findByUserIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(statementRefreshRunRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        inflowKindRepository = mock(com.finora.repository.InflowKindRepository.class);
        senderInflowRuleRepository = mock(com.finora.repository.SenderInflowRuleRepository.class);

        when(passwordEncoder.matches(any(), any())).thenReturn(true);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        service = new DataExportService(userRepository,
                new GoogleReauthVerifier(passwordEncoder, googleIdTokenVerifierService, appleIdTokenVerifierService), accountRepository, transactionRepository,
                budgetService, goalRepository, goalContributionRepository, categoryRepository, categoryRuleRepository, relationshipService,
                netWorthSnapshotRepository, merchantRepository, importJobRepository, importSessionRepository,
                importSessionService, statementImportRepository, statementImportService, gmailConnectionRepository,
                userSettingsService, workspaceSettingsService, bankManagementService, auditService,
                subscriptionRepository, planRepository, planChangeRepository,
                supportTicketRepository, supportTicketAttachmentRepository, feedbackEntryRepository,
                chatConversationRepository, chatMessageRepository, healthScoreSnapshotRepository,
                userFinancialFocusRepository, userChecklistEventRepository, recurringDismissalRepository,
                accountAggregatorLinkRepository, userMerchantCategoryResolutionRepository,
                inflowKindRepository, senderInflowRuleRepository, objectMapper,
                mock(com.finora.repository.StatementPasswordRepository.class), featureViewCountRepository,
paymentRepository, subscriptionOrderRepository, referralRepository, referralCodeRepository, referralGrantRepository, walletLedgerRepository, notificationRepository, notificationPreferenceRepository, timelineEventRepository, transactionRelationshipRepository, statementImportExcludedRowRepository, counterpartyCategoryObservationRepository, statementRefreshRunRepository, userActivityDayRepository,
                statementContentService);
    }

    private User user() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", userId);
        u.setEmail("jane@example.com");
        u.setPasswordHash("hashed");
        return u;
    }

    /** Bug fix (review): used to verifyNoInteractions on only 4 of ~14 injected dependencies,
     *  so a regression reordering buildBundle to run an expensive query before the password
     *  check -- the exact thing this test's own name promises to catch -- would have passed
     *  undetected as long as it didn't touch one of those specific four. Every repository/service
     *  buildBundle can reach is checked now. */
    @Test
    void buildBundle_wrongPassword_rejectsBeforeTouchingAnyRepository() {
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.buildBundle(userId, "wrong-password", null, null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verify(auditService).recordEvenOnRollback(eq(userId), eq("INVALID_CURRENT_PASSWORD"), eq("User"), eq(userId));
        verifyNoInteractions(accountRepository, transactionRepository, budgetService, goalRepository,
                goalContributionRepository, categoryRepository,
                categoryRuleRepository, relationshipService, netWorthSnapshotRepository, merchantRepository,
                importJobRepository, importSessionRepository, importSessionService, statementImportRepository,
                statementImportService, gmailConnectionRepository, userSettingsService, workspaceSettingsService,
                bankManagementService, subscriptionRepository, planRepository, planChangeRepository,
                supportTicketRepository, supportTicketAttachmentRepository, feedbackEntryRepository,
                chatConversationRepository, chatMessageRepository, healthScoreSnapshotRepository,
                userFinancialFocusRepository, userChecklistEventRepository, recurringDismissalRepository,
                accountAggregatorLinkRepository, userMerchantCategoryResolutionRepository,
                inflowKindRepository, senderInflowRuleRepository, featureViewCountRepository,
paymentRepository, subscriptionOrderRepository, referralRepository, referralCodeRepository, referralGrantRepository, walletLedgerRepository, notificationRepository, notificationPreferenceRepository, timelineEventRepository, transactionRelationshipRepository, statementImportExcludedRowRepository, counterpartyCategoryObservationRepository, statementRefreshRunRepository);
    }

    @Test
    void buildBundle_correctPassword_proceeds() {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.userId()).isEqualTo(userId);
        assertThat(bundle.email()).isEqualTo("jane@example.com");
        assertThat(bundle.accounts()).isEmpty();
    }

    @Test
    void buildBundle_onAGoogleAccount_verifiesAFreshGoogleTokenInsteadOfAPassword() {
        User googleUser = user();
        googleUser.setSignInMethod(User.SIGN_IN_METHOD_GOOGLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(googleUser));
        when(googleIdTokenVerifierService.verify("fresh-google-token"))
                .thenReturn(new com.finora.integrations.google.login.GoogleIdentity(googleUser.getEmail(), "Jane"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, null, "fresh-google-token", null);

        assertThat(bundle.userId()).isEqualTo(userId);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // D-26 gap closed: an Apple-only account used to fall through to the password branch here
    // and fail forever -- mirrors the Google test immediately above.
    @Test
    void buildBundle_onAnAppleAccount_verifiesAFreshAppleTokenInsteadOfAPassword() {
        User appleUser = user();
        appleUser.setSignInMethod(User.SIGN_IN_METHOD_APPLE);
        when(userRepository.findById(userId)).thenReturn(Optional.of(appleUser));
        when(appleIdTokenVerifierService.verify("fresh-apple-token"))
                .thenReturn(new com.finora.integrations.apple.login.AppleIdentity(appleUser.getEmail(), "apple-subject"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, null, null, "fresh-apple-token");

        assertThat(bundle.userId()).isEqualTo(userId);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    /** Finding 4: the purge scope this export mirrors reads accounts via
     *  findByUserIdIncludingDeleted, not the filtered finder -- a soft-deleted account must still
     *  appear in the export, explicitly marked, rather than silently vanishing. */
    @Test
    void buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted() {
        Account active = new Account();
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        active.setUserId(userId);
        active.setAccountType(Account.Type.SAVINGS);
        active.setName("Active Savings");

        Account deleted = new Account();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setAccountType(Account.Type.SAVINGS);
        deleted.setName("Closed Account");
        Instant deletedAt = Instant.now();
        deleted.setDeletedAt(deletedAt);

        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(active, deleted));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.accounts()).hasSize(2);
        assertThat(bundle.accounts()).anySatisfy(e -> {
            assertThat(e.account().id()).isEqualTo(active.getId());
            assertThat(e.deleted()).isFalse();
            assertThat(e.deletedAt()).isNull();
        });
        assertThat(bundle.accounts()).anySatisfy(e -> {
            assertThat(e.account().id()).isEqualTo(deleted.getId());
            assertThat(e.deleted()).isTrue();
            assertThat(e.deletedAt()).isEqualTo(deletedAt);
        });
    }

    /** Mirrors buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted -- goals.json reads
     *  via GoalRepository.findByUserIdIncludingDeleted, not GoalService.listForUser (which stays
     *  filtered on purpose, since it's also the live Goals page's own data source): a soft-deleted
     *  goal must still appear in the export, explicitly marked, rather than silently vanishing. */
    @Test
    void buildBundle_goals_includesSoftDeletedGoalMarkedDeleted() {
        Goal active = new Goal();
        ReflectionTestUtils.setField(active, "id", UUID.randomUUID());
        active.setUserId(userId);
        active.setName("Emergency Fund");
        active.setTargetAmount(BigDecimal.valueOf(100000));
        active.setCurrentAmount(BigDecimal.valueOf(20000));

        Goal deleted = new Goal();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setName("Old Vacation Fund");
        deleted.setTargetAmount(BigDecimal.valueOf(50000));
        deleted.setCurrentAmount(BigDecimal.ZERO);
        Instant deletedAt = Instant.now();
        deleted.setDeletedAt(deletedAt);

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(active, deleted));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goals()).hasSize(2);
        assertThat(bundle.goals()).anySatisfy(e -> {
            assertThat(e.goal().id()).isEqualTo(active.getId());
            assertThat(e.deleted()).isFalse();
            assertThat(e.deletedAt()).isNull();
        });
        assertThat(bundle.goals()).anySatisfy(e -> {
            assertThat(e.goal().id()).isEqualTo(deleted.getId());
            assertThat(e.deleted()).isTrue();
            assertThat(e.deletedAt()).isEqualTo(deletedAt);
        });
    }

    /** goal_contributions.json -- one batched findByGoalIdInOrderByContributedAtDesc call across
     *  every one of this user's goals, not one query per goal. */
    @Test
    void buildBundle_goalContributions_batchFetchesAcrossAllUserGoals() {
        Goal goalOne = new Goal();
        UUID goalOneId = UUID.randomUUID();
        ReflectionTestUtils.setField(goalOne, "id", goalOneId);
        goalOne.setUserId(userId);
        goalOne.setName("Emergency Fund");
        goalOne.setTargetAmount(BigDecimal.valueOf(100000));
        goalOne.setCurrentAmount(BigDecimal.valueOf(20000));

        Goal goalTwo = new Goal();
        UUID goalTwoId = UUID.randomUUID();
        ReflectionTestUtils.setField(goalTwo, "id", goalTwoId);
        goalTwo.setUserId(userId);
        goalTwo.setName("Vacation");
        goalTwo.setTargetAmount(BigDecimal.valueOf(50000));
        goalTwo.setCurrentAmount(BigDecimal.ZERO);

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(goalOne, goalTwo));

        GoalContribution contribution = new GoalContribution();
        UUID contributionId = UUID.randomUUID();
        ReflectionTestUtils.setField(contribution, "id", contributionId);
        contribution.setGoalId(goalOneId);
        contribution.setAmount(BigDecimal.valueOf(5000));
        contribution.setContributedAt(LocalDate.of(2026, 7, 15));
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(List.of(goalOneId, goalTwoId)))
                .thenReturn(List.of(contribution));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goalContributions()).hasSize(1);
        var dto = bundle.goalContributions().get(0);
        assertThat(dto.id()).isEqualTo(contributionId);
        assertThat(dto.goalId()).isEqualTo(goalOneId);
        assertThat(dto.amount()).isEqualByComparingTo(BigDecimal.valueOf(5000));
        assertThat(dto.contributedAt()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    /** A soft-deleted goal's own contribution history must still export -- the goal IDs fed into
     *  the batched contribution lookup come from the including-deleted list, not a filtered one. */
    @Test
    void buildBundle_goalContributions_includesHistoryForASoftDeletedGoal() {
        Goal deletedGoal = new Goal();
        UUID deletedGoalId = UUID.randomUUID();
        ReflectionTestUtils.setField(deletedGoal, "id", deletedGoalId);
        deletedGoal.setUserId(userId);
        deletedGoal.setName("Closed Goal");
        deletedGoal.setTargetAmount(BigDecimal.valueOf(10000));
        deletedGoal.setCurrentAmount(BigDecimal.valueOf(3000));
        deletedGoal.setDeletedAt(Instant.now());

        when(goalRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(deletedGoal));

        GoalContribution contribution = new GoalContribution();
        ReflectionTestUtils.setField(contribution, "id", UUID.randomUUID());
        contribution.setGoalId(deletedGoalId);
        contribution.setAmount(BigDecimal.valueOf(3000));
        contribution.setContributedAt(LocalDate.of(2026, 5, 1));
        when(goalContributionRepository.findByGoalIdInOrderByContributedAtDesc(List.of(deletedGoalId)))
                .thenReturn(List.of(contribution));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.goalContributions()).hasSize(1);
        assertThat(bundle.goalContributions().get(0).goalId()).isEqualTo(deletedGoalId);
    }

    /** subscriptions.json -- planId resolved to the plan's own code/name via a batched lookup,
     *  the same way transactions.json resolves categoryId to categoryName. */
    @Test
    void buildBundle_subscriptions_resolvesPlanCodeAndName() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("PREMIUM");
        plan.setName("Premium");

        Subscription subscription = new Subscription();
        UUID subscriptionId = UUID.randomUUID();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        subscription.setRenewalDate(LocalDate.of(2026, 2, 1));
        subscription.setPaymentProvider("STRIPE");
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of(plan));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.id()).isEqualTo(subscriptionId);
        assertThat(dto.planCode()).isEqualTo("PREMIUM");
        assertThat(dto.planName()).isEqualTo("Premium");
        assertThat(dto.status()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(dto.startDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(dto.renewalDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(dto.paymentProvider()).isEqualTo("STRIPE");
    }

    /** A subscription whose plan row no longer exists must still appear in the export -- with a
     *  null planCode/planName -- rather than being silently dropped or throwing an NPE. */
    @Test
    void buildBundle_subscriptions_missingPlanRowFailsSoftWithNullCodeAndName() {
        UUID planId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        subscription.setUserId(userId);
        subscription.setPlanId(planId);
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setStartDate(LocalDate.of(2025, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of());

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.planCode()).isNull();
        assertThat(dto.planName()).isNull();
        assertThat(dto.status()).isEqualTo(Subscription.STATUS_CANCELLED);
    }

    /** Mirrors buildBundle_accounts_includesSoftDeletedAccountMarkedDeleted -- a soft-deleted
     *  subscription must still appear in the export, explicitly marked, not silently vanish.
     *  Nothing soft-deletes a Subscription today, but the entity supports it, and this class's
     *  own "mirrors the purge scope exactly" rule means the export can't quietly assume otherwise. */
    @Test
    void buildBundle_subscriptions_includesSoftDeletedSubscriptionMarkedDeleted() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("FREE");
        plan.setName("Free");

        Subscription deleted = new Subscription();
        ReflectionTestUtils.setField(deleted, "id", UUID.randomUUID());
        deleted.setUserId(userId);
        deleted.setPlanId(planId);
        deleted.setStatus(Subscription.STATUS_CANCELLED);
        deleted.setStartDate(LocalDate.of(2025, 1, 1));
        Instant deletedAt = Instant.parse("2026-03-01T00:00:00Z");
        deleted.setDeletedAt(deletedAt);
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(deleted));
        when(planRepository.findAllById(List.of(planId))).thenReturn(List.of(plan));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.subscriptions()).hasSize(1);
        var dto = bundle.subscriptions().get(0);
        assertThat(dto.deleted()).isTrue();
        assertThat(dto.deletedAt()).isEqualTo(deletedAt);
        assertThat(dto.planCode()).isEqualTo("FREE");
    }

    /** plan_changes.json -- one batched findBySubscriptionIdInOrderByCreatedAtDesc call across
     *  every one of this user's subscriptions, and fromPlanId/toPlanId resolved to each plan's
     *  own code/name via the same batched Plan lookup subscriptions.json's planId uses --
     *  including a fromPlanId the current subscription isn't even on anymore. */
    @Test
    void buildBundle_planChanges_batchFetchesAndResolvesFromAndToPlanCodeAndName() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(UUID.randomUUID());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));

        UUID fromPlanId = UUID.randomUUID();
        UUID toPlanId = UUID.randomUUID();
        Plan fromPlan = new Plan();
        ReflectionTestUtils.setField(fromPlan, "id", fromPlanId);
        fromPlan.setCode("FREE");
        fromPlan.setName("Free");
        Plan toPlan = new Plan();
        ReflectionTestUtils.setField(toPlan, "id", toPlanId);
        toPlan.setCode("PLUS");
        toPlan.setName("Plus");
        when(planRepository.findAllById(any())).thenReturn(List.of(fromPlan, toPlan));

        PlanChange change = new PlanChange();
        UUID changeId = UUID.randomUUID();
        ReflectionTestUtils.setField(change, "id", changeId);
        change.setSubscriptionId(subscriptionId);
        change.setFromPlanId(fromPlanId);
        change.setToPlanId(toPlanId);
        change.setReason(PlanChange.REASON_USER_INITIATED);
        change.setEffectiveAt(Instant.parse("2026-02-01T00:00:00Z"));
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(List.of(subscriptionId)))
                .thenReturn(List.of(change));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.planChanges()).hasSize(1);
        var dto = bundle.planChanges().get(0);
        assertThat(dto.id()).isEqualTo(changeId);
        assertThat(dto.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(dto.fromPlanCode()).isEqualTo("FREE");
        assertThat(dto.fromPlanName()).isEqualTo("Free");
        assertThat(dto.toPlanCode()).isEqualTo("PLUS");
        assertThat(dto.toPlanName()).isEqualTo("Plus");
        assertThat(dto.reason()).isEqualTo(PlanChange.REASON_USER_INITIATED);
        assertThat(dto.effectiveAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    /** A subscription's very first plan change has no fromPlanId at all (there was no prior
     *  plan) -- must map to a null fromPlanCode/fromPlanName rather than throwing on a null map
     *  lookup key. */
    @Test
    void buildBundle_planChanges_firstChangeHasNullFromPlan() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setUserId(userId);
        subscription.setPlanId(UUID.randomUUID());
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setStartDate(LocalDate.of(2026, 1, 1));
        when(subscriptionRepository.findByUserIdIncludingDeletedOrderByCreatedAtDesc(userId)).thenReturn(List.of(subscription));

        UUID toPlanId = UUID.randomUUID();
        Plan toPlan = new Plan();
        ReflectionTestUtils.setField(toPlan, "id", toPlanId);
        toPlan.setCode("FREE");
        toPlan.setName("Free");
        when(planRepository.findAllById(any())).thenReturn(List.of(toPlan));

        PlanChange change = new PlanChange();
        ReflectionTestUtils.setField(change, "id", UUID.randomUUID());
        change.setSubscriptionId(subscriptionId);
        change.setFromPlanId(null);
        change.setToPlanId(toPlanId);
        change.setReason(PlanChange.REASON_USER_INITIATED);
        change.setEffectiveAt(Instant.now());
        when(planChangeRepository.findBySubscriptionIdInOrderByCreatedAtDesc(List.of(subscriptionId)))
                .thenReturn(List.of(change));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.planChanges()).hasSize(1);
        assertThat(bundle.planChanges().get(0).fromPlanCode()).isNull();
        assertThat(bundle.planChanges().get(0).fromPlanName()).isNull();
        assertThat(bundle.planChanges().get(0).toPlanCode()).isEqualTo("FREE");
    }

    /** Finding 3: ImportSessionService.readStagedRows() throws for anything but a SINGLE_ACCOUNT
     *  session -- a MULTI_ACCOUNT session's row count must come from readSections() instead, or
     *  any user who ever staged a composite statement can't export their data at all. */
    @Test
    void buildBundle_importSessions_multiAccountSessionUsesSectionsNotStagedRows() {
        ImportSession single = new ImportSession();
        ReflectionTestUtils.setField(single, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(single, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        single.setFileName("single.csv");

        ImportSession multi = new ImportSession();
        ReflectionTestUtils.setField(multi, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(multi, "sessionKind", ImportSession.KIND_MULTI_ACCOUNT);
        multi.setFileName("multi.pdf");

        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(single, multi));
        when(importSessionService.readStagedRows(single)).thenReturn(nRows(3));
        // A MULTI_ACCOUNT session throws from readStagedRows() in the real implementation --
        // stubbing it to throw here too, so this test fails loudly if the service under test
        // ever calls the wrong method for this session's kind.
        when(importSessionService.readStagedRows(multi))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "wrong kind"));
        when(importSessionService.readSections(multi)).thenReturn(List.of(
                section(2), section(3)));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        Map<UUID, ImportSessionSummaryDto> byId = bundle.importSessions().stream()
                .collect(java.util.stream.Collectors.toMap(ImportSessionSummaryDto::id, s -> s));
        assertThat(byId.get(single.getId()).rowCount()).isEqualTo(3);
        assertThat(byId.get(multi.getId()).rowCount()).isEqualTo(5);
    }

    /** Bug fix (review): buildBundle used to map every import session unguarded -- one session
     *  whose staged JSON fails to deserialize threw ImportSessionService.readJson's uncaught
     *  IllegalStateException straight out of buildBundle, failing the ENTIRE export over one
     *  unrelated, unreadable session. Now caught, logged, and that one session dropped -- the
     *  rest of the export (including the other, healthy session) still succeeds. */
    @Test
    void buildBundle_importSessions_oneUnreadableSessionIsDroppedNotFatal() {
        ImportSession healthy = new ImportSession();
        ReflectionTestUtils.setField(healthy, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(healthy, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        healthy.setFileName("healthy.csv");

        ImportSession corrupted = new ImportSession();
        ReflectionTestUtils.setField(corrupted, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(corrupted, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        corrupted.setFileName("corrupted.csv");

        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(healthy, corrupted));
        when(importSessionService.readStagedRows(healthy)).thenReturn(nRows(4));
        when(importSessionService.readStagedRows(corrupted))
                .thenThrow(new IllegalStateException("failed to deserialize stagedRowsJson"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.importSessions()).hasSize(1);
        assertThat(bundle.importSessions().get(0).id()).isEqualTo(healthy.getId());
        assertThat(bundle.importSessions().get(0).rowCount()).isEqualTo(4);
    }

    private static List<StagedRow> nRows(int n) {
        List<StagedRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(null);
        return rows;
    }

    private static StagedAccountSection section(int rowCount) {
        return new StagedAccountSection(null, nRows(rowCount), rowCount, 0, List.of());
    }

    private static StatementImportRepository.StatementMetadata statementMetadata(UUID id, String fileName) {
        StatementImportRepository.StatementMetadata m = mock(StatementImportRepository.StatementMetadata.class);
        when(m.getId()).thenReturn(id);
        when(m.getFileName()).thenReturn(fileName);
        return m;
    }

    /** One statement's storage failure (a non-IOException, e.g. a bad object key) must not abort
     *  the whole export -- same "one bad row doesn't sink the batch" discipline
     *  AccountPurgeSweepService already established for its own per-statement loop. */
    @Test
    void writeZip_oneStatementFileReadFails_writesPlaceholderAndContinues() throws IOException {
        UUID okId = UUID.randomUUID();
        UUID failingId = UUID.randomUUID();
        // Built as separate statements, not inline inside the when(...).thenReturn(...) call
        // below -- each statementMetadata(...) call does its own when(...).thenReturn(...)
        // internally, and Mockito's stubbing-in-progress state doesn't nest: calling when()
        // again before an outer when(...) has received its thenReturn(...) throws
        // UnfinishedStubbingException.
        StatementImportRepository.StatementMetadata failingMeta = statementMetadata(failingId, "failing.pdf");
        StatementImportRepository.StatementMetadata okMeta = statementMetadata(okId, "ok.csv");
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(failingMeta, okMeta));
        when(statementImportService.getFile(userId, okId))
                .thenReturn(new StatementImportService.FileDownload("ok.csv", "hello".getBytes(), "text/csv"));
        // Not an IOException: a storage-layer failure (bad object key, missing row) is exactly
        // the case this loop's placeholder path exists for -- see the sibling test below for the
        // IOException (broken pipe) case, which must NOT get a placeholder.
        when(statementImportService.getFile(userId, failingId))
                .thenThrow(new RuntimeException("object storage unreachable"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        String okEntry = "statements/" + okId + "-ok.csv";
        String failingPlaceholder = "statements/" + failingId + "-failing.pdf.MISSING.txt";
        assertThat(entries).containsKey(okEntry);
        assertThat(new String(entries.get(okEntry))).isEqualTo("hello");
        assertThat(entries).containsKey(failingPlaceholder);
        assertThat(new String(entries.get(failingPlaceholder))).contains("RuntimeException");
        // The failed file's own bad bytes never got a normal, unmarked entry.
        assertThat(entries).doesNotContainKey("statements/" + failingId + "-failing.pdf");
    }

    /** Bug fix (review): an IOException reading/writing one statement (a broken pipe / client
     *  disconnect being the realistic case, but any IOException means the same thing -- the
     *  STREAM is unusable) used to be caught by the same handler as an ordinary storage failure,
     *  which then tried to write a ".MISSING.txt" placeholder onto that same broken stream --
     *  throwing a second, uncaught exception, misattributing an ordinary client-side cancel as a
     *  generic internal failure once it propagated out of writeZip. An IOException must propagate
     *  directly instead, with no placeholder attempted and no later statement reached. */
    @Test
    void writeZip_ioExceptionMidStatement_propagatesWithoutAttemptingAPlaceholder() throws IOException {
        UUID brokenId = UUID.randomUUID();
        UUID neverReachedId = UUID.randomUUID();
        StatementImportRepository.StatementMetadata brokenMeta = statementMetadata(brokenId, "broken.csv");
        StatementImportRepository.StatementMetadata neverReachedMeta = statementMetadata(neverReachedId, "later.csv");
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId))
                .thenReturn(List.of(brokenMeta, neverReachedMeta));
        // getFile() itself can't be stubbed to throw IOException -- its real signature declares no
        // checked exception, so Mockito rejects it (correctly: this method never throws one). The
        // realistic source of a genuine IOException here is the ZIP output stream itself going bad
        // mid-write (a broken pipe), so this test breaks the OutputStream instead, once real bytes
        // start flowing for this statement. The threshold is the length of the same archive with
        // no files in it -- longer than everything written ahead of statements/ (it adds the
        // central directory), far shorter than that plus 50KB of incompressible content. It used
        // to be a fixed 4096, which the manifest/README/JSON entries alone already exceeded: the
        // stream broke before getFile was ever called, and this test passed without exercising
        // the statement loop at all (proven by the getFile verify below failing at 4096).
        byte[] largeIncompressibleContent = new byte[50_000];
        new java.util.Random(42).nextBytes(largeIncompressibleContent);
        when(statementImportService.getFile(userId, brokenId))
                .thenReturn(new StatementImportService.FileDownload("broken.csv", largeIncompressibleContent, "text/csv"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        ByteArrayOutputStream withoutFiles = new ByteArrayOutputStream();
        service.writeZip(userId, withStoredFiles(bundle, List.of(), List.of()), withoutFiles);
        OutputStream out = new FailAfterNBytesOutputStream(new ByteArrayOutputStream(), withoutFiles.size());

        assertThatThrownBy(() -> service.writeZip(userId, bundle, out)).isInstanceOf(IOException.class);
        // The stream broke on this statement's bytes, not on some entry ahead of it.
        verify(statementImportService).getFile(userId, brokenId);
        // The stream stayed broken -- the loop must not have gone on to attempt the next
        // statement after the one that broke it.
        verify(statementImportService, org.mockito.Mockito.never()).getFile(userId, neverReachedId);
    }

    /** Starts passing bytes through untouched, then throws on every write once a byte-count
     *  threshold is crossed -- standing in for a client disconnecting partway through a download,
     *  without depending on exactly which internal ZipOutputStream/Deflater call happens to be
     *  in flight at that moment. */
    private static final class FailAfterNBytesOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream delegate;
        private final int failAfterBytes;
        private int written = 0;

        FailAfterNBytesOutputStream(java.io.OutputStream delegate, int failAfterBytes) {
            this.delegate = delegate;
            this.failAfterBytes = failAfterBytes;
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= failAfterBytes) throw new IOException("simulated broken pipe");
            written++;
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written >= failAfterBytes) throw new IOException("simulated broken pipe");
            written += len;
            delegate.write(b, off, len);
        }
    }

    /** imports/: a document already exported under statements/ (same content_hash) is not
     *  repeated, the same document uploaded twice is one entry from its newest job, and a job with
     *  no stored object has nothing to export. */
    @Test
    void buildBundle_unimportedUploads_skipsDocumentsUnderStatements_keepsNewestJobPerDocument() {
        ImportJob newestOfTwice = job("twice (1).pdf", "hash-twice", "key-twice-2");
        ImportJob alreadyImported = job("imported.pdf", "hash-imported", "key-imported");
        ImportJob olderOfTwice = job("twice.pdf", "hash-twice", "key-twice-1");
        ImportJob unaddressed = job("no-object.pdf", "hash-none", null);
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(List.of(newestOfTwice, alreadyImported, olderOfTwice, unaddressed));
        when(statementImportRepository.findContentHashesByUserId(userId)).thenReturn(List.of("hash-imported"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.unimportedUploads()).containsExactly(new DataExportService.UnimportedUpload(
                DataExportService.UnimportedUpload.Source.IMPORT_JOB, newestOfTwice.getId(), "twice (1).pdf"));
        // import_jobs.json itself is untouched by the dedupe -- every job is still listed there.
        assertThat(bundle.importJobs()).hasSize(4);
    }

    /** Sessions come after statements and jobs: a session holding a document already claimed by
     *  either is skipped; a session-only document is kept (newest session wins); a pre-V79 session
     *  with no hash cannot be matched and is kept rather than risk leaving it out. */
    @Test
    void buildBundle_unimportedUploads_sessionsOnlyForDocumentsNoStatementOrJobHolds() {
        ImportJob job = job("job.pdf", "hash-job", "key-job");
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of(job));
        when(statementImportRepository.findContentHashesByUserId(userId)).thenReturn(List.of("hash-imported"));
        ImportSession ofJob = stagedSession("job.pdf", "hash-job");
        ImportSession ofStatement = stagedSession("imported.csv", "hash-imported");
        ImportSession newestOnly = stagedSession("only (1).csv", "hash-only");
        ImportSession olderOnly = stagedSession("only.csv", "hash-only");
        ImportSession unhashed = stagedSession("old.csv", null);
        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .thenReturn(List.of(ofJob, ofStatement, newestOnly, olderOnly, unhashed));
        when(importSessionService.readStagedRows(any())).thenReturn(List.of());

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.unimportedUploads()).containsExactly(
                new DataExportService.UnimportedUpload(DataExportService.UnimportedUpload.Source.IMPORT_JOB, job.getId(), "job.pdf"),
                new DataExportService.UnimportedUpload(DataExportService.UnimportedUpload.Source.IMPORT_SESSION, newestOnly.getId(), "only (1).csv"),
                new DataExportService.UnimportedUpload(DataExportService.UnimportedUpload.Source.IMPORT_SESSION, unhashed.getId(), "old.csv"));
    }

    /** A session's bytes come from ImportSessionService.readOwnedFileContent, and a session that
     *  is gone by write time (confirmed and swept, say) becomes a placeholder like any other. */
    @Test
    void writeZip_sessionUploads_readThroughImportSessionService_placeholderWhenGone() throws IOException {
        ImportSession ok = stagedSession("ok.csv", "hash-ok");
        ImportSession gone = stagedSession("gone.csv", "hash-gone");
        when(importSessionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(ok, gone));
        when(importSessionService.readStagedRows(any())).thenReturn(List.of());
        when(importSessionService.readOwnedFileContent(userId, ok.getId())).thenReturn("staged".getBytes());
        when(importSessionService.readOwnedFileContent(userId, gone.getId()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Import session not found"));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        assertThat(new String(entries.get("imports/" + ok.getId() + "-ok.csv"))).isEqualTo("staged");
        assertThat(new String(entries.get("imports/" + gone.getId() + "-gone.csv.MISSING.txt"))).contains("ApiException");
        verifyNoInteractions(statementContentService);
    }

    private static ImportSession stagedSession(String fileName, String contentHash) {
        ImportSession session = new ImportSession();
        ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(session, "sessionKind", ImportSession.KIND_SINGLE_ACCOUNT);
        session.setFileName(fileName);
        session.setContentHash(contentHash);
        return session;
    }

    /** Same "one bad file doesn't sink the export" discipline as statements/: a storage failure,
     *  or a job deleted between buildBundle and writeZip, becomes a placeholder; the rest goes on. */
    @Test
    void writeZip_oneUnimportedUploadReadFails_writesPlaceholderAndContinues() throws IOException {
        ImportJob failing = job("failing.pdf", "hash-failing", "key-failing");
        ImportJob gone = job("gone.pdf", "hash-gone", "key-gone");
        ImportJob ok = job("ok.csv", "hash-ok", "key-ok");
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of(failing, gone, ok));
        when(importJobRepository.findByIdAndUserId(failing.getId(), userId)).thenReturn(Optional.of(failing));
        when(importJobRepository.findByIdAndUserId(gone.getId(), userId)).thenReturn(Optional.empty());
        when(importJobRepository.findByIdAndUserId(ok.getId(), userId)).thenReturn(Optional.of(ok));
        when(statementContentService.read(failing))
                .thenThrow(new com.finora.imports.storage.StatementStorageException("object missing"));
        when(statementContentService.read(ok)).thenReturn("hello".getBytes());

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        assertThat(new String(entries.get("imports/" + ok.getId() + "-ok.csv"))).isEqualTo("hello");
        assertThat(new String(entries.get("imports/" + failing.getId() + "-failing.pdf.MISSING.txt")))
                .contains("StatementStorageException");
        assertThat(new String(entries.get("imports/" + gone.getId() + "-gone.pdf.MISSING.txt")))
                .contains("IllegalStateException");
        assertThat(entries).doesNotContainKeys("imports/" + failing.getId() + "-failing.pdf",
                "imports/" + gone.getId() + "-gone.pdf");
        String manifest = new String(entries.get("manifest.json"));
        assertThat(manifest).contains("\"name\":\"imports/\"");
        assertThat(new String(entries.get("README.txt"))).contains("imports/");
    }

    /** Sibling of writeZip_ioExceptionMidStatement_propagatesWithoutAttemptingAPlaceholder, for
     *  imports/: a broken stream propagates, with no placeholder and no later upload read. */
    @Test
    void writeZip_ioExceptionMidUnimportedUpload_propagatesWithoutAttemptingAPlaceholder() throws IOException {
        ImportJob broken = job("broken.pdf", "hash-broken", "key-broken");
        ImportJob neverReached = job("later.pdf", "hash-later", "key-later");
        when(importJobRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any())).thenReturn(List.of(broken, neverReached));
        when(importJobRepository.findByIdAndUserId(broken.getId(), userId)).thenReturn(Optional.of(broken));
        when(importJobRepository.findByIdAndUserId(neverReached.getId(), userId)).thenReturn(Optional.of(neverReached));
        byte[] largeIncompressibleContent = new byte[50_000];
        new java.util.Random(42).nextBytes(largeIncompressibleContent);
        when(statementContentService.read(broken)).thenReturn(largeIncompressibleContent);

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        // Breaks the stream only past everything written ahead of imports/: a whole archive with
        // no uploads in it is longer than that prefix (it adds the central directory), and far
        // shorter than the prefix plus 50KB of incompressible upload. A fixed small threshold broke
        // inside the JSON entries instead, before any upload was read -- passing vacuously.
        ByteArrayOutputStream withoutUploads = new ByteArrayOutputStream();
        service.writeZip(userId, withStoredFiles(bundle, bundle.statementSummaries(), List.of()), withoutUploads);
        OutputStream out = new FailAfterNBytesOutputStream(new ByteArrayOutputStream(), withoutUploads.size());

        assertThatThrownBy(() -> service.writeZip(userId, bundle, out)).isInstanceOf(IOException.class);
        // The stream broke on this upload's bytes, not on some entry ahead of it.
        verify(statementContentService).read(broken);
        verify(statementContentService, org.mockito.Mockito.never()).read(neverReached);
    }

    /** The same bundle with its statements/ and imports/ file lists replaced. */
    private static DataExportService.ExportBundle withStoredFiles(DataExportService.ExportBundle b,
                                                                  List<com.finora.dto.StatementImportDto.Summary> statements,
                                                                  List<DataExportService.UnimportedUpload> uploads) {
        return new DataExportService.ExportBundle(b.userId(), b.email(), b.accounts(), b.transactions(), b.budgets(),
                b.goals(), b.goalContributions(), b.categories(), b.categoryRules(), b.relationships(),
                b.netWorthSnapshots(), b.merchants(), b.importJobs(), b.importSessions(), statements,
                uploads, b.gmailConnections(), b.userSettings(), b.workspaceSettings(), b.subscriptions(),
                b.planChanges(), b.supportTickets(), b.feedback(), b.chatConversations(), b.chatMessages(),
                b.healthScoreHistory(), b.financialFocus(), b.checklistEvents(), b.recurringDismissals(),
                b.accountAggregatorLinks(), b.merchantCategoryResolutions(), b.inflowKinds(), b.senderInflowRules(),
                b.paymentInflowChoices(), b.savedStatementPasswords(), b.featureViews(), b.payments(),
                b.subscriptionOrders(), b.referrals(), b.referralCode(), b.referralRewards(), b.wallet(),
                b.notifications(), b.notificationPreferences(), b.timeline(), b.transactionLinks(),
                b.statementExcludedRows(), b.merchantCategoryVotes(), b.statementRefreshRuns(), b.activityDays());
    }

    private ImportJob job(String fileName, String contentHash, String objectKey) {
        return new ImportJob(userId, fileName, contentHash, objectKey, "PDF");
    }

    /** Bug fix (review): toAccountExportEntry used to call AccountDto's 2-arg overload, which
     *  hardcodes statementsCount/transactionsCount/lastImportedAt to 0/0/null regardless of the
     *  account's real history -- see this test's own commit for the fix. Proves the real values
     *  now come through, computed the same batched way AccountService.listForUser does it. */
    @Test
    void buildBundle_accounts_computesRealStatementAndTransactionStats() {
        Account account = new Account();
        UUID accountId = UUID.randomUUID();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setUserId(userId);
        account.setAccountType(Account.Type.SAVINGS);
        account.setName("Everyday Savings");
        when(accountRepository.findByUserIdIncludingDeleted(userId)).thenReturn(List.of(account));

        LocalDate period = LocalDate.of(2026, 7, 1);
        StatementImportRepository.StatementMetadata statement = mock(StatementImportRepository.StatementMetadata.class);
        when(statement.getId()).thenReturn(UUID.randomUUID());
        when(statement.getAccountId()).thenReturn(accountId);
        when(statement.getFileName()).thenReturn("july.csv");
        when(statement.getImportedAt()).thenReturn(Instant.parse("2026-07-05T00:00:00Z"));
        when(statement.getStatementPeriodStart()).thenReturn(period);
        when(statement.getStatementPeriodEnd()).thenReturn(period.plusDays(30));
        when(statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)).thenReturn(List.of(statement));

        TransactionRepository.AccountTransactionCount count = mock(TransactionRepository.AccountTransactionCount.class);
        when(count.getAccountId()).thenReturn(accountId);
        when(count.getCount()).thenReturn(42L);
        when(transactionRepository.countByAccountForUser(userId)).thenReturn(List.of(count));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.accounts()).hasSize(1);
        var dto = bundle.accounts().get(0).account();
        assertThat(dto.statementsCount()).isEqualTo(1);
        assertThat(dto.transactionsCount()).isEqualTo(42L);
        assertThat(dto.lastImportedAt()).isEqualTo(Instant.parse("2026-07-05T00:00:00Z"));
        assertThat(dto.lastStatementPeriodStart()).isEqualTo(period);
    }

    /** transactions.json's real transformation -- category id resolved to a name via a batched
     *  lookup, not passed through untouched. */
    @Test
    void buildBundle_transactions_resolvesCategoryNameFromId() {
        Category category = new Category();
        UUID categoryId = UUID.randomUUID();
        ReflectionTestUtils.setField(category, "id", categoryId);
        category.setUserId(userId);
        category.setName("Groceries");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(category));

        Transaction categorized = new Transaction();
        ReflectionTestUtils.setField(categorized, "id", UUID.randomUUID());
        categorized.setUserId(userId);
        categorized.setCategoryId(categoryId);
        categorized.setTxnDate(LocalDate.of(2026, 7, 10));
        categorized.setAmount(BigDecimal.valueOf(500));
        categorized.setTxnType(Transaction.Type.EXPENSE);
        categorized.setDescription("Big Bazaar");

        Transaction uncategorized = new Transaction();
        ReflectionTestUtils.setField(uncategorized, "id", UUID.randomUUID());
        uncategorized.setUserId(userId);
        uncategorized.setCategoryId(null);
        uncategorized.setTxnDate(LocalDate.of(2026, 7, 11));
        uncategorized.setAmount(BigDecimal.valueOf(100));
        uncategorized.setTxnType(Transaction.Type.EXPENSE);
        uncategorized.setDescription("Cash withdrawal");

        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(categorized, uncategorized));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.transactions()).hasSize(2);
        assertThat(bundle.transactions()).anySatisfy(t -> {
            assertThat(t.description()).isEqualTo("Big Bazaar");
            assertThat(t.categoryName()).isEqualTo("Groceries");
        });
        assertThat(bundle.transactions()).anySatisfy(t -> {
            assertThat(t.description()).isEqualTo("Cash withdrawal");
            assertThat(t.categoryName()).isEqualTo("Uncategorized");
        });
    }

    /** net_worth_history.json's field mapping -- catches an argument-order mistake between
     *  totalAssets/totalLiabilities/netWorth, which an empty-list-only test never could. */
    @Test
    void buildBundle_netWorthSnapshots_mapsAllFieldsCorrectly() {
        NetWorthSnapshot snapshot = new NetWorthSnapshot();
        ReflectionTestUtils.setField(snapshot, "id", UUID.randomUUID());
        snapshot.setUserId(userId);
        snapshot.setSnapshotDate(LocalDate.of(2026, 6, 30));
        snapshot.setTotalAssets(BigDecimal.valueOf(500000));
        snapshot.setTotalLiabilities(BigDecimal.valueOf(120000));
        snapshot.setNetWorth(BigDecimal.valueOf(380000));
        when(netWorthSnapshotRepository.findByUserIdOrderBySnapshotDateAsc(userId)).thenReturn(List.of(snapshot));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.netWorthSnapshots()).hasSize(1);
        var dto = bundle.netWorthSnapshots().get(0);
        assertThat(dto.totalAssets()).isEqualByComparingTo(BigDecimal.valueOf(500000));
        assertThat(dto.totalLiabilities()).isEqualByComparingTo(BigDecimal.valueOf(120000));
        assertThat(dto.netWorth()).isEqualByComparingTo(BigDecimal.valueOf(380000));
    }

    /** merchants.json's identity-only mapping -- the regression this plan's own Finding 2 exists
     *  to catch: MerchantExportDto must never carry topCategory/topCategoryConfidence/distribution
     *  (derived from the excluded merchant-learning tables). Structurally impossible today since
     *  the DTO has no such fields, but this pins the identity fields it does carry actually map
     *  correctly rather than just compiling. */
    @Test
    void buildBundle_merchants_mapsIdentityFieldsOnly() {
        Merchant merchant = new Merchant();
        ReflectionTestUtils.setField(merchant, "id", UUID.randomUUID());
        merchant.setUserId(userId);
        merchant.setCanonicalName("Amazon");
        merchant.setLogoUrl("https://example.com/amazon.png");
        merchant.setWebsite("https://amazon.in");
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.merchants()).hasSize(1);
        var dto = bundle.merchants().get(0);
        assertThat(dto.canonicalName()).isEqualTo("Amazon");
        assertThat(dto.website()).isEqualTo("https://amazon.in");
        assertThat(dto.lifecycleStatus()).isEqualTo("APPROVED");
    }

    /** category_rules.json now goes through the shared RuleDto.from(CategoryRule) factory (also
     *  used by RuleService) instead of a hand-duplicated mapping -- proves the shared path still
     *  produces the right shape for export specifically. */
    @Test
    void buildBundle_categoryRules_mapsViaSharedRuleDtoFactory() {
        CategoryRule rule = new CategoryRule();
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        rule.setUserId(userId);
        rule.setScope(CategoryRule.Scope.USER);
        rule.setField(CategoryRule.Field.MERCHANT);
        rule.setOperator(CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("Amazon");
        rule.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.categoryRules()).hasSize(1);
        var dto = bundle.categoryRules().get(0);
        assertThat(dto.scope()).isEqualTo("USER");
        assertThat(dto.field()).isEqualTo("MERCHANT");
        assertThat(dto.comparisonValue()).isEqualTo("Amazon");
    }

    /** Plan 2: the user's own answers about their money -- their kinds, remembered senders and
     *  per-payment choices -- are their data and must leave with the export. */
    @Test
    void buildBundle_includesInflowKindsRememberedSendersAndPaymentChoices() {
        com.finora.entity.InflowKind kind = new com.finora.entity.InflowKind();
        ReflectionTestUtils.setField(kind, "id", UUID.randomUUID());
        kind.setUserId(userId);
        kind.setName("Rent from tenant");
        kind.setCountsAsIncome(true);
        when(inflowKindRepository.findByUserId(userId)).thenReturn(List.of(kind));
        com.finora.entity.SenderInflowRule rule = new com.finora.entity.SenderInflowRule();
        ReflectionTestUtils.setField(rule, "id", UUID.randomUUID());
        rule.setUserId(userId);
        rule.setCounterpartyKey("vpa:tenant1");
        rule.setInflowKindId(kind.getId());
        when(senderInflowRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        Transaction chosen = new Transaction();
        ReflectionTestUtils.setField(chosen, "id", UUID.randomUUID());
        chosen.setUserId(userId);
        chosen.setTxnType(Transaction.Type.INCOME);
        chosen.setAmount(java.math.BigDecimal.TEN);
        chosen.setInflowKindId(kind.getId());
        when(transactionRepository.findByUserId(userId)).thenReturn(List.of(chosen));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.inflowKinds()).singleElement().satisfies(k -> {
            assertThat(k.name()).isEqualTo("Rent from tenant");
            assertThat(k.countsAsIncome()).isTrue();
        });
        assertThat(bundle.senderInflowRules()).singleElement().satisfies(r -> {
            assertThat(r.senderKey()).isEqualTo("vpa:tenant1");
            assertThat(r.kindName()).isEqualTo("Rent from tenant");
        });
        assertThat(bundle.paymentInflowChoices()).singleElement().satisfies(c -> {
            assertThat(c.transactionId()).isEqualTo(chosen.getId());
            assertThat(c.kindName()).isEqualTo("Rent from tenant");
        });
    }

    /** gmail_connection.json's scope-splitting logic -- grantedScopes is stored as one
     *  space-separated string and must come back as a real list, not a single-element list
     *  containing the whole string. */
    @Test
    void buildBundle_gmailConnections_splitsGrantedScopesIntoAList() {
        GmailConnection connection = new GmailConnection();
        connection.setUserId(userId);
        connection.setGoogleEmail("jane@example.com");
        connection.setGrantedScopes("https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/userinfo.email");
        when(gmailConnectionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(connection));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.gmailConnections()).hasSize(1);
        var dto = bundle.gmailConnections().get(0);
        assertThat(dto.grantedScopes()).containsExactly(
                "https://www.googleapis.com/auth/gmail.readonly", "https://www.googleapis.com/auth/userinfo.email");
        assertThat(dto.googleEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void writeZip_manifestListsEveryOutOfScopeTableWithAReason() throws IOException {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        List<String> excludedNames = new ArrayList<>();
        manifest.get("excluded").forEach(n -> excludedNames.add(n.get("name").asText()));

        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("audit_logs"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("merchant_category_learning"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("refresh_tokens"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("password_history"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("statement_analysis_sessions"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("subscription_events"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("support_ticket_attachments"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("support_ticket_internal_notes"));
        // F-03 fix: the two internal AA-link fields left out of account_aggregator_links.json
        // must still be disclosed here, not silently dropped with no explanation anywhere.
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("link_idempotency_key"));
        assertThat(excludedNames).anySatisfy(n -> assertThat(n).contains("consent_handle_id"));
        assertThat(excludedNames).noneSatisfy(n -> assertThat(n).contains("plan_changes"));

        List<String> includedNames = new ArrayList<>();
        manifest.get("included").forEach(n -> includedNames.add(n.get("name").asText()));
        assertThat(includedNames).contains("accounts.json", "transactions.json", "statements/", "goal_contributions.json",
                "subscriptions.json", "plan_changes.json", "support_tickets.json", "feedback.json",
                // F-03 fix: these eight were previously neither included nor excluded anywhere in
                // this manifest -- the export gave no indication they existed at all.
                "fyn_chat_conversations.json", "fyn_chat_messages.json", "health_score_history.json",
                "financial_focus.json", "onboarding_checklist.json", "recurring_dismissals.json",
                "account_aggregator_links.json", "merchant_category_corrections.json",
                // Was in AccountPurgeSweepService's purge scope with no manifest entry either way.
                "feature_views.json");
        // manifest.json/README.txt describe the archive itself, not one more table in it.
        assertThat(includedNames).doesNotContain("manifest.json", "README.txt");

        assertThat(entries).containsKey("README.txt");
    }

    /** F-03 fix (security/privacy audit, 2026-09-18): fyn_chat_conversations.json/
     *  fyn_chat_messages.json -- messages are batch-fetched across every one of this user's
     *  conversations in one call, the same treatment goal_contributions.json already gives goals. */
    @Test
    void buildBundle_fynChat_includesConversationsAndBatchFetchesTheirMessages() {
        ChatConversation conversationOne = new ChatConversation();
        UUID conversationOneId = UUID.randomUUID();
        ReflectionTestUtils.setField(conversationOne, "id", conversationOneId);
        conversationOne.setUserId(userId);
        conversationOne.setTitle("Am I overspending on dining?");

        ChatConversation conversationTwo = new ChatConversation();
        UUID conversationTwoId = UUID.randomUUID();
        ReflectionTestUtils.setField(conversationTwo, "id", conversationTwoId);
        conversationTwo.setUserId(userId);
        conversationTwo.setTitle("Goal planning");

        when(chatConversationRepository.findByUserIdOrderByUpdatedAtDesc(userId))
                .thenReturn(List.of(conversationOne, conversationTwo));

        ChatMessage message = new ChatMessage();
        UUID messageId = UUID.randomUUID();
        ReflectionTestUtils.setField(message, "id", messageId);
        message.setConversationId(conversationOneId);
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent("Am I overspending on dining?");
        when(chatMessageRepository.findByConversationIdInOrderByCreatedAtAsc(List.of(conversationOneId, conversationTwoId)))
                .thenReturn(List.of(message));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.chatConversations()).hasSize(2);
        assertThat(bundle.chatConversations()).anySatisfy(c -> assertThat(c.id()).isEqualTo(conversationOneId));
        assertThat(bundle.chatMessages()).hasSize(1);
        assertThat(bundle.chatMessages().get(0).id()).isEqualTo(messageId);
        assertThat(bundle.chatMessages().get(0).conversationId()).isEqualTo(conversationOneId);
        assertThat(bundle.chatMessages().get(0).content()).isEqualTo("Am I overspending on dining?");
    }

    /** F-03 fix. health_score_history.json reads the FULL history via the export's own
     *  findByUserIdOrderByYearMonthAsc, not the dashboard's findTop6ByUserIdOrderByYearMonthDesc --
     *  this export owes the user everything stored, not just what the Health Score card shows. */
    @Test
    void buildBundle_healthScoreHistory_readsFullHistoryNotJustTheDashboardsTopSix() {
        HealthScoreSnapshot snapshot = new HealthScoreSnapshot();
        UUID snapshotId = UUID.randomUUID();
        ReflectionTestUtils.setField(snapshot, "id", snapshotId);
        snapshot.setUserId(userId);
        snapshot.setYearMonth("2026-06");
        snapshot.setOverallScore(72);
        snapshot.setLabel("Good");
        snapshot.setComputedAt(Instant.now());
        when(healthScoreSnapshotRepository.findByUserIdOrderByYearMonthAsc(userId)).thenReturn(List.of(snapshot));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.healthScoreHistory()).hasSize(1);
        var dto = bundle.healthScoreHistory().get(0);
        assertThat(dto.id()).isEqualTo(snapshotId);
        assertThat(dto.yearMonth()).isEqualTo("2026-06");
        assertThat(dto.overallScore()).isEqualTo(72);
        verify(healthScoreSnapshotRepository, never()).findTop6ByUserIdOrderByYearMonthDesc(any());
    }

    /** F-03 fix. financial_focus.json/onboarding_checklist.json -- onboarding preferences and
     *  completed checklist items, neither of which this export read before. */
    @Test
    void buildBundle_includesFinancialFocusAndOnboardingChecklist() {
        UserFinancialFocus focus = new UserFinancialFocus(userId, "BUDGETING");
        when(userFinancialFocusRepository.findByUserId(userId)).thenReturn(List.of(focus));

        UserChecklistEvent checklistEvent = new UserChecklistEvent(userId, "LINKED_FIRST_ACCOUNT");
        when(userChecklistEventRepository.findByUserId(userId)).thenReturn(List.of(checklistEvent));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.financialFocus()).hasSize(1);
        assertThat(bundle.financialFocus().get(0).focusKey()).isEqualTo("BUDGETING");
        assertThat(bundle.checklistEvents()).hasSize(1);
        assertThat(bundle.checklistEvents().get(0).itemKey()).isEqualTo("LINKED_FIRST_ACCOUNT");
    }

    /** V249. activity_days.json -- user_activity_days is in AccountPurgeSweepService's purge scope,
     *  so under the F-03 rule it must be exported (or disclosed as excluded), never silently absent.
     *  Asserted on the written ZIP, not just the bundle: the file name, the ISO date strings and the
     *  manifest entry are what the user actually receives. */
    @Test
    void writeZip_includesActivityDaysAsIsoDatesAndListsThemInTheManifest() throws IOException {
        com.finora.entity.UserActivityDay first = new com.finora.entity.UserActivityDay();
        ReflectionTestUtils.setField(first, "userId", userId);
        ReflectionTestUtils.setField(first, "activityDate", java.time.LocalDate.of(2026, 9, 30));
        com.finora.entity.UserActivityDay second = new com.finora.entity.UserActivityDay();
        ReflectionTestUtils.setField(second, "userId", userId);
        ReflectionTestUtils.setField(second, "activityDate", java.time.LocalDate.of(2026, 10, 1));
        when(userActivityDayRepository.findByUserIdOrderByActivityDateAsc(userId)).thenReturn(List.of(first, second));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode days = mapper.readTree(entries.get("activity_days.json"));
        assertThat(days.isArray()).isTrue();
        assertThat(days).extracting(JsonNode::asText).containsExactly("2026-09-30", "2026-10-01");

        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        List<String> includedNames = new ArrayList<>();
        manifest.get("included").forEach(n -> includedNames.add(n.get("name").asText()));
        assertThat(includedNames).contains("activity_days.json");
    }

    /** F-03 fix. recurring_dismissals.json -- a user-dismissed recurring transaction group. */
    @Test
    void buildBundle_includesRecurringDismissals() {
        RecurringDismissal dismissal = new RecurringDismissal(userId, "Netflix");
        when(recurringDismissalRepository.findByUserId(userId)).thenReturn(java.util.Set.of(dismissal));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.recurringDismissals()).hasSize(1);
        assertThat(bundle.recurringDismissals().get(0).merchant()).isEqualTo("Netflix");
    }

    /** feature_views.json -- V171's per-feature view counters, in AccountPurgeSweepService's purge
     *  scope but previously neither exported nor listed as excluded. Asserts the written ZIP entry
     *  itself and its manifest entry, not just the in-memory bundle. */
    @Test
    void writeZip_includesFeatureViewsEntryAndManifestEntry() throws IOException {
        FeatureViewCount insights = new FeatureViewCount();
        ReflectionTestUtils.setField(insights, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(insights, "userId", userId);
        ReflectionTestUtils.setField(insights, "feature", "INSIGHTS");
        ReflectionTestUtils.setField(insights, "viewCount", 7);
        Instant lastViewedAt = Instant.parse("2026-09-30T10:15:30Z");
        ReflectionTestUtils.setField(insights, "lastViewedAt", lastViewedAt);
        when(featureViewCountRepository.findByUserIdOrderByFeatureAsc(userId)).thenReturn(List.of(insights));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        JsonNode featureViews = mapper.readTree(entries.get("feature_views.json"));
        assertThat(featureViews.isArray()).isTrue();
        assertThat(featureViews).hasSize(1);
        JsonNode row = featureViews.get(0);
        assertThat(row.get("feature").asText()).isEqualTo("INSIGHTS");
        assertThat(row.get("viewCount").asInt()).isEqualTo(7);
        assertThat(mapper.treeToValue(row.get("lastViewedAt"), Instant.class)).isEqualTo(lastViewedAt);
        // The row's surrogate key and owner id are not exported.
        assertThat(row.has("id")).isFalse();
        assertThat(row.has("userId")).isFalse();

        JsonNode manifest = mapper.readTree(entries.get("manifest.json"));
        List<JsonNode> featureViewEntries = new ArrayList<>();
        manifest.get("included").forEach(n -> {
            if (n.get("name").asText().equals("feature_views.json")) featureViewEntries.add(n);
        });
        assertThat(featureViewEntries).hasSize(1);
        assertThat(featureViewEntries.get(0).get("rowCount").asInt()).isEqualTo(1);
        assertThat(featureViewEntries.get(0).get("description").asText()).isNotBlank();
        List<String> excludedNames = new ArrayList<>();
        manifest.get("excluded").forEach(n -> excludedNames.add(n.get("name").asText()));
        assertThat(excludedNames).noneSatisfy(n -> assertThat(n).contains("feature_view"));
    }

    /** F-03 fix. account_aggregator_links.json -- confirms the three internal-only fields
     *  (consentHandleId, linkIdempotencyKey, resolutionClaimedAt -- see this DTO's own doc
     *  comment) never leak into the export, while the user-meaningful consent/link state does. */
    @Test
    void buildBundle_accountAggregatorLinks_excludesInternalCorrelationAndClaimFields() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        UUID linkId = UUID.randomUUID();
        ReflectionTestUtils.setField(link, "id", linkId);
        link.setUserId(userId);
        link.setConsentHandleId("setu-consent-handle-123");
        link.setFiType(FiType.DEPOSIT);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLinkIdempotencyKey("client-minted-key-should-not-export");
        when(accountAggregatorLinkRepository.findByUserId(userId)).thenReturn(List.of(link));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.accountAggregatorLinks()).hasSize(1);
        var dto = bundle.accountAggregatorLinks().get(0);
        assertThat(dto.id()).isEqualTo(linkId);
        assertThat(dto.fiType()).isEqualTo("DEPOSIT");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        // None of the three internal fields exist on the DTO at all -- a compile-time guarantee,
        // not a runtime one, but the ZIP-level test above proves the manifest still discloses why.
    }

    /** F-03 fix. merchant_category_corrections.json -- categoryId resolved to categoryName via
     *  the same batched category lookup transactions.json already uses, same treatment
     *  transactions.json gives its own categoryId. Null categoryName (missing category) fails
     *  soft rather than dropping the resolution row -- proven by the second assertion below. */
    @Test
    void buildBundle_merchantCategoryResolutions_resolvesCategoryNameAndFailsSoftIfMissing() {
        UUID knownCategoryId = UUID.randomUUID();
        Category knownCategory = new Category();
        ReflectionTestUtils.setField(knownCategory, "id", knownCategoryId);
        knownCategory.setName("Dining");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(knownCategory));

        UUID missingCategoryId = UUID.randomUUID();

        UserMerchantCategoryResolution resolvedToKnown = new UserMerchantCategoryResolution();
        ReflectionTestUtils.setField(resolvedToKnown, "id", UUID.randomUUID());
        resolvedToKnown.setUserId(userId);
        resolvedToKnown.setCounterpartyKey("SWIGGY");
        resolvedToKnown.setDirection(Transaction.Type.EXPENSE);
        resolvedToKnown.setCategoryId(knownCategoryId);

        UserMerchantCategoryResolution resolvedToMissing = new UserMerchantCategoryResolution();
        ReflectionTestUtils.setField(resolvedToMissing, "id", UUID.randomUUID());
        resolvedToMissing.setUserId(userId);
        resolvedToMissing.setCounterpartyKey("OLDMERCHANT");
        resolvedToMissing.setDirection(Transaction.Type.EXPENSE);
        resolvedToMissing.setCategoryId(missingCategoryId);

        when(userMerchantCategoryResolutionRepository.findAllByUserId(userId))
                .thenReturn(List.of(resolvedToKnown, resolvedToMissing));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.merchantCategoryResolutions()).hasSize(2);
        assertThat(bundle.merchantCategoryResolutions()).anySatisfy(r -> {
            assertThat(r.counterpartyKey()).isEqualTo("SWIGGY");
            assertThat(r.categoryId()).isEqualTo(knownCategoryId);
            assertThat(r.categoryName()).isEqualTo("Dining");
        });
        assertThat(bundle.merchantCategoryResolutions()).anySatisfy(r -> {
            assertThat(r.counterpartyKey()).isEqualTo("OLDMERCHANT");
            assertThat(r.categoryId()).isEqualTo(missingCategoryId);
            assertThat(r.categoryName()).isNull();
        });
    }

    /** support_tickets.json/feedback.json (Phase 7): attachment metadata (filename, no bytes)
     *  comes through via the batched findMetadataByTicketIdIn, grouped back to the right ticket --
     *  and neither JSON entry ever carries an internal note, which is structurally impossible
     *  anyway since SupportTicketDto.Detail has no such field, but this pins the real values
     *  actually flow through rather than just compiling. */
    @Test
    void buildBundle_supportTicketsAndFeedback_includeAttachmentMetadataButNeverNoteContent() {
        SupportTicket ticket = new SupportTicket();
        UUID ticketId = UUID.randomUUID();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTicketNumber("SUP-000042");
        ticket.setUserId(userId);
        ticket.setCategory(SupportTicket.Category.STATEMENT_IMPORT);
        ticket.setSource(ClientPlatform.WEB);
        ticket.setSubject("Import stuck");
        ticket.setDescription("Progress bar froze at 60%.");
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        SupportTicketAttachment attachment = new SupportTicketAttachment();
        UUID attachmentId = UUID.randomUUID();
        ReflectionTestUtils.setField(attachment, "id", attachmentId);
        attachment.setTicketId(ticketId);
        attachment.setFilename("screenshot.png");
        attachment.setContentType("image/png");
        attachment.setSizeBytes(2048);
        SupportTicketAttachmentRepository.AttachmentMetadata attachmentMetadata =
                mock(SupportTicketAttachmentRepository.AttachmentMetadata.class);
        when(attachmentMetadata.getId()).thenReturn(attachmentId);
        when(attachmentMetadata.getTicketId()).thenReturn(ticketId);
        when(attachmentMetadata.getFilename()).thenReturn("screenshot.png");
        when(attachmentMetadata.getContentType()).thenReturn("image/png");
        when(attachmentMetadata.getSizeBytes()).thenReturn(2048L);
        when(supportTicketAttachmentRepository.findMetadataByTicketIdIn(List.of(ticketId)))
                .thenReturn(List.of(attachmentMetadata));

        FeedbackEntry feedback = new FeedbackEntry();
        ReflectionTestUtils.setField(feedback, "id", UUID.randomUUID());
        feedback.setUserId(userId);
        feedback.setType(FeedbackEntry.Type.BUG);
        feedback.setContext(FeedbackEntry.Context.IMPORT_FLOW);
        feedback.setSource(ClientPlatform.WEB);
        feedback.setMessage("The import bar sticks at 60%.");
        when(feedbackEntryRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(feedback));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.supportTickets()).hasSize(1);
        SupportTicketDto.Detail ticketDto = bundle.supportTickets().get(0);
        assertThat(ticketDto.ticketNumber()).isEqualTo("SUP-000042");
        assertThat(ticketDto.attachments()).hasSize(1);
        assertThat(ticketDto.attachments().get(0).filename()).isEqualTo("screenshot.png");
        assertThat(ticketDto.attachments().get(0).sizeBytes()).isEqualTo(2048);

        assertThat(bundle.feedback()).hasSize(1);
        FeedbackDto.Summary feedbackDto = bundle.feedback().get(0);
        assertThat(feedbackDto.message()).isEqualTo("The import bar sticks at 60%.");
        assertThat(feedbackDto.type()).isEqualTo(FeedbackEntry.Type.BUG);
    }

    /** A ticket with no attachment must not throw looking itself up in the batched-and-grouped
     *  map -- the missing-key case getOrDefault(..., List.of()) exists for. */
    @Test
    void buildBundle_supportTickets_ticketWithNoAttachmentGetsAnEmptyList() {
        SupportTicket ticket = new SupportTicket();
        UUID ticketId = UUID.randomUUID();
        ReflectionTestUtils.setField(ticket, "id", ticketId);
        ticket.setTicketNumber("SUP-000007");
        ticket.setUserId(userId);
        ticket.setCategory(SupportTicket.Category.OTHER);
        ticket.setSource(ClientPlatform.WEB);
        ticket.setSubject("No attachment");
        ticket.setDescription("Text-only ticket.");
        when(supportTicketRepository.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(ticket)));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);

        assertThat(bundle.supportTickets()).hasSize(1);
        assertThat(bundle.supportTickets().get(0).attachments()).isEmpty();
    }

    /** Instantiates a JPA entity through its no-arg constructor, protected or not, so a fixture can
     *  set exactly the fields a test reads without depending on each entity's own factories. */
    private static <T> T entity(Class<T> type, Object... fieldValuePairs) {
        T instance = org.springframework.beans.BeanUtils.instantiateClass(type);
        for (int i = 0; i < fieldValuePairs.length; i += 2) {
            ReflectionTestUtils.setField(instance, (String) fieldValuePairs[i], fieldValuePairs[i + 1]);
        }
        return instance;
    }

    /**
     * Every table AccountPurgeSweepService deletes for a user must be in the export or listed as
     * excluded with a reason (F-03). This reads that service's source, finds every purge call, and
     * fails when one has no entry below -- or when an entry names a file or table the manifest does
     * not actually carry. A new purge call therefore cannot ship without someone deciding, here,
     * whether the user gets that data back.
     *
     * <p>What it cannot see: tables removed by ON DELETE CASCADE (held_statements,
     * notification_logs, goal contributions and the like) and deletes routed through a service
     * rather than a repository (statements go through StatementImportService.delete). Those are
     * covered by their own manifest entries and tests, not by this scan.
     */
    @Test
    void everyPurgedTableIsExportedOrListedAsExcluded() throws IOException {
        Map<String, String> purgeCallToManifestName = Map.ofEntries(
                Map.entry("gmailConnectionRepository.deleteByUserId", "gmail_connection.json"),
                Map.entry("transactionRepository.hardDeleteByUserId", "transactions.json"),
                Map.entry("senderInflowRuleRepository.hardDeleteByUserId", "remembered_senders.json"),
                Map.entry("inflowKindRepository.hardDeleteByUserId", "money_kinds.json"),
                Map.entry("transactionRelationshipRepository.deleteByUserId", "transaction_links.json"),
                Map.entry("statementImportRepository.deleteExcludedRowsOfUser", "statement_excluded_rows.json"),
                Map.entry("statementImportRepository.deleteRefreshPreviewsOfUser", "statement_refresh_previews"),
                Map.entry("statementImportRepository.deleteRefreshRunsOfUser", "statement_refresh_runs.json"),
                Map.entry("statementImportRepository.deleteStatementPasswordsOfUser", "saved_statement_passwords.json"),
                Map.entry("merchantLearningEventRepository.deleteByUserId", "merchant_learning_event"),
                Map.entry("merchantLearningAuditRepository.deleteByUserId", "merchant_learning_audit"),
                Map.entry("merchantCategoryLearningRepository.deleteByUserId", "merchant_category_learning"),
                Map.entry("merchantAliasRepository.deleteByUserId", "merchant_aliases"),
                Map.entry("merchantCategoryMapRepository.deleteByUserId", "merchant_category_map"),
                Map.entry("merchantRepository.deleteByUserId", "merchants.json"),
                Map.entry("budgetRepository.hardDeleteByUserId", "budgets.json"),
                Map.entry("goalRepository.hardDeleteByUserId", "goals.json"),
                Map.entry("paymentRepository.hardDeleteByUserId", "payments.json"),
                Map.entry("subscriptionRepository.hardDeleteByUserId", "subscriptions.json"),
                Map.entry("subscriptionOrderRepository.hardDeleteByUserId", "subscription_orders.json"),
                Map.entry("referralGrantRepository.deleteByUserId", "referral_rewards.json"),
                Map.entry("referralCodeRepository.deleteByUserId", "referral_code.json"),
                Map.entry("referralChargeRepository.deleteByReferrerUserId", "referral_charges"),
                Map.entry("referralRepository.deleteByReferrerUserId", "referrals.json"),
                Map.entry("referralRepository.deleteByReferredUserId", "referrals.json"),
                Map.entry("walletLedgerRepository.deleteByUserId", "wallet.json"),
                Map.entry("categoryRuleRepository.deleteByUserId", "category_rules.json"),
                Map.entry("userMerchantCategoryResolutionRepository.deleteByUserId", "merchant_category_corrections.json"),
                Map.entry("categoryRepository.deleteByUserId", "categories.json"),
                Map.entry("relationshipIdentifierRepository.deleteByRelationshipId", "relationships.json"),
                Map.entry("relationshipRepository.deleteAll", "relationships.json"),
                Map.entry("netWorthSnapshotRepository.deleteByUserId", "net_worth_history.json"),
                Map.entry("timelineEventRepository.deleteByUserId", "timeline.json"),
                Map.entry("importJobRepository.deleteByUserId", "import_jobs.json"),
                Map.entry("importSessionRepository.deleteByUserId", "import_sessions.json"),
                Map.entry("passwordHistoryRepository.deleteByUserId", "password_history"),
                Map.entry("passwordChangeSessionRepository.deleteByUserId", "password_change_sessions"),
                Map.entry("passwordResetTokenRepository.deleteByUserId", "password_reset_tokens"),
                Map.entry("accountReactivationTokenRepository.deleteByUserId", "account_reactivation_tokens"),
                Map.entry("emailVerificationTokenRepository.deleteByUserId", "email_verification_tokens"),
                Map.entry("emailLoginOtpRepository.deleteByUserId", "email_login_otps"),
                Map.entry("refreshTokenRepository.deleteByUserId", "refresh_tokens"),
                Map.entry("userSettingsRepository.deleteByUserId", "account_settings.json"),
                Map.entry("notificationRepository.deleteByUserId", "notifications.json"),
                Map.entry("supportTicketRepository.deleteByUserId", "support_tickets.json"),
                Map.entry("feedbackEntryRepository.deleteByUserId", "feedback.json"),
                Map.entry("emailChangeSessionRepository.deleteByUserId", "email_change_sessions"),
                Map.entry("phoneChangeSessionRepository.deleteByUserId", "phone_change_sessions"),
                Map.entry("deviceTokenRepository.deleteByUserId", "device_tokens"),
                Map.entry("notificationPreferenceRepository.deleteByUserId", "notification_preferences.json"),
                Map.entry("reimportConfirmationClaimRepository.deleteByUserId", "reimport_confirmation_claims"),
                Map.entry("userFinancialFocusRepository.deleteByUserId", "financial_focus.json"),
                Map.entry("userChecklistEventRepository.deleteByUserId", "onboarding_checklist.json"),
                Map.entry("healthScoreSnapshotRepository.deleteByUserId", "health_score_history.json"),
                Map.entry("featureViewCountRepository.deleteByUserId", "feature_views.json"),
                Map.entry("recurringDismissalRepository.deleteByUserId", "recurring_dismissals.json"),
                Map.entry("accountAggregatorLinkRepository.deleteByUserId", "account_aggregator_links.json"),
                Map.entry("aiAuditLogRepository.deleteByUserId", "ai_audit_log"),
                Map.entry("chatMessageRepository.deleteByUserId", "fyn_chat_messages.json"),
                Map.entry("chatConversationRepository.deleteByUserId", "fyn_chat_conversations.json"),
                Map.entry("counterpartyCategoryObservationRepository.deleteByUserId", "merchant_category_votes.json"),
                Map.entry("userActivityDayRepository.deleteByUserId", "activity_days.json"),
                Map.entry("statementAnalysisSessionRepository.anonymizeByUserId", "statement_analysis_sessions"));

        String purgeSource = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/finora/service/AccountPurgeSweepService.java"));
        java.util.regex.Matcher calls = java.util.regex.Pattern
                .compile("(\\w+Repository)\\.((?:hardDelete|delete|anonymize)\\w*)\\(")
                .matcher(purgeSource);
        java.util.Set<String> purgeCalls = new java.util.TreeSet<>();
        while (calls.find()) purgeCalls.add(calls.group(1) + "." + calls.group(2));

        // Sanity: the scan itself found the purge, not an empty file or a renamed class.
        assertThat(purgeCalls).hasSizeGreaterThan(50);
        assertThat(purgeCalls)
                .as("purge calls with no export/exclusion decision -- add each to the export or to "
                        + "buildManifest's excluded list, then to this map")
                .allMatch(purgeCallToManifestName::containsKey);
        assertThat(purgeCallToManifestName.keySet())
                .as("entries naming a purge call AccountPurgeSweepService no longer makes")
                .allMatch(purgeCalls::contains);

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        JsonNode manifest = new ObjectMapper().readTree(writeZipAndReadEntries(bundle).get("manifest.json"));
        List<String> includedNames = new ArrayList<>();
        manifest.get("included").forEach(n -> includedNames.add(n.get("name").asText()));
        List<String> excludedNames = new ArrayList<>();
        manifest.get("excluded").forEach(n -> {
            assertThat(n.get("description").asText()).as("reason for " + n.get("name").asText()).isNotBlank();
            excludedNames.add(n.get("name").asText());
        });
        for (String name : new java.util.TreeSet<>(purgeCallToManifestName.values())) {
            boolean included = includedNames.contains(name);
            boolean excluded = excludedNames.stream().anyMatch(e ->
                    java.util.Arrays.stream(e.split(",")).map(String::trim)
                            .anyMatch(part -> part.equals(name) || part.startsWith(name + " ")));
            assertThat(included || excluded).as(name + " is neither in the manifest's included nor excluded list").isTrue();
        }
    }

    /** The twelve tables added to close the rest of the F-03 gap: each one is written to the ZIP,
     *  carries the row's real values, and appears in the manifest with its row count. */
    @Test
    void writeZip_exportsEveryRemainingPurgedTable() throws IOException {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("PLUS");
        plan.setName("Plus");
        when(planRepository.findAllById(any())).thenReturn(List.of(plan));

        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.entity.Payment.class, "id", paymentId, "userId", userId, "planId", planId,
                "amount", new java.math.BigDecimal("199.00"), "currency", "INR", "provider", "RAZORPAY",
                "providerTransactionId", "pay_123", "status", "CAPTURED", "invoiceId", "inv_1")));
        when(subscriptionOrderRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.entity.SubscriptionOrder.class, "id", UUID.randomUUID(), "userId", userId, "planId", planId,
                "billingCycle", "MONTHLY", "status", "PENDING", "razorpaySubscriptionId", "sub_secret",
                "amount", new java.math.BigDecimal("199.00"))));

        UUID invitedUserId = UUID.randomUUID();
        UUID inviterUserId = UUID.randomUUID();
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.entity.Referral.class, "id", UUID.randomUUID(), "referrerUserId", userId,
                "referredUserId", invitedUserId, "status", "REWARDED", "reward", new java.math.BigDecimal("75.00"))));
        when(referralRepository.findByReferredUserId(userId)).thenReturn(Optional.of(entity(
                com.finora.entity.Referral.class, "id", UUID.randomUUID(), "referrerUserId", inviterUserId,
                "referredUserId", userId, "status", "REWARDED", "reward", new java.math.BigDecimal("60.00"))));
        when(referralCodeRepository.findByUserId(userId)).thenReturn(Optional.of(entity(
                com.finora.entity.ReferralCode.class, "id", UUID.randomUUID(), "userId", userId, "code", "JANE42",
                "plusMilestoneCounter", 2)));
        when(referralGrantRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.entity.ReferralGrant.class, "id", UUID.randomUUID(), "userId", userId, "tier", "PLUS",
                "status", "ACTIVE")));
        when(walletLedgerRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.entity.WalletLedgerEntry.class, "id", UUID.randomUUID(), "userId", userId,
                "amount", new java.math.BigDecimal("50.00"), "reason", "REFERRAL_REWARD")));

        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.notification.domain.Notification.class, "id", UUID.randomUUID(), "userId", userId,
                "notificationKey", "key-secret", "type", com.finora.notification.domain.NotificationType.values()[0],
                "category", com.finora.notification.domain.NotificationCategory.values()[0],
                "channel", com.finora.notification.domain.NotificationChannel.PUSH,
                "title", "Budget alert", "message", "You've used 80% of Dining", "lastError", "provider said no")));
        when(notificationPreferenceRepository.findByUserId(userId)).thenReturn(List.of(entity(
                com.finora.notification.domain.NotificationPreference.class, "id", UUID.randomUUID(), "userId", userId,
                "category", com.finora.notification.domain.NotificationCategory.values()[0],
                "channel", com.finora.notification.domain.NotificationChannel.EMAIL, "enabled", false)));
        when(timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(entity(
                com.finora.timeline.TimelineEvent.class, "id", UUID.randomUUID(), "userId", userId,
                "eventType", "GOAL_COMPLETED", "title", "Completed Emergency Fund")));

        UUID fromTxn = UUID.randomUUID();
        UUID toTxn = UUID.randomUUID();
        when(transactionRelationshipRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(entity(
                com.finora.entity.TransactionRelationship.class, "id", UUID.randomUUID(), "userId", userId,
                "fromTransactionId", fromTxn, "toTransactionId", toTxn,
                "relationshipType", com.finora.entity.TransactionRelationship.RelationshipType.values()[0],
                "status", com.finora.entity.TransactionRelationship.Status.USER_CONFIRMED,
                "matchedAmount", new java.math.BigDecimal("1000.00"))));
        UUID statementId = UUID.randomUUID();
        when(statementImportExcludedRowRepository.findByUserIdOrderByStatementImportIdAscRowPositionAsc(userId))
                .thenReturn(List.of(entity(com.finora.entity.StatementImportExcludedRow.class, "id", UUID.randomUUID(),
                        "statementImportId", statementId, "userId", userId, "rowPosition", 7,
                        "description", "OPENING BALANCE", "amount", new java.math.BigDecimal("12.50"))));
        when(counterpartyCategoryObservationRepository.findByUserIdOrderByCreatedAtAsc(userId)).thenReturn(List.of(entity(
                com.finora.entity.CounterpartyCategoryObservation.class, "id", UUID.randomUUID(), "userId", userId,
                "counterpartyKey", "vpa:shop@okbank", "direction", Transaction.Type.EXPENSE, "category", "Groceries")));

        com.finora.entity.StatementRefreshRun run = new com.finora.entity.StatementRefreshRun(
                statementId, userId, "parser-2026.10", com.finora.entity.StatementRefreshRun.Status.APPLIED);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.setCounts(0, 0, 1, 0);
        run.setDetail(Map.of("removed", List.of(Map.of("transactionId", fromTxn.toString(), "date", "2026-09-01",
                "description", "UPI/RAHUL/REMOVED", "amount", "250.00", "type", "EXPENSE", "userEdited", false))));
        when(statementRefreshRunRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));

        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);
        ObjectMapper mapper = new ObjectMapper();

        JsonNode refreshRuns = mapper.readTree(entries.get("statement_refresh_runs.json"));
        assertThat(refreshRuns).hasSize(1);
        assertThat(refreshRuns.get(0).get("status").asText()).isEqualTo("APPLIED");
        assertThat(refreshRuns.get(0).get("statementImportId").asText()).isEqualTo(statementId.toString());
        assertThat(refreshRuns.get(0).get("removed").get(0).get("description").asText()).isEqualTo("UPI/RAHUL/REMOVED");
        assertThat(refreshRuns.get(0).get("removed").get(0).get("amount").asText()).isEqualTo("250.00");

        JsonNode payments = mapper.readTree(entries.get("payments.json"));
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("id").asText()).isEqualTo(paymentId.toString());
        assertThat(payments.get(0).get("planCode").asText()).isEqualTo("PLUS");
        assertThat(payments.get(0).get("providerTransactionId").asText()).isEqualTo("pay_123");
        assertThat(payments.get(0).has("userId")).isFalse();

        JsonNode orders = mapper.readTree(entries.get("subscription_orders.json"));
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("planName").asText()).isEqualTo("Plus");
        assertThat(new String(entries.get("subscription_orders.json"))).doesNotContain("sub_secret");

        JsonNode referrals = mapper.readTree(entries.get("referrals.json"));
        assertThat(referrals).hasSize(2);
        assertThat(referrals.findValuesAsText("role")).containsExactlyInAnyOrder("REFERRER", "REFERRED");
        // The reward is the inviter's credit: kept on the row where this user invited someone,
        // dropped on the row where someone else invited them.
        for (JsonNode referral : referrals) {
            if (referral.get("role").asText().equals("REFERRER")) {
                assertThat(referral.get("reward").decimalValue()).isEqualByComparingTo("75.00");
            } else {
                assertThat(referral.get("reward").isNull()).isTrue();
            }
        }
        String referralsJson = new String(entries.get("referrals.json"));
        assertThat(referralsJson).doesNotContain(invitedUserId.toString()).doesNotContain(inviterUserId.toString())
                .doesNotContain(userId.toString());

        assertThat(mapper.readTree(entries.get("referral_code.json")).get(0).get("code").asText()).isEqualTo("JANE42");
        assertThat(mapper.readTree(entries.get("referral_rewards.json")).get(0).get("tier").asText()).isEqualTo("PLUS");
        assertThat(mapper.readTree(entries.get("wallet.json")).get(0).get("amount").decimalValue())
                .isEqualByComparingTo("50.00");

        JsonNode notifications = mapper.readTree(entries.get("notifications.json"));
        assertThat(notifications.get(0).get("title").asText()).isEqualTo("Budget alert");
        assertThat(notifications.get(0).get("channel").asText()).isEqualTo("PUSH");
        assertThat(new String(entries.get("notifications.json"))).doesNotContain("key-secret").doesNotContain("provider said no");
        JsonNode preferences = mapper.readTree(entries.get("notification_preferences.json"));
        assertThat(preferences.get(0).get("channel").asText()).isEqualTo("EMAIL");
        assertThat(preferences.get(0).get("enabled").asBoolean()).isFalse();

        assertThat(mapper.readTree(entries.get("timeline.json")).get(0).get("title").asText())
                .isEqualTo("Completed Emergency Fund");
        JsonNode links = mapper.readTree(entries.get("transaction_links.json"));
        assertThat(links.get(0).get("fromTransactionId").asText()).isEqualTo(fromTxn.toString());
        assertThat(links.get(0).get("toTransactionId").asText()).isEqualTo(toTxn.toString());
        assertThat(links.get(0).get("status").asText()).isEqualTo("USER_CONFIRMED");
        JsonNode excludedRows = mapper.readTree(entries.get("statement_excluded_rows.json"));
        assertThat(excludedRows.get(0).get("statementImportId").asText()).isEqualTo(statementId.toString());
        assertThat(excludedRows.get(0).get("rowPosition").asInt()).isEqualTo(7);
        assertThat(excludedRows.get(0).get("description").asText()).isEqualTo("OPENING BALANCE");
        JsonNode votes = mapper.readTree(entries.get("merchant_category_votes.json"));
        assertThat(votes.get(0).get("counterpartyKey").asText()).isEqualTo("vpa:shop@okbank");
        assertThat(votes.get(0).get("direction").asText()).isEqualTo("EXPENSE");

        Map<String, Integer> rowCounts = new java.util.HashMap<>();
        mapper.readTree(entries.get("manifest.json")).get("included")
                .forEach(n -> rowCounts.put(n.get("name").asText(), n.get("rowCount").isNull() ? null : n.get("rowCount").asInt()));
        assertThat(rowCounts).containsEntry("payments.json", 1).containsEntry("subscription_orders.json", 1)
                .containsEntry("referrals.json", 2).containsEntry("referral_code.json", 1)
                .containsEntry("referral_rewards.json", 1).containsEntry("wallet.json", 1)
                .containsEntry("notifications.json", 1).containsEntry("notification_preferences.json", 1)
                .containsEntry("timeline.json", 1).containsEntry("transaction_links.json", 1)
                .containsEntry("statement_excluded_rows.json", 1).containsEntry("merchant_category_votes.json", 1)
                .containsEntry("statement_refresh_runs.json", 1);
    }

    /** A user with none of the new data still gets every file, each an empty JSON array -- the
     *  same shape every other empty table already exports as, not a missing entry. */
    @Test
    void writeZip_newEntriesAreEmptyArraysForAUserWithNoSuchData() throws IOException {
        DataExportService.ExportBundle bundle = service.buildBundle(userId, "correct-password", null, null);
        Map<String, byte[]> entries = writeZipAndReadEntries(bundle);
        ObjectMapper mapper = new ObjectMapper();
        for (String name : List.of("payments.json", "subscription_orders.json", "referrals.json", "referral_code.json",
                "referral_rewards.json", "wallet.json", "notifications.json", "notification_preferences.json",
                "timeline.json", "transaction_links.json", "statement_excluded_rows.json",
                "merchant_category_votes.json", "statement_refresh_runs.json")) {
            assertThat(entries).as(name).containsKey(name);
            JsonNode node = mapper.readTree(entries.get(name));
            assertThat(node.isArray()).as(name).isTrue();
            assertThat(node).as(name).isEmpty();
        }
    }

    private Map<String, byte[]> writeZipAndReadEntries(DataExportService.ExportBundle bundle) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeZip(userId, bundle, out);

        Map<String, byte[]> entries = new java.util.HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }
}
