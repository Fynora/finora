package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.MerchantUnderstanding;
import com.finora.entity.Transaction;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.MerchantUnderstandingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantUnderstandingServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private LlmClient llmClient;
    private AiAuditLogRepository aiAuditLogRepository;
    private MerchantUnderstandingRepository understandingRepository;
    private MerchantUnderstandingService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        llmClient = mock(LlmClient.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        understandingRepository = mock(MerchantUnderstandingRepository.class);
        service = new MerchantUnderstandingService(availabilityGuard, llmClient, aiAuditLogRepository, understandingRepository);
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(true);
    }

    @Test
    void understand_cacheHit_returnsWithoutCallingLlm() {
        MerchantUnderstanding cached = new MerchantUnderstanding();
        cached.setUnderstanding("A pet supplies retailer");
        when(understandingRepository.findByCounterpartyKeyAndDirection("vpa:headsupfortails", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(cached));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("A pet supplies retailer");
        verifyNoInteractions(llmClient);
    }

    @Test
    void understand_cacheMiss_callsToolAndUpsertsCache() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        ToolUse toolUse = new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "A pet supplies retailer"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).contains("A pet supplies retailer");
        verify(understandingRepository).upsert(eq("vpa:headsupfortails"), eq("EXPENSE"),
                eq("A pet supplies retailer"), eq("claude-haiku-4-5-20251001"), any());
        verify(aiAuditLogRepository).save(any(AiAuditLog.class));
    }

    /** The narration leaves Finora for a third party here, so the structural identifiers in it
     *  (UPI id, account number, reference number, IFSC) are stripped first. The merchant words
     *  the model needs survive. */
    @Test
    void understand_cacheMiss_sendsTheNarrationWithItsIdentifiersRedacted() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        ToolUse toolUse = new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "A pet supplies retailer"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));
        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);

        service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE,
                "UPI-PAWS AND CLAWS STORE-pawsclawsstore@okaxis-UTIB0XXXXXX-123456789012-A/C 00001234567890");   // synthetic-ok: invented placeholder identifiers

        verify(llmClient).complete(captor.capture());
        String sent = captor.getValue().messages().get(0).content();
        assertThat(sent).contains("PAWS AND CLAWS STORE");
        assertThat(sent).doesNotContain("pawsclawsstore@okaxis").doesNotContain("UTIB0XXXXXX")
                .doesNotContain("123456789012").doesNotContain("00001234567890"); // synthetic-ok
    }

    /** A caller that skipped CategorizationService.narrationForModel still sends no name. */
    @Test
    void understand_cacheMiss_masksNamesItself() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(),
                "claude-haiku-4-5-20251001", 40, 10, "end_turn"));
        var captor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);

        service.understand(userId, "vpa:paytmqr12345", Transaction.Type.EXPENSE,
                "UPI-PRIYA SHARMA-paytmqr12345@paytm-UPI");

        verify(llmClient).complete(captor.capture());
        assertThat(captor.getValue().messages().get(0).content()).isEqualTo("UPI-[name]-[redacted-id]-UPI");
    }

    @Test
    void understand_notAvailable_returnsEmptyWithoutCallingLlm() {
        when(availabilityGuard.categorizationAvailableFor(userId)).thenReturn(false);

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void understand_keyThatNamesNoOnePayee_asksButNeverReadsOrWritesTheSharedCache() {
        // This cache is shared by every user: whichever shop's narration reached it first would
        // describe the gateway's id, or a cut id, for everyone.
        ToolUse toolUse = new ToolUse("t1", "UNDERSTAND_MERCHANT", Map.of("understanding", "An online travel agency"));
        when(llmClient.complete(any())).thenReturn(new LlmCompletion(null, List.of(toolUse),
                "claude-haiku-4-5-20251001", 40, 10, "tool_use"));

        for (String key : List.of("vpa:pg.razorpay", "cut:sampleqr1111111", "masked:.payu@shopcobk")) {
            Optional<String> result = service.understand(userId, key, Transaction.Type.INCOME,
                    "UPI-RAZORPAY-PG.RAZORPAY@SAMPLEBANK-SMPL0XXXXXX-111111111111-SAMPLETRAVELREFUNDX1");
            assertThat(result).contains("An online travel agency");
        }
        verifyNoInteractions(understandingRepository);
        verify(aiAuditLogRepository, times(3)).save(any(AiAuditLog.class));
    }

    @Test
    void understand_llmThrows_writesFailureAuditAndReturnsEmptyWithoutCaching() {
        when(understandingRepository.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());
        when(llmClient.complete(any())).thenThrow(new RuntimeException("upstream error"));

        Optional<String> result = service.understand(userId, "vpa:headsupfortails", Transaction.Type.EXPENSE, "UPI/HUFT/...");

        assertThat(result).isEmpty();
        verify(aiAuditLogRepository).save(argThat(log -> log.getError() != null));
        verify(understandingRepository, never()).upsert(any(), any(), any(), any(), any());
    }
}
