package com.finora.service;

import com.finora.dto.WrappedDto;
import com.finora.goals.GoalContributionRepository;
import com.finora.repository.UserRepository;
import com.finora.timeline.TimelineEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Layer 2 of the design spec: the marketing vehicle built entirely from Layer 1
 *  (TimelineEvent) and existing GoalContribution data -- no separate computation of its own.
 *  Only LANDMARK-importance events are eligible, per spec §4.3. */
@Service
public class WrappedService {

    private final TimelineEventRepository timelineEventRepository;
    private final GoalContributionRepository contributionRepository;
    private final UserRepository userRepository;

    public WrappedService(TimelineEventRepository timelineEventRepository,
                           GoalContributionRepository contributionRepository,
                           UserRepository userRepository) {
        this.timelineEventRepository = timelineEventRepository;
        this.contributionRepository = contributionRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public WrappedDto build(UUID userId, int year) {
        // Bug fix: this used to bucket TimelineEvent.occurredAt by ZoneOffset.UTC -- the exact
        // class of bug already fixed independently in GoalService/BudgetService/NetWorthService/
        // DashboardService (bare UTC or server-zone instead of the user's own), and the reason
        // UserZone exists as a single shared utility instead of a sixth hand-copied safeZoneId. A
        // milestone reached late at night IST (UTC+5:30) around New Year's could land in the
        // wrong year's Wrapped under UTC bucketing.
        ZoneId zone = com.finora.util.UserZone.forUser(userRepository, userId);

        List<String> landmarkTitles = timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .filter(e -> "LANDMARK".equals(e.getImportance()))
                .filter(e -> Year.from(e.getOccurredAt().atZone(zone)).getValue() == year)
                .map(com.finora.timeline.TimelineEvent::getTitle)
                .toList();

        long contributions = contributionRepository.findByUserId(userId).stream()
                .filter(c -> YearMonth.from(c.getContributedAt()).getYear() == year)
                .count();

        return new WrappedDto(year, landmarkTitles.size(), (int) contributions, landmarkTitles);
    }
}
