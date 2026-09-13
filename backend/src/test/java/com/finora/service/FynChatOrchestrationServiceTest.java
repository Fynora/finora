package com.finora.service;

import com.finora.entity.AiAuditLog;
import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import com.finora.exception.ApiException;
import com.finora.integrations.anthropic.LlmClient;
import com.finora.integrations.anthropic.LlmClient.LlmCompletion;
import com.finora.integrations.anthropic.LlmClient.ToolUse;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.ChatConversationRepository;
import com.finora.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FynChatOrchestrationServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private ChatConversationRepository conversationRepository;
    private ChatMessageRepository messageRepository;
    private AiAuditLogRepository aiAuditLogRepository;
    private LlmClient llmClient;
    private FynChatTool stubTool;
    private FynChatOrchestrationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        conversationRepository = mock(ChatConversationRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        llmClient = mock(LlmClient.class);
        stubTool = mock(FynChatTool.class);
        when(stubTool.name()).thenReturn("GET_BALANCE");
        when(stubTool.toLlmTool()).thenReturn(new LlmClient.LlmTool("GET_BALANCE", "d", Map.of()));

        when(availabilityGuard.chatAvailableFor(userId)).thenReturn(true);
        // Every save() echoes its argument back with a freshly assigned id, mirroring what a real
        // JPA save() does for a @GeneratedValue entity -- ReflectionTestUtils because neither
        // entity exposes a public setId(), by design (the id is server-assigned, never client-set).
        when(conversationRepository.save(any())).thenAnswer(inv -> {
            ChatConversation c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(messageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        service = new FynChatOrchestrationService(availabilityGuard, conversationRepository,
                messageRepository, aiAuditLogRepository, llmClient, List.of(stubTool));
    }

    private static LlmCompletion textCompletion(String text) {
        return new LlmCompletion(text, List.of(), "claude-haiku-4-5-20251001", 100, 20, "end_turn");
    }

    private static LlmCompletion toolUseCompletion(String toolName) {
        return new LlmCompletion(null, List.of(new ToolUse("toolu_01", toolName, Map.of())),
                "claude-haiku-4-5-20251001", 100, 20, "tool_use");
    }

    @Test
    void refusesWhenAvailabilityGuardSaysNo() {
        when(availabilityGuard.chatAvailableFor(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(llmClient, never()).complete(any());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void startsANewConversationAndReturnsTheFinalTextAnswer() {
        when(llmClient.complete(any())).thenReturn(textCompletion("Your balance is fine."));

        var result = service.sendMessage(userId, null, "how am I doing?");

        assertThat(result.reply()).isEqualTo("Your balance is fine.");
        assertThat(result.conversationId()).isNotNull();
        verify(conversationRepository, times(2)).save(any()); // create, then title/updatedAt on finish
        verify(messageRepository, times(2)).save(any()); // user turn, assistant turn
    }

    @Test
    void continuesAnExistingConversationOwnedByTheCaller() {
        ChatConversation existing = new ChatConversation();
        existing.setUserId(userId);
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(conversationRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(llmClient.complete(any())).thenReturn(textCompletion("Sure, here's an update."));

        var result = service.sendMessage(userId, existing.getId(), "and now?");

        assertThat(result.conversationId()).isEqualTo(existing.getId());
    }

    @Test
    void refusesAConversationBelongingToAnotherUserWithNotFoundNotForbidden() {
        ChatConversation someoneElses = new ChatConversation();
        someoneElses.setUserId(UUID.randomUUID());
        UUID conversationId = UUID.randomUUID();
        ReflectionTestUtils.setField(someoneElses, "id", conversationId);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.sendMessage(userId, conversationId, "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(llmClient, never()).complete(any());
    }

    @Test
    void aNonexistentConversationIdIsAlsoNotFound() {
        UUID conversationId = UUID.randomUUID();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendMessage(userId, conversationId, "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void executesARequestedToolAndFeedsTheResultBackForAFinalAnswer() {
        when(stubTool.execute(any(), any())).thenReturn("Current balance: ₹50,000");
        when(llmClient.complete(any()))
                .thenReturn(toolUseCompletion("GET_BALANCE"))
                .thenReturn(textCompletion("Your balance is ₹50,000."));

        var result = service.sendMessage(userId, null, "what's my balance?");

        assertThat(result.reply()).contains("50,000");
        verify(llmClient, times(2)).complete(any());
        verify(stubTool).execute(userId, Map.of());
        verify(aiAuditLogRepository, times(2)).save(any()); // one row per Anthropic call
    }

    @Test
    void anUnknownToolNameDoesNotCrashTheTurn() {
        when(llmClient.complete(any()))
                .thenReturn(toolUseCompletion("SOME_TOOL_THAT_DOES_NOT_EXIST"))
                .thenReturn(textCompletion("I couldn't look that up."));

        var result = service.sendMessage(userId, null, "what's my balance?");

        assertThat(result.reply()).isEqualTo("I couldn't look that up.");
    }

    @Test
    void aThrowingToolDoesNotCrashTheTurn() {
        when(stubTool.execute(any(), any())).thenThrow(new RuntimeException("db is down"));
        when(llmClient.complete(any()))
                .thenReturn(toolUseCompletion("GET_BALANCE"))
                .thenReturn(textCompletion("I couldn't check that right now."));

        var result = service.sendMessage(userId, null, "what's my balance?");

        assertThat(result.reply()).isEqualTo("I couldn't check that right now.");
    }

    @Test
    void stopsAfterMaxToolRoundsRatherThanLoopingForever() {
        when(stubTool.execute(any(), any())).thenReturn("some fact");
        when(llmClient.complete(any())).thenReturn(toolUseCompletion("GET_BALANCE")); // always wants another tool

        assertThatThrownBy(() -> service.sendMessage(userId, null, "what's my balance?"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        verify(llmClient, times(5)).complete(any()); // MAX_TOOL_ROUNDS, not unbounded
    }

    @Test
    void aBareRuntimeExceptionFromTheLlmClientStillWritesAnAuditLogRowAndDoesNotPersistAMessage() {
        when(llmClient.complete(any())).thenThrow(new IllegalStateException("no api key"));

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi")).isInstanceOf(ApiException.class);

        verify(aiAuditLogRepository).save(any());
        verify(messageRepository, times(1)).save(any()); // only the user's own turn, no assistant reply
    }

    @Test
    void aBlankFinalReplyIsRejectedRatherThanPersisted() {
        when(llmClient.complete(any())).thenReturn(textCompletion("   "));

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");

        verify(messageRepository, times(1)).save(any()); // user turn only
    }

    @Test
    void writesConversationIdOntoTheAuditLogRow() {
        when(llmClient.complete(any())).thenReturn(textCompletion("ok"));

        service.sendMessage(userId, null, "hi");

        var captor = org.mockito.ArgumentCaptor.forClass(AiAuditLog.class);
        verify(aiAuditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getConversationId()).isNotNull();
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void recordsWhichToolsWereUsedOnTheAssistantMessage() {
        when(stubTool.execute(any(), any())).thenReturn("fact");
        when(llmClient.complete(any()))
                .thenReturn(toolUseCompletion("GET_BALANCE"))
                .thenReturn(textCompletion("done"));

        service.sendMessage(userId, null, "hi");

        var captor = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage assistantRow = captor.getAllValues().get(1);
        assertThat(assistantRow.getRole()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(assistantRow.getToolCallsJson()).containsEntry("tools", List.of("GET_BALANCE"));
    }
}
