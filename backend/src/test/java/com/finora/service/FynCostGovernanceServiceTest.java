package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.repository.AiAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FynCostGovernanceServiceTest {

    private AiAuditLogRepository repository;
    private FynProperties properties;
    private FynCostGovernanceService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(AiAuditLogRepository.class);
        properties = new FynProperties();
        properties.setDailyUserCostCapUsd(new BigDecimal("1.00"));
        properties.setMonthlyBudgetUsd(new BigDecimal("50.00"));
        service = new FynCostGovernanceService(repository, properties);
    }

    @Test
    void userDailyCapNotReachedWhenWellUnderBudget() {
        when(repository.sumCostByUserSince(eq(userId), any(Instant.class))).thenReturn(new BigDecimal("0.03"));

        assertThat(service.userDailyCapReached(userId)).isFalse();
    }

    @Test
    void userDailyCapReachedAtExactlyTheLimit() {
        when(repository.sumCostByUserSince(eq(userId), any(Instant.class))).thenReturn(new BigDecimal("1.00"));

        assertThat(service.userDailyCapReached(userId)).isTrue();
    }

    @Test
    void userDailyCapReachedWhenOverTheLimit() {
        when(repository.sumCostByUserSince(eq(userId), any(Instant.class))).thenReturn(new BigDecimal("5.00"));

        assertThat(service.userDailyCapReached(userId)).isTrue();
    }

    @Test
    void monthlyBudgetOkBelow70Percent() {
        when(repository.sumCostSince(any(Instant.class))).thenReturn(new BigDecimal("10.00"));

        assertThat(service.monthlyBudget().status()).isEqualTo(FynCostGovernanceService.BudgetStatus.OK);
    }

    @Test
    void monthlyBudgetWarning70AtExactly70Percent() {
        when(repository.sumCostSince(any(Instant.class))).thenReturn(new BigDecimal("35.00"));

        assertThat(service.monthlyBudget().status()).isEqualTo(FynCostGovernanceService.BudgetStatus.WARNING_70);
    }

    @Test
    void monthlyBudgetWarning90AtExactly90Percent() {
        when(repository.sumCostSince(any(Instant.class))).thenReturn(new BigDecimal("45.00"));

        assertThat(service.monthlyBudget().status()).isEqualTo(FynCostGovernanceService.BudgetStatus.WARNING_90);
    }

    @Test
    void monthlyBudgetStoppedAtExactlyTheBudget() {
        when(repository.sumCostSince(any(Instant.class))).thenReturn(new BigDecimal("50.00"));

        assertThat(service.monthlyBudget().status()).isEqualTo(FynCostGovernanceService.BudgetStatus.STOPPED);
    }

    @Test
    void monthlyBudgetStoppedWhenOverTheBudget() {
        when(repository.sumCostSince(any(Instant.class))).thenReturn(new BigDecimal("75.00"));

        assertThat(service.monthlyBudget().status()).isEqualTo(FynCostGovernanceService.BudgetStatus.STOPPED);
    }
}
