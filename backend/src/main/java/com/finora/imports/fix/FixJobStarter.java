package com.finora.imports.fix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.blanked.BlankedCopy;
import com.finora.imports.blanked.BlankedCopyOutcome;
import com.finora.imports.blanked.BlankedCopyRefusedException;
import com.finora.imports.blanked.BlankedCopyService;
import com.finora.imports.blanked.StaffStatementContent;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.service.AdminHeldImportService;
import com.finora.service.HeldItemAdminAlertService;
import com.finora.service.HeldStatementService;
import com.finora.util.UserZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Decides, once per held import job, whether the automatic Claude fix starts, and sends attempt 1
 * when it does (docs/superpowers/specs/2026-10-15-claude-fix-job-design.md, section 1).
 *
 * <p>Runs after the hold commits, on its own single thread, so neither the worker nor the trust
 * queue ever waits on GitHub. It never throws, and nothing it does changes the hold: every failure
 * leaves the item for a person, with a reason on the row.
 *
 * <p>The two queue services are looked up when used, not injected: both of them (through the
 * worker, or directly) depend on this class, which would otherwise be a construction cycle.
 *
 * <p>What leaves production is the blanked copy and a JSON of the reference, the attempt, the bank
 * name and the hold's reason codes and finding kinds (rule names and outcomes). Never a user, an
 * email, a file name, an amount or narration. Logs carry the job id and exception class names only.
 */
@Service
public class FixJobStarter {

    private static final Logger log = LoggerFactory.getLogger(FixJobStarter.class);

    /** Finding outcomes that say something went wrong; PASS and NOT_APPLICABLE are left out. */
    private static final Set<String> FINDING_OUTCOMES = Set.of("WARNING", "FAILED");

    /** What one copy-and-send came to: a commit sha, or the reason nothing was sent. */
    public record Attempt(String commitSha, ClaudeFixJob.Reason notSentReason) {
        static Attempt sent(String sha) { return new Attempt(sha, null); }
        static Attempt notSent(ClaudeFixJob.Reason reason) { return new Attempt(null, reason); }
        public boolean wasSent() { return commitSha != null; }
    }

    private final ClaudeFixJobRepository jobs;
    private final ImportJobRepository importJobs;
    private final HeldStatementRepository heldStatements;
    private final ImportVerificationFindingRepository findings;
    private final BlankedCopyService copies;
    private final FixChannel channel;
    private final ObjectProvider<AdminHeldImportService> importQueue;
    private final ObjectProvider<HeldStatementService> trustQueue;
    private final HeldItemAdminAlertService alerts;
    private final ClaudeFixProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Clock clock;

    @Autowired
    public FixJobStarter(ClaudeFixJobRepository jobs, ImportJobRepository importJobs,
                         HeldStatementRepository heldStatements, ImportVerificationFindingRepository findings,
                         BlankedCopyService copies, FixChannel channel,
                         ObjectProvider<AdminHeldImportService> importQueue,
                         ObjectProvider<HeldStatementService> trustQueue, HeldItemAdminAlertService alerts,
                         ClaudeFixProperties properties, ObjectMapper objectMapper,
                         @Qualifier("claudeFixExecutor") Executor executor) {
        this(jobs, importJobs, heldStatements, findings, copies, channel, importQueue, trustQueue, alerts, properties,
                objectMapper, executor, Clock.systemUTC());
    }

    public FixJobStarter(ClaudeFixJobRepository jobs, ImportJobRepository importJobs,
                         HeldStatementRepository heldStatements, ImportVerificationFindingRepository findings,
                         BlankedCopyService copies, FixChannel channel,
                         ObjectProvider<AdminHeldImportService> importQueue,
                         ObjectProvider<HeldStatementService> trustQueue, HeldItemAdminAlertService alerts,
                         ClaudeFixProperties properties, ObjectMapper objectMapper, Executor executor, Clock clock) {
        this.jobs = jobs;
        this.importJobs = importJobs;
        this.heldStatements = heldStatements;
        this.findings = findings;
        this.copies = copies;
        this.channel = channel;
        this.importQueue = importQueue;
        this.trustQueue = trustQueue;
        this.alerts = alerts;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.clock = clock;
    }

    /** A statement was held, and the hold has committed. Hands off at once; never throws. */
    public void onHeld(UUID jobId) {
        try {
            executor.execute(() -> start(jobId));
        } catch (RuntimeException e) {
            log.warn("Claude fix for held job {} could not be queued ({})", jobId, typeName(e));
        }
    }

    /** As {@link #onHeld}, on the caller's thread: the follow-up's catch-up, already on the fix
     *  job's own thread. Never throws. */
    public void startNow(UUID jobId) {
        start(jobId);
    }

    private void start(UUID jobId) {
        try {
            decide(jobId);
        } catch (RuntimeException e) {
            log.warn("Claude fix for held job {} could not start ({})", jobId, typeName(e));
            try {
                if (!jobs.existsById(jobId)) {
                    jobs.save(ClaudeFixJob.notStarted(jobId, ClaudeFixJob.Reason.ERROR, clock.instant()));
                }
            } catch (RuntimeException recordFailed) {
                log.warn("Claude fix for held job {}: the error could not be recorded ({})", jobId,
                        typeName(recordFailed));
            }
        }
    }

    private void decide(UUID jobId) {
        if (jobs.existsById(jobId)) return;
        Instant now = clock.instant();
        if (!properties.isEnabled()) {
            jobs.save(ClaudeFixJob.notStarted(jobId, ClaudeFixJob.Reason.OFF, now));
            return;
        }
        Optional<ImportJob> found = importJobs.findById(jobId).filter(FixJobStarter::isHeld);
        if (found.isEmpty()) return;
        ImportJob job = found.get();
        if (!"PDF".equalsIgnoreCase(job.getSourceFormat())) {
            jobs.save(ClaudeFixJob.notStarted(jobId, ClaudeFixJob.Reason.CSV, now));
            return;
        }
        YearMonth month = YearMonth.from(now.atZone(UserZone.DEFAULT));
        if (startedIn(month) >= properties.getMonthlyCap()) {
            jobs.save(ClaudeFixJob.notStarted(jobId, ClaudeFixJob.Reason.CAP_REACHED, now));
            if (jobs.claimCapAlert(month.toString(), now) == 1) {
                alerts.alertClaudeFixCapReached(month, properties.getMonthlyCap());
            }
            log.info("Claude fix for held job {} not started: this month's cap of {} is reached", jobId,
                    properties.getMonthlyCap());
            return;
        }
        ClaudeFixJob row = ClaudeFixJob.reserved(jobId, now);
        try {
            jobs.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            log.info("Claude fix for held job {}: another start already holds it", jobId);
            return;
        }
        // From here a failure leaves the reservation's ERROR ("could not start"): start() finds the
        // row and writes nothing more.
        Attempt attempt = copyAndSend(job, 1);
        if (attempt.wasSent()) {
            row.firstSent(attempt.commitSha(), clock.instant());
        } else {
            row.notSent(attempt.notSentReason(), clock.instant());
        }
        jobs.save(row);
        log.info("Claude fix for held job {}: {}", jobId,
                attempt.wasSent() ? "attempt 1 sent" : "not started (" + attempt.notSentReason() + ")");
    }

    /**
     * Makes a fresh blanked copy of a held job and sends it as {@code attempt}. Used for attempt 1
     * here, and for attempt 2 by {@link FixJobFollowUp}. A job no longer held, a copy that is not
     * MATCHES, or a refused copy sends nothing and says why; a failed call to GitHub throws.
     */
    public Attempt copyAndSend(ImportJob job, int attempt) {
        if (!isHeld(job)) return Attempt.notSent(ClaudeFixJob.Reason.ERROR);
        Queue queue = queueFor(job);
        BlankedCopy copy;
        try {
            copy = copies.make(queue.content());
        } catch (BlankedCopyRefusedException e) {
            queue.recordResult(BlankedCopyOutcome.refused(e.kinds()));
            return Attempt.notSent(ClaudeFixJob.Reason.REFUSED);
        }
        queue.recordResult(BlankedCopyOutcome.of(copy));
        return switch (copy.check()) {
            case DIFFERS -> Attempt.notSent(ClaudeFixJob.Reason.DIFFERS);
            case NOT_CHECKED -> Attempt.notSent(ClaudeFixJob.Reason.NOT_CHECKED);
            case MATCHES -> Attempt.sent(channel.send(job.getId(), attempt, copy.trace(),
                    metaJson(job.getId(), attempt, queue.bank(), queue.reasonCodes(), findingKinds(job.getId()))));
        };
    }

    private long startedIn(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(UserZone.DEFAULT).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(UserZone.DEFAULT).toInstant();
        return jobs.countStartedBetween(from, to);
    }

    /** The queue a held job sits in, with what the copy and the metadata need from it. */
    private interface Queue {
        StaffStatementContent content();
        void recordResult(BlankedCopyOutcome outcome);
        String bank();
        List<String> reasonCodes();
    }

    private Queue queueFor(ImportJob job) {
        if (job.getStatus() == ImportJob.Status.HELD_FOR_REVIEW) {
            AdminHeldImportService service = importQueue.getObject();
            return new Queue() {
                public StaffStatementContent content() { return service.contentForSystemBlankedCopy(job.getId()); }
                public void recordResult(BlankedCopyOutcome o) { service.recordBlankedCopyResult(null, job.getId(), o); }
                // An import hold never learns its bank: the parse that would have named it failed.
                public String bank() { return null; }
                public List<String> reasonCodes() {
                    return job.getFailureCode() == null ? List.of() : List.of(job.getFailureCode());
                }
            };
        }
        HeldStatement held = heldStatements.findByImportJobId(job.getId())
                .orElseThrow(() -> new IllegalStateException("trust hold has no review record"));
        HeldStatementService service = trustQueue.getObject();
        return new Queue() {
            public StaffStatementContent content() { return service.contentForSystemBlankedCopy(held.getHeldId()); }
            public void recordResult(BlankedCopyOutcome o) { service.recordBlankedCopyResult(null, held.getHeldId(), o); }
            public String bank() { return held.getBankName(); }
            public List<String> reasonCodes() {
                return held.getHoldReasonCategories() == null ? List.of() : List.copyOf(held.getHoldReasonCategories());
            }
        };
    }

    /** Distinct {@code RULE:OUTCOME} for the job's findings that warned or failed, in rule order. */
    private List<String> findingKinds(UUID jobId) {
        return findings.findByImportJobIdOrderBySectionIndexAscRuleAsc(jobId).stream()
                .filter(f -> FINDING_OUTCOMES.contains(f.getOutcome()))
                .map(f -> f.getRule() + ":" + f.getOutcome())
                .distinct()
                .sorted()
                .toList();
    }

    private String metaJson(UUID jobId, int attempt, String bank, List<String> reasonCodes, List<String> findingKinds) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reference", jobId.toString());
        meta.put("attempt", attempt);
        meta.put("bank", bank == null || bank.isBlank() ? null : bank);
        meta.put("reasonCodes", reasonCodes);
        meta.put("findingKinds", findingKinds);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("fix metadata could not be written", e);
        }
    }

    static boolean isHeld(ImportJob job) {
        return job.getStatus() == ImportJob.Status.HELD_FOR_REVIEW
                || job.getStatus() == ImportJob.Status.HELD_FOR_TRUST_REVIEW;
    }

    static String typeName(Throwable e) {
        String simple = e.getClass().getSimpleName();
        return simple.isEmpty() ? e.getClass().getName() : simple;
    }
}
