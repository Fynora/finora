package com.finora.service;

import com.finora.dto.WrappedDto;
import com.finora.goals.GoalContribution;
import com.finora.goals.GoalContributionRepository;
import com.finora.timeline.TimelineEvent;
import com.finora.timeline.TimelineEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WrappedServiceTest {

    private TimelineEventRepository timelineEventRepository;
    private GoalContributionRepository contributionRepository;
    private WrappedService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        timelineEventRepository = mock(TimelineEventRepository.class);
        contributionRepository = mock(GoalContributionRepository.class);
        service = new WrappedService(timelineEventRepository, contributionRepository);
    }

    private TimelineEvent eventOf(String importance, String title, Instant occurredAt) {
        TimelineEvent e = new TimelineEvent();
        ReflectionTestUtils.setField(e, "importance", importance);
        ReflectionTestUtils.setField(e, "title", title);
        ReflectionTestUtils.setField(e, "occurredAt", occurredAt);
        return e;
    }

    private GoalContribution contributionOn(LocalDate date) {
        GoalContribution gc = new GoalContribution();
        ReflectionTestUtils.setField(gc, "contributedAt", date);
        return gc;
    }

    @Test
    void build_countsOnlyLandmarkEvents_inTheRequestedYear() {
        when(timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of(
                eventOf("LANDMARK", "Completed Emergency Fund", Instant.parse("2026-06-01T00:00:00Z")),
                eventOf("MAJOR", "Created your first budget", Instant.parse("2026-01-01T00:00:00Z")),
                eventOf("LANDMARK", "Saved your first ₹10,000", Instant.parse("2025-12-01T00:00:00Z"))
        ));
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of(
                contributionOn(LocalDate.of(2026, 3, 1)), contributionOn(LocalDate.of(2026, 6, 1))
        ));

        WrappedDto result = service.build(userId, 2026);

        assertThat(result.year()).isEqualTo(2026);
        assertThat(result.landmarksReached()).isEqualTo(1);
        assertThat(result.landmarkTitles()).containsExactly("Completed Emergency Fund");
        assertThat(result.goalContributions()).isEqualTo(2);
    }

    @Test
    void build_returnsZeroes_whenTheUserHasNothingThatYear() {
        when(timelineEventRepository.findByUserIdOrderByOccurredAtDesc(userId)).thenReturn(List.of());
        when(contributionRepository.findByUserId(userId)).thenReturn(List.of());

        WrappedDto result = service.build(userId, 2026);

        assertThat(result.landmarksReached()).isEqualTo(0);
        assertThat(result.goalContributions()).isEqualTo(0);
        assertThat(result.landmarkTitles()).isEmpty();
    }
}
