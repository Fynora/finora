package com.finora.repository;

import com.finora.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    /** AccountPurgeSweepService -- must run AFTER {@code chatMessageRepository.deleteByUserId}
     *  (that call's own subquery needs these rows to still exist). {@code user_id} has no FK at
     *  all, so nothing else ever removes this table's rows for a purged user. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChatConversation c WHERE c.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
