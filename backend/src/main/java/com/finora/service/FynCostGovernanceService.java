package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.repository.AiAuditLogRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Cost governance -- plan §4.4. Two independent limits, checked from {@code ai_audit_log}'s real
 * spend, not an estimate: a per-user rolling-24h cap (catches one runaway conversation or a single
 * abusive account) and an org-wide calendar-month budget with warning thresholds (catches a
 * cross-account cost spike or a misconfigured deployment). Both feed {@link FynAvailabilityGuard},
 * which is the actual enforcement point -- this class only answers "how much, and what state."
 */
@Service
public class FynCostGovernanceService {

    /** plan §4.4's named thresholds. Stop is >= budget, not > -- hitting the budget exactly should
     *  already read as "no headroom left," not "one dollar away." */
    public enum BudgetStatus { OK, WARNING_70, WARNING_90, STOPPED }

    public record MonthlyBudget(BigDecimal spent, BigDecimal budget, BudgetStatus status) {}

    private final AiAuditLogRepository aiAuditLogRepository;
    private final FynProperties properties;

    public FynCostGovernanceService(AiAuditLogRepository aiAuditLogRepository, FynProperties properties) {
        this.aiAuditLogRepository = aiAuditLogRepository;
        this.properties = properties;
    }

    /** True once a user's trailing-24h Fyn spend has reached the configured daily cap. Rolling,
     *  not calendar-day: a fixed midnight boundary would let a burst at 11:59pm and another at
     *  12:01am both dodge the cap despite landing two minutes apart. */
    public boolean userDailyCapReached(UUID userId) {
        BigDecimal spent = aiAuditLogRepository.sumCostByUserSince(userId, Instant.now().minusSeconds(86_400));
        return spent.compareTo(properties.getDailyUserCostCapUsd()) >= 0;
    }

    /** Calendar month, not rolling: this is the number an operator checking "this month's AI
     *  spend" expects, and it resets deliberately at each month boundary rather than always
     *  looking back exactly 30 days. */
    public MonthlyBudget monthlyBudget() {
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC)
                .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        BigDecimal spent = aiAuditLogRepository.sumCostSince(monthStart);
        BigDecimal budget = properties.getMonthlyBudgetUsd();

        BudgetStatus status;
        if (spent.compareTo(budget) >= 0) {
            status = BudgetStatus.STOPPED;
        } else if (spent.compareTo(budget.multiply(new BigDecimal("0.90"))) >= 0) {
            status = BudgetStatus.WARNING_90;
        } else if (spent.compareTo(budget.multiply(new BigDecimal("0.70"))) >= 0) {
            status = BudgetStatus.WARNING_70;
        } else {
            status = BudgetStatus.OK;
        }
        return new MonthlyBudget(spent, budget, status);
    }
}
