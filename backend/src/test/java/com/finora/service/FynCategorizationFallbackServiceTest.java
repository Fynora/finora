package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.SharedMerchantCategoryAiSuggestion;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.SharedMerchantCategoryAiSuggestionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FynCategorizationFallbackServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private SharedMerchantCategoryAiSuggestionRepository aiSuggestionRepository;
    private FynCategorizationFallbackService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        aiSuggestionRepository = mock(SharedMerchantCategoryAiSuggestionRepository.class);
        service = new FynCategorizationFallbackService(availabilityGuard, llmClient,
                aiAuditLogRepository, aiSuggestionRepository);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void suggest_available_returnsCategoryAndUpsertsSuggestionAndAudits() {
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(
                "Dining", List.of(), "claude-haiku-4-5-20251001", 50, 5, "end_turn"));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).contains("Dining");
        verify(aiSuggestionRepository).upsert(eq("vpa:newcafe"), eq("EXPENSE"), eq("Dining"),
                eq("claude-haiku-4-5-20251001"), any());
        verify(aiAuditLogRepository).save(any(AiAuditLog.class));
    }

    @Test
    void suggest_cachedSuggestionExists_returnsItWithoutCallingLlm() {
        SharedMerchantCategoryAiSuggestion cached = new SharedMerchantCategoryAiSuggestion();
        cached.setCounterpartyKey("vpa:newcafe");
        cached.setDirection(Transaction.Type.EXPENSE);
        cached.setCategory("Dining");
        cached.setModel("claude-haiku-4-5-20251001");
        when(aiSuggestionRepository.findByCounterpartyKeyAndDirection("vpa:newcafe", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).contains("Dining");
        verifyNoInteractions(llmClient);
        verifyNoInteractions(aiAuditLogRepository);
        verify(aiSuggestionRepository, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void suggest_notAvailable_returnsEmptyWithoutCallingLlm() {
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(false);

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void suggest_llmThrows_writesFailureAuditLogAndReturnsEmpty() {
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verify(aiSuggestionRepository, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    void suggest_blankCompletion_returnsEmptyWithoutUpserting() {
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(
                "  ", List.of(), "claude-haiku-4-5-20251001", 50, 1, "end_turn"));

        Optional<String> result = service.suggest(userId, "vpa:newcafe", Transaction.Type.EXPENSE,
                "UPI-NEW CAFE-newcafe@ybl-REF123");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(any(AiAuditLog.class));
        verify(aiSuggestionRepository, never()).upsert(any(), any(), any(), any(), any());
    }
}
