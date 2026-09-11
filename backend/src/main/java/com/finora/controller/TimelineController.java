package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.GoalMomentumDto;
import com.finora.dto.TimelineEventDto;
import com.finora.dto.WrappedDto;
import com.finora.goals.GoalMomentumService;
import com.finora.security.CurrentUser;
import com.finora.service.WrappedService;
import com.finora.timeline.TimelineEventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/timeline")
public class TimelineController {

    private final TimelineEventService timelineEventService;
    private final GoalMomentumService goalMomentumService;
    private final WrappedService wrappedService;
    private final CurrentUser currentUser;

    public TimelineController(TimelineEventService timelineEventService, GoalMomentumService goalMomentumService,
                               WrappedService wrappedService, CurrentUser currentUser) {
        this.timelineEventService = timelineEventService;
        this.goalMomentumService = goalMomentumService;
        this.wrappedService = wrappedService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<TimelineEventDto>> list() {
        return ApiResponse.ok(timelineEventService.listForUser(currentUser.id()));
    }

    @GetMapping("/momentum")
    public ApiResponse<GoalMomentumDto> momentum() {
        return ApiResponse.ok(goalMomentumService.compute(currentUser.id()));
    }

    @GetMapping("/wrapped")
    public ApiResponse<WrappedDto> wrapped(@RequestParam int year) {
        return ApiResponse.ok(wrappedService.build(currentUser.id(), year));
    }
}
