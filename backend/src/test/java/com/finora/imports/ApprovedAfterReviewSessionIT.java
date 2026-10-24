package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2b (spec §5.3): the review screen says "Approved. Nothing is saved until you confirm." only
 * for a session a reviewer released after an accuracy hold. The released job is COMPLETED and still
 * points at its held statement; every other case must answer false.
 */
class ApprovedAfterReviewSessionIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("approved-after-review-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Approved After Review IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportSession stagedSession(UUID ownerId) {
        ImportSession session = new ImportSession();
        session.setUserId(ownerId);
        session.setFileName("statement.csv");
        session.setFileContent(new byte[] {1, 2, 3});
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(ImportSession.STATUS_STAGED);
        return importSessionRepository.save(session);
    }

    private ImportJob jobFor(UUID ownerId, UUID sessionId) {
        ImportJob job = new ImportJob(ownerId, "statement.csv", "hash-" + sessionId, "objects/key-" + sessionId, "CSV");
        job.markClaimed("worker", Instant.now());
        return importJobRepository.save(job);
    }

    private HeldStatement heldFor(ImportJob job, UUID ownerId) {
        return heldStatementRepository.save(new HeldStatement("IT-" + System.nanoTime(), job.getId(), ownerId,
                job.getObjectKey(), "test trigger summary"));
    }

    @Test
    @Transactional
    void approvedWhenTheHoldWasReleased() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        job.holdForTrustReview(session.getId(), heldFor(job, owner.getId()).getId(), Instant.now());
        job.releaseAfterTrustReview(Instant.now());
        importJobRepository.save(job);

        assertThat(importSessionService.wasApprovedAfterReview(session.getId())).isTrue();
    }

    @Test
    @Transactional
    void notApprovedWhileStillHeld() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        job.holdForTrustReview(session.getId(), heldFor(job, owner.getId()).getId(), Instant.now());
        importJobRepository.save(job);

        assertThat(importSessionService.wasApprovedAfterReview(session.getId())).isFalse();
    }

    @Test
    @Transactional
    void notApprovedWhenTheHoldWasRejected() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        job.holdForTrustReview(session.getId(), heldFor(job, owner.getId()).getId(), Instant.now());
        job.rejectAfterTrustReview("IMPORT_TRUST_REVIEW_REJECTED", Instant.now());
        importJobRepository.save(job);

        assertThat(importSessionService.wasApprovedAfterReview(session.getId())).isFalse();
    }

    @Test
    @Transactional
    void notApprovedForASessionThatWasNeverHeld() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());

        assertThat(importSessionService.wasApprovedAfterReview(session.getId())).isFalse();
    }
}
