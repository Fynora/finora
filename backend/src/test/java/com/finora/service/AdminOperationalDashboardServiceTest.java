package com.finora.service;

import com.finora.dto.AdminDtos.ActivationFunnelDto;
import com.finora.dto.AdminDtos.ActivityTrendPointDto;
import com.finora.dto.AdminDtos.OperationalDashboardDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.dto.HealthDtos.ProviderStatusDto;
import com.finora.entity.AuditLog;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.goals.GoalRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mocked-repository unit test, same pattern as WorkspaceDashboardServiceTest. Proves the
 *  alerts panel is derived purely from the health registry (not a second, independent alerting
 *  path) and that recent activity gets mapped into DTOs correctly. */
class AdminOperationalDashboardServiceTest {

    private UserRepository userRepository;
    private TransactionRepository transactionRepository;
    private StatementImportRepository statementImportRepository;
    private BudgetRepository budgetRepository;
    private GoalRepository goalRepository;
    private AuditLogRepository auditLogRepository;
    private AdminHealthRegistryService healthRegistryService;
    private HeldStatementRepository heldStatementRepository;
    private ImportJobRepository importJobRepository;
    private AdminOperationalDashboardService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        budgetRepository = mock(BudgetRepository.class);
        goalRepository = mock(GoalRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        healthRegistryService = mock(AdminHealthRegistryService.class);
        heldStatementRepository = mock(HeldStatementRepository.class);
        importJobRepository = mock(ImportJobRepository.class);
        service = new AdminOperationalDashboardService(userRepository, transactionRepository,
                statementImportRepository, budgetRepository, goalRepository, auditLogRepository, healthRegistryService,
                heldStatementRepository, importJobRepository);

        when(userRepository.countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER)).thenReturn(0L);
        when(auditLogRepository.countDistinctUsersByActionSince(any(), any())).thenReturn(0L);
        when(auditLogRepository.countDistinctUsersByActionBetween(any(), any(), any())).thenReturn(0L);
        when(transactionRepository.countByCreatedAtAfter(any())).thenReturn(0L);
        when(transactionRepository.countByCreatedAtBetween(any(), any())).thenReturn(0L);
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(0L);
        when(statementImportRepository.countByImportedAtBetween(any(), any())).thenReturn(0L);
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(0L);
        when(statementImportRepository.countWithSkippedRowsBetween(any(), any())).thenReturn(0L);
        when(statementImportRepository.countDistinctUsersEverActivated()).thenReturn(0L);
        when(budgetRepository.countDistinctUsersEverActivated()).thenReturn(0L);
        when(goalRepository.countDistinctUsersEverActivated()).thenReturn(0L);
        when(userRepository.countByLockedUntilAfter(any())).thenReturn(0L);
        when(userRepository.countByEmailNotAndCreatedAtBetween(any(), any(), any())).thenReturn(0L);
        when(userRepository.countWithNoAuditActionSince(any(), any(), any())).thenReturn(0L);
        when(transactionRepository.countByNeedsCategoryReviewTrue()).thenReturn(0L);
        when(transactionRepository.countByIsDuplicateOfIsNotNull()).thenReturn(0L);
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(Page.empty());
        when(importJobRepository.summarizeOverdueHolds(any(), any(), any())).thenReturn(new Summary(0, null));
    }

    /** A plain implementation, not a mock: a projection mock stubbed inside another stubbing is
     *  Mockito's UnfinishedStubbing trap. */
    private record Summary(long count, Instant oldest) implements ImportJobRepository.OverdueHoldSummary {
        @Override public long getCount() { return count; }
        @Override public Instant getOldest() { return oldest; }
    }

    /** Gate 1 spec §4: overdue holds, per kind, with their oldest -- so each row links to its queue. */
    @Test
    void overview_reportsOverdueHoldsPerKindWithTheOldest() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        Instant oldestTrust = Instant.parse("2026-10-05T08:00:00Z");
        when(importJobRepository.summarizeOverdueHolds(eq(ImportJob.Status.HELD_FOR_TRUST_REVIEW), any(), any()))
                .thenReturn(new Summary(2, oldestTrust));
        when(importJobRepository.summarizeOverdueHolds(eq(ImportJob.Status.HELD_FOR_REVIEW), any(), any()))
                .thenReturn(new Summary(0, null));
        Instant before = Instant.now();

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.needsAttention().trustHoldsOverdue().count()).isEqualTo(2L);
        assertThat(dto.needsAttention().trustHoldsOverdue().oldestHeldSince()).isEqualTo(oldestTrust);
        assertThat(dto.needsAttention().importHoldsOverdue().count()).isZero();
        assertThat(dto.needsAttention().importHoldsOverdue().oldestHeldSince()).isNull();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(importJobRepository).summarizeOverdueHolds(eq(ImportJob.Status.HELD_FOR_TRUST_REVIEW),
                cutoff.capture(), eq(HeldStatement.Status.RESOLVED));
        // 48 hours before "now", taken during the call.
        assertThat(cutoff.getValue()).isBetween(before.minus(ImportJob.HOLD_PROMISE),
                Instant.now().minus(ImportJob.HOLD_PROMISE));
    }

    @Test
    void overview_populatesNeedsAttentionFromRealPlatformWideCounts() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(3L);
        when(userRepository.countByLockedUntilAfter(any())).thenReturn(2L);
        when(transactionRepository.countByNeedsCategoryReviewTrue()).thenReturn(14L);
        when(transactionRepository.countByIsDuplicateOfIsNotNull()).thenReturn(5L);

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.needsAttention().importsWithSkippedRowsToday()).isEqualTo(3L);
        assertThat(dto.needsAttention().lockedAccounts()).isEqualTo(2L);
        assertThat(dto.needsAttention().transactionsNeedingCategoryReview()).isEqualTo(14L);
        assertThat(dto.needsAttention().transactionsFlaggedAsDuplicates()).isEqualTo(5L);
    }

    /**
     * Every user waiting on a reviewer is counted: undecided review records (whatever stage the
     * reviewer has them at) plus trust holds the worker opened with no record, and separately the
     * parser-gap holds. In October 2026 holds sat 3-6 days with nothing in the portal pointing at
     * them; this is that pointer.
     */
    @Test
    @SuppressWarnings("unchecked")
    void overview_countsEveryUndecidedHoldInNeedsAttention() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        when(heldStatementRepository.countByStatusIn(any())).thenReturn(3L);
        when(importJobRepository.countByStatusAndHeldStatementIdIsNull(ImportJob.Status.HELD_FOR_TRUST_REVIEW))
                .thenReturn(1L);
        when(importJobRepository.countByStatus(ImportJob.Status.HELD_FOR_REVIEW)).thenReturn(2L);

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.needsAttention().statementsHeldForTrustReview()).isEqualTo(4L);
        assertThat(dto.needsAttention().importsHeldForReview()).isEqualTo(2L);

        ArgumentCaptor<Collection<HeldStatement.Status>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(heldStatementRepository).countByStatusIn(statuses.capture());
        // Assigned and investigating holds are still a user waiting; only a decision ends the wait.
        assertThat(statuses.getValue()).containsExactlyInAnyOrderElementsOf(
                EnumSet.of(HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
                        HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT));
    }

    @Test
    void overview_populatesPreviousDayFromTheBetweenVariantQueries_notTheSinceVariantQueries() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        when(auditLogRepository.countDistinctUsersByActionBetween(any(), any(), any())).thenReturn(7L);
        when(transactionRepository.countByCreatedAtBetween(any(), any())).thenReturn(41L);
        when(statementImportRepository.countByImportedAtBetween(any(), any())).thenReturn(9L);
        when(statementImportRepository.countWithSkippedRowsBetween(any(), any())).thenReturn(2L);
        // Today's own ("since") counts differ from yesterday's, proving previousDay isn't
        // accidentally re-reporting today's figures under a new name.
        when(auditLogRepository.countDistinctUsersByActionSince(any(), any())).thenReturn(99L);
        when(transactionRepository.countByCreatedAtAfter(any())).thenReturn(99L);
        when(statementImportRepository.countByImportedAtAfter(any())).thenReturn(99L);
        when(statementImportRepository.countWithSkippedRowsAfter(any())).thenReturn(99L);

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.previousDay().activeUsers()).isEqualTo(7L);
        assertThat(dto.previousDay().transactions()).isEqualTo(41L);
        assertThat(dto.previousDay().imports()).isEqualTo(9L);
        assertThat(dto.previousDay().importsWithSkippedRows()).isEqualTo(2L);
        assertThat(dto.activeUsersToday()).isEqualTo(99L);
    }

    @Test
    void overview_populatesInactiveUsersLast7Days_fromTheInverseOfActiveUsersQuery() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        when(userRepository.countWithNoAuditActionSince(eq("USER_LOGIN"), any(), eq("BOOTSTRAP_ADMIN")))
                .thenReturn(17L);

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.inactiveUsersLast7Days()).isEqualTo(17L);
    }

    @Test
    void overview_producesNoAlerts_whenEveryHealthProviderIsUp() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of(
                new ProviderStatusDto("Database", "Platform", "UP", "all good")
        )));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.alerts()).isEmpty();
    }

    @Test
    void overview_turnsEveryNonUpProviderIntoExactlyOneAlert() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("DOWN", List.of(
                new ProviderStatusDto("Database", "Platform", "UP", "all good"),
                new ProviderStatusDto("Financial Intelligence Engine", "Financial Intelligence", "DOWN", "2 dangling pointers"),
                new ProviderStatusDto("Statement Import Pipeline", "Financial Intelligence", "DEGRADED", "high skip rate")
        )));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.alerts()).hasSize(2);
        assertThat(dto.alerts()).anySatisfy(a -> {
            assertThat(a.title()).isEqualTo("Financial Intelligence Engine");
            assertThat(a.severity()).isEqualTo("critical");
        });
        assertThat(dto.alerts()).anySatisfy(a -> {
            assertThat(a.title()).isEqualTo("Statement Import Pipeline");
            assertThat(a.severity()).isEqualTo("warning");
        });
    }

    @Test
    void overview_mapsRecentAuditLogEntriesIntoDtos() {
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));
        AuditLog log = new AuditLog();
        ReflectionTestUtils.setField(log, "id", UUID.randomUUID());
        log.setUserId(UUID.randomUUID());
        log.setAction("USER_LOGIN");
        log.setEntityType("User");
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(log)));

        OperationalDashboardDto dto = service.overview();

        assertThat(dto.recentActivity()).hasSize(1);
        assertThat(dto.recentActivity().get(0).action()).isEqualTo("USER_LOGIN");
    }

    // D-27 PR3-D.
    @Test
    void activationFunnel_reportsEachStageFromItsOwnDistinctUserCount() {
        when(userRepository.countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER)).thenReturn(100L);
        when(statementImportRepository.countDistinctUsersEverActivated()).thenReturn(72L);
        when(budgetRepository.countDistinctUsersEverActivated()).thenReturn(33L);
        when(goalRepository.countDistinctUsersEverActivated()).thenReturn(21L);

        ActivationFunnelDto dto = service.activationFunnel();

        assertThat(dto.signedUp()).isEqualTo(100L);
        assertThat(dto.firstImport()).isEqualTo(72L);
        assertThat(dto.firstBudget()).isEqualTo(33L);
        assertThat(dto.firstGoal()).isEqualTo(21L);
    }

    @Test
    void activationFunnel_signedUpCount_agreesWithOverviewsOwnTotalUsers() {
        // Both must read the same underlying figure -- see the service method's own doc comment
        // on why this reuses countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER) rather than a
        // second definition of "total users."
        when(userRepository.countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER)).thenReturn(42L);
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));

        assertThat(service.activationFunnel().signedUp()).isEqualTo(42L);
        assertThat(service.overview().totalUsers()).isEqualTo(42L);
    }

    /** Bug fix regression test. Both totalUsers and signedUp used to be
     *  {@code countByRoleNot("BOOTSTRAP_ADMIN")}, which stopped excluding the bootstrap account
     *  the instant setup completed -- {@code SetupService.completeSetup()} resets its legacy
     *  {@code User.role} column to {@code DEFAULT_ROLE} via {@code RoleService.revokeRole}. This
     *  pins that both figures are now read via the account's EMAIL
     *  ({@code BootstrapService.BOOTSTRAP_IDENTIFIER}), which never changes -- see
     *  {@code UserRepositoryIT} for proof against a real Postgres that the email-based query
     *  actually survives that role reset. */
    @Test
    void overview_and_activationFunnel_excludeTheBootstrapAdminByEmail_notByItsResettableRoleColumn() {
        when(userRepository.countByEmailNot(BootstrapService.BOOTSTRAP_IDENTIFIER)).thenReturn(7L);
        when(healthRegistryService.platformHealth()).thenReturn(new PlatformHealthDto("UP", List.of()));

        assertThat(service.overview().totalUsers()).isEqualTo(7L);
        assertThat(service.activationFunnel().signedUp()).isEqualTo(7L);
    }

    @Test
    void activityTrend_returnsSevenPointsOldestFirst_endingToday() {
        List<ActivityTrendPointDto> points = service.activityTrend();

        assertThat(points).hasSize(7);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        assertThat(points.get(6).date()).isEqualTo(today);
        for (int i = 0; i < 6; i++) {
            assertThat(points.get(i).date()).isEqualTo(points.get(i + 1).date().minusDays(1));
        }
    }

    @Test
    void activityTrend_populatesEachPointFromTheThreeBetweenQueries() {
        when(userRepository.countByEmailNotAndCreatedAtBetween(any(), any(), any())).thenReturn(2L);
        when(statementImportRepository.countByImportedAtBetween(any(), any())).thenReturn(1L);
        when(transactionRepository.countByCreatedAtBetween(any(), any())).thenReturn(9L);

        List<ActivityTrendPointDto> points = service.activityTrend();

        assertThat(points).allSatisfy(p -> {
            assertThat(p.signups()).isEqualTo(2L);
            assertThat(p.imports()).isEqualTo(1L);
            assertThat(p.transactions()).isEqualTo(9L);
        });
    }

    @Test
    void activityTrend_excludesBootstrapAdminByEmail_notByItsResettableRoleColumn() {
        // Deliberately email, not role: SetupService.completeSetup()'s revokeRole() resets the
        // bootstrap account's legacy role column to USER once setup finishes, so a role-based
        // filter (the countByRoleNot("BOOTSTRAP_ADMIN") totalUsers/activationFunnel both use)
        // silently stops excluding it forever from that point on -- confirmed via manual browser
        // verification against a freshly-bootstrapped local stack, not by inspection alone.
        service.activityTrend();

        verify(userRepository, times(7)).countByEmailNotAndCreatedAtBetween(eq("BOOTSTRAP_ADMIN"), any(), any());
    }

    @Test
    void activityTrend_queriesEachDayAsItsOwnContiguousNonOverlappingWindow() {
        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);

        service.activityTrend();

        verify(transactionRepository, times(7)).countByCreatedAtBetween(startCaptor.capture(), endCaptor.capture());
        List<Instant> starts = startCaptor.getAllValues();
        List<Instant> ends = endCaptor.getAllValues();

        for (int i = 0; i < 7; i++) {
            assertThat(ends.get(i)).isAfter(starts.get(i));
        }
        // Day i's end is exactly day i+1's start -- back-to-back calendar days, no gap and no
        // double-counted instant between them.
        for (int i = 0; i < 6; i++) {
            assertThat(ends.get(i)).isEqualTo(starts.get(i + 1));
        }
    }
}
