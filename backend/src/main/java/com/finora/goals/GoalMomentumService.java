package com.finora.goals;

import com.finora.dto.GoalMomentumDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** "N of last M months" momentum -- deliberately NOT a hard consecutive streak that resets to
 *  zero on one miss (design spec §4, Layer 4): a rolling window count is fair to freelancers/
 *  commission earners/seasonal workers whose cash flow isn't monthly-regular. */
@Service
public class GoalMomentumService {

    private static final int WINDOW_MONTHS = 6;

    private final GoalContributionRepository contributionRepository;

    public GoalMomentumService(GoalContributionRepository contributionRepository) {
        this.contributionRepository = contributionRepository;
    }

    @Transactional(readOnly = true)
    public GoalMomentumDto compute(UUID userId, LocalDate today) {
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth windowStart = currentMonth.minusMonths(WINDOW_MONTHS - 1L);

        long activeMonths = contributionRepository.findByUserId(userId).stream()
                .map(GoalContribution::getContributedAt)
                .map(YearMonth::from)
                .filter(m -> !m.isBefore(windowStart) && !m.isAfter(currentMonth))
                .distinct()
                .count();

        return new GoalMomentumDto((int) activeMonths, WINDOW_MONTHS);
    }
}
