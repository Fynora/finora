package com.finora.repository;

import com.finora.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** Free plan's daily question cap (FynChatOrchestrationService) counts real user-asked
     *  questions, not {@code ai_audit_log} rows -- one question can cost several Anthropic calls
     *  across tool-call rounds, and counting those would silently under-deliver the promised
     *  3-a-day. {@code ChatMessage} carries no {@code userId} of its own (only {@code
     *  conversationId}), hence the subquery against {@code ChatConversation} rather than a direct
     *  join column. */
    @Query("SELECT COUNT(m) FROM ChatMessage m WHERE m.role = 'USER' AND m.createdAt >= :since "
            + "AND m.conversationId IN (SELECT c.id FROM ChatConversation c WHERE c.userId = :userId)")
    long countUserMessagesSince(@Param("userId") UUID userId, @Param("since") Instant since);
}
