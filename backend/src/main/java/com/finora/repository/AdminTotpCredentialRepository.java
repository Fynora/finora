package com.finora.repository;

import com.finora.entity.AdminTotpCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AdminTotpCredentialRepository extends JpaRepository<AdminTotpCredential, UUID> {
    Optional<AdminTotpCredential> findByUserId(UUID userId);
    /** True only for a credential that finished enrolment (a scanned-but-unconfirmed secret does
     *  not protect logins, so it does not count). One indexed lookup on the unique user_id. */
    boolean existsByUserIdAndEnabledTrue(UUID userId);
    void deleteByUserId(UUID userId);
}
