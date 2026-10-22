package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.FynInsightsNarrationDto;
import com.finora.dto.InsightAcknowledgementDto;
import com.finora.dto.InsightsDto;
import com.finora.exception.ApiException;
import com.finora.security.CurrentUser;
import com.finora.service.AiInsightService;
import com.finora.service.InsightAcknowledgementService;
import com.finora.service.InsightsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightsController {

    private final InsightsService insightsService;
    private final AiInsightService aiInsightService;
    private final InsightAcknowledgementService insightAcknowledgementService;
    private final CurrentUser currentUser;

    public InsightsController(InsightsService insightsService, AiInsightService aiInsightService,
                               InsightAcknowledgementService insightAcknowledgementService, CurrentUser currentUser) {
        this.insightsService = insightsService;
        this.insightAcknowledgementService = insightAcknowledgementService;
        this.aiInsightService = aiInsightService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<InsightsDto> insights(@RequestParam(required = false) String month) {
        return ApiResponse.ok(insightsService.build(currentUser.id(), month));
    }

    /** The rows this user marked "Got it" (V277); the app hides them. */
    @GetMapping("/acknowledgements")
    public ApiResponse<List<InsightAcknowledgementDto>> acknowledgements() {
        return ApiResponse.ok(insightAcknowledgementService.list(currentUser.id()));
    }

    /** "Got it" on one row. Idempotent: the same kind and key again changes nothing. */
    @PostMapping("/acknowledgements")
    public ApiResponse<Void> acknowledge(@Valid @RequestBody InsightAcknowledgementDto request) {
        insightAcknowledgementService.acknowledge(currentUser.id(), request);
        return ApiResponse.ok(null, "Saved");
    }

    /** The website's narration slot, served from the AI month story (AI insights foundation,
     *  docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §8.4): the stored story, or
     *  one written now when it may be. Anything other than an AI story -- not allowed, unavailable,
     *  nothing to describe, a failed check -- is 404, which the page shows as no narration. Its own
     *  endpoint, deliberately: nothing here can affect {@link #insights}. The website moves to
     *  GET/POST /story (with its label and feedback) in its own PR; this stays for the version live now. */
    @GetMapping("/narration")
    public ApiResponse<FynInsightsNarrationDto> narration(@RequestParam(required = false) String month) {
        if (month != null && !month.matches(com.finora.dto.AiInsightDtos.MONTH_PATTERN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "month must be yyyy-MM");
        }
        AiInsightService.TextView story = aiInsightService.getStory(currentUser.id(), month);
        if (!"ready".equals(story.status()) && story.canGenerate()) {
            story = aiInsightService.generateStory(currentUser.id(), month);
        }
        if (!"ready".equals(story.status()) || !story.writtenByAi() || story.text() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Nothing to narrate for this month yet.");
        }
        return ApiResponse.ok(new FynInsightsNarrationDto(story.text()));
    }
}
