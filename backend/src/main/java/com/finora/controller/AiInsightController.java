package com.finora.controller;

import com.finora.dto.AiInsightDtos;
import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.security.CurrentUser;
import com.finora.service.AiInsightService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * AI insights (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §8.1): the month
 * story, "To look at" in the order to show it, "Why?" on a running-high row, and thumbs on an AI text.
 * Every endpoint is the signed-in user's own: a row, key or text of anyone else's is a 404. None of
 * them ever answers with an error because of the AI -- a refusal is the rules text (AiInsightService).
 * GET never calls the model; POST may.
 */
@RestController
@RequestMapping("/api/v1/insights")
public class AiInsightController {

    private final AiInsightService aiInsightService;
    private final CurrentUser currentUser;

    public AiInsightController(AiInsightService aiInsightService, CurrentUser currentUser) {
        this.aiInsightService = aiInsightService;
        this.currentUser = currentUser;
    }

    @GetMapping("/story")
    public ApiResponse<AiInsightDtos.StoryDto> story(@RequestParam(required = false) String month) {
        return ApiResponse.ok(story(aiInsightService.getStory(currentUser.id(), validMonth(month))));
    }

    @PostMapping("/story")
    public ApiResponse<AiInsightDtos.StoryDto> generateStory(@Valid @RequestBody AiInsightDtos.StoryRequest request) {
        return ApiResponse.ok(story(aiInsightService.generateStory(currentUser.id(), request.month())));
    }

    @GetMapping("/look-at")
    public ApiResponse<AiInsightDtos.LookAtDto> lookAt(@RequestParam(required = false) String month) {
        AiInsightService.LookAt lookAt = aiInsightService.lookAt(currentUser.id(), validMonth(month));
        return ApiResponse.ok(new AiInsightDtos.LookAtDto(lookAt.rows(), lookAt.orderedByAi()));
    }

    @PostMapping("/look-at/why")
    public ApiResponse<AiInsightDtos.WhyDto> why(@Valid @RequestBody AiInsightDtos.WhyRequest request) {
        AiInsightService.TextView v = aiInsightService.why(currentUser.id(), request.kind() + "|" + request.itemKey(),
                request.month());
        return ApiResponse.ok(new AiInsightDtos.WhyDto(v.status(), v.id(), v.text(), v.writtenByAi(), v.basis(),
                v.freeLeftThisMonth()));
    }

    @PatchMapping("/ai-texts/{id}/feedback")
    public ApiResponse<Void> feedback(@PathVariable UUID id, @Valid @RequestBody AiInsightDtos.AiTextFeedbackRequest request) {
        aiInsightService.feedback(currentUser.id(), id, request.rating(), request.reason());
        return ApiResponse.ok(null, "Feedback saved");
    }

    private static AiInsightDtos.StoryDto story(AiInsightService.TextView v) {
        return new AiInsightDtos.StoryDto(v.status(), v.id(), v.text(), v.writtenByAi(), v.basis(), v.canGenerate());
    }

    static String validMonth(String month) {
        if (month != null && !month.matches(AiInsightDtos.MONTH_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must be yyyy-MM");
        }
        return month;
    }
}
