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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * How long after a hold begins "no Claude row yet" still means Claude is about to start. The
     * starter reserves its row within seconds of the hold ({@code FixJobStarter.decide}); a hold
     * with no row after this never got one (its queue refused it, the hold predates the job, or
     * recording its error failed) and needs a person.
     */
    static final Duration CLAUDE_START_GRACE = Duration.ofMinutes(15);

    /** Next steps Claude takes, not a person: only these put Claude "on it". */
    private static final Set<String> CLAUDE_STEPS = Set.of("CLAUDE_WORKING", "CLAUDE_REREADING", "CLAUDE_NOT_STARTED");

    private final ImportJobRepository jobs;
    private final HeldStatementRepository statements;
    private final HeldStatementService heldStatementService;
    private final ClaudeFixStatusLookup claude;
    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public DecisionQueueService(ImportJobRepository jobs, HeldStatementRepository statements,
                                HeldStatementService heldStatementService, ClaudeFixStatusLookup claude,
                                UserRepository users) {
        this(jobs, statements, heldStatementService, claude, users, Clock.systemUTC());
    }

    DecisionQueueService(ImportJobRepository jobs, HeldStatementRepository statements,
                         HeldStatementService heldStatementService, ClaudeFixStatusLookup claude,
                         UserRepository users, Clock clock) {
        this.jobs = jobs;
        this.statements = statements;
        this.heldStatementService = heldStatementService;
        this.claude = claude;
        this.users = users;
        this.clock = clock;
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
        Instant now = clock.instant();

        List<ImportJob> held = jobs.findByStatusInOrderByFinishedAtAscIdAsc(wanted, PageRequest.of(0, LIMIT))
                .stream().filter(job -> wanted.contains(job.getStatus())).toList();
        if (held.isEmpty()) return List.of();

        Map<UUID, HeldStatement> reviewById = new HashMap<>();
        List<UUID> reviewIds = held.stream().map(ImportJob::getHeldStatementId).filter(Objects::nonNull).toList();
        if (!reviewIds.isEmpty()) statements.findAllById(reviewIds).forEach(r -> reviewById.put(r.getId(), r));

        Map<UUID, ClaudeFixStatusLookup.Entry> claudeByJob = claude.forJobs(held.stream().map(ImportJob::getId).toList());

        Map<UUID, String> names = new HashMap<>();
        List<UUID> assignees = reviewById.values().stream()
                .map(HeldStatement::getAssignedEngineerId).filter(Objects::nonNull).distinct().toList();
        if (!assignees.isEmpty()) users.findAllById(assignees).forEach(u -> names.put(u.getId(), nameOf(u)));

        // Re-uploads an open review decides: not rows of their own, but counted on that review's row,
        // whose clock starts at the earliest of them -- so this list agrees with the dashboard's
        // counts, which include them, and with the overdue escalation, which goes by the review.
        Map<UUID, List<ImportJob>> coveredByReview = new HashMap<>();
        List<ImportJob> own = new ArrayList<>();
        for (ImportJob job : held) {
            if (job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW && job.getHeldStatementId() == null) {
                Optional<HeldStatement> covering = heldStatementService.openReviewCoveringJob(job);
                if (covering.isPresent()) {
                    coveredByReview.computeIfAbsent(covering.get().getId(), k -> new ArrayList<>()).add(job);
                    continue;
                }
            }
            own.add(job);
        }

        List<DecisionQueueRowDto> rows = new ArrayList<>();
        for (ImportJob job : own) {
            List<ImportJob> covered = job.getHeldStatementId() == null
                    ? List.of() : coveredByReview.getOrDefault(job.getHeldStatementId(), List.of());
            DecisionQueueRowDto row = rowFor(job, covered, reviewById, claudeByJob, names, now);
            if (row != null) rows.add(row);
        }
        rows.sort(Comparator.comparing((DecisionQueueRowDto r) -> !"NOBODY".equals(r.who()))
                .thenComparing(DecisionQueueRowDto::promiseAt)
                .thenComparing(DecisionQueueRowDto::jobId));
        return rows;
    }

    private DecisionQueueRowDto rowFor(ImportJob job, List<ImportJob> covered, Map<UUID, HeldStatement> reviewById,
                                       Map<UUID, ClaudeFixStatusLookup.Entry> claudeByJob, Map<UUID, String> names,
                                       Instant now) {
        String kind;
        HeldStatement review = null;
        if (job.getStatus() == ImportJob.Status.HELD_FOR_REVIEW) {
            kind = "IMPORT";
        } else if (job.getHeldStatementId() != null) {
            review = reviewById.get(job.getHeldStatementId());
            // A decided review: approve and reject move its job in the same transaction, so this is a
            // read between the two, or a record that has gone. Nothing to decide here.
            if (review == null || review.getStatus().isResolved()) return null;
            kind = "STATEMENT";
        } else {
            kind = "NO_RECORD";
        }

        Instant heldAt = heldAt(job);
        for (ImportJob other : covered) {
            if (heldAt(other).isBefore(heldAt)) heldAt = heldAt(other);
        }
        ClaudeFixStatusLookup.Entry entry = claudeByJob.get(job.getId());
        ClaudeFixStatus fix = entry == null ? null : entry.status();
        UUID assignee = review == null ? null : review.getAssignedEngineerId();
        String step = nextStep(kind, review, entry, assignee, heldAt(job), now);

        String who;
        if (assignee != null) who = "ADMIN";
        else if (CLAUDE_STEPS.contains(step)) who = "CLAUDE";
        else who = "NOBODY";

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
                step,
                fix == null ? null : fix.publicPr(),
                fix,
                covered.size());
    }

    private static Instant heldAt(ImportJob job) {
        return job.getFinishedAt() != null ? job.getFinishedAt() : job.getCreatedAt();
    }

    /**
     * The first match of the design's table (docs/superpowers/specs/2026-11-14-needs-a-decision-design.md).
     *
     * @param ownHeldAt when this job's own hold began: the Claude starter runs on it, not on re-uploads
     */
    static String nextStep(String kind, HeldStatement review, ClaudeFixStatusLookup.Entry entry, UUID assignee,
                           Instant ownHeldAt, Instant now) {
        if ("NO_RECORD".equals(kind)) return "OPEN_REVIEW";
        if (review != null && review.getStatus() == HeldStatement.Status.READY_FOR_IMPORT) return "APPROVE_RERUN_PASSED";
        if (entry == null) {
            if (now.isBefore(ownHeldAt.plus(CLAUDE_START_GRACE))) return "CLAUDE_NOT_STARTED";
            // A person already has it: say so, not what Claude failed to do.
            if (assignee != null) return "ADMIN_LOOKING";
            return "CLAUDE_DID_NOT_START";
        }
        switch (entry.status().state()) {
            case "PR_OPEN": return "MERGE_PR";
            case "SENT":
                return entry.updatedAt() != null
                        && !now.isBefore(entry.updatedAt().plus(ClaudeFixStatusLookup.SENT_STALLED_AFTER))
                        ? "CLAUDE_STALLED" : "CLAUDE_WORKING";
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
