package com.finora.service;

import com.finora.dto.SharedCorpusMetricsDto;
import com.finora.entity.SharedMerchantCategory;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SharedCorpusMetricsServiceTest {

    private SharedMerchantCategoryRepository corpus;
    private CounterpartyCategoryObservationRepository observations;
    private SharedCorpusMetricsService service;

    @BeforeEach
    void setUp() {
        corpus = mock(SharedMerchantCategoryRepository.class);
        observations = mock(CounterpartyCategoryObservationRepository.class);
        service = new SharedCorpusMetricsService(corpus, observations);
    }

    @Test
    void summary_computesPromotionRateAndTierCounts() {
        when(corpus.countByStatus(SharedMerchantCategory.Status.TRUSTED)).thenReturn(40L);
        when(corpus.countByStatus(SharedMerchantCategory.Status.DISPUTED)).thenReturn(10L);
        when(corpus.countByStatus(SharedMerchantCategory.Status.REVALIDATING)).thenReturn(2L);
        when(observations.countDistinctKeysEverObserved()).thenReturn(200L);

        SharedCorpusMetricsDto summary = service.summary();

        assertThat(summary.trustedRows()).isEqualTo(40);
        assertThat(summary.disputedRows()).isEqualTo(10);
        assertThat(summary.revalidatingRows()).isEqualTo(2);
        // (40 + 10 + 2) promoted out of 200 ever-observed keys.
        assertThat(summary.promotionRate()).isEqualByComparingTo("0.260000");
    }

    @Test
    void summary_zeroObservedKeys_promotionRateIsZeroNotDivideByZero() {
        when(observations.countDistinctKeysEverObserved()).thenReturn(0L);

        SharedCorpusMetricsDto summary = service.summary();

        assertThat(summary.promotionRate()).isEqualByComparingTo("0");
    }
}
