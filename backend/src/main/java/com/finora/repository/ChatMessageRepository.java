package com.finora.repository;

import com.finora.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    /** AccountPurgeSweepService -- must run BEFORE {@code chatConversationRepository.deleteByUserId}.
     *  {@code ChatMessage} carries no {@code userId} of its own (only {@code conversationId}, same
     *  reason {@code countUserMessagesSince} above needs a subquery), and neither column has an FK,
     *  so nothing else ever removes these rows for a purged user. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatMessage m WHERE m.conversationId IN "
            + "(SELECT c.id FROM ChatConversation c WHERE c.userId = :userId)")
    int deleteByUserId(@Param("userId") UUID userId);

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
