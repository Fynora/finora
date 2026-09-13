package com.finora.service;

import com.finora.dto.HeldStatementDetailDto;
import com.finora.dto.HeldStatementDto;
import com.finora.entity.AiAuditLog;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.repository.AiAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FynImportDiagnosisServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private HeldStatementService heldStatementService;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private FynImportDiagnosisService service;

    private final UUID admin = UUID.randomUUID();
    private static final String HELD_ID = "HLD-2026-000001";

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        heldStatementService = mock(HeldStatementService.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        service = new FynImportDiagnosisService(availabilityGuard, heldStatementService, llmClient,
                aiAuditLogRepository);

        when(availabilityGuard.importAssistAvailable()).thenReturn(true);
        when(heldStatementService.detail(HELD_ID)).thenReturn(detailFixture());
    }

    private static HeldStatementDetailDto detailFixture() {
        HeldStatementDto summary = new HeldStatementDto(
                UUID.randomUUID(), HELD_ID, UUID.randomUUID(), UUID.randomUUID(),
                "HDFC", "HELD", "Printed and parsed transaction count disagree (DIRECTION)",
                "UNRELIABLE", "OCR", true, "parser-v42",
                List.of("COUNT_MISMATCH"), null, null, null, null, null,
                Instant.now(), null, null, null, null, null);
        return new HeldStatementDetailDto(summary, "statement.pdf", List.of(), List.of());
    }

    @Test
    void refusesWhenAvailabilityGuardSaysNo() {
        when(availabilityGuard.importAssistAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.suggestDiagnosis(admin, HELD_ID))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(llmClient, never()).complete(any());
    }

    @Test
    void onSuccessWritesAuditLogAndPersistsTheSuggestion() {
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("Likely a merged header row.", "claude-haiku-4-5-20251001",
                        800, 150, "end_turn"));

        HeldStatementDetailDto result = service.suggestDiagnosis(admin, HELD_ID);

        assertThat(result).isNotNull();
        verify(heldStatementService).recordAiSuggestion(admin, HELD_ID, "Likely a merged header row.");

        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        AiAuditLog saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(admin);
        assertThat(saved.getModel()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(saved.getToolName()).isEqualTo("SUGGEST_IMPORT_DIAGNOSIS");
        assertThat(saved.getTokensIn()).isEqualTo(800);
        assertThat(saved.getTokensOut()).isEqualTo(150);
        assertThat(saved.getCost()).isEqualByComparingTo(FynPricing.cost("claude-haiku-4-5-20251001", 800, 150));
        assertThat(saved.getError()).isNull();
    }

    @Test
    void sendsOnlyStructuralFieldsToTheModelNeverRawContent() {
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("suggestion", "claude-haiku-4-5-20251001", 10, 10, "end_turn"));

        service.suggestDiagnosis(admin, HELD_ID);

        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        String userMessage = captor.getValue().messages().get(0).content();
        assertThat(userMessage).contains("parser-v42", "UNRELIABLE", "OCR", "COUNT_MISMATCH",
                "Printed and parsed transaction count disagree (DIRECTION)");
        // Never fabricates a currency figure, account number, or narration -- those are never
        // fields on HeldStatementDto at all (see that DTO's own doc), so this is really asserting
        // the prompt builder didn't invent a field it doesn't have.
        assertThat(userMessage).doesNotContain("₹", "statement.pdf");
    }

    @Test
    void aBareRuntimeExceptionFromTheLlmClientStillWritesAnAuditLogRow() {
        // AnthropicClient's own defensive "no API key" check throws IllegalStateException, not an
        // ApiException -- this must not skip the audit-log-on-failure write.
        when(llmClient.complete(any())).thenThrow(new IllegalStateException("no api key"));

        assertThatThrownBy(() -> service.suggestDiagnosis(admin, HELD_ID)).isInstanceOf(ApiException.class);

        verify(aiAuditLogRepository).save(any());
        verify(heldStatementService, never()).recordAiSuggestion(any(), anyString(), anyString());
    }

    @Test
    void anUnrecognizedModelStillWritesAnAuditLogRowAndPersistsTheSuggestion() {
        // The call already happened and already cost money by the time FynPricing.cost() could
        // throw for a model it doesn't know -- losing the audit row (or the suggestion) over a
        // pricing-table gap would be worse than recording it with cost unknown.
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("A suggestion.", "some-future-model", 100, 50, "end_turn"));

        HeldStatementDetailDto result = service.suggestDiagnosis(admin, HELD_ID);

        assertThat(result).isNotNull();
        verify(heldStatementService).recordAiSuggestion(admin, HELD_ID, "A suggestion.");

        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        AiAuditLog saved = captor.getValue();
        assertThat(saved.getCost()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        assertThat(saved.getError()).contains("some-future-model");
    }

    @Test
    void aBlankCompletionIsRejectedRatherThanPersisted() {
        when(llmClient.complete(any())).thenReturn(
                new LlmCompletion("   ", "claude-haiku-4-5-20251001", 10, 0, "end_turn"));

        assertThatThrownBy(() -> service.suggestDiagnosis(admin, HELD_ID))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");

        verify(heldStatementService, never()).recordAiSuggestion(any(), anyString(), anyString());
    }

    @Test
    void onFailureStillWritesAnAuditLogRowBeforeRethrowing() {
        ApiException failure = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Anthropic rate limit reached (429).");
        when(llmClient.complete(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.suggestDiagnosis(admin, HELD_ID)).isSameAs(failure);

        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        AiAuditLog saved = captor.getValue();
        assertThat(saved.getError()).contains("429");
        assertThat(saved.getTokensIn()).isZero();
        assertThat(saved.getCost()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        verify(heldStatementService, never()).recordAiSuggestion(any(), anyString(), anyString());
    }
}
