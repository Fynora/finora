package com.finora.imports.send;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StatementSendCodeRepository extends JpaRepository<StatementSendCode, UUID> {

    /** The user's most recent code, which is the only one the phone ever shows. */
    Optional<StatementSendCode> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    /** AccountPurgeSweepService -- hard delete. The purge anonymises the user row rather than
     *  deleting it, so V293's ON DELETE CASCADE never fires on its own. */
    void deleteByUserId(UUID userId);
}
