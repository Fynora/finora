package com.finora.dto;

import com.finora.entity.HeldStatement;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One held statement as an operator sees it.
 *
 * <p>Carries no statement content and no row data -- the Held ID, why it fired, and where it is in
 * the review. That is deliberate and follows {@link HeldImportDto}'s own split: browsing a queue
 * should not put customer financial detail on screen, so listing is cheap and unaudited while
 * anything that opens the document is audited separately.
 *
 * @param userId a bare id, same reason {@link HeldImportDto#userId} is: this controller never joins
 *               to a user's contact details, so no email or phone can reach this screen even
 *               indirectly.
 * @param bankName the snapshot from V150, not a live read -- see {@code HeldStatement}'s own doc for
 *                 why {@code import_sessions}, the only other source, cannot be joined instead. Null
 *                 when the parser could not name a bank.
 * @param triggerSummary every trust condition that fired, rendered by {@code TrustPredicate}. Not a
 *                       new signal -- a sentence about evidence the pipeline already computed.
 * @param holdReasonCategories the machine-readable tag behind each of {@code triggerSummary}'s
 *                             sentences (e.g. {@code COUNT_MISMATCH}) -- see {@code
 *                             TrustPredicate.Category}'s own doc for why this exists instead of
 *                             parsing the prose back apart.
 * @param aiSuggestedDiagnosis Fyn's most recent suggested root cause (Phase 2, plan §6) -- a
 *                             separate field from {@code engineerNotes}/{@code rootCause}, not a
 *                             replacement for either; see {@code HeldStatement.recordAiSuggestion}.
 */
public record HeldStatementDto(
        UUID id,
        String heldId,
        UUID importJobId,
        UUID userId,
        String bankName,
        String status,
        String triggerSummary,
        String reliabilityStatus,
        String textSource,
        Boolean headerReconstructionUncertain,
        String parserVersion,
        List<String> holdReasonCategories,
        UUID assignedEngineerId,
        String engineerNotes,
        String rootCause,
        String fixReference,
        Boolean falsePositive,
        Instant createdAt,
        Instant assignedAt,
        Instant readyAt,
        Instant resolvedAt,
        String aiSuggestedDiagnosis,
        Instant aiSuggestedDiagnosisAt) {

    public static HeldStatementDto from(HeldStatement held) {
        return new HeldStatementDto(
                held.getId(),
                held.getHeldId(),
                held.getImportJobId(),
                held.getUserId(),
                held.getBankName(),
                held.getStatus().name(),
                held.getTriggerSummary(),
                held.getReliabilityStatus(),
                held.getTextSource(),
                held.getHeaderReconstructionUncertain(),
                held.getParserVersion(),
                held.getHoldReasonCategories(),
                held.getAssignedEngineerId(),
                held.getEngineerNotes(),
                held.getRootCause(),
                held.getFixReference(),
                held.getFalsePositive(),
                held.getCreatedAt(),
                held.getAssignedAt(),
                held.getReadyAt(),
                held.getResolvedAt(),
                held.getAiSuggestedDiagnosis(),
                held.getAiSuggestedDiagnosisAt());
    }
}
