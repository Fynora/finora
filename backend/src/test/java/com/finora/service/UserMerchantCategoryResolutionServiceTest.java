package com.finora.service;

import com.finora.entity.*;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserMerchantCategoryResolutionServiceTest {

    private MerchantUnderstandingService understandingService;
    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private UserMerchantCategoryResolutionRepository resolutionRepository;
    private CategoryRepository categoryRepository;
    private CategorizationService categorizationService;
    private UserMerchantCategoryResolutionService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        understandingService = mock(MerchantUnderstandingService.class);
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        resolutionRepository = mock(UserMerchantCategoryResolutionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        categorizationService = mock(CategorizationService.class);
        service = new UserMerchantCategoryResolutionService(understandingService, availabilityGuard,
                llmClient, aiAuditLogRepository, resolutionRepository, categoryRepository, categorizationService);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void resolve_cacheHit_returnsExistingCategoryNameWithoutCallingLlm() {
        UUID categoryId = UUID.randomUUID();
        UserMerchantCategoryResolution cached = new UserMerchantCategoryResolution();
        cached.setCategoryId(categoryId);
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));
        Category category = new Category();
        category.setName("Pet Care");
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        verifyNoInteractions(llmClient);
    }

    @Test
    void resolve_cacheMiss_reusesExistingCategoryTheModelNamed_createsNothing() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        Category existing = new Category();
        existing.setUserId(userId);
        existing.setName("Pet Care");
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(existing));
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "Pet Care"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", null)).thenReturn(existing);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        // Model named an EXISTING category, so no reason should be passed through as a create reason.
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", null);
    }

    @Test
    void resolve_cacheMiss_sendsTheUsersCategoriesSortedByName() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        Category zebra = new Category();
        zebra.setUserId(userId);
        zebra.setName("Zebra Crossing Tolls");
        Category apple = new Category();
        apple.setUserId(userId);
        apple.setName("Apple Purchases");
        // Deliberately returned out of alphabetical order -- findByUserId makes no ordering
        // guarantee, so the service itself must sort before sending (spec §4: "ordered by name").
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(zebra, apple));
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY", Map.of("category", "Apple Purchases"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 8, "tool_use"));
        when(categorizationService.resolveOrCreateCategory(userId, "Apple Purchases", null)).thenReturn(apple);
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                com.finora.integrations.anthropic.LlmClient.LlmRequest.class);
        verify(llmClient).complete(requestCaptor.capture());
        assertThat(requestCaptor.getValue().systemPrompt())
                .contains("Apple Purchases, Zebra Crossing Tolls");
    }

    @Test
    void resolve_cacheMiss_inventsNewCategory_passesReasonAndPinsResolution() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        ToolUse toolUse = new ToolUse("t1", "RESOLVE_CATEGORY",
                Map.of("category", "Pet Care", "reason", "Pet supplies retailer, no existing match"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 60, 12, "tool_use"));
        Category created = new Category();
        UUID createdId = UUID.randomUUID();
        created.setUserId(userId);
        created.setName("Pet Care");
        when(categorizationService.resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match"))
                .thenAnswer(inv -> { created.setAiCreationReason(inv.getArgument(2)); return created; });
        when(resolutionRepository.insertIfAbsent(any(), any(), any(), any(), any())).thenReturn(1);

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("Pet Care");
        verify(categorizationService).resolveOrCreateCategory(userId, "Pet Care", "Pet supplies retailer, no existing match");
        verify(resolutionRepository).insertIfAbsent(eq(userId), eq("vpa:headsupfortails"), eq("EXPENSE"), any(), any());
    }

    @Test
    void resolve_understandingUnavailable_fallsThroughWithoutCallingResolutionLlm() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.empty());

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
        verifyNoInteractions(categorizationService);
    }

    @Test
    void resolve_llmThrows_writesFailureAuditReturnsEmptyNoPlaceholderRow() {
        when(resolutionRepository.findByUserIdAndCounterpartyKeyAndDirection(any(), any(), any())).thenReturn(Optional.empty());
        when(understandingService.understand(any(), any(), any(), any())).thenReturn(Optional.of("A pet supplies retailer"));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of());
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.resolve(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verify(resolutionRepository, never()).insertIfAbsent(any(), any(), any(), any(), any());
    }

    @Test
    void pin_writesTheResolution() {
        UUID categoryId = UUID.randomUUID();

        service.pin(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, categoryId);

        verify(resolutionRepository).upsertPinned(eq(userId), eq("vpa:headsupfortails"), eq("EXPENSE"), eq(categoryId), any());
    }

    /**
     * Every pin() call site passes a transaction's PERSISTED counterparty_key column, which is
     * nullable and can still be null for a transaction that predates
     * Transaction#applyCounterpartyTyping -- unlike resolve()'s own caller, which always passes a
     * freshly-computed CounterpartyTyping.of(...).key() that is never null. Without this guard, a
     * null key here would violate user_merchant_category_resolution.counterparty_key's NOT NULL
     * constraint and roll back the caller's whole category-update transaction -- turning an
     * ordinary "change this old transaction's category" request into a 500.
     */
    @Test
    void pin_nullCounterpartyKey_writesNothingRatherThanViolatingNotNull() {
        service.pin(userId, null, Transaction.Type.EXPENSE, UUID.randomUUID());

        verifyNoInteractions(resolutionRepository);
    }

    /**
     * A blank (but non-null) key would satisfy the NOT NULL constraint and succeed -- but every
     * transaction with no derivable counterparty identity shares that same "" key, so one manual
     * correction on such a transaction would silently overwrite the cached resolution read by
     * every OTHER unrelated no-identity transaction for that user+direction.
     */
    @Test
    void pin_blankCounterpartyKey_writesNothing() {
        service.pin(userId, "", Transaction.Type.EXPENSE, UUID.randomUUID());

        verifyNoInteractions(resolutionRepository);
    }
}
