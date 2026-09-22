package com.finora.repository;

import com.finora.entity.AdminTotpCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AdminTotpCredentialRepository extends JpaRepository<AdminTotpCredential, UUID> {
    Optional<AdminTotpCredential> findByUserId(UUID userId);
    /** True only for a credential that finished enrolment (a scanned-but-unconfirmed secret does
     *  not protect logins, so it does not count). One indexed lookup on the unique user_id. */
    boolean existsByUserIdAndEnabledTrue(UUID userId);
    void deleteByUserId(UUID userId);

    /**
     * Records that a code for {@code step} was accepted, but only if no code for this step or a
     * later one has been -- returns 1 if this call claimed it, 0 if it was already taken (or the
     * user has no credential). One statement, so two simultaneous requests carrying the same code
     * cannot both succeed: the row lock serialises them and the loser re-evaluates the WHERE
     * against the winner's write. This return value, not the value read earlier, is what decides
     * whether a code counts.
     */
    @Modifying
    @Query("UPDATE AdminTotpCredential c SET c.lastUsedStep = :step "
            + "WHERE c.userId = :userId AND (c.lastUsedStep IS NULL OR c.lastUsedStep < :step)")
    int claimStep(@Param("userId") UUID userId, @Param("step") long step);
}
