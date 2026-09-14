package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ConfirmRecurringRequest;
import com.finora.dto.DismissRecurringRequest;
import com.finora.dto.RecurringDto;
import com.finora.security.CurrentUser;
import com.finora.service.RecurringService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recurring")
public class RecurringController {

    private final RecurringService recurringService;
    private final CurrentUser currentUser;

    public RecurringController(RecurringService recurringService, CurrentUser currentUser) {
        this.recurringService = recurringService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<RecurringDto>> list() {
        return ApiResponse.ok(recurringService.detectForUser(currentUser.id()));
    }

    // No {id} path variable: a detected group has no persisted identity of its own (see
    // RecurringDismissal's own doc comment) -- the merchant string it's grouped by IS the
    // identity, so it travels in the body rather than being forced into a path segment.
    @PostMapping("/dismiss")
    public ApiResponse<Void> dismiss(@Valid @RequestBody DismissRecurringRequest request) {
        recurringService.dismiss(currentUser.id(), request.merchant());
        return ApiResponse.ok(null, "Dismissed");
    }

    // Issue #1451: the "Fynora will remember this" reinforcement copy trigger. See
    // RecurringService.confirm's own doc comment for why this has no persisted "confirmed" state
    // of its own.
    @PostMapping("/confirm")
    public ApiResponse<Void> confirm(@Valid @RequestBody ConfirmRecurringRequest request) {
        recurringService.confirm(currentUser.id(), request.merchant());
        return ApiResponse.ok(null, "Confirmed");
    }
}
