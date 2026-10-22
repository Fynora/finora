package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The AI insights endpoints' bodies (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §8.1). */
public final class AiInsightDtos {

    private AiInsightDtos() {}

    public static final String MONTH_PATTERN = "^\\d{4}-\\d{2}$";

    /** {@code status}: ready | none | generating. On "none", keep today's month line. {@code
     *  canGenerate}: POST /story is worth calling. {@code basis}: what the text compares against. */
    public record StoryDto(String status, UUID id, String text, boolean writtenByAi, String basis, boolean canGenerate) {}

    /** {@code freeLeftThisMonth}: "Why?" answers a Free user has left this month; null on Plus and Premium. */
    public record WhyDto(String status, UUID id, String text, boolean writtenByAi, String basis, Integer freeLeftThisMonth) {}

    /** "To look at" rows in the order to show them; {@code orderedByAi} when that is the AI's order. */
    public record LookAtDto(List<LookAtRowDto> rows, boolean orderedByAi) {}

    public record StoryRequest(@Pattern(regexp = MONTH_PATTERN) String month) {}

    /** A running-high row by its "Got it" kind and key, as GET /look-at gave them. */
    public record WhyRequest(
            @NotNull @Pattern(regexp = "CATEGORY_HIGH|AMOUNT_CHANGED|CARD_STATEMENT_MISSING"
                    + "|SHARE_CHECK|LENT_CHECK|PAYBACK_CHECK|SETTLE|EVERY_MONTH|ALWAYS_PERSON|LEFTOVER_CHECK") String kind,
            @NotBlank @Size(max = 300) String itemKey,
            @Pattern(regexp = MONTH_PATTERN) String month) {}

    /** Thumbs on an AI text: a rating or null (cleared), and a reason only with NOT_HELPFUL. */
    public record AiTextFeedbackRequest(
            @Pattern(regexp = "HELPFUL|NOT_HELPFUL") String rating,
            @Pattern(regexp = "WRONG_NUMBER|NOT_USEFUL|CONFUSING") String reason) {}
}
