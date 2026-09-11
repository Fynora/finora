package com.finora.controller;

import com.finora.dto.TimelineEventDto;
import com.finora.security.CurrentUser;
import com.finora.timeline.TimelineEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimelineControllerTest {

    @Test
    void list_returnsTheCurrentUsersTimelineEvents() {
        TimelineEventService service = mock(TimelineEventService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);
        List<TimelineEventDto> events = List.of(new TimelineEventDto(
                "GOAL_COMPLETED", "TRANSFORMATION", "LANDMARK", true,
                "Completed Emergency Fund", null, Instant.now()));
        when(service.listForUser(userId)).thenReturn(events);

        var controller = new TimelineController(service, currentUser);

        assertThat(controller.list().data()).isEqualTo(events);
    }
}
