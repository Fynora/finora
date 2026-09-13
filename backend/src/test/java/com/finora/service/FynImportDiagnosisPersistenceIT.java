package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.HeldStatementDetailDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeldStatement#recordAiSuggestion}/{@link HeldStatementService#recordAiSuggestion} against
 * real Postgres (V203) -- {@code HeldStatementSchemaIT} already proves the new columns validate
 * against the entity mapping, but not that a write/read round-trip through the actual service
 * method works, or that {@link HeldStatementDetailDto} surfaces the result. This is the persistence
 * half of Fyn Phase 2; {@code FynImportDiagnosisServiceTest} covers the LLM-calling half with mocks.
 */
class FynImportDiagnosisPersistenceIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementService heldStatementService;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private HeldStatement seed(String heldId) {
        User owner = new User();
        owner.setEmail("held-ai-" + UUID.randomUUID() + "@example.com");
        owner.setPasswordHash("irrelevant-for-this-test");
        owner.setFullName("Held AI Suggestion Test");
        owner = userRepository.save(owner);

        ImportJob job = importJobRepository.save(new ImportJob(
                owner.getId(), "s.pdf", "h-" + UUID.randomUUID(), "objects/k1", "PDF"));
        return heldStatementRepository.save(new HeldStatement(heldId, job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree"));
    }

    @Test
    void recordsAndSurfacesTheSuggestion() {
        HeldStatement held = seed("HLD-2026-AI-000001");
        UUID admin = held.getUserId(); // any real user id satisfies the FK; identity isn't asserted here

        heldStatementService.recordAiSuggestion(admin, held.getHeldId(),
                "Likely a COUNT_MISMATCH from a merged header row -- check PdfTableLocator's "
                        + "header-detection heuristic for this bank's layout.");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getAiSuggestedDiagnosis()).contains("PdfTableLocator");
        assertThat(reloaded.getAiSuggestedDiagnosisAt()).isNotNull();

        HeldStatementDetailDto detail = heldStatementService.detail(held.getHeldId());
        assertThat(detail.summary().aiSuggestedDiagnosis()).contains("PdfTableLocator");
        assertThat(detail.summary().aiSuggestedDiagnosisAt()).isNotNull();
    }

    @Test
    void aSecondSuggestionReplacesTheFirstWholesale() {
        HeldStatement held = seed("HLD-2026-AI-000002");
        UUID admin = held.getUserId();

        heldStatementService.recordAiSuggestion(admin, held.getHeldId(), "First guess.");
        heldStatementService.recordAiSuggestion(admin, held.getHeldId(), "Second, better guess.");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getAiSuggestedDiagnosis()).isEqualTo("Second, better guess.");
    }

    @Test
    void doesNotTouchEngineerNotes() {
        HeldStatement held = seed("HLD-2026-AI-000003");
        UUID admin = held.getUserId();
        heldStatementService.addNotes(admin, held.getHeldId(), "Engineer's own investigation notes.");

        heldStatementService.recordAiSuggestion(admin, held.getHeldId(), "Fyn's suggestion.");

        HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
        assertThat(reloaded.getEngineerNotes()).isEqualTo("Engineer's own investigation notes.");
        assertThat(reloaded.getAiSuggestedDiagnosis()).isEqualTo("Fyn's suggestion.");
    }
}
