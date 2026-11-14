package com.finora.service;

import com.finora.dto.DecisionQueueRowDto;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.fix.ClaudeFixStatus;
import com.finora.imports.fix.ClaudeFixStatusLookup;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The "Needs a decision" page: every open hold of both kinds in one list, with who is on it, the
 * next step and what the Claude fix did -- the order of the 48-hour promise, rows nobody is on first.
 *
 * <p>Read only, and no statement content (see {@link DecisionQueueRowDto}). Deciding stays on each
 * hold's own page. The clock and the promise are {@link ImportJob#isHoldOverdue}'s: a hold begins at
 * {@code finishedAt}, which every entry into a hold sets.
 *
 * <p>A trust hold with no review record that an open review already covers is a re-upload that
 * review decides, so it is not listed on its own -- the rule {@code HoldOverdueEscalationService}
 * uses to email once per review rather than once per upload.
 */
@Service
public class DecisionQueueService {

    /** The queue is small (none open in production on 2026-11-14); this only bounds a runaway. */
    static final int LIMIT = 500;

    private static final Set<ImportJob.Status> HELD =
            EnumSet.of(ImportJob.Status.HELD_FOR_REVIEW, ImportJob.Status.HELD_FOR_TRUST_REVIEW);

    /** Claude states in which Claude, not a person, moves the hold on next. */
    private static final Set<String> CLAUDE_ACTIVE = Set.of("SENT", "REREADING");

    private final ImportJobRepository jobs;
    private final HeldStatementRepository statements;
    private final HeldStatementService heldStatementService;
    private final ClaudeFixStatusLookup claude;
    private final UserRepository users;

    public DecisionQueueService(ImportJobRepository jobs, HeldStatementRepository statements,
                                HeldStatementService heldStatementService, ClaudeFixStatusLookup claude,
                                UserRepository users) {
        this.jobs = jobs;
        this.statements = statements;
        this.heldStatementService = heldStatementService;
        this.claude = claude;
        this.users = users;
    }

    /**
     * @param includeImports    the caller may decide held imports ({@code IMPORT_TRIAGE_MANAGE})
     * @param includeStatements the caller may decide trust holds ({@code TRUST_REVIEW_MANAGE})
     */
    @Transactional(readOnly = true)
    public List<DecisionQueueRowDto> list(boolean includeImports, boolean includeStatements) {
        if (!includeImports && !includeStatements) return List.of();
        Set<ImportJob.Status> wanted = EnumSet.noneOf(ImportJob.Status.class);
        if (includeImports) wanted.add(ImportJob.Status.HELD_FOR_REVIEW);
        if (includeStatements) wanted.add(ImportJob.Status.HELD_FOR_TRUST_REVIEW);

        List<ImportJob> held = jobs.findByStatusInOrderByFinishedAtAscIdAsc(wanted, PageRequest.of(0, LIMIT))
                .stream().filter(job -> wanted.contains(job.getStatus())).toList();
        if (held.isEmpty()) return List.of();

        Map<UUID, HeldStatement> reviewById = new HashMap<>();
        List<UUID> reviewIds = held.stream().map(ImportJob::getHeldStatementId).filter(Objects::nonNull).toList();
        if (!reviewIds.isEmpty()) statements.findAllById(reviewIds).forEach(r -> reviewById.put(r.getId(), r));

        Map<UUID, ClaudeFixStatus> claudeByJob = claude.forJobs(held.stream().map(ImportJob::getId).toList());

        Map<UUID, String> names = new HashMap<>();
        List<UUID> assignees = reviewById.values().stream()
                .map(HeldStatement::getAssignedEngineerId).filter(Objects::nonNull).distinct().toList();
        if (!assignees.isEmpty()) users.findAllById(assignees).forEach(u -> names.put(u.getId(), nameOf(u)));

        List<DecisionQueueRowDto> rows = new ArrayList<>();
        for (ImportJob job : held) {
            DecisionQueueRowDto row = rowFor(job, reviewById, claudeByJob, names);
            if (row != null) rows.add(row);
        }
        rows.sort(Comparator.comparing((DecisionQueueRowDto r) -> !"NOBODY".equals(r.who()))
                .thenComparing(DecisionQueueRowDto::promiseAt)
                .thenComparing(DecisionQueueRowDto::jobId));
        return rows;
    }

    private DecisionQueueRowDto rowFor(ImportJob job, Map<UUID, HeldStatement> reviewById,
                                       Map<UUID, ClaudeFixStatus> claudeByJob, Map<UUID, String> names) {
        String kind;
        HeldStatement review = null;
        if (job.getStatus() == ImportJob.Status.HELD_FOR_REVIEW) {
            kind = "IMPORT";
        } else if (job.getHeldStatementId() != null) {
            review = reviewById.get(job.getHeldStatementId());
            // A decided review whose job has not moved yet, or a record that has gone: nothing to decide here.
            if (review == null || review.getStatus().isResolved()) return null;
            kind = "STATEMENT";
        } else {
            if (heldStatementService.isCoveredByOpenReview(job)) return null;
            kind = "NO_RECORD";
        }

        ClaudeFixStatus fix = claudeByJob.get(job.getId());
        String claudeState = fix == null ? null : fix.state();
        boolean claudeActive = fix == null || CLAUDE_ACTIVE.contains(claudeState);
        UUID assignee = review == null ? null : review.getAssignedEngineerId();

        String who;
        if (assignee != null) who = "ADMIN";
        else if (claudeActive && !"NO_RECORD".equals(kind)) who = "CLAUDE";
        else who = "NOBODY";

        Instant heldAt = job.getFinishedAt() != null ? job.getFinishedAt() : job.getCreatedAt();
        return new DecisionQueueRowDto(
                kind,
                job.getId(),
                review == null ? null : review.getHeldId(),
                review == null ? null : review.getBankName(),
                job.getSourceFormat(),
                heldAt,
                heldAt.plus(ImportJob.HOLD_PROMISE),
                who,
                assignee == null ? null : names.get(assignee),
                nextStep(kind, review, fix, assignee),
                fix == null ? null : fix.publicPr(),
                fix);
    }

    /** The first match of the design's table (docs/superpowers/specs/2026-11-14-needs-a-decision-design.md). */
    static String nextStep(String kind, HeldStatement review, ClaudeFixStatus fix, UUID assignee) {
        if ("NO_RECORD".equals(kind)) return "OPEN_REVIEW";
        if (review != null && review.getStatus() == HeldStatement.Status.READY_FOR_IMPORT) return "APPROVE_RERUN_PASSED";
        if (fix == null) return "CLAUDE_NOT_STARTED";
        switch (fix.state()) {
            case "PR_OPEN": return "MERGE_PR";
            case "SENT": return "CLAUDE_WORKING";
            case "REREADING": return "CLAUDE_REREADING";
            default: break;
        }
        if (assignee != null) return "ADMIN_LOOKING";
        return "IMPORT".equals(kind) ? "FIX_AND_REPROCESS" : "CHECK_AND_DECIDE";
    }

    private static String nameOf(User user) {
        String name = user.getFullName();
        return name == null || name.isBlank() ? null : name;
    }
}
