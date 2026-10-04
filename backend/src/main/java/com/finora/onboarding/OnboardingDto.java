package com.finora.onboarding;

import java.util.List;

public class OnboardingDto {

    /** {@code spendingTrackingMethod}: the answer to the required "How do you keep track of your
     *  spending today?" question, a SpendingTrackingMethod name, or null until answered -- the
     *  clients show the question, and nothing else, while it is null. */
    public record StatusResponse(boolean onboardingCompleted, List<String> financialFocus,
                                 String spendingTrackingMethod) {}

    public record FinancialFocusRequest(List<String> focusKeys) {}

    public record SpendingTrackingRequest(String method) {}

    public record ChecklistItemDto(String key, boolean completed) {}

    public record ChecklistResponse(List<ChecklistItemDto> items, int completedCount, int totalCount) {}
}
