package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PushCampaignDtos.PushCampaignCancelResultDto;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignRunDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestRequest;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
import com.finora.repository.UserRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The emergency brake. Stopping a campaign only ends future runs; a mistaken message sent to
 * thousands of people would otherwise keep delivering for hours. These cover: what a cancel
 * withdraws and what it must leave alone, the slots it gives back (so a corrected message the same
 * day reaches the same people), and the race where the cancel lands while a run is still queuing.
 *
 * <p>Same fixture rules as {@link PushCampaignIT}: the audience is global, so every live device is
 * revoked first and each test works with exactly the users it creates.
 */
class PushCampaignCancelIT extends AbstractIntegrationTest {

    @Autowired private PushCampaignService service;
    @Autowired private PushCampaignRunner runner;
    @MockitoSpyBean private CampaignEnqueuer enqueuer;
    @Autowired private DailyCapStore dailyCap;
    @Autowired private PushCampaignRepository campaigns;
    @Autowired private PushCampaignRunRepository runs;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private JdbcTemplate jdbc;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("UPDATE device_tokens SET revoked_at = now() WHERE revoked_at IS NULL");
        adminId = newUser(User.SCOPE_ADMIN).getId();
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(runner, "pageSize", 200);
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM notification_logs WHERE notification_id IN "
                + "(SELECT id FROM notifications WHERE type = 'CUSTOM_PUSH')");
        jdbc.update("DELETE FROM notifications WHERE type = 'CUSTOM_PUSH'");
        jdbc.update("DELETE FROM custom_push_daily_cap");
        jdbc.update("DELETE FROM push_campaign_runs");
        jdbc.update("DELETE FROM push_campaigns");
    }

    private User newUser(String scope) {
        User user = new User();
        user.setEmail("push-cancel-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Push Cancel IT User");
        user.setAccountScope(scope);
        return userRepository.save(user);
    }

    private List<UUID> eligibleUsers(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = newUser(User.SCOPE_USER).getId();
            deviceTokenService.register(id, "ANDROID", "cancel-it-token-" + UUID.randomUUID());
            ids.add(id);
        }
        return ids;
    }

    private PushCampaignDto createNowOnly(String title) {
        return service.create(adminId, new PushCampaignSaveRequest("Cancel IT", title, "Body text.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null));
    }

    private PushCampaignDto createDaily() {
        return service.create(adminId, new PushCampaignSaveRequest("Cancel IT daily", "Daily", "Body text.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, LocalTime.of(19, 0), null));
    }

    private PushCampaignRunDto sendNowAndWait(UUID campaignId) {
        UUID runId = service.sendNow(adminId, campaignId).id();
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
        throw new AssertionError("run did not finish");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private int rowsWithStatus(UUID campaignId, String status) {
        return count("SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' "
                + "AND notification_key LIKE ? AND status = ?", "PUSHCAMPAIGN\\_" + campaignId + "\\_%", status);
    }

    private int capRows(UUID campaignId) {
        return count("SELECT count(*) FROM custom_push_daily_cap WHERE campaign_id = ?", campaignId);
    }

    // ------------------------------------------------------------------ what a cancel does

    @Test
    void cancelWithdrawsQueuedPushesAndGivesTheSlotBack_soACorrectedMessageReachesTheSamePeople() {
        eligibleUsers(3);
        PushCampaignDto wrong = createNowOnly("Wrong message");
        sendNowAndWait(wrong.id());
        assertThat(rowsWithStatus(wrong.id(), "QUEUED")).isEqualTo(3);
        assertThat(capRows(wrong.id())).isEqualTo(3);

        PushCampaignCancelResultDto result = service.cancelSending(adminId, wrong.id());

        assertThat(result.cancelledPushes()).isEqualTo(3);
        assertThat(result.releasedSlots()).isEqualTo(3);
        assertThat(rowsWithStatus(wrong.id(), "CANCELLED")).isEqualTo(3);
        assertThat(rowsWithStatus(wrong.id(), "QUEUED")).isZero();
        assertThat(capRows(wrong.id())).isZero();

        // The same day, the corrected message reaches all three: their slots were given back.
        PushCampaignDto corrected = createNowOnly("Right message");
        PushCampaignRunDto run = sendNowAndWait(corrected.id());
        assertThat(run.queuedCount()).isEqualTo(3);
        assertThat(run.skippedCapCount()).isZero();

        // And the wrong campaign's own history shows what happened, without calling them failures.
        var delivery = service.get(wrong.id()).runs().get(0);
        assertThat(delivery.cancelled()).isEqualTo(3);
        assertThat(delivery.failed()).isZero();
        assertThat(delivery.pending()).isZero();
        // The campaign itself is left as it was: a one-off that already ran stays COMPLETED.
        assertThat(campaigns.findById(wrong.id()).orElseThrow().getStatus()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void afterACancelTheSameCampaignCanBeFixedAndSentAgainTheSameDay() {
        // A draft daily campaign can be sent now repeatedly. Send, cancel, fix the text, send again.
        List<UUID> users = eligibleUsers(3);
        PushCampaignDto campaign = createDaily();
        sendNowAndWait(campaign.id());
        service.cancelSending(adminId, campaign.id());
        service.update(adminId, campaign.id(), new PushCampaignSaveRequest("Cancel IT daily", "Fixed title",
                "Fixed body.", AudienceType.ALL_WITH_DEVICE, ScheduleKind.DAILY_AT, null, LocalTime.of(19, 0), null));

        PushCampaignRunDto again = sendNowAndWait(campaign.id());

        // Nobody is skipped as "already queued": the withdrawn rows no longer hold the dedupe key.
        assertThat(again.queuedCount()).isEqualTo(3);
        assertThat(again.skippedAlreadyQueuedCount()).isZero();
        users.forEach(u -> assertThat(count("SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' "
                + "AND user_id = ? AND status = 'QUEUED' AND title = 'Fixed title'", u)).isEqualTo(1));
        assertThat(rowsWithStatus(campaign.id(), "CANCELLED")).isEqualTo(3); // history kept
        assertThat(rowsWithStatus(campaign.id(), "QUEUED")).isEqualTo(3);
    }

    @Test
    void onlyQueuedPushesAreWithdrawn_sentProcessingFailedAndTestPushesAreLeftAlone() {
        List<UUID> users = eligibleUsers(5);
        PushCampaignDto campaign = createNowOnly("Hello");
        sendNowAndWait(campaign.id());
        set(users.get(0), "SENT");
        set(users.get(1), "PROCESSING");
        set(users.get(2), "DEAD_LETTER");
        set(users.get(3), "RETRYING");
        // users.get(4) stays QUEUED.
        // A test push to a sixth person, queued under its own key prefix.
        UUID sixth = eligibleUsers(1).get(0);
        assertThat(service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(sixth, null)).queued()).isTrue();

        PushCampaignCancelResultDto result = service.cancelSending(adminId, campaign.id());

        assertThat(result.cancelledPushes()).isEqualTo(2); // the RETRYING one and the QUEUED one
        assertThat(result.releasedSlots()).isEqualTo(2);
        assertThat(statusOf(users.get(0))).isEqualTo("SENT");
        assertThat(statusOf(users.get(1))).isEqualTo("PROCESSING");
        assertThat(statusOf(users.get(2))).isEqualTo("DEAD_LETTER");
        assertThat(statusOf(users.get(3))).isEqualTo("CANCELLED");
        assertThat(statusOf(users.get(4))).isEqualTo("CANCELLED");
        assertThat(statusOf(sixth)).isEqualTo("QUEUED");
        // Slots are kept for the people who were (or are being) sent to.
        assertThat(dailyCap.holder(users.get(0), IstClock.dateOf(Instant.now()))).isPresent();
        assertThat(dailyCap.holder(users.get(1), IstClock.dateOf(Instant.now()))).isPresent();
        assertThat(dailyCap.holder(users.get(2), IstClock.dateOf(Instant.now()))).isPresent();
        assertThat(dailyCap.holder(users.get(3), IstClock.dateOf(Instant.now()))).isEmpty();
        assertThat(dailyCap.holder(users.get(4), IstClock.dateOf(Instant.now()))).isEmpty();
    }

    private void set(UUID userId, String status) {
        jdbc.update("UPDATE notifications SET status = ? WHERE type = 'CUSTOM_PUSH' AND user_id = ?", status, userId);
    }

    private String statusOf(UUID userId) {
        return jdbc.queryForObject("SELECT status FROM notifications WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                String.class, userId);
    }

    @Test
    void cancelOnlyTouchesItsOwnCampaign() {
        eligibleUsers(2);
        PushCampaignDto a = createNowOnly("A");
        PushCampaignDto b = createDaily();
        sendNowAndWait(a.id()); // reaches both people
        PushCampaignRunDto runB = sendNowAndWait(b.id()); // every one skipped: same-day cap
        assertThat(runB.skippedCapCount()).isEqualTo(2);

        assertThat(service.cancelSending(adminId, b.id()).cancelledPushes()).isZero();
        assertThat(rowsWithStatus(a.id(), "QUEUED")).isEqualTo(2);
        assertThat(capRows(a.id())).isEqualTo(2);
    }

    @Test
    void cancelIsIdempotentAndAuditedWithTheActingAdmin() {
        eligibleUsers(2);
        PushCampaignDto campaign = createNowOnly("Hello");
        sendNowAndWait(campaign.id());

        assertThat(service.cancelSending(adminId, campaign.id()).cancelledPushes()).isEqualTo(2);
        PushCampaignCancelResultDto again = service.cancelSending(adminId, campaign.id());

        assertThat(again.cancelledPushes()).isZero();
        assertThat(again.releasedSlots()).isZero();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = "
                + "'PUSH_CAMPAIGN_SENDING_CANCELLED' AND user_id = ? AND metadata->>'actorId' = ?",
                campaign.id(), adminId, adminId.toString())).isEqualTo(2);
    }

    @Test
    void stopAlsoWithdrawsWhatIsStillQueued() {
        eligibleUsers(3);
        PushCampaignDto daily = createDaily(); // a draft daily campaign can be sent now
        sendNowAndWait(daily.id());

        PushCampaignDto stopped = service.stop(adminId, daily.id());

        assertThat(stopped.status()).isEqualTo(CampaignStatus.STOPPED);
        assertThat(rowsWithStatus(daily.id(), "CANCELLED")).isEqualTo(3);
        assertThat(rowsWithStatus(daily.id(), "QUEUED")).isZero();
        assertThat(capRows(daily.id())).isZero();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE entity_id = ? AND action = 'PUSH_CAMPAIGN_STOPPED' "
                + "AND (metadata->>'cancelledPushes')::int = 3", daily.id())).isEqualTo(1);
    }

    @Test
    void cancelWorksOnAFinishedCampaignAndNeverFailsForNothingToCancel() {
        PushCampaignDto empty = createNowOnly("Nobody");
        assertThat(service.cancelSending(adminId, empty.id()).cancelledPushes()).isZero();
        PushCampaignDto stopped = createDaily();
        service.stop(adminId, stopped.id());
        assertThat(service.cancelSending(adminId, stopped.id()).cancelledPushes()).isZero();
    }

    // ------------------------------------------------------------------ the race with a run in progress

    @Test
    void aRunCancelledBeforeItStartsQueuesNobody() {
        List<UUID> users = eligibleUsers(3);
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        PushCampaignRun run = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(Instant.now()), null,
                RunTrigger.ADMIN_NOW, adminId, Instant.now()));

        service.cancelSending(adminId, campaign.id());
        runner.execute(run.getId());

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        users.forEach(u -> assertThat(count("SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' "
                + "AND user_id = ?", u)).isZero());
    }

    @Test
    void aRunCancelledWhileItIsQueuingStopsAtOnce_andWithdrawsWhatItCommittedAfterTheCancelLooked() {
        eligibleUsers(5);
        ReflectionTestUtils.setField(runner, "pageSize", 1); // one person per page: five pages
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        PushCampaignRun run = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(Instant.now()), null,
                RunTrigger.ADMIN_NOW, adminId, Instant.now()));
        // The admin's cancel lands just before the first page commits: that page's push is queued
        // AFTER the cancel withdrew everything, so only the runner's own sweep can catch it.
        doAnswer(invocation -> {
            service.cancelSending(adminId, campaign.id());
            return invocation.callRealMethod();
        }).doCallRealMethod().when(enqueuer).enqueuePage(any(), anyString(), anyString(), any(), anyList());

        runner.execute(run.getId());

        verify(enqueuer, times(1)).enqueuePage(any(), anyString(), anyString(), any(), anyList());
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(rowsWithStatus(campaign.id(), "QUEUED")).isZero();
        assertThat(rowsWithStatus(campaign.id(), "CANCELLED")).isEqualTo(1);
        assertThat(capRows(campaign.id())).isZero();
    }

    @Test
    void aRunTheSweepJudgedInterruptedStopsQueuingButWithdrawsNothing() {
        eligibleUsers(4);
        ReflectionTestUtils.setField(runner, "pageSize", 1);
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        PushCampaignRun run = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(Instant.now()), null,
                RunTrigger.ADMIN_NOW, adminId, Instant.now()));
        // After the first page the run is marked FAILED by something other than an admin cancel.
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            jdbc.update("UPDATE push_campaign_runs SET status = 'FAILED', note = 'Interrupted' WHERE id = ?",
                    run.getId());
            return result;
        }).doCallRealMethod().when(enqueuer).enqueuePage(any(), anyString(), anyString(), any(), anyList());

        runner.execute(run.getId());

        verify(enqueuer, times(1)).enqueuePage(any(), anyString(), anyString(), any(), anyList());
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        // What was legitimately queued is not taken away from the person who got it.
        assertThat(rowsWithStatus(campaign.id(), "QUEUED")).isEqualTo(1);
        assertThat(rowsWithStatus(campaign.id(), "CANCELLED")).isZero();
    }

    // ------------------------------------------------------------------ heartbeat

    @Test
    void aSlowButAliveRunIsNotJudgedInterrupted_onlyOneWithAStaleHeartbeatIs() {
        PushCampaignDto campaign = createDaily();
        PushCampaign entity = campaigns.findById(campaign.id()).orElseThrow();
        Instant now = Instant.now();
        Instant started = now.minus(3, ChronoUnit.HOURS);
        PushCampaignRun alive = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(now), null,
                RunTrigger.ADMIN_NOW, adminId, started));
        PushCampaignRun dead = runs.save(PushCampaignRun.starting(entity, IstClock.dateOf(now), null,
                RunTrigger.ADMIN_NOW, adminId, started));
        jdbc.update("UPDATE push_campaign_runs SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(1, ChronoUnit.MINUTES)), alive.getId());
        jdbc.update("UPDATE push_campaign_runs SET updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(40, ChronoUnit.MINUTES)), dead.getId());

        service.housekeeping(now, false);

        assertThat(runs.findById(alive.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(runs.findById(dead.getId()).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void everyPageOfARunMovesItsHeartbeat() {
        eligibleUsers(3);
        PushCampaignDto campaign = createDaily();
        PushCampaignRunDto first = sendNowAndWait(campaign.id());
        PushCampaignRun finished = runs.findById(first.id()).orElseThrow();

        assertThat(finished.getUpdatedAt()).isAfterOrEqualTo(finished.getStartedAt());
        assertThat(finished.getStatus()).isEqualTo(RunStatus.DONE);
        assertThat(finished.getFinishedAt()).isNotNull();
    }
}
