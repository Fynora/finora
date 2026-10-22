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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // AI insights foundation §6: Free usage has its own slice of the monthly AI budget.

    @Test
    void freeBudgetSpentAtExactlyTheSlice() {
        properties.setFreeMonthlyBudgetUsd(new BigDecimal("10.00"));
        when(repository.sumCostByPlanSince(eq("FREE"), any(Instant.class))).thenReturn(new BigDecimal("10.00"));

        assertThat(service.freeBudgetSpent()).isTrue();
    }

    @Test
    void freeBudgetNotSpentJustBelow() {
        properties.setFreeMonthlyBudgetUsd(new BigDecimal("10.00"));
        when(repository.sumCostByPlanSince(eq("FREE"), any(Instant.class))).thenReturn(new BigDecimal("9.99"));

        assertThat(service.freeBudgetSpent()).isFalse();
    }

    @Test
    void freeSpendIgnoresPlusRows() {
        properties.setFreeMonthlyBudgetUsd(new BigDecimal("10.00"));
        when(repository.sumCostByPlanSince(eq("FREE"), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        service.freeBudgetSpent();

        verify(repository).sumCostByPlanSince(eq("FREE"), any(Instant.class));
        verify(repository, never()).sumCostByPlanSince(eq("PLUS"), any(Instant.class));
        verify(repository, never()).sumCostSince(any(Instant.class));
    }

    @Test
    void freeSliceCountsFromTheStartOfTheUtcMonth() {
        properties.setFreeMonthlyBudgetUsd(new BigDecimal("10.00"));
        when(repository.sumCostByPlanSince(eq("FREE"), any(Instant.class))).thenReturn(BigDecimal.ZERO);

        service.freeBudgetSpent();

        org.mockito.ArgumentCaptor<Instant> since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).sumCostByPlanSince(eq("FREE"), since.capture());
        java.time.ZonedDateTime start = since.getValue().atZone(java.time.ZoneOffset.UTC);
        assertThat(start.getDayOfMonth()).isEqualTo(1);
        assertThat(start.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
    }

    @Test
    void insightsSettingsHaveTheSpecDefaults() {
        FynProperties fresh = new FynProperties();
        assertThat(fresh.getFreeMonthlyBudgetUsd()).isEqualByComparingTo("10.00");
        assertThat(fresh.getInsightsAudience()).isEqualTo("internal");
        assertThat(fresh.isInsightsAiRanking()).isFalse();
        assertThat(fresh.getInsightsMaxConcurrent()).isEqualTo(4);
    }
}
