package com.finora.service;

import com.finora.dto.DashboardSummaryDto;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HealthScoreSnapshotSweepServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private DashboardService dashboardService;
    private HealthScoreSnapshotSweepService sweepService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        dashboardService = mock(DashboardService.class);
        sweepService = new HealthScoreSnapshotSweepService(accountRepository, userRepository, dashboardService);
    }

    private User activeUser(UUID id) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // A real record instance, not a mock -- Mockito's inline mock maker cannot reliably mock a
    // record's own accessors (UnfinishedStubbingException on the very first `when(...)` call, a
    // known trap in this codebase). Every field but healthScoreAvailable is a throwaway default;
    // only that one is what this test suite actually cares about.
    private DashboardSummaryDto summaryWithAvailability(boolean available) {
        return new DashboardSummaryDto(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null,
                null, null, Map.of(),
                Map.of(),
                available, 0, 10,
                null, List.of(),
                null, null,
                Map.of(), List.of(),
                null, true,
                false, 0, 3, 0, 0,
                false, 0, BigDecimal.ZERO, 0, 20,
                null, 3,
                List.of(),
                0, List.of(),
                null, 0, 5
        );
    }

    private DashboardSummaryDto scoreAvailable() {
        return summaryWithAvailability(true);
    }

    private DashboardSummaryDto scoreUnavailable() {
        return summaryWithAvailability(false);
    }

    @Test
    void callsSummarizeForEveryActiveUser() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(u1, u2));
        when(userRepository.findByIdInAndStatus(List.of(u1, u2), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(u1), activeUser(u2)));
        when(dashboardService.summarize(u1)).thenReturn(scoreAvailable());
        when(dashboardService.summarize(u2)).thenReturn(scoreAvailable());

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(u1);
        verify(dashboardService).summarize(u2);
        assertThat(result.snapshotsSaved()).isEqualTo(2);
        assertThat(result.scoreUnavailable()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void countsAttemptsWithNoAvailableScoreSeparatelyFromSaved() {
        UUID userId = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(userId));
        when(userRepository.findByIdInAndStatus(List.of(userId), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(userId)));
        when(dashboardService.summarize(userId)).thenReturn(scoreUnavailable());

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        // summarize() ran (this user is not "skipped" or "failed"), but nothing was actually
        // persisted -- too few transactions to score yet. Distinct from snapshotsSaved so the
        // metric never overstates real coverage.
        assertThat(result.snapshotsSaved()).isEqualTo(0);
        assertThat(result.scoreUnavailable()).isEqualTo(1);
    }

    @Test
    void countsInactiveUsersAsSkippedWithoutCallingSummarize() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(active, inactive));
        when(userRepository.findByIdInAndStatus(List.of(active, inactive), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(active))); // inactive filtered out by the query itself
        when(dashboardService.summarize(active)).thenReturn(scoreAvailable());

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(active);
        verify(dashboardService, never()).summarize(inactive);
        assertThat(result.snapshotsSaved()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void oneUsersFailureDoesNotStopTheBatch() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(failing, ok));
        when(userRepository.findByIdInAndStatus(List.of(failing, ok), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(failing), activeUser(ok)));
        doThrow(new RuntimeException("boom")).when(dashboardService).summarize(failing);
        when(dashboardService.summarize(ok)).thenReturn(scoreAvailable());

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(ok); // still ran despite the other user's failure
        assertThat(result.snapshotsSaved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void scheduledSweepDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(sweepService, "sweepEnabled", false);
        sweepService.scheduledSweep();
        verifyNoInteractions(accountRepository, userRepository, dashboardService);
    }
}
