package com.finora.imports.storage;

import com.finora.entity.ImportJob;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * BH-017. Reclaims R2/filesystem objects that no row references, once they have been that way
 * for at least the configured re-importability window (90 days by default).
 *
 * <h2>Why this exists</h2>
 * Three code paths drop a DB row that carries a {@code content_hash}/{@code object_key}: the 48h
 * {@code import_sessions} TTL sweep ({@code ImportSessionService.sweepExpiredSessions}), a user
 * deleting a statement ({@code StatementImportService.delete}, a soft delete), and
 * {@code ON DELETE CASCADE} on user deletion. None of them ever touched the underlying object, so
 * once {@code app.statement-storage.provider} is actually configured (PR #67), the documented
 * retention window was fiction: the row disappears, the object stays forever.
 *
 * <p>A third table holds references too, discovered after production evidence surfaced a real
 * FAILED {@code import_jobs} row whose object had no other live reference at all: async imports
 * ({@code ImportJobService}) write to storage before a job is even queued, so a job that later
 * fails still names a real object, one a future "retry without re-upload" needs intact. Neither
 * {@code statement_imports} nor {@code import_sessions} ever gets a row for work that failed
 * before producing one, so until {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}
 * joined this check, a failed job's bytes survived only by the accident of some other reference
 * existing -- never because the failed job itself counted as one.
 *
 * <p>A job the user cancelled before staging finished (also possible with no {@code
 * import_sessions} row ever created -- see {@code ImportJob#isCancellable}) has the identical
 * absence of any other reference, but does NOT get the identical protection: {@link
 * #IMPORT_JOB_EXCLUDED_STATUSES} excludes CANCELLED alongside COMPLETED, not FAILED alongside
 * COMPLETED. Retaining a FAILED job's object trades storage for a real, if not yet built, product
 * need -- retry without re-upload. A CANCELLED job has no such need: the user chose to stop, and
 * nothing in this codebase offers to resume a cancelled import. Protecting it anyway would spend
 * the identical unbounded, never-expiring cost (see the next paragraph) for no corresponding
 * benefit, so it follows the object's normal lifecycle instead, exactly like COMPLETED.
 *
 * <p><b>Accepted trade-off: no TTL on FAILED's protection.</b> {@code import_jobs} rows never
 * expire on their own -- see {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}'s own doc
 * -- so a FAILED job's object is retained for as long as that row exists, which is until the
 * account is purged ({@link #reclaimImportJobObjectsOf}) and otherwise indefinitely. This is
 * intentional, not a gap: it means a failed import stays
 * retryable-without-reupload no matter how old, at the cost of one object per FAILED job ever
 * occurring. Revisit if that cost becomes real -- it would need a bounded window on this check, or
 * a cleanup mechanism for {@code import_jobs} rows themselves, neither of which exists today.
 *
 * <h2>Reference counting, not delete-on-row-expiry</h2>
 * Sid decided explicitly against an R2 lifecycle rule or an immediate delete-on-row-expiry, because
 * objects are shared by design (docs/engineering/statement-storage-migration.md §2.1, §3.2): a
 * staged session and the import it confirms into hold identical bytes and resolve to the same
 * object, and every account section of a composite statement plus every re-import shares one
 * object too. A row disappearing says nothing about whether the object is still needed -- only
 * the ABSENCE of every referencing row, across all three of {@code statement_imports},
 * {@code import_sessions}, and {@code import_jobs}, does.
 *
 * <h2>What "eligible" means here</h2>
 * A candidate comes from one of two discovery queries:
 * <ul>
 *   <li>{@link StatementImportRepository#findObjectsUnreferencedSince}: a soft-deleted
 *       {@code statement_imports} row whose {@code deleted_at} is older than the retention
 *       window;</li>
 *   <li>{@link ImportJobRepository#findReleasableObjects}: a COMPLETED or CANCELLED
 *       {@code import_jobs} row whose {@code finished_at} is older than the same window and which
 *       still holds its object -- see "Async uploads" below.</li>
 * </ul>
 * For each candidate, this service re-checks -- fresh, right before acting -- whether ANY row in ANY
 * of three tables currently references that key
 * ({@link StatementImportRepository#existsByObjectKey}, which respects the entity's
 * {@code @SQLRestriction} and so only counts LIVE rows, OR'd with
 * {@link ImportSessionRepository#existsByObjectKey}, which has no lifecycle state to exclude, OR'd
 * with {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn} against
 * {@link #IMPORT_JOB_EXCLUDED_STATUSES} -- see that method's own doc for why COMPLETED and
 * CANCELLED are excluded rather than checked like the others).
 * Only when all three say no does {@link StatementStorage#delete} get called.
 *
 * <h2>Async uploads: an object only import_jobs ever names</h2>
 * {@code ImportJobService.accept} stores the upload itself, encrypted under a fresh IV and
 * uncompressed. Confirming the session the worker staged stores the same bytes again, through
 * {@link StatementContentService#store} -- compressed, then encrypted under another fresh IV. A key
 * is the hash of what was stored, so the two keys always differ though both rows carry the same
 * {@code content_hash}, and the job's object is named by its {@code import_jobs} row and by nothing
 * else ({@code held_statements.statement_object_key} aside, which is the same key and cascades away
 * with the job). Discovery from {@code statement_imports} alone never returned it, so every
 * COMPLETED and CANCELLED job's object was kept forever -- the status set below said they should
 * not protect their object, but nothing ever asked. {@code ImportJobObjectRetentionIT} proves both
 * halves against the real upload, worker and confirm paths.
 *
 * <p>So import_jobs is the second discovery source. The window runs from {@code finished_at}: a
 * COMPLETED job's object is a second copy of what a confirmed statement holds in its own object, or
 * the only copy of a staging the user never confirmed; a CANCELLED job's is an upload the user
 * stopped. The worker never re-runs a COMPLETED or CANCELLED job. One reader does remain: {@code
 * HeldStatementService.download} does not refuse a resolved hold, and an approved hold's job is
 * COMPLETED -- so a reviewer opening an approved hold's document more than the window after
 * approval gets a storage error, the same as for any other reclaimed object.
 *
 * <p>After the re-check the job's {@code object_released_at} (V250) is set, whether the object was
 * deleted or another live row was found naming the key, so a job is considered once and never
 * again. Without it the job would stay a candidate after its object was gone and, oldest first
 * under a batch limit, crowd newer candidates out of every run.
 *
 * <p>Account purge hard-deletes every {@code import_jobs} row a user has, and those rows are the
 * only ones naming their objects. It calls {@link #reclaimImportJobObjectsOf} first.
 *
 * <h2>Considered once</h2>
 * The soft-deleted rows a candidate comes from are never removed, so once the sweep has acted on a
 * key those rows are marked ({@code statement_imports.object_released_at}, V251, via
 * {@link StatementImportRepository#markObjectReleased}) and discovery leaves them out. Unmarked,
 * a deleted object's key came back on every run and was "deleted" again ({@link
 * StatementStorage#delete} is idempotent), and because discovery is oldest first under the batch
 * limit, a batch's worth of such keys kept every newer candidate out of every run. The rows are
 * marked when the object was deleted, or when a live statement_imports row still names the key --
 * that row's own deletion brings the key back. They are not marked when the delete failed, or
 * when only a session or an import job still names the key, so those candidates are re-checked on
 * every run as before. {@link #reclaimIfUnreferenced} does not mark: the scheduled sweep finds
 * that key once more after the window, repeats the idempotent delete, and marks the rows then.
 *
 * <h2>A known, deliberate gap</h2>
 * This can only discover candidates that leave a queryable trace. {@code statement_imports} does --
 * its {@code @SQLDelete} soft-delete keeps the row (and its {@code deleted_at}) forever -- and so do
 * {@code import_jobs} rows, which never expire. {@code import_sessions} has no soft delete and
 * {@code ON DELETE CASCADE} is a database-level cascade that bypasses Hibernate entirely, so
 * content whose ONLY reference was ever an abandoned, never-confirmed session, or a since-deleted
 * user's rows, leaves nothing this query -- or any DB query -- can find once that hard delete has
 * run. Closing that would need either object-listing/metadata support added to {@link
 * StatementStorage} (a larger interface change than BH-017 asked for) or a durable tombstone
 * recorded at the moment such a row is hard-deleted (a behavioural change to the existing, well-tested TTL sweep, which BH-017
 * deliberately does not touch). Flagged rather than guessed at -- see the PR description.
 *
 * <h2>Safety margin</h2>
 * Two layers, mirroring this codebase's existing guards against a race with an in-flight request
 * (the 48h TTL sweep's own reasoning, and {@code claimForConfirmation}'s atomic re-check):
 * <ul>
 *   <li>{@link #MINIMUM_SAFETY_BUFFER} puts a floor under the configured retention window, so even
 *       a misconfigured {@code retention-days} of 0 cannot make an object eligible for deletion
 *       within 24 hours of its last reference disappearing -- far longer than any in-flight
 *       upload, confirm, or download takes.</li>
 *   <li>The reference check is re-run immediately before each individual delete, not once for the
 *       whole batch -- a request that re-uploads identical bytes (creating a fresh reference)
 *       between candidate discovery and this service reaching that candidate is caught by the
 *       fresh check and skipped.</li>
 * </ul>
 *
 * <h2>Guarded by configuration, like the rest of this migration</h2>
 * {@link StatementStorage} is {@link Optional}, exactly as it is in {@link StatementContentService}:
 * with no provider configured there is no bean, {@link #sweep} is a no-op, and nothing here runs
 * against a database that has never had an object-storage-backed row in the first place.
 */
@Component
public class StatementStorageSweepService {

    private static final Logger log = LoggerFactory.getLogger(StatementStorageSweepService.class);

    /** See this class's "Safety margin" doc section. Not configurable -- it exists specifically to
     *  bound how far a bad configuration value could push the effective window down. */
    static final Duration MINIMUM_SAFETY_BUFFER = Duration.ofHours(24);

    /** See this class's "Accepted trade-off" doc section, and
     *  {@link ImportJobRepository#existsByObjectKeyAndStatusNotIn}'s own doc, for why these two
     *  statuses -- and only these two -- don't make an import_jobs row count as a live reference.
     *
     *  <p>Note what the shape of this set means for a NEW status: because it names the statuses
     *  that stop protecting an object, anything added to {@link ImportJob.Status} is protected by
     *  default. That is the safe direction, and it is also why the protection is easy to remove by
     *  accident -- an edit that "tidied" this set by adding a hold to it would read as harmless.
     *
     *  <p>{@code HELD_FOR_TRUST_REVIEW} is absent and must stay absent. It is the status most
     *  likely to be added here by mistake, because unlike every other hold its job staged
     *  successfully and looks finished. Its object is the reviewer's only copy of the statement
     *  they are being asked to judge, and a trust hold can outlast a failure by a wide margin --
     *  it waits on a person reading a document, not on a retry timer.
     *  {@code StatementStorageSweepServiceTest#heldForTrustReviewIsNotAnExcludedStatus_soTheReviewersCopySurvives}
     *  fails if this set ever grows to include it. */
    static final Set<ImportJob.Status> IMPORT_JOB_EXCLUDED_STATUSES =
            EnumSet.of(ImportJob.Status.COMPLETED, ImportJob.Status.CANCELLED);

    private final Optional<StatementStorage> storage;
    private final StatementImportRepository statementImportRepository;
    private final ImportSessionRepository importSessionRepository;
    private final ImportJobRepository importJobRepository;

    @Value("${app.statement-storage.sweep.enabled:true}")
    private boolean sweepEnabled;

    @Value("${app.statement-storage.sweep.retention-days:90}")
    private int retentionDays;

    /** How many candidates one sweep run considers. Same reasoning as
     *  {@code ImportSessionService.CLEANUP_BATCH_SIZE}: a backlog drains across runs rather than in
     *  one unbounded pass. */
    @Value("${app.statement-storage.sweep.batch-size:200}")
    private int batchSize;

    public StatementStorageSweepService(Optional<StatementStorage> storage,
                                         StatementImportRepository statementImportRepository,
                                         ImportSessionRepository importSessionRepository,
                                         ImportJobRepository importJobRepository) {
        this.storage = storage;
        this.importSessionRepository = importSessionRepository;
        this.statementImportRepository = statementImportRepository;
        this.importJobRepository = importJobRepository;
    }

    /**
     * The scheduled trigger. Gated by a flag for the same reason
     * {@code ImportSessionService.scheduledSweep} is: tests need this deterministic, and a
     * background thread deleting objects mid-test is exactly the cross-test pollution BH-058 was
     * about. {@code application-test.yml} turns it off; tests call {@link #sweep()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}, matching the same precedent: the next run
     * starts after the previous one finishes, so a slow sweep (network calls to R2, potentially
     * many of them) cannot pile up overlapping runs.
     *
     * <p>The default interval is hours, not minutes -- unlike the 48h session TTL, nothing depends
     * on this running promptly. An object sits at "0 references" for 90 days before it is even a
     * candidate, so a sweep that runs a few hours later or earlier changes nothing observable.
     */
    @Scheduled(fixedDelayString = "${app.statement-storage.sweep.interval-ms:21600000}",
            initialDelayString = "${app.statement-storage.sweep.initial-delay-ms:300000}")
    public void scheduledSweep() {
        if (!sweepEnabled) return;
        Result result = sweep();
        if (result.swept() > 0 || result.skipped() > 0 || result.failed() > 0) {
            log.info("Statement storage sweep: {} object(s) reclaimed, {} still referenced, {} failed to delete.",
                    result.swept(), result.skipped(), result.failed());
        }
    }

    /**
     * Runs one sweep pass. No-ops -- returns {@link Result#EMPTY} without touching the database --
     * when no storage provider is configured, matching how every other consumer of
     * {@link StatementStorage} behaves when {@code app.statement-storage.provider} is unset.
     *
     * <p>Does not run inside a single database transaction: every step here is either a read or a
     * call to external object storage, never a database write, so there is nothing that needs
     * transactional atomicity across the batch, and holding one open for however long N network
     * calls to R2 take would only cost a connection-pool slot for no benefit.
     *
     * @return how many objects were reclaimed, skipped (still referenced by the time this got to
     *         them), or failed to delete -- so a caller or test can see the sweep did something
     */
    public Result sweep() {
        if (storage.isEmpty()) return Result.EMPTY;

        Instant cutoff = Instant.now().minus(effectiveRetention());

        int swept = 0;
        int skipped = 0;
        int failed = 0;
        for (Object[] row : statementImportRepository.findObjectsUnreferencedSince(cutoff, batchSize)) {
            String contentHash = (String) row[0];
            String objectKey = (String) row[1];
            Instant lastReferencedAt = Instant.ofEpochMilli((Long) row[2]);

            ReclaimOutcome outcome = reclaim(objectKey, contentHash, lastReferencedAt);
            switch (outcome) {
                case DELETED -> swept++;
                case STILL_REFERENCED -> skipped++;
                case FAILED -> failed++;
            }
            if (releasesCandidate(outcome, objectKey)) {
                statementImportRepository.markObjectReleased(objectKey, cutoff, Instant.now());
            }
        }

        // The import_jobs half -- see this class's "Async uploads" doc section. Same cutoff, same
        // fresh re-check per object; the job is then marked so no later run considers it again.
        for (Object[] row : importJobRepository.findReleasableObjects(cutoff, batchSize)) {
            UUID jobId = (UUID) row[0];
            String objectKey = (String) row[1];
            Instant finishedAt = Instant.ofEpochMilli((Long) row[2]);

            ReclaimOutcome outcome = reclaim(objectKey, null, finishedAt);
            switch (outcome) {
                case DELETED -> swept++;
                case STILL_REFERENCED -> skipped++;
                case FAILED -> failed++;
            }
            // STILL_REFERENCED releases the job too: another live row names this key, so that row's
            // own lifecycle now decides the object's, and this job's claim adds nothing -- the same
            // reason COMPLETED and CANCELLED are excluded from the re-check. FAILED does not: the
            // object is still here and this job is still the reason to come back for it.
            if (outcome != ReclaimOutcome.FAILED) {
                importJobRepository.markObjectReleased(jobId, Instant.now());
            }
        }
        return new Result(swept, skipped, failed);
    }

    /**
     * Whether this candidate's soft-deleted rows are done with -- see this class's "Considered once"
     * doc section. A deleted object, yes. A live statement_imports row still naming the key, yes:
     * that row's own soft delete makes the key a candidate again, measured from then. A failed
     * delete, no -- the object is still there and these rows are the reason to come back for it.
     * Only a session or an import job naming the key, no: a session is hard deleted at its TTL and
     * leaves nothing behind, so these rows would be the only way left to find the object.
     */
    private boolean releasesCandidate(ReclaimOutcome outcome, String objectKey) {
        return switch (outcome) {
            case DELETED -> true;
            case STILL_REFERENCED -> statementImportRepository.existsByObjectKey(objectKey);
            case FAILED -> false;
        };
    }

    /**
     * Account purge's half of the import_jobs story. Reclaims every object this user's jobs still
     * hold -- any status, FAILED and both holds included: their protection exists for the user's
     * retry and for a reviewer, and the user is leaving -- unless another live row still names it.
     *
     * <p>Must run BEFORE {@code ImportJobRepository.deleteByUserId}. Those rows are the only ones
     * that name a job's object (see "Async uploads" above), so once they are deleted nothing in the
     * database can lead back to it and the object is kept forever. Which is also why the re-check
     * here leaves this user's own jobs out ({@code existsByObjectKeyAndUserIdNotAndStatusNotIn}):
     * they are still present at this point and would otherwise protect every object they name.
     * Every other user's jobs still count, as do statement_imports and import_sessions rows,
     * whoever owns them (BH-039).
     *
     * <p>Throws on the first storage failure rather than logging it, unlike {@link
     * #reclaimIfUnreferenced}. That method has the scheduled sweep behind it because a soft-deleted
     * statement row stays discoverable; a purged job row does not. Failing the purge leaves the
     * rows in place, and the next purge run retries from the start -- deleting an object twice is
     * harmless, {@link StatementStorage#delete} is idempotent.
     *
     * @return how many objects were deleted
     * @throws StatementStorageException if any object could not be deleted
     */
    public int reclaimImportJobObjectsOf(UUID userId) {
        if (storage.isEmpty()) return 0;
        int deleted = 0;
        for (String objectKey : importJobRepository.findHeldObjectKeysByUserId(userId)) {
            if (statementImportRepository.existsByObjectKey(objectKey)
                    || importSessionRepository.existsByObjectKey(objectKey)
                    || importJobRepository.existsByObjectKeyAndUserIdNotAndStatusNotIn(
                            objectKey, userId, IMPORT_JOB_EXCLUDED_STATUSES)) {
                continue;
            }
            storage.get().delete(objectKey);
            log.info("Reclaimed a purged account's import job object: key={} user={}", objectKey, userId);
            deleted++;
        }
        return deleted;
    }

    /**
     * Reclaims one object right now, if nothing currently references it -- the same safety check
     * {@link #sweep} runs per-candidate, exposed so a caller with its own reason to believe an
     * object may already be unreferenced can act immediately instead of waiting up to {@code
     * retention-days} for the next scheduled discovery pass.
     *
     * <p>{@link com.finora.service.AccountPurgeSweepService} is the only caller today: once account
     * deletion has cleared a user's own {@code import_sessions}/{@code import_jobs} rows and
     * soft-deleted their {@code statement_imports} row, the only thing that can still make one of
     * their objects live is a genuinely different reference -- another user's byte-identical
     * upload, or (same account) a re-import sharing the same content hash -- which is exactly what
     * this recheck already exists to catch. No new risk: it is the identical query {@link #sweep}
     * runs, just invoked sooner and for one key instead of a batch.
     *
     * @param objectKey the object to attempt to reclaim; a no-op (returns {@code false}) if
     *                   {@code null} or if no storage provider is configured
     * @return whether the object was actually deleted -- {@code false} for "still referenced" and
     *         for "delete failed" alike, since either way the 90-day sweep remains the backstop
     */
    public boolean reclaimIfUnreferenced(String objectKey) {
        if (storage.isEmpty() || objectKey == null) return false;
        return reclaim(objectKey, null, null) == ReclaimOutcome.DELETED;
    }

    /**
     * The safety-critical check shared by {@link #sweep} and {@link #reclaimIfUnreferenced}. A
     * discovery query (or a caller's own belief that a key is now unreferenced) can be stale by
     * the time execution reaches here -- another statement could have been confirmed with
     * identical bytes (a fresh {@code statement_imports} row), a new session staged (a fresh
     * {@code import_sessions} row), or an async import queued, failed, or cancelled on the same
     * bytes (a live {@code import_jobs} row outside {@link #IMPORT_JOB_EXCLUDED_STATUSES}) in the
     * meantime. Re-checking fresh, immediately before the irreversible call, is what actually
     * makes this safe -- the same shape of guard as
     * {@code ImportSessionRepository.claimForConfirmation}'s atomic re-check.
     *
     * @param contentHash logging only -- may be {@code null} (the {@link #reclaimIfUnreferenced}
     *                     caller doesn't have it to hand)
     * @param lastReferencedAt logging only -- may be {@code null}, same reason as above
     */
    private ReclaimOutcome reclaim(String objectKey, String contentHash, Instant lastReferencedAt) {
        if (statementImportRepository.existsByObjectKey(objectKey)
                || importSessionRepository.existsByObjectKey(objectKey)
                || importJobRepository.existsByObjectKeyAndStatusNotIn(objectKey, IMPORT_JOB_EXCLUDED_STATUSES)) {
            return ReclaimOutcome.STILL_REFERENCED;
        }

        try {
            storage.get().delete(objectKey);
            log.info("Swept unreferenced statement object: key={} hash={} unreferencedSince={} age={}",
                    objectKey, contentHash, lastReferencedAt,
                    lastReferencedAt == null ? null : Duration.between(lastReferencedAt, Instant.now()));
            return ReclaimOutcome.DELETED;
        } catch (StatementStorageException e) {
            // One bad object must not abort the rest of the batch -- log and move on. The object
            // stays a candidate and is retried on the next scheduled run regardless of which
            // caller reached this point.
            log.error("Failed to sweep statement object: key={} hash={}: {}", objectKey, contentHash,
                    e.getMessage(), e);
            return ReclaimOutcome.FAILED;
        }
    }

    private enum ReclaimOutcome { DELETED, STILL_REFERENCED, FAILED }

    /** See {@link #MINIMUM_SAFETY_BUFFER}. */
    private Duration effectiveRetention() {
        Duration configured = Duration.of(retentionDays, ChronoUnit.DAYS);
        return configured.compareTo(MINIMUM_SAFETY_BUFFER) > 0 ? configured : MINIMUM_SAFETY_BUFFER;
    }

    public record Result(int swept, int skipped, int failed) {
        static final Result EMPTY = new Result(0, 0, 0);
    }
}
