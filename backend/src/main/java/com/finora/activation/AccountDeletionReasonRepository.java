package com.finora.activation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountDeletionReasonRepository extends JpaRepository<AccountDeletionReason, UUID> {
}
