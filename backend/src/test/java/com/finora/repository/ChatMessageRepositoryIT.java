package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code countUserMessagesSince} against real Postgres, not a mock -- {@code
 * FynChatOrchestrationServiceTest} only ever exercises this query through a Mockito stub, which
 * proves the Free-tier daily cap's threshold logic but nothing about whether the JPQL subquery
 * itself (joining {@link ChatMessage} to {@link ChatConversation} on a plain scalar column, since
 * neither entity declares a JPA relationship to the other) actually compiles to correct SQL. Same
 * reasoning as {@link AiAuditLogRepositoryIT} for the cost-governance queries.
 */
class ChatMessageRepositoryIT extends AbstractIntegrationTest {

    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private ChatConversationRepository conversationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // chat_messages/chat_conversations aren't among AbstractIntegrationTest's own cleaned-up
    // shared queues -- same shared-table leakage risk AiAuditLogRepositoryIT's own doc comment
    // describes, since countUserMessagesSince has no lower bound other than "since", and a leaked
    // row from an earlier test class with a recent enough createdAt would inflate this class's own
    // counts. Deleting outright is safe: no phase has shipped that would leave real rows to lose.
    @BeforeEach
    void emptyChatTables() {
        jdbcTemplate.update("DELETE FROM chat_messages");
        jdbcTemplate.update("DELETE FROM chat_conversations");
    }

    private UUID newConversation(UUID userId) {
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        return conversationRepository.save(conversation).getId();
    }

    /** {@code created_at} has a Java-side default of {@code Instant.now()} and no public setter
     *  (by design -- server-assigned) -- overwritten directly, same technique
     *  AiAuditLogRepositoryIT's own {@code row()} helper uses, so boundary tests can place a
     *  message precisely in or out of the "since" window. */
    private void saveMessage(UUID conversationId, String role, Instant createdAt) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent("test message");
        ChatMessage saved = messageRepository.save(message);
        jdbcTemplate.update("UPDATE chat_messages SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(createdAt), saved.getId());
    }

    @Test
    void countsOnlyUserRoleMessagesWithinTheWindow() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = newConversation(userId);
        Instant now = Instant.now();
        saveMessage(conversationId, ChatMessage.ROLE_USER, now.minusSeconds(3600));
        saveMessage(conversationId, ChatMessage.ROLE_ASSISTANT, now.minusSeconds(3500));
        saveMessage(conversationId, ChatMessage.ROLE_USER, now.minusSeconds(60));

        long count = messageRepository.countUserMessagesSince(userId, now.minusSeconds(86_400));

        assertThat(count).isEqualTo(2); // the two USER rows, not the ASSISTANT one
    }

    @Test
    void excludesMessagesOlderThanTheSinceBoundary() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = newConversation(userId);
        Instant now = Instant.now();
        saveMessage(conversationId, ChatMessage.ROLE_USER, now.minusSeconds(90_000)); // > 24h ago
        saveMessage(conversationId, ChatMessage.ROLE_USER, now.minusSeconds(60)); // within 24h

        long count = messageRepository.countUserMessagesSince(userId, now.minusSeconds(86_400));

        assertThat(count).isEqualTo(1);
    }

    /** The whole point of the subquery -- {@code ChatMessage} carries no {@code userId} of its
     *  own, only {@code conversationId}, so a naive query with no join to {@code
     *  ChatConversation} could not scope by user at all. This proves it actually does. */
    @Test
    void doesNotCountAnotherUsersMessagesEvenInTheSameTimeWindow() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID myConversation = newConversation(userId);
        UUID otherConversation = newConversation(otherUserId);
        Instant now = Instant.now();
        saveMessage(myConversation, ChatMessage.ROLE_USER, now.minusSeconds(60));
        saveMessage(otherConversation, ChatMessage.ROLE_USER, now.minusSeconds(60));
        saveMessage(otherConversation, ChatMessage.ROLE_USER, now.minusSeconds(30));

        long count = messageRepository.countUserMessagesSince(userId, now.minusSeconds(86_400));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void zeroWhenTheUserHasNeverSentAMessage() {
        long count = messageRepository.countUserMessagesSince(UUID.randomUUID(), Instant.now().minusSeconds(86_400));

        assertThat(count).isZero();
    }
}
