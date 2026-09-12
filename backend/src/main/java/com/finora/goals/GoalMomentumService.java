package com.finora.goals;

import com.finora.dto.GoalMomentumDto;
import com.finora.repository.UserRepository;
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
    private final UserRepository userRepository;

    public GoalMomentumService(GoalContributionRepository contributionRepository, UserRepository userRepository) {
        this.contributionRepository = contributionRepository;
        this.userRepository = userRepository;
    }

    /** Bug fix: the controller used to pass a bare {@code LocalDate.now()} (the server's JVM
     *  default zone) here -- the exact class of bug already fixed independently in GoalService/
     *  BudgetService/NetWorthService/DashboardService, and the reason {@code UserZone} exists as
     *  a single shared utility. A user meaningfully east or west of wherever the server runs
     *  could get "this month" computed against the wrong month right around either end of the
     *  month, from their own point of view -- shifting which months fall inside the 6-month
     *  window. */
    @Transactional(readOnly = true)
    public GoalMomentumDto compute(UUID userId) {
        return compute(userId, LocalDate.now(com.finora.util.UserZone.forUser(userRepository, userId)));
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
