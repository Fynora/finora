package com.finora.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class SharedCorpusRevalidationSweepServiceTest {

    private SharedCorpusService sharedCorpusService;
    private SharedCorpusRevalidationSweepService sweepService;

    @BeforeEach
    void setUp() {
        sharedCorpusService = mock(SharedCorpusService.class);
        sweepService = new SharedCorpusRevalidationSweepService(sharedCorpusService);
    }

    @Test
    void sweep_noneDue_callsOnceAndStops() {
        when(sharedCorpusService.reevaluateTimedOutRevalidations(500)).thenReturn(0);

        sweepService.sweep();

        verify(sharedCorpusService, times(1)).reevaluateTimedOutRevalidations(500);
    }

    @Test
    void sweep_partialBatch_stopsAfterOneCall() {
        when(sharedCorpusService.reevaluateTimedOutRevalidations(500)).thenReturn(37);

        sweepService.sweep();

        verify(sharedCorpusService, times(1)).reevaluateTimedOutRevalidations(500);
    }

    @Test
    void sweep_fullBatchThenPartial_loopsUntilPartialPage() {
        when(sharedCorpusService.reevaluateTimedOutRevalidations(500)).thenReturn(500, 500, 12);

        sweepService.sweep();

        verify(sharedCorpusService, times(3)).reevaluateTimedOutRevalidations(500);
    }
}
