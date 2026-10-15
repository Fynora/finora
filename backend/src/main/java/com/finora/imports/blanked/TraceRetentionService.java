package com.finora.imports.blanked;

import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Decides which held-item references no longer name a statement, for the private trace
 * repository's nightly retention job (see {@code TraceRetentionController}).
 *
 * <p>A reference is what {@link BlankedCopyService} writes into a trace's
 * {@code generatedFrom: held item <ref>} line: the import job's id, or a trust-gate hold's
 * {@code HLD-YYYY-NNNNNN} id. Account purge deletes the customer's import jobs, and a hold row goes
 * with its job (ON DELETE CASCADE, V144), so either form disappears with the customer.
 *
 * <p>Anything that is neither form is skipped, never reported gone: the caller deletes what this
 * returns, and deleting a trace over a typo would lose a test for nothing.
 */
@Service
public class TraceRetentionService {

    private static final Logger log = LoggerFactory.getLogger(TraceRetentionService.class);

    /** {@code HeldStatementIdGenerator}'s shape: the year, then a sequence of six digits or more. */
    private static final Pattern HELD_ID = Pattern.compile("HLD-\\d{4}-\\d{6,}");

    private final ImportJobRepository importJobRepository;
    private final HeldStatementRepository heldStatementRepository;

    public TraceRetentionService(ImportJobRepository importJobRepository,
                                 HeldStatementRepository heldStatementRepository) {
        this.importJobRepository = importJobRepository;
        this.heldStatementRepository = heldStatementRepository;
    }

    /** The references, of those given, that no longer name an import job. Counts only are logged. */
    @Transactional(readOnly = true)
    public List<String> gone(List<String> references) {
        List<String> gone = new ArrayList<>();
        for (String reference : references) {
            if (reference == null) continue;
            UUID jobId = parseUuid(reference);
            if (jobId != null) {
                if (!importJobRepository.existsById(jobId)) gone.add(reference);
            } else if (HELD_ID.matcher(reference).matches()) {
                boolean present = heldStatementRepository.findByHeldId(reference)
                        .map(held -> importJobRepository.existsById(held.getImportJobId()))
                        .orElse(false);
                if (!present) gone.add(reference);
            }
        }
        log.info("trace retention: {} asked, {} gone", references.size(), gone.size());
        return gone;
    }

    /** A UUID in its canonical 36-character form only; {@link UUID#fromString} alone also accepts
     *  shortened groups such as {@code 1-1-1-1-1}, which no job id is written as. */
    private static UUID parseUuid(String reference) {
        if (reference.length() != 36) return null;
        try {
            UUID parsed = UUID.fromString(reference);
            return parsed.toString().equalsIgnoreCase(reference) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
