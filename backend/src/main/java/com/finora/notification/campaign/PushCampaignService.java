package com.finora.notification.campaign;

import com.finora.dto.PushCampaignDtos.PushCampaignAudienceCountDto;
import com.finora.dto.PushCampaignDtos.PushCampaignCancelResultDto;
import com.finora.dto.PushCampaignDtos.PushCampaignDetailDto;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignRunDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestResultDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationPreferenceResolver;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.DeviceTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AuditService;
import com.finora.service.FeatureFlagService;
import com.finora.util.AfterCommit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin push campaigns: create, edit, schedule, send, pause, stop -- the rules, in one place.
 *
 * <p>Every admin action takes {@code actingAdminId} and writes an audit entry with it (the audit
 * actor-attribution rule), and none of them can reach FCM: sending is the outbox and dispatcher's
 * job, via {@link PushCampaignRunner}.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   DRAFT --start--> ACTIVE --pause--> PAUSED --resume--> ACTIVE
 *   DRAFT / ACTIVE / PAUSED --stop--> STOPPED          ONCE_AT and NOW_ONLY --run--> COMPLETED
 *   DAILY_AT --last day passes--> COMPLETED
 * </pre>
 * STOPPED and COMPLETED are final; to send the same words again, clone. Edits are allowed only in
 * DRAFT and PAUSED, so what is running is never rewritten under it.
 *
 * <h2>Send now counts as today's run</h2>
 * Pressing it on a daily campaign whose slot is today consumes that slot (the next automatic send is
 * tomorrow), so nobody is offered two sends in one day and the scheduled run never looks broken
 * because nearly everyone was skipped. On a one-off it completes the campaign. It is the one send
 * that ignores the 07:00-21:59 IST window: the admin chose the moment.
 *
 * <h2>The scheduler's claim</h2>
 * {@link #claimDue} turns a due slot into a run (or a MISSED record) with a compare-and-set, so
 * with several servers exactly one acts on it. A slot that is more than two hours late, or that
 * comes due outside the window, is recorded as MISSED and not sent: a 7 PM reminder at 3 AM is
 * worse than none (see {@link ScheduleCalculator#decide}).
 */
@Service
public class PushCampaignService {

    private static final Logger log = LoggerFactory.getLogger(PushCampaignService.class);

    static final String FEATURE_FLAG = "PUSH_CAMPAIGNS_ENABLED";

    /** Newlines are allowed in a message; every other control character is refused. */
    private static final Pattern DISALLOWED_CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

    /** How long daily-cap rows are kept after their day: only today's row enforces anything; the
     *  rest answer "why did this person not get it". */
    static final int CAP_RETENTION_DAYS = 30;
    /** A run RUNNING for longer than this is treated as interrupted, not as a reason to refuse a new one. */
    static final long STALE_RUN_MINUTES = 30;

    private final PushCampaignRepository campaigns;
    private final PushCampaignRunRepository runs;
    private final PushCampaignRunner runner;
    private final CampaignDeliveryStats deliveryStats;
    private final IstClock clock;
    private final AuditService auditService;
    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationPreferenceResolver preferenceResolver;
    private final NotificationService notificationService;
    private final DailyCapStore dailyCap;
    private final CampaignCancellation cancellation;

    public PushCampaignService(PushCampaignRepository campaigns, PushCampaignRunRepository runs,
            PushCampaignRunner runner, CampaignDeliveryStats deliveryStats, IstClock clock,
            AuditService auditService, FeatureFlagService featureFlagService,
            UserRepository userRepository, DeviceTokenRepository deviceTokenRepository,
            NotificationPreferenceResolver preferenceResolver,
            NotificationService notificationService, DailyCapStore dailyCap,
            CampaignCancellation cancellation) {
        this.campaigns = campaigns;
        this.runs = runs;
        this.runner = runner;
        this.deliveryStats = deliveryStats;
        this.clock = clock;
        this.auditService = auditService;
        this.featureFlagService = featureFlagService;
        this.userRepository = userRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.preferenceResolver = preferenceResolver;
        this.notificationService = notificationService;
        this.dailyCap = dailyCap;
        this.cancellation = cancellation;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<PushCampaignDto> list() {
        return campaigns.findTop200ByOrderByCreatedAtDesc().stream().map(PushCampaignService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PushCampaignDetailDto get(UUID id) {
        PushCampaign campaign = load(id);
        Map<LocalDate, CampaignDeliveryStats.Delivery> byDay = new HashMap<>();
        List<PushCampaignRunDto> history = runs.findTop50ByCampaignIdOrderByStartedAtDesc(id).stream()
                .map(run -> {
                    if (run.getStatus() == RunStatus.MISSED) {
                        return toDto(run, null);
                    }
                    return toDto(run, byDay.computeIfAbsent(run.getRunDateIst(),
                            day -> deliveryStats.forRunDay(id, day)));
                })
                .toList();
        return new PushCampaignDetailDto(toDto(campaign), history);
    }

    /** The dry run. Needs no saved campaign, so the editor can show it while the admin is still typing. */
    @Transactional(readOnly = true)
    public PushCampaignAudienceCountDto audienceCount(AudienceType type) {
        return new PushCampaignAudienceCountDto(type, runner.resolverFor(type).count(), runner.maxAudience());
    }

    // ---------------------------------------------------------------- create / edit / clone

    @Transactional
    public PushCampaignDto create(UUID actingAdminId, PushCampaignSaveRequest request) {
        Instant now = clock.now();
        Cleaned cleaned = validate(request, now);
        PushCampaign campaign = new PushCampaign(cleaned.name, cleaned.title, cleaned.message,
                request.audienceType(), request.scheduleKind(), cleaned.runAt, cleaned.sendTime,
                request.endsOn(), actingAdminId, now);
        campaigns.save(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_CREATED", campaign, Map.of());
        return toDto(campaign);
    }

    @Transactional
    public PushCampaignDto update(UUID actingAdminId, UUID id, PushCampaignSaveRequest request) {
        PushCampaign campaign = load(id);
        if (!campaign.isEditable()) {
            throw conflict("A campaign can only be edited while it is a draft or paused. "
                    + "Pause it first, or clone it to start from a copy.");
        }
        if (request.expectedVersion() != null && request.expectedVersion() != campaign.getVersion()) {
            throw conflict("This campaign was changed by someone else since you opened it. "
                    + "Reload it to see their changes, then edit again.");
        }
        Instant now = clock.now();
        Cleaned cleaned = validate(request, now);
        campaign.edit(cleaned.name, cleaned.title, cleaned.message, request.audienceType(),
                request.scheduleKind(), cleaned.runAt, cleaned.sendTime, request.endsOn(), now);
        campaigns.save(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_UPDATED", campaign, Map.of());
        return toDto(campaign);
    }

    /** A new DRAFT with the same words, audience and schedule, to send again later or tweak. */
    @Transactional
    public PushCampaignDto clone(UUID actingAdminId, UUID id) {
        PushCampaign source = load(id);
        String name = "Copy of " + source.getName();
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        PushCampaign copy = new PushCampaign(name, source.getTitle(), source.getMessage(),
                source.getAudienceType(), source.getScheduleKind(), source.getRunAt(),
                source.getSendTimeIst(), source.getEndsOn(), actingAdminId, clock.now());
        campaigns.save(copy);
        audit(actingAdminId, "PUSH_CAMPAIGN_CLONED", copy, Map.of("sourceCampaignId", source.getId().toString()));
        return toDto(copy);
    }

    // ---------------------------------------------------------------- lifecycle

    @Transactional
    public PushCampaignDto start(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw conflict("Only a draft can be started. A paused campaign is resumed instead.");
        }
        activate(campaign, false);
        audit(actingAdminId, "PUSH_CAMPAIGN_STARTED", campaign,
                Map.of("nextRunAt", String.valueOf(campaign.getNextRunAt())));
        return toDto(campaign);
    }

    @Transactional
    public PushCampaignDto pause(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            throw conflict("Only a running (scheduled) campaign can be paused.");
        }
        campaign.pause(clock.now());
        campaigns.save(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_PAUSED", campaign, Map.of());
        return toDto(campaign);
    }

    @Transactional
    public PushCampaignDto resume(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        if (campaign.getStatus() != CampaignStatus.PAUSED) {
            throw conflict("Only a paused campaign can be resumed.");
        }
        activate(campaign, true);
        audit(actingAdminId, "PUSH_CAMPAIGN_RESUMED", campaign,
                Map.of("nextRunAt", String.valueOf(campaign.getNextRunAt())));
        return toDto(campaign);
    }

    @Transactional
    public PushCampaignDto stop(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        if (campaign.getStatus().isTerminal()) {
            throw conflict("This campaign has already finished.");
        }
        campaign.stop(clock.now());
        campaigns.save(campaign);
        // Stop is the emergency stop: besides ending future runs it withdraws what an in-flight run
        // has queued but the dispatcher has not yet sent (otherwise a mistaken send keeps going).
        CampaignCancellation.Result withdrawn = withdrawQueued(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_STOPPED", campaign,
                Map.of("cancelledPushes", withdrawn.cancelled(), "releasedSlots", withdrawn.released()));
        return toDto(campaign);
    }

    /**
     * Emergency brake without ending the campaign: cancels any run still queuing people and
     * withdraws every push of this campaign that is queued but not yet handed to the dispatcher,
     * giving those people their one-per-day slot back. Allowed in every status (a one-off send-now
     * campaign is COMPLETED the moment it launches, and that is exactly when this is needed) and
     * deliberately not gated by the master switch. To also end future runs of a daily campaign, stop
     * it, which does this too. Pushes already claimed by the dispatcher (at most one batch) still go.
     */
    @Transactional
    public PushCampaignCancelResultDto cancelSending(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        CampaignCancellation.Result withdrawn = withdrawQueued(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_SENDING_CANCELLED", campaign,
                Map.of("cancelledPushes", withdrawn.cancelled(), "releasedSlots", withdrawn.released()));
        return new PushCampaignCancelResultDto(withdrawn.cancelled(), withdrawn.released());
    }

    /** Cancels running runs first, then withdraws the queued rows; a run mid-page sweeps itself. */
    private CampaignCancellation.Result withdrawQueued(PushCampaign campaign) {
        runs.cancelRunning(campaign.getId(), clock.now(), "Cancelled by an admin.");
        return cancellation.cancelPending(campaign.getId());
    }

    /** Works out the next slot and moves the campaign to ACTIVE. Shared by start and resume. */
    private void activate(PushCampaign campaign, boolean resuming) {
        // Checked here, not only in start: a paused daily campaign can be edited to send-now-only and
        // then resumed, and a send-now-only campaign has no slot to compute.
        if (campaign.getScheduleKind() == ScheduleKind.NOW_ONLY) {
            throw badRequest("This campaign has no schedule. Use Send now, or edit it to pick a time.");
        }
        Instant now = clock.now();
        checkRolloutLimit(campaign.getAudienceType());
        Instant first;
        if (campaign.getScheduleKind() == ScheduleKind.ONCE_AT) {
            Instant runAt = campaign.getRunAt();
            if (runAt.isAfter(now)) {
                first = runAt;
            } else if (!resuming) {
                throw badRequest("The scheduled time has already passed. Edit it to a future time.");
            } else if (ScheduleCalculator.isInsideWindow(IstClock.timeOf(now))) {
                // A one-off whose time passed while paused or missed: resume sends it as soon as the
                // scheduler next looks, but only at an hour a push is allowed to go out.
                first = now;
            } else {
                throw badRequest("The scheduled time has passed and it is outside 07:00-21:59 IST. "
                        + "Use Send now, or edit the time.");
            }
        } else {
            first = ScheduleCalculator.nextDailySlotAfter(campaign.getSendTimeIst(), now);
            if (ScheduleCalculator.isPastEnd(first, campaign.getEndsOn())) {
                throw badRequest("The end date has passed, so there is no next send. Edit the end date or clone it.");
            }
        }
        campaign.activate(first, now);
        campaigns.save(campaign);
    }

    // ---------------------------------------------------------------- send now / send test

    /**
     * Starts a run immediately and returns it (RUNNING); the run itself executes on the campaign
     * thread after this commits. See the class doc for how it interacts with the schedule.
     */
    @Transactional
    public PushCampaignRunDto sendNow(UUID actingAdminId, UUID id) {
        PushCampaign campaign = load(id);
        requireSwitchedOn();
        CampaignStatus status = campaign.getStatus();
        if (status != CampaignStatus.DRAFT && status != CampaignStatus.ACTIVE && status != CampaignStatus.PAUSED) {
            throw conflict("This campaign has already finished. Clone it to send it again.");
        }
        Instant now = clock.now();
        if (runs.existsByCampaignIdAndStatusAndUpdatedAtAfter(id, RunStatus.RUNNING,
                now.minus(STALE_RUN_MINUTES, ChronoUnit.MINUTES))) {
            throw conflict("This campaign is already sending. Wait for it to finish.");
        }
        long audience = checkRolloutLimit(campaign.getAudienceType());
        LocalDate today = clock.today();

        consumeScheduleForSendNow(campaign, now, today);

        PushCampaignRun run = PushCampaignRun.starting(campaign, today, null, RunTrigger.ADMIN_NOW,
                actingAdminId, now);
        runs.save(run);
        audit(actingAdminId, "PUSH_CAMPAIGN_SENT_NOW", campaign,
                Map.of("runId", run.getId().toString(), "estimatedAudience", audience));
        UUID runId = run.getId();
        AfterCommit.run("push campaign run " + runId, () -> runner.executeAsync(runId));
        return toDto(run, null);
    }

    /**
     * Send now is today's run (see class doc). A one-off completes; a daily campaign that was due
     * today moves to tomorrow's slot, or completes if tomorrow is past its end date. A draft or
     * paused daily campaign is left as it is.
     */
    private void consumeScheduleForSendNow(PushCampaign campaign, Instant now, LocalDate today) {
        switch (campaign.getScheduleKind()) {
            case NOW_ONLY, ONCE_AT -> {
                campaign.complete(now);
                campaigns.save(campaign);
            }
            case DAILY_AT -> {
                Instant due = campaign.getNextRunAt();
                if (campaign.getStatus() == CampaignStatus.ACTIVE && due != null
                        && IstClock.dateOf(due).equals(today)) {
                    Instant next = ScheduleCalculator.slotAfterRunOf(due, campaign.getSendTimeIst());
                    if (ScheduleCalculator.isPastEnd(next, campaign.getEndsOn())) {
                        campaign.complete(now);
                    } else {
                        campaign.rescheduleNextRun(next, now);
                    }
                    campaigns.save(campaign);
                }
            }
        }
    }

    /**
     * Sends the campaign's words to ONE account, to see them on a real phone. Completely separate
     * from running the campaign: its own key prefix (never {@code PUSHCAMPAIGN_}), no daily-cap row,
     * no run row, no counters, so a test can never block or be blocked by a real send, and every
     * test sends. It goes out at NORMAL priority so a test is not stuck behind a campaign backlog.
     * It still honours the person's push switch and needs a live device, and says plainly when
     * nothing was queued and why.
     */
    @Transactional
    public PushCampaignTestResultDto sendTest(UUID actingAdminId, UUID id, PushCampaignTestRequest request) {
        PushCampaign campaign = load(id);
        requireSwitchedOn();
        User target = resolveTestTarget(request);
        if (!User.SCOPE_USER.equals(target.getAccountScope())) {
            throw badRequest("Test pushes go to end-user accounts, not admin-portal accounts.");
        }
        PushCampaignTestResultDto result = trySendTest(campaign, target);
        // The test is recorded whether or not it was queued: it is the admin's attempt that is audited.
        campaign.recordTest(actingAdminId, clock.now());
        campaigns.save(campaign);
        audit(actingAdminId, "PUSH_CAMPAIGN_TESTED", campaign,
                Map.of("targetUserId", target.getId().toString(), "queued", result.queued()));
        return result;
    }

    private PushCampaignTestResultDto trySendTest(PushCampaign campaign, User target) {
        if (!User.STATUS_ACTIVE.equals(target.getStatus())) {
            return new PushCampaignTestResultDto(false, "Nothing was sent: this account is " + target.getStatus() + ".");
        }
        if (deviceTokenRepository.findByUserIdAndRevokedAtIsNull(target.getId()).isEmpty()) {
            return new PushCampaignTestResultDto(false,
                    "Nothing was sent: this account has no registered device (the app has not been "
                            + "opened signed in on a phone with notifications allowed).");
        }
        if (!preferenceResolver.isEnabled(target.getId(), NotificationCategory.FINANCIAL,
                NotificationChannel.PUSH)) {
            return new PushCampaignTestResultDto(false, "Nothing was sent: this account has push notifications switched off.");
        }
        String key = "PUSHCAMPAIGNTEST_" + campaign.getId() + "_" + UUID.randomUUID();
        List<UUID> written = notificationService.request(NotificationRequest.of(target.getId(),
                NotificationType.CUSTOM_PUSH, NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL, key, Set.of(NotificationChannel.PUSH),
                Map.of("title", campaign.getTitle(), "message", campaign.getMessage())));
        if (written.isEmpty()) {
            return new PushCampaignTestResultDto(false, "Nothing was queued; see the server logs.");
        }
        return new PushCampaignTestResultDto(true, "Test queued. It should reach the phone within about a minute.");
    }

    private User resolveTestTarget(PushCampaignTestRequest request) {
        boolean hasId = request != null && request.userId() != null;
        boolean hasEmail = request != null && request.email() != null && !request.email().isBlank();
        if (hasId == hasEmail) {
            throw badRequest("Give exactly one of userId or email for the test.");
        }
        Optional<User> user = hasId
                ? userRepository.findById(request.userId())
                : userRepository.findByEmailIgnoreCaseAndAccountScope(request.email().trim(), User.SCOPE_USER);
        return user.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such user account."));
    }

    // ---------------------------------------------------------------- scheduler

    /**
     * Acts on one due slot. Returns the id of a run to execute, or empty if the slot was missed,
     * already taken by another server, or no longer due. Called by {@link PushCampaignScheduler}
     * outside any transaction; the run is executed only after this has committed.
     */
    @Transactional
    public Optional<UUID> claimDue(UUID id, Instant now) {
        PushCampaign campaign = campaigns.findById(id).orElse(null);
        if (campaign == null || campaign.getStatus() != CampaignStatus.ACTIVE
                || campaign.getNextRunAt() == null || campaign.getNextRunAt().isAfter(now)) {
            return Optional.empty();
        }
        Instant slot = campaign.getNextRunAt();
        ScheduleCalculator.Decision decision = ScheduleCalculator.decide(slot, now);

        CampaignStatus nextStatus;
        Instant nextRunAt;
        if (campaign.getScheduleKind() == ScheduleKind.ONCE_AT) {
            // A sent one-off is finished; a missed one waits, paused, for the admin to choose.
            nextStatus = decision.send() ? CampaignStatus.COMPLETED : CampaignStatus.PAUSED;
            nextRunAt = null;
        } else {
            // Never earlier than tomorrow's slot, and never in the past after a long outage.
            Instant tomorrow = ScheduleCalculator.slotAfterRunOf(slot, campaign.getSendTimeIst());
            Instant afterNow = ScheduleCalculator.nextDailySlotAfter(campaign.getSendTimeIst(), now);
            nextRunAt = tomorrow.isAfter(afterNow) ? tomorrow : afterNow;
            if (ScheduleCalculator.isPastEnd(nextRunAt, campaign.getEndsOn())) {
                nextStatus = CampaignStatus.COMPLETED;
                nextRunAt = null;
            } else {
                nextStatus = CampaignStatus.ACTIVE;
            }
        }

        if (campaigns.claimSlot(id, slot, nextRunAt, nextStatus, now) != 1) {
            return Optional.empty(); // another server (or an admin action) got there first
        }
        LocalDate runDate = IstClock.dateOf(slot);
        if (!decision.send()) {
            runs.save(PushCampaignRun.missed(campaign, runDate, slot, decision.reason(), now));
            return Optional.empty();
        }
        PushCampaignRun run = PushCampaignRun.starting(campaign, runDate, slot, RunTrigger.SCHEDULE, null, now);
        runs.save(run);
        return Optional.of(run.getId());
    }

    /**
     * Scheduler housekeeping, safe to run on every tick on every server: marks runs a crash or
     * deploy left RUNNING as FAILED (so the history is honest and a new run is not refused), and
     * once a day deletes daily-cap rows older than {@link #CAP_RETENTION_DAYS} days.
     */
    @Transactional
    public void housekeeping(Instant now, boolean pruneCap) {
        int interrupted = runs.failInterrupted(now.minus(STALE_RUN_MINUTES, ChronoUnit.MINUTES), now);
        if (interrupted > 0) {
            log.warn("Marked {} interrupted push campaign run(s) as FAILED", interrupted);
        }
        if (pruneCap) {
            int pruned = dailyCap.deleteBefore(IstClock.dateOf(now).minusDays(CAP_RETENTION_DAYS));
            if (pruned > 0) {
                log.info("Deleted {} push campaign daily-cap row(s) older than {} days", pruned,
                        CAP_RETENTION_DAYS);
            }
        }
    }

    boolean isSwitchedOn() {
        return featureFlagService.isEnabled(FEATURE_FLAG);
    }

    // ---------------------------------------------------------------- helpers

    private void requireSwitchedOn() {
        if (!isSwitchedOn()) {
            throw conflict("Push campaigns are switched off (feature flag " + FEATURE_FLAG + ").");
        }
    }

    /** @return the current audience count, for the audit entry */
    private long checkRolloutLimit(AudienceType type) {
        long audience = runner.resolverFor(type).count();
        if (audience > runner.maxAudience()) {
            throw conflict("This audience is " + audience + " people, over the current rollout limit of "
                    + runner.maxAudience() + ". Raise app.push-campaigns.max-audience to allow a larger send.");
        }
        return audience;
    }

    private PushCampaign load(UUID id) {
        return campaigns.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Campaign not found."));
    }

    private void audit(UUID actingAdminId, String action, PushCampaign campaign, Map<String, Object> extra) {
        Map<String, Object> metadata = new HashMap<>(extra);
        metadata.put("actorId", actingAdminId.toString());
        metadata.put("name", campaign.getName());
        // The words themselves, so the audit trail answers "what did this admin change it to" even
        // after the campaign is edited again (run history keeps what each run actually sent).
        metadata.put("title", campaign.getTitle());
        metadata.put("message", campaign.getMessage());
        metadata.put("audience", campaign.getAudienceType().name());
        metadata.put("scheduleKind", campaign.getScheduleKind().name());
        auditService.record(actingAdminId, action, "PushCampaign", campaign.getId(), metadata);
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** The validated, trimmed values of a save request. */
    private record Cleaned(String name, String title, String message, Instant runAt, LocalTime sendTime) {
    }

    private Cleaned validate(PushCampaignSaveRequest request, Instant now) {
        String name = clean(request.name(), "name");
        String title = clean(request.title(), "title");
        String message = clean(request.message(), "message");
        if (name.length() > 120 || title.length() > 80 || message.length() > 240) {
            throw badRequest("Name is limited to 120 characters, title to 80 and message to 240.");
        }
        Instant runAt = request.runAt();
        LocalTime sendTime = request.sendTimeIst() == null ? null
                : request.sendTimeIst().truncatedTo(ChronoUnit.MINUTES);
        switch (request.scheduleKind()) {
            case NOW_ONLY -> {
                if (runAt != null || sendTime != null || request.endsOn() != null) {
                    throw badRequest("A send-now-only campaign takes no schedule.");
                }
            }
            case ONCE_AT -> {
                if (runAt == null || sendTime != null || request.endsOn() != null) {
                    throw badRequest("A one-time campaign needs a date and time (runAt), and nothing else.");
                }
                runAt = runAt.truncatedTo(ChronoUnit.MINUTES);
                if (!runAt.isAfter(now)) {
                    throw badRequest("The scheduled time must be in the future.");
                }
                requireInsideWindow(IstClock.timeOf(runAt));
            }
            case DAILY_AT -> {
                if (sendTime == null || runAt != null) {
                    throw badRequest("A daily campaign needs a time of day (sendTimeIst), and no runAt.");
                }
                requireInsideWindow(sendTime);
                if (request.endsOn() != null && request.endsOn().isBefore(clock.today())) {
                    throw badRequest("The end date is in the past.");
                }
            }
        }
        return new Cleaned(name, title, message, runAt, sendTime);
    }

    private static void requireInsideWindow(LocalTime time) {
        if (!ScheduleCalculator.isInsideWindow(time)) {
            throw badRequest("Scheduled sends must be between 07:00 and 21:59 IST. "
                    + "Send now can be used at any hour.");
        }
    }

    private static String clean(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw badRequest("The " + field + " cannot be blank.");
        }
        if (DISALLOWED_CONTROL.matcher(trimmed).find()) {
            throw badRequest("The " + field + " contains characters that cannot be sent.");
        }
        return trimmed;
    }

    static PushCampaignDto toDto(PushCampaign c) {
        return new PushCampaignDto(c.getId(), c.getName(), c.getTitle(), c.getMessage(), c.getAudienceType(),
                c.getScheduleKind(), c.getRunAt(), c.getSendTimeIst(), c.getEndsOn(), c.getStatus(),
                c.getNextRunAt(), c.getLastTestedAt(), c.getLastTestedBy(), c.getCreatedBy(),
                c.getCreatedAt(), c.getUpdatedAt(), c.getVersion());
    }

    static PushCampaignRunDto toDto(PushCampaignRun r, CampaignDeliveryStats.Delivery delivery) {
        return new PushCampaignRunDto(r.getId(), r.getCampaignId(), r.getCampaignVersion(), r.getRunDateIst(),
                r.getScheduledFor(), r.getTriggeredBy(), r.getTriggeredByUser(), r.getStartedAt(),
                r.getFinishedAt(), r.getStatus(), r.getAudienceSize(), r.getQueuedCount(),
                r.getSkippedCapCount(), r.getSkippedAlreadyQueuedCount(), r.getTitleSnapshot(),
                r.getMessageSnapshot(), r.getAudienceSnapshot(), r.getNote(),
                delivery == null ? null : delivery.sent(), delivery == null ? null : delivery.failed(),
                delivery == null ? null : delivery.pending(), delivery == null ? null : delivery.cancelled(),
                delivery == null ? null : delivery.skipped());
    }
}
