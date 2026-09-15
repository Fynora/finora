package com.finora.service;

import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SharedCorpusRetentionSweepServiceTest {

    private CounterpartyCategoryObservationRepository observations;
    private SharedCorpusRetentionSweepService sweep;

    @BeforeEach
    void setUp() {
        observations = mock(CounterpartyCategoryObservationRepository.class);
        sweep = new SharedCorpusRetentionSweepService(observations);
    }

    @Test
    void sweep_deletesEachExpiredUnpromotedKey() {
        when(observations.findUnpromotedKeysPastRetention(any(), any(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{"vpa:oneoffperson", "EXPENSE"}))
                .thenReturn(List.<Object[]>of());

        sweep.sweep();

        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:oneoffperson", Transaction.Type.EXPENSE);
    }

    @Test
    void sweep_continuesWhenAFullBatchComesBack_stopsOnceAPartialBatchArrives() {
        // A FULL page (500, matching the sweep's own BATCH_SIZE) is the only signal that more
        // might remain -- a page smaller than that means the query has nothing left, since
        // findUnpromotedKeysPastRetention re-queries the same "still not deleted" set on every
        // call. A prior version of this test stubbed two separate small batches, which the real
        // bounded loop would never produce two calls for -- fixed to a realistic full-page shape.
        List<Object[]> fullPage = IntStream.range(0, 500)
                .mapToObj(i -> new Object[]{"vpa:key" + i, "EXPENSE"})
                .toList();
        when(observations.findUnpromotedKeysPastRetention(any(), any(), anyInt()))
                .thenReturn(fullPage)
                .thenReturn(List.<Object[]>of(new Object[]{"vpa:last", "EXPENSE"}));

        sweep.sweep();

        verify(observations, times(2)).findUnpromotedKeysPastRetention(any(), any(), anyInt());
        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:key0", Transaction.Type.EXPENSE);
        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:key499", Transaction.Type.EXPENSE);
        verify(observations).deleteByCounterpartyKeyAndDirection("vpa:last", Transaction.Type.EXPENSE);
    }

    @Test
    void sweep_nothingExpired_deletesNothing() {
        when(observations.findUnpromotedKeysPastRetention(any(), any(), anyInt())).thenReturn(List.<Object[]>of());

        sweep.sweep();

        verify(observations, never()).deleteByCounterpartyKeyAndDirection(any(), any());
    }
}
