package com.finora.service;

import com.finora.config.FynProperties;
import com.finora.dto.FynChatDtos;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FynChatOrchestrationServiceTest {

    private FynAvailabilityGuard availabilityGuard;
    private EntitlementService entitlementService;
    private ChatConversationRepository conversationRepository;
    private ChatMessageRepository messageRepository;
    private AiAuditLogRepository aiAuditLogRepository;
    private LlmClient llmClient;
    private FynChatTool stubTool;
    private FynProperties properties;
    private FynChatOrchestrationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        availabilityGuard = mock(FynAvailabilityGuard.class);
        entitlementService = mock(EntitlementService.class);
        conversationRepository = mock(ChatConversationRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        aiAuditLogRepository = mock(AiAuditLogRepository.class);
        llmClient = mock(LlmClient.class);
        stubTool = mock(FynChatTool.class);
        properties = new FynProperties();
        when(stubTool.name()).thenReturn("GET_BALANCE");
        when(stubTool.toLlmTool()).thenReturn(new LlmClient.LlmTool("GET_BALANCE", "d", Map.of()));

        when(availabilityGuard.chatAvailableFor(userId)).thenReturn(true);
        // PREMIUM by default -- most tests exercise the chat loop itself, not the Free-tier
        // question cap, and PREMIUM keeps that cap out of their way without every test needing to
        // stub it. Tests that DO exercise the cap override this explicitly.
        when(entitlementService.planCodeFor(userId)).thenReturn("PREMIUM");
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

        service = new FynChatOrchestrationService(availabilityGuard, entitlementService, conversationRepository,
                messageRepository, aiAuditLogRepository, llmClient, List.of(stubTool), properties);
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
        var captor = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture()); // user turn + fallback assistant reply
        assertThat(captor.getAllValues().get(1).getRole()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
    }

    @Test
    void aBareRuntimeExceptionFromTheLlmClientStillWritesAnAuditLogRowAndPersistsAFallbackReply() {
        when(llmClient.complete(any())).thenThrow(new IllegalStateException("no api key"));

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi")).isInstanceOf(ApiException.class);

        verify(aiAuditLogRepository).save(any());
        // user turn + a fallback assistant reply -- without the fallback, this conversation's next
        // message would send two consecutive user-role turns to Anthropic and get rejected outright.
        var captor = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage fallback = captor.getAllValues().get(1);
        assertThat(fallback.getRole()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(fallback.getContent()).isNotBlank();
    }

    @Test
    void aBlankFinalReplyIsRejectedButStillGetsAFallbackAssistantReplyPersisted() {
        when(llmClient.complete(any())).thenReturn(textCompletion("   "));

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");

        var captor = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        ChatMessage fallback = captor.getAllValues().get(1);
        assertThat(fallback.getRole()).isEqualTo(ChatMessage.ROLE_ASSISTANT);
        assertThat(fallback.getContent()).isNotBlank();
    }

    @Test
    void aFailureDoesNotLeaveTwoConsecutiveUserTurnsForTheNextMessageOnTheSameConversation() {
        when(llmClient.complete(any())).thenThrow(new IllegalStateException("boom"));
        assertThatThrownBy(() -> service.sendMessage(userId, null, "first"));

        // Simulate the repository now containing what was actually persisted above, then send a
        // second message on the same conversation -- history handed to the LLM must alternate.
        var conversationIdCaptor = org.mockito.ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationRepository).save(conversationIdCaptor.capture());
        UUID conversationId = conversationIdCaptor.getValue().getId();
        ChatConversation existing = conversationIdCaptor.getValue();
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(existing));

        ChatMessage userTurn = new ChatMessage();
        userTurn.setConversationId(conversationId);
        userTurn.setRole(ChatMessage.ROLE_USER);
        userTurn.setContent("first");
        ChatMessage fallbackTurn = new ChatMessage();
        fallbackTurn.setConversationId(conversationId);
        fallbackTurn.setRole(ChatMessage.ROLE_ASSISTANT);
        fallbackTurn.setContent("Sorry, I couldn't answer that. Please try asking again.");
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(userTurn, fallbackTurn));

        reset(llmClient);
        when(llmClient.complete(any())).thenReturn(textCompletion("second reply"));

        var result = service.sendMessage(userId, conversationId, "second");

        var requestCaptor = org.mockito.ArgumentCaptor.forClass(LlmClient.LlmRequest.class);
        verify(llmClient).complete(requestCaptor.capture());
        List<String> roles = requestCaptor.getValue().messages().stream()
                .map(LlmClient.LlmMessage::role).toList();
        for (int i = 1; i < roles.size(); i++) {
            assertThat(roles.get(i)).isNotEqualTo(roles.get(i - 1));
        }
        assertThat(result.reply()).isEqualTo("second reply");
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

    // -- Free-tier daily question cap (2026-09-14 costing decision) --

    @Test
    void aFreeUserUnderTheDailyLimitCanStillSendAMessage() {
        when(entitlementService.planCodeFor(userId)).thenReturn("FREE");
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(2L); // limit is 3
        when(llmClient.complete(any())).thenReturn(textCompletion("Your balance is fine."));

        var result = service.sendMessage(userId, null, "how am I doing?");

        assertThat(result.reply()).isEqualTo("Your balance is fine.");
    }

    @Test
    void aFreeUserAtTheDailyLimitIsRefusedBeforeAnyLlmCallOrPersistence() {
        when(entitlementService.planCodeFor(userId)).thenReturn("FREE");
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(3L); // limit is 3

        assertThatThrownBy(() -> service.sendMessage(userId, null, "one more question"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(llmClient, never()).complete(any());
        verify(messageRepository, never()).save(any());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void aFreeUserPastTheDailyLimitIsAlsoRefused() {
        when(entitlementService.planCodeFor(userId)).thenReturn("FREE");
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi")).isInstanceOf(ApiException.class);
    }

    @Test
    void plusAndPremiumUsersAreNeverBlockedByTheDailyQuestionCountRegardlessOfCount() {
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(500L);
        when(llmClient.complete(any())).thenReturn(textCompletion("ok"));

        when(entitlementService.planCodeFor(userId)).thenReturn("PLUS");
        assertThat(service.sendMessage(userId, null, "hi").reply()).isEqualTo("ok");

        when(entitlementService.planCodeFor(userId)).thenReturn("PREMIUM");
        assertThat(service.sendMessage(userId, null, "hi").reply()).isEqualTo("ok");
    }

    /** {@link EntitlementService#planCodeFor} returns null for a user with no active/trial
     *  subscription -- shouldn't happen in practice (the caller already passed a FYN_CHAT
     *  entitlement check to get here), but the cap must fail toward restrictive, not toward
     *  "unlimited," if it ever does. */
    @Test
    void anUnrecognizedOrMissingPlanCodeIsTreatedAsCapped() {
        when(entitlementService.planCodeFor(userId)).thenReturn(null);
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(3L);

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theFreeDailyQuestionLimitIsConfigurable() {
        properties.setFreeDailyQuestionLimit(1);
        when(entitlementService.planCodeFor(userId)).thenReturn("FREE");
        when(messageRepository.countUserMessagesSince(any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.sendMessage(userId, null, "hi")).isInstanceOf(ApiException.class);
    }

    private ChatMessage message(String role, String content) {
        ChatMessage m = new ChatMessage();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    @Test
    void latestConversationHistoryReturnsNoneForAUserWithNoConversationsYet() {
        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

        var result = service.latestConversationHistory(userId);

        assertThat(result.conversationId()).isNull();
        assertThat(result.turns()).isEmpty();
    }

    @Test
    void latestConversationHistoryReturnsTheMostRecentConversationsTurnsInOrder() {
        ChatConversation mostRecent = new ChatConversation();
        ReflectionTestUtils.setField(mostRecent, "id", UUID.randomUUID());
        // findByUserIdOrderByUpdatedAtDesc already orders by recency -- this test only needs to
        // prove the FIRST one wins, not re-verify the query's own ORDER BY.
        ChatMessage userMsg = message(ChatMessage.ROLE_USER, "what's my balance?");
        ChatMessage assistantMsg = message(ChatMessage.ROLE_ASSISTANT, "Your balance is ₹50,000.");
        when(conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(mostRecent));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(mostRecent.getId()))
                .thenReturn(List.of(userMsg, assistantMsg));

        var result = service.latestConversationHistory(userId);

        assertThat(result.conversationId()).isEqualTo(mostRecent.getId());
        assertThat(result.turns()).containsExactly(
                new FynChatDtos.ChatTurnDto(userMsg.getId(), "user", "what's my balance?", null),
                new FynChatDtos.ChatTurnDto(assistantMsg.getId(), "assistant", "Your balance is ₹50,000.", null));
    }

    private ChatConversation conversation(UUID owner) {
        ChatConversation c = new ChatConversation();
        c.setUserId(owner);
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    @Test
    void setMessageFeedbackSavesHelpfulOnTheCallersOwnAssistantMessage() {
        ChatConversation owned = conversation(userId);
        ChatMessage assistantMsg = message(ChatMessage.ROLE_ASSISTANT, "reply");
        assistantMsg.setConversationId(owned.getId());
        when(messageRepository.findById(assistantMsg.getId())).thenReturn(Optional.of(assistantMsg));
        when(conversationRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        service.setMessageFeedback(userId, assistantMsg.getId(), ChatMessage.FEEDBACK_HELPFUL);

        assertThat(assistantMsg.getFeedback()).isEqualTo(ChatMessage.FEEDBACK_HELPFUL);
        verify(messageRepository).save(assistantMsg);
    }

    @Test
    void setMessageFeedbackWithNullClearsAPreviousRating() {
        ChatConversation owned = conversation(userId);
        ChatMessage assistantMsg = message(ChatMessage.ROLE_ASSISTANT, "reply");
        assistantMsg.setConversationId(owned.getId());
        assistantMsg.setFeedback(ChatMessage.FEEDBACK_HELPFUL);
        when(messageRepository.findById(assistantMsg.getId())).thenReturn(Optional.of(assistantMsg));
        when(conversationRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        service.setMessageFeedback(userId, assistantMsg.getId(), null);

        assertThat(assistantMsg.getFeedback()).isNull();
    }

    @Test
    void setMessageFeedbackRejectsAnUnrecognizedValue() {
        assertThatThrownBy(() -> service.setMessageFeedback(userId, UUID.randomUUID(), "LOVE_IT"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(messageRepository, never()).findById(any());
    }

    @Test
    void setMessageFeedbackRejectsAUserRoleMessage() {
        ChatConversation owned = conversation(userId);
        ChatMessage userMsg = message(ChatMessage.ROLE_USER, "my own question");
        userMsg.setConversationId(owned.getId());
        when(messageRepository.findById(userMsg.getId())).thenReturn(Optional.of(userMsg));

        assertThatThrownBy(() -> service.setMessageFeedback(userId, userMsg.getId(), ChatMessage.FEEDBACK_HELPFUL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(messageRepository, never()).save(any());
    }

    @Test
    void setMessageFeedback404sForANonexistentMessage() {
        UUID messageId = UUID.randomUUID();
        when(messageRepository.findById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setMessageFeedback(userId, messageId, ChatMessage.FEEDBACK_HELPFUL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Same not-found-not-forbidden reasoning as sendMessage's own conversation-ownership check --
     *  confirming "that message belongs to someone else" would itself be a small information leak. */
    @Test
    void setMessageFeedback404sForAMessageBelongingToAnotherUsersConversation() {
        ChatConversation someoneElses = conversation(UUID.randomUUID());
        ChatMessage assistantMsg = message(ChatMessage.ROLE_ASSISTANT, "reply");
        assistantMsg.setConversationId(someoneElses.getId());
        when(messageRepository.findById(assistantMsg.getId())).thenReturn(Optional.of(assistantMsg));
        when(conversationRepository.findById(someoneElses.getId())).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.setMessageFeedback(userId, assistantMsg.getId(), ChatMessage.FEEDBACK_HELPFUL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(messageRepository, never()).save(any());
    }
}
