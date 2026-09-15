package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.*;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SharedCorpusRepositoriesIT extends AbstractIntegrationTest {

    @Autowired CounterpartyCategoryObservationRepository observations;
    @Autowired SharedMerchantCategoryRepository corpus;
    @Autowired SharedMerchantCategoryAiSuggestionRepository aiSuggestions;

    @Test
    void savesAndFindsAnObservationByKeyAndDirection() {
        CounterpartyCategoryObservation obs = new CounterpartyCategoryObservation();
        obs.setCounterpartyKey("vpa:zeptoonline");
        obs.setDirection(Transaction.Type.EXPENSE);
        obs.setCategory("Shopping");
        obs.setUserId(UUID.randomUUID());
        obs.setCounterpartyTypeAtVote(CounterpartyType.BUSINESS);
        observations.save(obs);

        assertThat(observations.findByCounterpartyKeyAndDirection("vpa:zeptoonline", Transaction.Type.EXPENSE))
                .hasSize(1);
        assertThat(observations.findByCounterpartyKeyAndDirection("vpa:zeptoonline", Transaction.Type.INCOME))
                .isEmpty();
    }

    @Test
    void enforcesOneCorpusRowPerKeyAndDirection() {
        SharedMerchantCategory row = new SharedMerchantCategory();
        row.setCounterpartyKey("vpa:kronos");
        row.setDirection(Transaction.Type.INCOME);
        row.setStatus(SharedMerchantCategory.Status.TRUSTED);
        row.setCategory("Salary");
        row.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        row.setDistinctUserCount(3);
        corpus.save(row);

        assertThat(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .isPresent()
                .get().extracting(SharedMerchantCategory::getStatus).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
    }

    @Test
    void upsertReplacesRatherThanDuplicatingAnAiSuggestion() {
        aiSuggestions.upsert("vpa:newmerchant", "EXPENSE", "Dining", "claude-haiku-4-5-20251001", Instant.now());
        aiSuggestions.upsert("vpa:newmerchant", "EXPENSE", "Shopping", "claude-haiku-4-5-20251001", Instant.now());

        assertThat(aiSuggestions.findByCounterpartyKeyAndDirection("vpa:newmerchant", Transaction.Type.EXPENSE))
                .isPresent()
                .get().extracting(SharedMerchantCategoryAiSuggestion::getCategory).isEqualTo("Shopping");
    }
}
