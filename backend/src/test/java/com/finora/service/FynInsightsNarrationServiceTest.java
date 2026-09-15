package com.finora.service;

import com.finora.dto.InsightsDto;
import com.finora.entity.AiAuditLog;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.repository.AiAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FynInsightsNarrationServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private InsightsService insightsService;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private FynInsightsNarrationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        insightsService = mock(InsightsService.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        service = new FynInsightsNarrationService(availabilityGuard, insightsService, llmClient,
                aiAuditLogRepository);

        when(availabilityGuard.insightsAvailableFor(userId)).thenReturn(true);
    }

    private static InsightsDto withMovers() {
        return new InsightsDto(
                List.of("Dining was your biggest category."),
                List.of(new InsightsDto.CategoryMover("Dining", new BigDecimal("4200"),
                        new BigDecimal("3000"), 40.0)),
                null,
                new InsightsDto.CategoryHighlight("Dining", new BigDecimal("4200")),
                new InsightsDto.MerchantHighlight("Some Restaurant", new BigDecimal("1500")));
    }

    @Test
    void refusesWhenAvailabilityGuardSaysNo() {
        when(availabilityGuard.insightsAvailableFor(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.narrate(userId, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(llmClient, never()).complete(any());
    }

    @Test
    void nothingToNarrateThrowsNotFoundWithoutCallingTheLlm() {
        when(insightsService.build(userId, null)).thenReturn(
                new InsightsDto(List.of(), List.of(), null, null, null));

        assertThatThrownBy(() -> service.narrate(userId, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(llmClient, never()).complete(any());
        verify(aiAuditLogRepository, never()).save(any());
    }

    @Test
    void onSuccessReturnsTheNarrationAndWritesAnAuditLogRow() {
        when(insightsService.build(userId, null)).thenReturn(withMovers());
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("Dining spending is up 40% from your usual average.",
                        List.of(), "claude-haiku-4-5-20251001", 300, 40, "end_turn"));

        String narration = service.narrate(userId, null);

        assertThat(narration).contains("Dining");
        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        AiAuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getToolName()).isEqualTo("NARRATE_INSIGHTS");
        assertThat(saved.getCost()).isEqualByComparingTo(
                FynPricing.cost("claude-haiku-4-5-20251001", 300, 40));
    }

    @Test
    void neverSendsTheTopMerchantNameToTheModel() {
        when(insightsService.build(userId, null)).thenReturn(withMovers());
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("suggestion", List.of(), "claude-haiku-4-5-20251001", 10, 10, "end_turn"));

        service.narrate(userId, null);

        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        String userMessage = captor.getValue().messages().get(0).content();
        assertThat(userMessage).contains("Dining", "4200", "3000", "40.0");
        // "Some Restaurant" is withMovers()'s topMerchant -- must never appear in what's sent.
        assertThat(userMessage).doesNotContain("Some Restaurant");
    }

    @Test
    void aBareRuntimeExceptionFromTheLlmClientStillWritesAnAuditLogRow() {
        when(insightsService.build(userId, null)).thenReturn(withMovers());
        when(llmClient.complete(any())).thenThrow(new IllegalStateException("no api key"));

        assertThatThrownBy(() -> service.narrate(userId, null)).isInstanceOf(ApiException.class);

        verify(aiAuditLogRepository).save(any());
    }

    @Test
    void anUnrecognizedModelStillWritesAnAuditLogRowAndReturnsTheNarration() {
        when(insightsService.build(userId, null)).thenReturn(withMovers());
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("A narration.", List.of(), "some-future-model", 100, 50, "end_turn"));

        String narration = service.narrate(userId, null);

        assertThat(narration).isEqualTo("A narration.");
        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getError()).contains("some-future-model");
    }

    @Test
    void includesTheCoverageCaveatInWhatIsSentToTheModel() {
        InsightsDto.CoverageCaveat caveat = new InsightsDto.CoverageCaveat("2026-07",
                List.of(new InsightsDto.CoverageCaveat.GapWindow(
                        java.time.LocalDate.of(2026, 7, 10), java.time.LocalDate.of(2026, 7, 20))));
        when(insightsService.build(userId, null)).thenReturn(new InsightsDto(
                List.of(), List.of(), caveat, null, null));
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("Some transactions may be missing this month.",
                        List.of(), "claude-haiku-4-5-20251001", 50, 20, "end_turn"));

        service.narrate(userId, null);

        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        assertThat(captor.getValue().messages().get(0).content()).contains("2026-07", "gap in imported statements");
    }

    @Test
    void aBlankCompletionIsRejected() {
        when(insightsService.build(userId, null)).thenReturn(withMovers());
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("   ", List.of(), "claude-haiku-4-5-20251001", 10, 0, "end_turn"));

        assertThatThrownBy(() -> service.narrate(userId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
    }
}
