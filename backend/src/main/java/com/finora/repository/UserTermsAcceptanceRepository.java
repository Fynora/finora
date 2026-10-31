package com.finora.repository;

import com.finora.entity.UserTermsAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserTermsAcceptanceRepository extends JpaRepository<UserTermsAcceptance, UUID> {

    List<UserTermsAcceptance> findByUserIdOrderByAcceptedAtAsc(UUID userId);
}
