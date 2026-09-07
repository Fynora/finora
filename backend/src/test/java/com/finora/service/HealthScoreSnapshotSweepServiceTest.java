package com.finora.service;

import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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

    @Test
    void callsSummarizeForEveryActiveUser() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(u1, u2));
        when(userRepository.findByIdInAndStatus(List.of(u1, u2), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(u1), activeUser(u2)));

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(u1);
        verify(dashboardService).summarize(u2);
        assertThat(result.saved()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void countsInactiveUsersAsSkippedWithoutCallingSummarize() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(accountRepository.findDistinctUserIds()).thenReturn(List.of(active, inactive));
        when(userRepository.findByIdInAndStatus(List.of(active, inactive), User.STATUS_ACTIVE))
                .thenReturn(List.of(activeUser(active))); // inactive filtered out by the query itself

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(active);
        verify(dashboardService, never()).summarize(inactive);
        assertThat(result.saved()).isEqualTo(1);
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

        HealthScoreSnapshotSweepService.Result result = sweepService.sweep();

        verify(dashboardService).summarize(ok); // still ran despite the other user's failure
        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
    }

    @Test
    void scheduledSweepDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(sweepService, "sweepEnabled", false);
        sweepService.scheduledSweep();
        verifyNoInteractions(accountRepository, userRepository, dashboardService);
    }
}
