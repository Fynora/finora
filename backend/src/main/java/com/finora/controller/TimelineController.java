package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.TimelineEventDto;
import com.finora.security.CurrentUser;
import com.finora.timeline.TimelineEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/timeline")
public class TimelineController {

    private final TimelineEventService timelineEventService;
    private final CurrentUser currentUser;

    public TimelineController(TimelineEventService timelineEventService, CurrentUser currentUser) {
        this.timelineEventService = timelineEventService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<TimelineEventDto>> list() {
        return ApiResponse.ok(timelineEventService.listForUser(currentUser.id()));
    }
}
