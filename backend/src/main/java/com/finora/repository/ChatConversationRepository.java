package com.finora.repository;

import com.finora.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
