package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.DecisionQueueRowDto;
import com.finora.security.CurrentUser;
import com.finora.service.DecisionQueueService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The "Needs a decision" page: every open hold the caller may decide, in one list
 * ({@link DecisionQueueService}). Either permission opens it; each kind of hold is shown only to
 * those who may decide it -- held imports with {@code IMPORT_TRIAGE_MANAGE}, trust holds with
 * {@code TRUST_REVIEW_MANAGE}, the same split as their own pages.
 */
@RestController
@RequestMapping("/api/v1/admin/decision-queue")
@PreAuthorize("hasAnyAuthority('IMPORT_TRIAGE_MANAGE', 'TRUST_REVIEW_MANAGE')")
public class AdminDecisionQueueController {

    private final DecisionQueueService decisionQueue;
    private final CurrentUser currentUser;

    public AdminDecisionQueueController(DecisionQueueService decisionQueue, CurrentUser currentUser) {
        this.decisionQueue = decisionQueue;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<DecisionQueueRowDto>> list() {
        return ApiResponse.ok(decisionQueue.list(
                currentUser.hasAuthority("IMPORT_TRIAGE_MANAGE"),
                currentUser.hasAuthority("TRUST_REVIEW_MANAGE")));
    }
}
