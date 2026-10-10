package com.finora.imports.jobs;

import com.finora.repository.ImportJobRepository;
import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.HeldStatementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled tick never does the work itself: each email may take the provider's full timeout,
 * and the scheduler thread is shared with the import-queue poll and the notification dispatcher.
 * The escalation rules themselves are {@code HoldOverdueEscalationServiceIT}'s.
 */
class HoldOverdueEscalationServiceTest {

    private final ImportJobRepository jobs = mock(ImportJobRepository.class);
    private final List<Runnable> handedOff = new ArrayList<>();
    private final Executor recording = handedOff::add;

    private HoldOverdueEscalationService service(boolean enabled) {
        HoldOverdueEscalationService service = new HoldOverdueEscalationService(jobs,
                mock(HeldItemAdminAlertService.class), mock(HeldStatementService.class),
                mock(PlatformTransactionManager.class), recording);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    @Test
    void theTickHandsTheRunToItsOwnThreadAndDoesNoWorkItself() {
        when(jobs.findOverdueUnescalatedHolds(any(), any(), any())).thenReturn(List.of());

        service(true).scheduled();

        assertThat(handedOff).hasSize(1);
        verifyNoInteractions(jobs);
        handedOff.get(0).run();
        verify(jobs).findOverdueUnescalatedHolds(any(), any(), any());
    }

    @Test
    void aDisabledServiceHandsOffNothing() {
        service(false).scheduled();

        assertThat(handedOff).isEmpty();
        verifyNoInteractions(jobs);
    }
}
