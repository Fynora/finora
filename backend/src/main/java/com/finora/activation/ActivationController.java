package com.finora.activation;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gate 2a: what the apps report about a user's way to a first statement. The user is always the
 * signed-in caller, never a parameter.
 */
@RestController
@RequestMapping("/api/v1/activation")
public class ActivationController {

    private final ImportFunnelService importFunnelService;
    private final ActivationQuestionService questionService;
    private final CurrentUser currentUser;

    public ActivationController(ImportFunnelService importFunnelService, ActivationQuestionService questionService,
                                CurrentUser currentUser) {
        this.importFunnelService = importFunnelService;
        this.questionService = questionService;
        this.currentUser = currentUser;
    }

    @PostMapping("/funnel-events")
    public ApiResponse<Void> funnelEvent(@Valid @RequestBody ActivationDto.FunnelEventRequest request) {
        importFunnelService.record(currentUser.id(), request.event());
        return ApiResponse.ok(null);
    }

    /** The "What stopped you?" question the dashboard should show now, if any (spec §4.2). */
    @GetMapping("/question")
    public ApiResponse<ActivationDto.QuestionResponse> question() {
        return ApiResponse.ok(new ActivationDto.QuestionResponse(
                questionService.nextDashboardQuestion(currentUser.id()).orElse(null)));
    }

    /** One tap: an answer from the moment's fixed list, or SKIPPED. */
    @PostMapping("/answers")
    public ApiResponse<Void> answer(@Valid @RequestBody ActivationDto.ActivationAnswerRequest request) {
        questionService.answer(currentUser.id(), request.moment(), request.answer());
        return ApiResponse.ok(null);
    }
}
