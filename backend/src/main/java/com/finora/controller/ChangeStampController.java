package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ChangeStampDto;
import com.finora.security.CurrentUser;
import com.finora.service.ChangeStampService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mobile app's "did anything change?" probe -- see {@link ChangeStampService}. Polled about
 * every 30 seconds by an app that is in the foreground, so it must stay a single small query.
 */
@RestController
@RequestMapping("/api/v1/changes")
public class ChangeStampController {

    private final ChangeStampService changeStampService;
    private final CurrentUser currentUser;

    public ChangeStampController(ChangeStampService changeStampService, CurrentUser currentUser) {
        this.changeStampService = changeStampService;
        this.currentUser = currentUser;
    }

    @GetMapping("/stamp")
    public ApiResponse<ChangeStampDto> stamp() {
        return ApiResponse.ok(changeStampService.stampFor(currentUser.id()));
    }
}
