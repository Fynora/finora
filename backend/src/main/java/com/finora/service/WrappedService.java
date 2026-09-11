package com.finora.service;

import com.finora.dto.WrappedDto;
import com.finora.goals.GoalContributionRepository;
import com.finora.timeline.TimelineEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Layer 2 of the design spec: the marketing vehicle built entirely from Layer 1
 *  (TimelineEvent) and existing GoalContribution data -- no separate computation of its own.
 *  Only LANDMARK-importance events are eligible, per spec §4.3. */
@Service
public class WrappedService {

    private final TimelineEventRepository timelineEventRepository;
    private final GoalContributionRepository contributionRepository;

    public WrappedService(TimelineEventRepository timelineEventRepository,
                           GoalContributionRepository contributionRepository) {
        this.timelineEventRepository = timelineEventRepository;
        this.contributionRepository = contributionRepository;
    }

    @Transactional(readOnly = true)
    public WrappedDto build(UUID userId, int year) {
        List<String> landmarkTitles = timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId).stream()
                .filter(e -> "LANDMARK".equals(e.getImportance()))
                .filter(e -> Year.from(e.getOccurredAt().atZone(ZoneOffset.UTC)).getValue() == year)
                .map(com.finora.timeline.TimelineEvent::getTitle)
                .toList();

        long contributions = contributionRepository.findByUserId(userId).stream()
                .filter(c -> YearMonth.from(c.getContributedAt()).getYear() == year)
                .count();

        return new WrappedDto(year, landmarkTitles.size(), (int) contributions, landmarkTitles);
    }
}
