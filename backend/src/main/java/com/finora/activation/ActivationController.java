package com.finora.activation;

import com.finora.dto.ApiResponse;
import com.finora.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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

    /**
     * Without {@code moment}: the "What stopped you?" question the dashboard should show now, if any
     * (spec §4.2). With it: that moment back if the limits allow asking it now, else none -- the
     * import screen knows its moment but not the limits, and asks before showing the question.
     */
    @GetMapping("/question")
    public ApiResponse<ActivationDto.QuestionResponse> question(
            @RequestParam(required = false) ActivationMoment moment) {
        UUID userId = currentUser.id();
        ActivationMoment asked = moment == null
                ? questionService.nextDashboardQuestion(userId).orElse(null)
                : questionService.isAskable(userId, moment) ? moment : null;
        return ApiResponse.ok(new ActivationDto.QuestionResponse(asked));
    }

    /** One tap: an answer from the moment's fixed list, or SKIPPED. */
    @PostMapping("/answers")
    public ApiResponse<Void> answer(@Valid @RequestBody ActivationDto.ActivationAnswerRequest request) {
        questionService.answer(currentUser.id(), request.moment(), request.answer());
        return ApiResponse.ok(null);
    }
}
