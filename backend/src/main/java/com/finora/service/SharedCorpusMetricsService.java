package com.finora.service;

import com.finora.dto.SharedCorpusMetricsDto;
import com.finora.entity.SharedMerchantCategory;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Spec §10 -- the metrics that answer "does the corpus exist" vs. "is the corpus helping". */
@Service
public class SharedCorpusMetricsService {

    private final SharedMerchantCategoryRepository corpus;
    private final CounterpartyCategoryObservationRepository observations;

    public SharedCorpusMetricsService(SharedMerchantCategoryRepository corpus,
                                       CounterpartyCategoryObservationRepository observations) {
        this.corpus = corpus;
        this.observations = observations;
    }

    public SharedCorpusMetricsDto summary() {
        long trusted = corpus.countByStatus(SharedMerchantCategory.Status.TRUSTED);
        long disputed = corpus.countByStatus(SharedMerchantCategory.Status.DISPUTED);
        long revalidating = corpus.countByStatus(SharedMerchantCategory.Status.REVALIDATING);
        long everObserved = observations.countDistinctKeysEverObserved();

        BigDecimal promotionRate = everObserved == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trusted + disputed + revalidating)
                        .divide(BigDecimal.valueOf(everObserved), 6, RoundingMode.HALF_UP);

        return new SharedCorpusMetricsDto(trusted, disputed, revalidating, promotionRate);
    }
}
