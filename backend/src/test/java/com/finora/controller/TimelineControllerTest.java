package com.finora.controller;

import com.finora.dto.GoalMomentumDto;
import com.finora.dto.TimelineEventDto;
import com.finora.dto.WrappedDto;
import com.finora.goals.GoalMomentumService;
import com.finora.security.CurrentUser;
import com.finora.service.WrappedService;
import com.finora.timeline.TimelineEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimelineControllerTest {

    @Test
    void list_returnsTheCurrentUsersTimelineEvents() {
        TimelineEventService service = mock(TimelineEventService.class);
        GoalMomentumService goalMomentumService = mock(GoalMomentumService.class);
        WrappedService wrappedService = mock(WrappedService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        List<TimelineEventDto> events = List.of(new TimelineEventDto(
                "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.now()));
        when(service.listForUser(userId)).thenReturn(events);

        var controller = new TimelineController(service, goalMomentumService, wrappedService, currentUser);

        assertThat(controller.list().data()).isEqualTo(events);
    }

    @Test
    void momentum_returnsTheCurrentUsersMomentum() {
        TimelineEventService service = mock(TimelineEventService.class);
        GoalMomentumService goalMomentumService = mock(GoalMomentumService.class);
        WrappedService wrappedService = mock(WrappedService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        GoalMomentumDto momentum = new GoalMomentumDto(3, 6);
        when(goalMomentumService.compute(eq(userId), any())).thenReturn(momentum);

        var controller = new TimelineController(service, goalMomentumService, wrappedService, currentUser);

        assertThat(controller.momentum().data()).isEqualTo(momentum);
    }

    @Test
    void wrapped_returnsTheCurrentUsersWrappedForTheGivenYear() {
        TimelineEventService service = mock(TimelineEventService.class);
        GoalMomentumService goalMomentumService = mock(GoalMomentumService.class);
        WrappedService wrappedService = mock(WrappedService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        WrappedDto wrapped = new WrappedDto(2026, 2, 14, List.of("Completed Emergency Fund"));
        when(wrappedService.build(userId, 2026)).thenReturn(wrapped);

        var controller = new TimelineController(service, goalMomentumService, wrappedService, currentUser);

        assertThat(controller.wrapped(2026).data()).isEqualTo(wrapped);
    }
}
