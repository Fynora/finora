package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignRunDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestResultDto;
import com.finora.entity.Account;
import com.finora.entity.FeatureFlag;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.DeviceTokenService;
import com.finora.repository.AccountRepository;
import com.finora.repository.FeatureFlagRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.FeatureFlagService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Admin push campaigns on real Postgres: the audience rules, the run/queue/cap mechanics, the
 * scheduler's claim and missed-run rule, test isolation, the master switch and the rollout limit.
 *
 * <p>The audience is global (every active user with a live device), and other test classes leave
 * such users behind, so {@link #setUp} revokes every live device token first and each test then
 * works with exactly the users it created. Runs that a test starts through {@code sendNow} execute
 * on the real campaign thread; {@link #waitUntilFinished} waits for them. Scheduled runs are driven
 * through {@link PushCampaignScheduler#claimDueRuns} and executed synchronously. The queue's own
 * poller and the drain budget are off under test, so nothing is actually delivered.
 */
class PushCampaignIT extends AbstractIntegrationTest {

    private static final LocalTime SEVEN_PM = LocalTime.of(19, 0);

    @Autowired private PushCampaignService service;
    @Autowired private PushCampaignScheduler scheduler;
    @Autowired private PushCampaignRunner runner;
    @Autowired private CampaignEnqueuer enqueuer;
    @Autowired private DailyCapStore dailyCap;
    @Autowired private PushCampaignRepository campaigns;
    @Autowired private PushCampaignRunRepository runs;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImports;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private FeatureFlagService featureFlagService;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        cleanCampaignData();
        // The audience is global; see the class comment.
        jdbc.update("UPDATE device_tokens SET revoked_at = now() WHERE revoked_at IS NULL");
        adminId = newUser(User.STATUS_ACTIVE, User.SCOPE_ADMIN, null).getId();
    }

    @AfterEach
    void tearDown() {
        setSwitch(true);
        ReflectionTestUtils.setField(runner, "maxAudience", 1_000_000L);
        cleanCampaignData();
    }

    private void cleanCampaignData() {
        jdbc.update("DELETE FROM notification_logs WHERE notification_id IN "
                + "(SELECT id FROM notifications WHERE type = 'CUSTOM_PUSH')");
        jdbc.update("DELETE FROM notifications WHERE type = 'CUSTOM_PUSH'");
        jdbc.update("DELETE FROM custom_push_daily_cap");
        jdbc.update("DELETE FROM push_campaign_runs");
        jdbc.update("DELETE FROM push_campaigns");
    }

    // ------------------------------------------------------------------ fixtures

    private User newUser(String status, String scope, String email) {
        User user = new User();
        user.setEmail(email != null ? email : "push-campaign-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Push Campaign IT User");
        user.setStatus(status);
        user.setAccountScope(scope);
        return userRepository.save(user);
    }

    /** An ACTIVE end-user with one live device: in the audience. */
    private UUID eligibleUser() {
        UUID id = newUser(User.STATUS_ACTIVE, User.SCOPE_USER, null).getId();
        deviceTokenService.register(id, "ANDROID", "it-token-" + UUID.randomUUID());
        return id;
    }

    private List<UUID> eligibleUsers(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(eligibleUser());
        }
        return ids;
    }

    private void switchPushOff(UUID userId) {
        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                + "VALUES (gen_random_uuid(), ?, 'FINANCIAL', 'PUSH', false)", userId);
    }

    private void giveStatement(UUID userId, boolean deleted) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("IT Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(1000));
        UUID accountId = accountRepository.save(account).getId();
        StatementImport s = new StatementImport();
        s.setUserId(userId);
        s.setAccountId(accountId);
        s.setFileName("statement.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(new byte[] {1});
        s.setContentHash("push-campaign-it-" + UUID.randomUUID());
        UUID id = statementImports.save(s).getId();
        if (deleted) {
            jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE id = ?", id);
        }
    }

    private void giveImportJob(UUID userId) {
        jdbc.update("INSERT INTO import_jobs (id, user_id, content_hash, file_name, status, source_format, "
                + "created_at) VALUES (?, ?, ?, 'statement.pdf', 'QUEUED', 'PDF', now())", UUID.randomUUID(), userId,
                "push-campaign-it-" + UUID.randomUUID());
    }

    private PushCampaignSaveRequest nowOnly(String title) {
        return new PushCampaignSaveRequest("IT campaign", title, "Upload your statement to see your money.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null);
    }

    private PushCampaignSaveRequest daily(LocalTime at, LocalDate endsOn) {
        return new PushCampaignSaveRequest("IT daily", "Daily reminder", "Upload your statement.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, at, endsOn);
    }

    private PushCampaignDto createNowOnly() {
        return service.create(adminId, nowOnly("Fynora is waiting"));
    }

    private PushCampaignDto createDaily() {
        return service.create(adminId, daily(SEVEN_PM, null));
    }

    private PushCampaignRunDto waitUntilFinished(UUID runId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            PushCampaignRun run = runs.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING) {
                return PushCampaignService.toDto(run, null);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("run " + runId + " did not finish in 20 seconds");
    }

    private PushCampaignRunDto sendNowAndWait(UUID campaignId) {
        return waitUntilFinished(service.sendNow(adminId, campaignId).id());
    }

    /** Makes an ACTIVE campaign's slot be {@code slot}, as if time had passed since start(). */
    private void setNextRunAt(UUID campaignId, Instant slot) {
        jdbc.update("UPDATE push_campaigns SET next_run_at = ? WHERE id = ?",
                java.sql.Timestamp.from(slot), campaignId);
    }

    private int outboxRows(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    private void setSwitch(boolean on) {
        FeatureFlag flag = featureFlagRepository.findByKey(PushCampaignService.FEATURE_FLAG).orElseThrow();
        if (flag.isEnabled() != on) {
            featureFlagService.setEnabled(adminId, flag.getId(), on);
        }
    }

    private static Instant ist(LocalDate date, int hour, int minute) {
        return IstClock.at(date, LocalTime.of(hour, minute));
    }

    // ------------------------------------------------------------------ audience

    @Test
    void audienceRulesAreAppliedExactlyAsDocumented() {
        UUID eligible = eligibleUser();

        UUID noDevice = newUser(User.STATUS_ACTIVE, User.SCOPE_USER, null).getId();

        UUID revoked = newUser(User.STATUS_ACTIVE, User.SCOPE_USER, null).getId();
        deviceTokenService.register(revoked, "ANDROID", "revoked-token");
        deviceTokenService.revoke(revoked, "revoked-token");

        UUID suspended = newUser(User.STATUS_SUSPENDED, User.SCOPE_USER, null).getId();
        deviceTokenService.register(suspended, "ANDROID", "suspended-token");

        UUID pushOff = eligibleUser();
        switchPushOff(pushOff);

        UUID staff = newUser(User.STATUS_ACTIVE, User.SCOPE_ADMIN, null).getId();
        deviceTokenService.register(staff, "ANDROID", "staff-token");

        UUID hasStatement = eligibleUser();
        giveStatement(hasStatement, false);

        UUID hasJob = eligibleUser();
        giveImportJob(hasJob);

        UUID deletedStatement = eligibleUser();
        giveStatement(deletedStatement, true);

        List<UUID> all = allIds(runner.resolverFor(AudienceType.ALL_WITH_DEVICE));
        assertThat(all).containsExactlyInAnyOrder(eligible, hasStatement, hasJob, deletedStatement);
        assertThat(all).doesNotContain(noDevice, revoked, suspended, pushOff, staff);

        List<UUID> noStatement = allIds(runner.resolverFor(AudienceType.NO_STATEMENT_UPLOADED));
        assertThat(noStatement).containsExactlyInAnyOrder(eligible, deletedStatement);

        // The count is the number of ids, i.e. what the send will try.
        assertThat(runner.resolverFor(AudienceType.ALL_WITH_DEVICE).count()).isEqualTo(4);
        assertThat(runner.resolverFor(AudienceType.NO_STATEMENT_UPLOADED).count()).isEqualTo(2);
    }

    @Test
    void audiencePagingWalksEveryoneOnceInIdOrder() {
        List<UUID> users = eligibleUsers(7);
        AudienceResolver resolver = runner.resolverFor(AudienceType.ALL_WITH_DEVICE);
        List<UUID> walked = new ArrayList<>();
        UUID after = AudienceSql.FIRST;
        while (true) {
            List<UUID> page = resolver.page(after, 3);
            if (page.isEmpty()) {
                break;
            }
            walked.addAll(page);
            after = page.get(page.size() - 1);
        }
        assertThat(walked).containsExactlyInAnyOrderElementsOf(users).doesNotHaveDuplicates();
        // Postgres orders uuids as unsigned bytes, which is the order of the canonical lowercase
        // hex string -- not java.util.UUID.compareTo, which compares signed longs.
        assertThat(walked.stream().map(UUID::toString).toList()).isSorted();
    }

    private List<UUID> allIds(AudienceResolver resolver) {
        List<UUID> ids = new ArrayList<>();
        UUID after = AudienceSql.FIRST;
        while (true) {
            List<UUID> page = resolver.page(after, 100);
            if (page.isEmpty()) {
                return ids;
            }
            ids.addAll(page);
            after = page.get(page.size() - 1);
        }
    }

    // ------------------------------------------------------------------ one run, and the cap

    @Test
    void aRunQueuesEachPersonOnce_andALaterRunTheSameDayQueuesNobody() {
        List<UUID> users = eligibleUsers(3);
        PushCampaignDto campaign = createDaily(); // a draft daily campaign can be sent now repeatedly

        PushCampaignRunDto first = sendNowAndWait(campaign.id());
        assertThat(first.status()).isEqualTo(RunStatus.DONE);
        assertThat(first.audienceSize()).isEqualTo(3);
        assertThat(first.queuedCount()).isEqualTo(3);
        assertThat(first.skippedCapCount()).isZero();
        users.forEach(u -> assertThat(outboxRows(u)).isEqualTo(1));

        PushCampaignRunDto second = sendNowAndWait(campaign.id());
        assertThat(second.status()).isEqualTo(RunStatus.DONE);
        assertThat(second.queuedCount()).isZero();
        assertThat(second.skippedAlreadyQueuedCount()).isEqualTo(3);
        assertThat(second.skippedCapCount()).isZero();
        users.forEach(u -> assertThat(outboxRows(u)).isEqualTo(1));
    }

    @Test
    void queuedRowsAreLowPriorityFinancialPushWithTheAdminsWords() {
        UUID user = eligibleUser();
        sendNowAndWait(createNowOnly().id());

        var row = jdbc.queryForMap("SELECT priority, category, channel, title, message, status "
                + "FROM notifications WHERE type = 'CUSTOM_PUSH' AND user_id = ?", user);
        assertThat(row.get("priority")).isEqualTo("LOW");
        assertThat(row.get("category")).isEqualTo("FINANCIAL");
        assertThat(row.get("channel")).isEqualTo("PUSH");
        assertThat(row.get("title")).isEqualTo("Fynora is waiting");
        assertThat(row.get("message")).isEqualTo("Upload your statement to see your money.");
        assertThat(row.get("status")).isEqualTo("QUEUED");
    }

    @Test
    void twoCampaignsCannotBothReachTheSamePersonOnTheSameDay() {
        List<UUID> users = eligibleUsers(4);
        PushCampaignDto a = createNowOnly();
        PushCampaignDto b = createNowOnly();

        PushCampaignRunDto runA = sendNowAndWait(a.id());
        PushCampaignRunDto runB = sendNowAndWait(b.id());

        assertThat(runA.queuedCount()).isEqualTo(4);
        assertThat(runB.queuedCount()).isZero();
        assertThat(runB.skippedCapCount()).isEqualTo(4);
        assertThat(runB.skippedAlreadyQueuedCount()).isZero();
        users.forEach(u -> assertThat(outboxRows(u)).isEqualTo(1));
    }

    @Test
    void twoCampaignsRacingForTheSamePeopleGiveEachPersonExactlyOnePush() throws Exception {
        List<UUID> users = eligibleUsers(30);
        PushCampaignDto a = createNowOnly();
        PushCampaignDto b = createNowOnly();
        LocalDate today = IstClock.dateOf(Instant.now());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<CampaignEnqueuer.PageResult> fa = pool.submit(() -> {
                go.await();
                return enqueuer.enqueuePage(a.id(), "T", "M", today, users);
            });
            Future<CampaignEnqueuer.PageResult> fb = pool.submit(() -> {
                go.await();
                return enqueuer.enqueuePage(b.id(), "T", "M", today, users);
            });
            go.countDown();
            CampaignEnqueuer.PageResult ra = fa.get(30, TimeUnit.SECONDS);
            CampaignEnqueuer.PageResult rb = fb.get(30, TimeUnit.SECONDS);

            assertThat(ra.queued() + rb.queued()).isEqualTo(30);
            assertThat(ra.skippedCap() + rb.skippedCap()).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        users.forEach(u -> assertThat(outboxRows(u)).isEqualTo(1));
    }

    @Test
    void aDailyCampaignStillSendsOnTheNextDay() {
        // Guards the rolling-24h bug: a "24 hours" cap would have blocked day two (yesterday's row
        // is a few seconds under 24h old when today's run starts).
        List<UUID> users = eligibleUsers(3);
        PushCampaignDto campaign = createDaily();
        LocalDate dayOne = LocalDate.of(2026, 10, 6);

        CampaignEnqueuer.PageResult first =
                enqueuer.enqueuePage(campaign.id(), "T", "M", dayOne, users);
        CampaignEnqueuer.PageResult second =
                enqueuer.enqueuePage(campaign.id(), "T", "M", dayOne.plusDays(1), users);

        assertThat(first.queued()).isEqualTo(3);
        assertThat(second.queued()).isEqualTo(3);
        users.forEach(u -> assertThat(outboxRows(u)).isEqualTo(2));
    }

    @Test
    void aFailedPageLeavesNobodyWithAClaimedSlotAndNoRow() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createNowOnly();
        LocalDate today = LocalDate.of(2026, 10, 7);
        // An unknown user id makes the outbox insert violate its foreign key: the whole page rolls
        // back, including the cap claims that came before the failing row.
        List<UUID> page = List.of(user, UUID.randomUUID());

        assertThatThrownBy(() -> enqueuer.enqueuePage(campaign.id(), "T", "M", today, page))
                .isInstanceOf(RuntimeException.class);

        assertThat(dailyCap.holder(user, today)).isEmpty();
        assertThat(outboxRows(user)).isZero();
        // ...so a retry still delivers.
        assertThat(enqueuer.enqueuePage(campaign.id(), "T", "M", today, List.of(user)).queued()).isEqualTo(1);
    }

    @Test
    void sendNowOnAFreshDraftCompletesAOneOffCampaign() {
        eligibleUser();
        PushCampaignDto campaign = createNowOnly();
        sendNowAndWait(campaign.id());

        assertThat(campaigns.findById(campaign.id()).orElseThrow().getStatus())
                .isEqualTo(CampaignStatus.COMPLETED);
        assertThatThrownBy(() -> service.sendNow(adminId, campaign.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("Clone");
    }

    @Test
    void sendNowWhileARunIsInProgressIsRefused() {
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(Instant.now()), null,
                RunTrigger.ADMIN_NOW, adminId, Instant.now()));

        assertThatThrownBy(() -> service.sendNow(adminId, campaign.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("already sending");
    }

    // ------------------------------------------------------------------ delivery counts and snapshots

    @Test
    void runHistoryShowsLiveSentFailedAndPendingFromTheOutbox() {
        List<UUID> users = eligibleUsers(3);
        PushCampaignDto campaign = createDaily();
        sendNowAndWait(campaign.id());

        jdbc.update("UPDATE notifications SET status = 'DEAD_LETTER' WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                users.get(0));
        jdbc.update("UPDATE notifications SET status = 'SENT' WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                users.get(1));

        PushCampaignRunDto run = service.get(campaign.id()).runs().get(0);
        assertThat(run.failed()).isEqualTo(1);
        assertThat(run.sent()).isEqualTo(1);
        assertThat(run.pending()).isEqualTo(1);
    }

    @Test
    void peopleWithNoWorkingDeviceShowAsSkippedNotFailed() {
        List<UUID> users = eligibleUsers(2);
        PushCampaignDto campaign = createDaily();
        sendNowAndWait(campaign.id());
        jdbc.update("UPDATE notifications SET status = 'SKIPPED' WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                users.get(0));

        PushCampaignRunDto run = service.get(campaign.id()).runs().get(0);

        assertThat(run.skipped()).isEqualTo(1);
        assertThat(run.failed()).isZero();
        assertThat(run.pending()).isEqualTo(1);
    }

    @Test
    void testSendsAreNotCountedInARunsDeliveryNumbers() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(user, null));
        sendNowAndWait(campaign.id());

        PushCampaignRunDto run = service.get(campaign.id()).runs().get(0);
        assertThat(run.pending()).isEqualTo(1); // the real send only, not the test
        assertThat(outboxRows(user)).isEqualTo(2);
    }

    @Test
    void aRunKeepsWhatWasActuallySentEvenAfterTheCampaignIsEdited() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        sendNowAndWait(campaign.id());

        service.update(adminId, campaign.id(), new PushCampaignSaveRequest("Renamed", "A different title",
                "A different message", AudienceType.NO_STATEMENT_UPLOADED, ScheduleKind.DAILY_AT, null,
                SEVEN_PM, null));

        PushCampaignRunDto run = service.get(campaign.id()).runs().get(0);
        assertThat(run.titleSnapshot()).isEqualTo("Daily reminder");
        assertThat(run.messageSnapshot()).isEqualTo("Upload your statement.");
        assertThat(run.audienceSnapshot()).isEqualTo(AudienceType.ALL_WITH_DEVICE);
        assertThat(service.get(campaign.id()).campaign().title()).isEqualTo("A different title");
    }

    @Test
    void aRunRecordsTheCampaignVersionItRanUnder() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        PushCampaignRunDto first = sendNowAndWait(campaign.id());
        service.update(adminId, campaign.id(), daily(SEVEN_PM, null));
        PushCampaignRunDto second = sendNowAndWait(campaign.id());

        assertThat(second.campaignVersion()).isGreaterThan(first.campaignVersion());
    }

    // ------------------------------------------------------------------ send now vs the schedule (Option B)

    @Test
    void sendNowOnADailyCampaignDueTodayMovesTheNextRunToTomorrow() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate today = IstClock.dateOf(Instant.now());
        Instant todaysSlot = ist(today, 19, 0);
        setNextRunAt(campaign.id(), todaysSlot);

        sendNowAndWait(campaign.id());

        PushCampaign after = campaigns.findById(campaign.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(after.getNextRunAt()).isEqualTo(ist(today.plusDays(1), 19, 0));
    }

    @Test
    void sendNowOnTheLastDayOfADailyCampaignCompletesIt() {
        eligibleUser();
        LocalDate today = IstClock.dateOf(Instant.now());
        // Created with an end date of tomorrow so start() works at any hour (after 19:00 the first
        // slot is tomorrow), then moved to today: send now on the final day must complete it.
        PushCampaignDto campaign = service.create(adminId, daily(SEVEN_PM, today.plusDays(1)));
        service.start(adminId, campaign.id());
        jdbc.update("UPDATE push_campaigns SET ends_on = ? WHERE id = ?", today, campaign.id());
        setNextRunAt(campaign.id(), ist(today, 19, 0));

        sendNowAndWait(campaign.id());

        assertThat(campaigns.findById(campaign.id()).orElseThrow().getStatus())
                .isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void sendNowOnADraftDailyCampaignLeavesItADraft() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        sendNowAndWait(campaign.id());
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    // ------------------------------------------------------------------ scheduler: claim, late, missed

    @Test
    void aDueSlotBecomesOneRunAndTheNextSlotIsTomorrow() {
        eligibleUsers(2);
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        List<UUID> runIds = scheduler.claimDueRuns(ist(day, 19, 1));
        assertThat(runIds).hasSize(1);
        runner.execute(runIds.get(0));

        PushCampaignRun run = runs.findById(runIds.get(0)).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(run.getTriggeredBy()).isEqualTo(RunTrigger.SCHEDULE);
        assertThat(run.getRunDateIst()).isEqualTo(day);
        assertThat(run.getQueuedCount()).isEqualTo(2);
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getNextRunAt())
                .isEqualTo(ist(day.plusDays(1), 19, 0));

        // Nothing more is due in the same minute.
        assertThat(scheduler.claimDueRuns(ist(day, 19, 2))).isEmpty();
    }

    @Test
    void aRunThatIsFortyMinutesLateIsStillSent() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        List<UUID> runIds = scheduler.claimDueRuns(ist(day, 19, 40));

        assertThat(runIds).hasSize(1);
        assertThat(runs.findById(runIds.get(0)).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getNextRunAt())
                .isEqualTo(ist(day.plusDays(1), 19, 0));
    }

    @Test
    void aRunThreeInTheMorningLateIsRecordedAsMissedNotSent() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        List<UUID> runIds = scheduler.claimDueRuns(ist(day.plusDays(1), 3, 0));

        assertThat(runIds).isEmpty();
        PushCampaignRun missed = runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id()).get(0);
        assertThat(missed.getStatus()).isEqualTo(RunStatus.MISSED);
        assertThat(missed.getNote()).contains("2 hours");
        assertThat(missed.getQueuedCount()).isZero();
        assertThat(outboxRows(user)).isZero();
        // The next send is that evening, not a stale slot in the past.
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getNextRunAt())
                .isEqualTo(ist(day.plusDays(1), 19, 0));
    }

    @Test
    void sendNowThenAnOutageGivesMissedAndTheDayAfterNotTwoDaysLate() {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        // State right after "send now at 15:00": the slot for `day` was consumed.
        setNextRunAt(campaign.id(), ist(day.plusDays(1), 19, 0));

        // Outage 18:00-22:30 the next day.
        List<UUID> runIds = scheduler.claimDueRuns(ist(day.plusDays(1), 22, 30));

        assertThat(runIds).isEmpty();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id()).get(0).getStatus())
                .isEqualTo(RunStatus.MISSED);
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getNextRunAt())
                .isEqualTo(ist(day.plusDays(2), 19, 0));
    }

    @Test
    void aLateRunThatWouldLandAfterTwentyOneFiftyNineIsMissed() {
        eligibleUser();
        PushCampaignDto campaign = service.create(adminId, daily(LocalTime.of(21, 30), null));
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 21, 30));

        assertThat(scheduler.claimDueRuns(ist(day, 22, 5))).isEmpty();
        PushCampaignRun run = runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id()).get(0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.MISSED);
        assertThat(run.getNote()).contains("07:00-21:59");
    }

    @Test
    void aOneOffCompletesWhenSent_andPausesWhenMissed() {
        eligibleUser();
        LocalDate day = LocalDate.of(2026, 10, 6);
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        // The service only accepts a future time inside the window; build one, then place its slot.
        Instant inWindow = IstClock.at(IstClock.dateOf(future), LocalTime.of(12, 0));
        PushCampaignDto sent = service.create(adminId, new PushCampaignSaveRequest("Once", "T", "M",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.ONCE_AT, inWindow, null, null));
        PushCampaignDto missed = service.create(adminId, new PushCampaignSaveRequest("Once late", "T", "M",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.ONCE_AT, inWindow, null, null));
        service.start(adminId, sent.id());
        service.start(adminId, missed.id());
        setNextRunAt(sent.id(), ist(day, 12, 0));
        setNextRunAt(missed.id(), ist(day, 12, 0));

        // Only `sent` is claimed on time; claim `missed` three hours late by claiming it alone.
        assertThat(service.claimDue(sent.id(), ist(day, 12, 1))).isPresent();
        assertThat(service.claimDue(missed.id(), ist(day, 15, 0))).isEmpty();

        assertThat(campaigns.findById(sent.id()).orElseThrow().getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        PushCampaign pausedOne = campaigns.findById(missed.id()).orElseThrow();
        assertThat(pausedOne.getStatus()).isEqualTo(CampaignStatus.PAUSED);
        assertThat(pausedOne.getNextRunAt()).isNull();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(missed.id()).get(0).getStatus())
                .isEqualTo(RunStatus.MISSED);
    }

    @Test
    void aDailyCampaignCompletesAfterItsEndDate() {
        eligibleUser();
        LocalDate day = IstClock.dateOf(Instant.now().plus(1, ChronoUnit.DAYS));
        PushCampaignDto campaign = service.create(adminId, daily(SEVEN_PM, day));
        service.start(adminId, campaign.id());
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        Optional<UUID> run = service.claimDue(campaign.id(), ist(day, 19, 0));

        assertThat(run).isPresent();
        PushCampaign after = campaigns.findById(campaign.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(CampaignStatus.COMPLETED);
        assertThat(after.getNextRunAt()).isNull();
    }

    @Test
    void pauseAndStopPreventRuns() {
        PushCampaignDto paused = createDaily();
        PushCampaignDto stopped = createDaily();
        service.start(adminId, paused.id());
        service.start(adminId, stopped.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        service.pause(adminId, paused.id());
        service.stop(adminId, stopped.id());
        setNextRunAt(paused.id(), ist(day, 19, 0)); // even if a slot were somehow still set
        setNextRunAt(stopped.id(), ist(day, 19, 0));

        assertThat(scheduler.claimDueRuns(ist(day, 19, 1))).isEmpty();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(paused.id())).isEmpty();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(stopped.id())).isEmpty();
    }

    @Test
    void twoServersClaimingTheSameSlotGiveExactlyOneWinner() throws Exception {
        eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));
        Instant now = ist(day, 19, 1);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return service.claimDue(campaign.id(), now);
                }));
            }
            go.countDown();
            int winners = 0;
            for (Future<Optional<UUID>> f : futures) {
                if (f.get(30, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id())).hasSize(1);
    }

    @Test
    void whileTheMasterSwitchIsOffNothingIsClaimedAndSendNowAndTestAreRefused() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        setSwitch(false);

        assertThat(scheduler.claimDueRuns(ist(day, 19, 1))).isEmpty();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id())).isEmpty();
        assertThatThrownBy(() -> service.sendNow(adminId, campaign.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("switched off");
        assertThatThrownBy(() -> service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(user, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("switched off");

        // Back on: the slot that waited is handled by the missed-run rule (on time here, so sent).
        setSwitch(true);
        assertThat(scheduler.claimDueRuns(ist(day, 19, 5))).hasSize(1);
    }

    // ------------------------------------------------------------------ rollout limit

    @Test
    void anAudienceOverTheRolloutLimitIsRefusedAtSendNowAndStart() {
        eligibleUsers(3);
        ReflectionTestUtils.setField(runner, "maxAudience", 2L);
        PushCampaignDto now = createNowOnly();
        PushCampaignDto daily = createDaily();

        assertThatThrownBy(() -> service.sendNow(adminId, now.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("rollout limit");
        assertThatThrownBy(() -> service.start(adminId, daily.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("rollout limit");
        assertThat(service.audienceCount(AudienceType.ALL_WITH_DEVICE).rolloutLimit()).isEqualTo(2L);
        assertThat(service.audienceCount(AudienceType.ALL_WITH_DEVICE).count()).isEqualTo(3L);
    }

    @Test
    void aScheduledRunWhoseAudienceGrewPastTheLimitFailsAndSendsNothing() {
        List<UUID> users = eligibleUsers(2);
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        ReflectionTestUtils.setField(runner, "maxAudience", 1L);
        LocalDate day = LocalDate.of(2026, 10, 6);
        setNextRunAt(campaign.id(), ist(day, 19, 0));

        List<UUID> runIds = scheduler.claimDueRuns(ist(day, 19, 1));
        runner.execute(runIds.get(0));

        PushCampaignRun run = runs.findById(runIds.get(0)).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getNote()).contains("rollout limit");
        users.forEach(u -> assertThat(outboxRows(u)).isZero());
    }

    // ------------------------------------------------------------------ test sends

    @Test
    void aTestSendDoesNotConsumeTheCapCreateARunOrBlockTheRealSend() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createDaily();

        PushCampaignTestResultDto result = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(user, null));

        assertThat(result.queued()).isTrue();
        assertThat(runs.findTop50ByCampaignIdOrderByStartedAtDesc(campaign.id())).isEmpty();
        assertThat(dailyCap.holder(user, IstClock.dateOf(Instant.now()))).isEmpty();
        var testRow = jdbc.queryForMap("SELECT priority, notification_key FROM notifications "
                + "WHERE type = 'CUSTOM_PUSH' AND user_id = ?", user);
        assertThat(testRow.get("priority")).isEqualTo("NORMAL");
        assertThat((String) testRow.get("notification_key")).startsWith("PUSHCAMPAIGNTEST_")
                .doesNotStartWith(CampaignEnqueuer.KEY_PREFIX);
        PushCampaign tested = campaigns.findById(campaign.id()).orElseThrow();
        assertThat(tested.getLastTestedAt()).isNotNull();
        assertThat(tested.getLastTestedBy()).isEqualTo(adminId);

        // The real send to the same person the same day still delivers, and so does a second test.
        PushCampaignRunDto run = sendNowAndWait(campaign.id());
        assertThat(run.queuedCount()).isEqualTo(1);
        assertThat(service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(user, null)).queued()).isTrue();
        assertThat(outboxRows(user)).isEqualTo(3);
    }

    @Test
    void aTestSendSaysPlainlyWhyNothingWasQueued() {
        PushCampaignDto campaign = createNowOnly();

        UUID noDevice = newUser(User.STATUS_ACTIVE, User.SCOPE_USER, null).getId();
        PushCampaignTestResultDto none = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(noDevice, null));
        assertThat(none.queued()).isFalse();
        assertThat(none.detail()).contains("no registered device");

        UUID off = eligibleUser();
        switchPushOff(off);
        PushCampaignTestResultDto switchedOff = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(off, null));
        assertThat(switchedOff.queued()).isFalse();
        assertThat(switchedOff.detail()).contains("switched off");

        UUID suspended = newUser(User.STATUS_SUSPENDED, User.SCOPE_USER, null).getId();
        PushCampaignTestResultDto susp = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(suspended, null));
        assertThat(susp.queued()).isFalse();
        assertThat(susp.detail()).contains("SUSPENDED");

        assertThat(outboxRows(noDevice) + outboxRows(off) + outboxRows(suspended)).isZero();
    }

    @Test
    void aTestByEmailTargetsTheEndUserAccountNotTheAdminAccountSharingThatEmail() {
        String email = "shared-" + UUID.randomUUID() + "@example.com";
        User admin = newUser(User.STATUS_ACTIVE, User.SCOPE_ADMIN, email);
        User endUser = newUser(User.STATUS_ACTIVE, User.SCOPE_USER, email);
        deviceTokenService.register(endUser.getId(), "ANDROID", "shared-email-token");
        PushCampaignDto campaign = createNowOnly();

        PushCampaignTestResultDto result = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(null, email));

        assertThat(result.queued()).isTrue();
        assertThat(outboxRows(endUser.getId())).isEqualTo(1);
        assertThat(outboxRows(admin.getId())).isZero();
    }

    @Test
    void aTestNeedsExactlyOneTargetAndAnAdminAccountIsNotATarget() {
        PushCampaignDto campaign = createNowOnly();
        assertThatThrownBy(() -> service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> service.sendTest(adminId, campaign.id(),
                new PushCampaignTestRequest(UUID.randomUUID(), "a@example.com")))
                .isInstanceOf(ApiException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(adminId, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("end-user");
        assertThatThrownBy(() -> service.sendTest(adminId, campaign.id(),
                new PushCampaignTestRequest(UUID.randomUUID(), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("No such user");
    }

    // ------------------------------------------------------------------ lifecycle and validation

    @Test
    void theLifecycleRefusesIllegalMoves() {
        PushCampaignDto campaign = createDaily();

        assertThatThrownBy(() -> service.pause(adminId, campaign.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resume(adminId, campaign.id())).isInstanceOf(ApiException.class);

        service.start(adminId, campaign.id());
        assertThatThrownBy(() -> service.start(adminId, campaign.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.update(adminId, campaign.id(), daily(SEVEN_PM, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("draft or paused");

        service.pause(adminId, campaign.id());
        assertThat(service.update(adminId, campaign.id(), daily(LocalTime.of(20, 0), null)).sendTimeIst())
                .isEqualTo(LocalTime.of(20, 0));
        assertThat(service.resume(adminId, campaign.id()).status()).isEqualTo(CampaignStatus.ACTIVE);

        service.stop(adminId, campaign.id());
        assertThatThrownBy(() -> service.stop(adminId, campaign.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resume(adminId, campaign.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.sendNow(adminId, campaign.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void aPausedCampaignEditedToSendNowOnlyCannotBeResumed() {
        // Resuming works out the next slot; a send-now-only campaign has none (this used to be a
        // NullPointerException on the missing send time, reachable only through pause -> edit -> resume).
        PushCampaignDto campaign = createDaily();
        service.start(adminId, campaign.id());
        service.pause(adminId, campaign.id());
        service.update(adminId, campaign.id(), nowOnly("Now only"));

        assertThatThrownBy(() -> service.resume(adminId, campaign.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("no schedule");
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getStatus()).isEqualTo(CampaignStatus.PAUSED);
    }

    @Test
    void anEditFromAStaleVersionIsRefusedInsteadOfOverwritingSomeoneElsesChange() {
        PushCampaignDto loaded = createDaily();
        // Another admin saves first.
        service.update(adminId, loaded.id(), new PushCampaignSaveRequest("IT daily", "Their title", "Their message.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, SEVEN_PM, null));

        // This admin still holds the version they loaded.
        PushCampaignSaveRequest stale = new PushCampaignSaveRequest("IT daily", "My title", "My message.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, SEVEN_PM, null, loaded.version());
        assertThatThrownBy(() -> service.update(adminId, loaded.id(), stale))
                .isInstanceOf(ApiException.class).hasMessageContaining("changed by someone else");
        assertThat(service.get(loaded.id()).campaign().title()).isEqualTo("Their title");

        // With the current version it goes through; without one it is not checked.
        long current = service.get(loaded.id()).campaign().version();
        assertThat(service.update(adminId, loaded.id(), new PushCampaignSaveRequest("IT daily", "Fresh title",
                "Fresh message.", AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, SEVEN_PM, null,
                current)).title()).isEqualTo("Fresh title");
        assertThat(service.update(adminId, loaded.id(), daily(SEVEN_PM, null)).title()).isEqualTo("Daily reminder");
    }

    @Test
    void theAuditTrailKeepsTheMessageAnAdminSaved() {
        PushCampaignDto campaign = service.create(adminId, new PushCampaignSaveRequest("n", "A title",
                "The exact words saved.", AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null));
        service.update(adminId, campaign.id(), new PushCampaignSaveRequest("n", "A title", "Changed words.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null));

        List<String> messages = jdbc.queryForList("SELECT metadata->>'message' FROM audit_logs WHERE entity_id = ? "
                + "AND action IN ('PUSH_CAMPAIGN_CREATED', 'PUSH_CAMPAIGN_UPDATED') ORDER BY created_at",
                String.class, campaign.id());
        assertThat(messages).containsExactly("The exact words saved.", "Changed words.");
    }

    @Test
    void aNowOnlyCampaignCannotBeStarted() {
        PushCampaignDto campaign = createNowOnly();
        assertThatThrownBy(() -> service.start(adminId, campaign.id()))
                .isInstanceOf(ApiException.class).hasMessageContaining("Send now");
    }

    @Test
    void startAndResumeWorkOutTheNextSlot() {
        PushCampaignDto campaign = createDaily();
        PushCampaignDto started = service.start(adminId, campaign.id());

        assertThat(started.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(started.nextRunAt()).isAfter(Instant.now());
        assertThat(IstClock.timeOf(started.nextRunAt())).isEqualTo(SEVEN_PM);

        service.pause(adminId, campaign.id());
        assertThat(campaigns.findById(campaign.id()).orElseThrow().getNextRunAt()).isNull();
        assertThat(service.resume(adminId, campaign.id()).nextRunAt()).isAfter(Instant.now());
    }

    @Test
    void cloneMakesADraftCopyOfTheWordsAudienceAndSchedule() {
        PushCampaignDto original = service.create(adminId, daily(LocalTime.of(8, 30), null));
        service.start(adminId, original.id());
        service.stop(adminId, original.id());

        PushCampaignDto copy = service.clone(adminId, original.id());

        assertThat(copy.id()).isNotEqualTo(original.id());
        assertThat(copy.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(copy.nextRunAt()).isNull();
        assertThat(copy.name()).isEqualTo("Copy of IT daily");
        assertThat(copy.title()).isEqualTo(original.title());
        assertThat(copy.message()).isEqualTo(original.message());
        assertThat(copy.audienceType()).isEqualTo(original.audienceType());
        assertThat(copy.sendTimeIst()).isEqualTo(LocalTime.of(8, 30));
    }

    @Test
    void validationRejectsQuietHoursPastTimesBadShapesAndControlCharacters() {
        // Scheduled sends only between 07:00 and 21:59 IST.
        assertThatThrownBy(() -> service.create(adminId, daily(LocalTime.of(3, 0), null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("07:00");
        assertThatThrownBy(() -> service.create(adminId, daily(LocalTime.of(22, 0), null)))
                .isInstanceOf(ApiException.class);
        assertThat(service.create(adminId, daily(LocalTime.of(21, 59), null)).sendTimeIst())
                .isEqualTo(LocalTime.of(21, 59));
        assertThat(service.create(adminId, daily(LocalTime.of(7, 0), null)).sendTimeIst())
                .isEqualTo(LocalTime.of(7, 0));

        // A one-off needs a future time.
        assertThatThrownBy(() -> service.create(adminId, new PushCampaignSaveRequest("n", "t", "m",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.ONCE_AT,
                Instant.now().minus(1, ChronoUnit.DAYS), null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("future");

        // Fields that do not belong to the kind.
        assertThatThrownBy(() -> service.create(adminId, new PushCampaignSaveRequest("n", "t", "m",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, SEVEN_PM, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, new PushCampaignSaveRequest("n", "t", "m",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, null, null)))
                .isInstanceOf(ApiException.class);

        // End date in the past.
        assertThatThrownBy(() -> service.create(adminId, daily(SEVEN_PM, LocalDate.now().minusDays(3))))
                .isInstanceOf(ApiException.class).hasMessageContaining("past");

        // Blank, over-long, control characters.
        assertThatThrownBy(() -> service.create(adminId, nowOnly("   "))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(adminId, nowOnly("x".repeat(81))))
                .isInstanceOf(ApiException.class).hasMessageContaining("80");
        assertThatThrownBy(() -> service.create(adminId, nowOnly("bad\u0007title")))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot be sent");
        // Exactly 80 and a newline in the message are fine.
        assertThat(service.create(adminId, nowOnly("x".repeat(80))).title()).hasSize(80);
        assertThat(service.create(adminId, new PushCampaignSaveRequest("n", "t", "line one\nline two",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null)).message())
                .contains("\n");
    }

    @Test
    void anAdminsWordsAreStoredAsTypedAndCannotInjectTemplatePlaceholders() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = service.create(adminId, new PushCampaignSaveRequest("n", "{{message}} hi", "{{title}}",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null));
        sendNowAndWait(campaign.id());

        var row = jdbc.queryForMap("SELECT title, message FROM notifications "
                + "WHERE type = 'CUSTOM_PUSH' AND user_id = ?", user);
        assertThat(row.get("title")).isEqualTo("{{message}} hi");
        assertThat(row.get("message")).isEqualTo("{{title}}");
    }

    // ------------------------------------------------------------------ audit

    @Test
    void everyAdminActionIsAuditedWithTheActingAdmin() {
        UUID user = eligibleUser();
        PushCampaignDto campaign = createDaily();
        service.update(adminId, campaign.id(), daily(LocalTime.of(20, 0), null));
        service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(user, null));
        service.start(adminId, campaign.id());
        service.pause(adminId, campaign.id());
        service.resume(adminId, campaign.id());
        sendNowAndWait(campaign.id());
        PushCampaignDto cloned = service.clone(adminId, campaign.id());
        service.stop(adminId, campaign.id());

        for (String action : List.of("PUSH_CAMPAIGN_CREATED", "PUSH_CAMPAIGN_UPDATED",
                "PUSH_CAMPAIGN_TESTED", "PUSH_CAMPAIGN_STARTED", "PUSH_CAMPAIGN_PAUSED",
                "PUSH_CAMPAIGN_RESUMED", "PUSH_CAMPAIGN_SENT_NOW", "PUSH_CAMPAIGN_STOPPED")) {
            assertThat(auditCount(campaign.id(), action)).as(action).isEqualTo(1);
        }
        assertThat(auditCount(cloned.id(), "PUSH_CAMPAIGN_CLONED")).isEqualTo(1);
        Integer actorInMetadata = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ? "
                + "AND metadata->>'actorId' = ?", Integer.class, campaign.id(), adminId.toString());
        assertThat(actorInMetadata).isGreaterThanOrEqualTo(8);
    }

    private int auditCount(UUID campaignId, String action) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE entity_id = ? "
                + "AND action = ? AND user_id = ?", Integer.class, campaignId, action, adminId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ housekeeping

    @Test
    void housekeepingFailsInterruptedRunsAndPrunesOldCapRowsButKeepsRecentOnes() {
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        Instant now = Instant.now();
        PushCampaignRun stale = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(now), null,
                RunTrigger.ADMIN_NOW, adminId, now.minus(3, ChronoUnit.HOURS)));
        PushCampaignRun fresh = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(now), null,
                RunTrigger.ADMIN_NOW, adminId, now.minus(10, ChronoUnit.MINUTES)));
        UUID user = UUID.randomUUID();
        LocalDate today = IstClock.dateOf(now);
        dailyCap.claim(user, today.minusDays(31), campaign.id());
        dailyCap.claim(user, today.minusDays(5), campaign.id());
        dailyCap.claim(user, today, campaign.id());

        service.housekeeping(now, true);

        assertThat(runs.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(runs.findById(stale.getId()).orElseThrow().getNote()).contains("Interrupted");
        assertThat(runs.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(dailyCap.holder(user, today.minusDays(31))).isEmpty();
        assertThat(dailyCap.holder(user, today.minusDays(5))).isPresent();
        assertThat(dailyCap.holder(user, today)).isPresent();
    }
}
