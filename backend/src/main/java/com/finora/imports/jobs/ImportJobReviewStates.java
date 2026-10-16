package com.finora.imports.jobs;

import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a COMPLETED import's review stands: still open, already imported, or expired.
 *
 * <p>A queued import reaches COMPLETED once its rows are staged, and the job never changes again.
 * Its staged rows do: the user confirms them, or the session sweep deletes them 48 hours after they
 * were ready ({@code ImportSessionService.sweepExpiredSessions}). Both apps read COMPLETED as "Ready
 * to review" and offered "Review this import" for as long as the job existed -- after the rows had
 * been imported, and after they had expired, when opening the review answered "This import session
 * has expired" and then "Import session not found". An unconfirmed statement simply dropped out of
 * every list, because the apps leave completed jobs to "Continue previous import", which only lists
 * live sessions. Gate 1 promises that every import ends somewhere the user can see; this is the end
 * that was missing.
 *
 * <p>Decided from the session row and the user's statements, never from the job alone:
 * <ul>
 *   <li><b>IMPORTED</b> -- the session was confirmed, or a statement with the same file
 *       ({@code contentHash}) was imported after this job was created. The sweep deletes confirmed
 *       sessions too once they pass their expiry, so the session row alone cannot answer this for an
 *       older import; the file can. A statement imported and later deleted still counts: it was
 *       imported, and "nothing was imported" would be false.</li>
 *   <li><b>OPEN</b> -- the session is still staged and not past its expiry: the review opens.</li>
 *   <li><b>EXPIRED</b> -- anything else: the rows are past their expiry or already deleted (by the
 *       sweep, or by the user cancelling the review), and nothing was imported. The two cannot be
 *       told apart once the rows are gone, so the clients' copy is true of both.</li>
 * </ul>
 * Null for any job that is not COMPLETED.
 */
@Component
public class ImportJobReviewStates {

    private final ImportSessionRepository sessions;
    private final StatementImportRepository statementImports;

    public ImportJobReviewStates(ImportSessionRepository sessions, StatementImportRepository statementImports) {
        this.sessions = sessions;
        this.statementImports = statementImports;
    }

    /** The review state of each COMPLETED job among {@code jobs}, keyed by job id. Two queries
     *  however many jobs, so the recent-imports list does not pay one lookup per row. */
    @Transactional(readOnly = true)
    public Map<UUID, ImportJobDto.ReviewState> of(Collection<ImportJob> jobs, Instant now) {
        List<ImportJob> completed = jobs.stream()
                .filter(job -> job.getStatus() == ImportJob.Status.COMPLETED)
                .toList();
        if (completed.isEmpty()) return Map.of();

        Map<UUID, ImportSession> sessionsById = sessions.findAllById(completed.stream()
                        .map(ImportJob::getImportSessionId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(ImportSession::getId, Function.identity()));

        Map<UUID, List<FileImport>> importsByUser = new HashMap<>();
        completed.stream().collect(Collectors.groupingBy(ImportJob::getUserId)).forEach((userId, ofUser) -> {
            List<String> hashes = ofUser.stream()
                    .map(ImportJob::getContentHash).filter(Objects::nonNull).distinct().toList();
            importsByUser.put(userId, hashes.isEmpty() ? List.of()
                    : statementImports.findImportTimesOfFiles(userId, hashes).stream()
                            .map(row -> new FileImport((String) row[0], row[1] == null ? null
                                    : Instant.ofEpochMilli(((Number) row[1]).longValue())))
                            .toList());
        });

        Map<UUID, ImportJobDto.ReviewState> states = new HashMap<>();
        for (ImportJob job : completed) {
            states.put(job.getId(), stateOf(job, sessionsById.get(job.getImportSessionId()),
                    importsByUser.getOrDefault(job.getUserId(), List.of()), now));
        }
        return states;
    }

    /** {@link #of(Collection, Instant)} for one job; null when it is not COMPLETED. */
    @Transactional(readOnly = true)
    public ImportJobDto.ReviewState of(ImportJob job, Instant now) {
        return of(List.of(job), now).get(job.getId());
    }

    /** One import of a file: its content hash and when it was imported. */
    record FileImport(String contentHash, Instant importedAt) {}

    static ImportJobDto.ReviewState stateOf(ImportJob job, ImportSession session,
                                            List<FileImport> importsOfUser, Instant now) {
        if (session != null && ImportSession.STATUS_CONFIRMED.equals(session.getStatus())) {
            return ImportJobDto.ReviewState.IMPORTED;
        }
        boolean fileImportedSince = job.getContentHash() != null && importsOfUser.stream().anyMatch(i ->
                job.getContentHash().equals(i.contentHash())
                        && i.importedAt() != null
                        && i.importedAt().isAfter(job.getCreatedAt()));
        if (fileImportedSince) return ImportJobDto.ReviewState.IMPORTED;
        if (session != null && ImportSession.STATUS_STAGED.equals(session.getStatus())
                && session.getExpiresAt() != null && session.getExpiresAt().isAfter(now)) {
            return ImportJobDto.ReviewState.OPEN;
        }
        return ImportJobDto.ReviewState.EXPIRED;
    }
}
