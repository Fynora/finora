package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignRunDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignSettingsRequest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.DeviceTokenService;
import com.finora.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin-set daily limit: how many campaign pushes one person may get per IST day, across all
 * campaigns. It was fixed at one (V261) and is now a setting (V262); the default of one reproduces
 * exactly what shipped, so these tests drive the cases that are new -- several slots, a limit
 * changed between campaigns, the same campaign never taking two slots, a cancel giving one back,
 * and many callers racing for the last slot.
 *
 * <p>Same fixture rules as {@link PushCampaignIT}: the audience is global, so every live device is
 * revoked first and each test works with exactly the users it creates.
 */
class PushCampaignDailyLimitIT extends AbstractIntegrationTest {

    @Autowired private PushCampaignService service;
    @Autowired private CampaignEnqueuer enqueuer;
    @Autowired private CampaignCancellation cancellation;
    @Autowired private DailyCapStore dailyCap;
    @Autowired private PushCampaignSettingsStore settingsStore;
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
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM notification_logs WHERE notification_id IN "
                + "(SELECT id FROM notifications WHERE type = 'CUSTOM_PUSH')");
        jdbc.update("DELETE FROM notifications WHERE type = 'CUSTOM_PUSH'");
        jdbc.update("DELETE FROM custom_push_daily_cap");
        jdbc.update("UPDATE push_campaign_settings SET daily_limit_per_person = 1, updated_by = NULL WHERE id = 1");
        jdbc.update("DELETE FROM push_campaign_runs");
        jdbc.update("DELETE FROM push_campaigns");
    }

    // ------------------------------------------------------------------ fixtures

    private User newUser(String scope) {
        User user = new User();
        user.setEmail("push-limit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Push Limit IT User");
        user.setAccountScope(scope);
        return userRepository.save(user);
    }

    private List<UUID> eligibleUsers(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = newUser(User.SCOPE_USER).getId();
            deviceTokenService.register(id, "ANDROID", "limit-it-token-" + UUID.randomUUID());
            ids.add(id);
        }
        return ids;
    }

    private void setLimit(int limit) {
        service.updateSettings(adminId, new PushCampaignSettingsRequest(limit));
    }

    private PushCampaignDto createNowOnly(String title) {
        return service.create(adminId, new PushCampaignSaveRequest("Limit IT", title, "Body text.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null));
    }

    /** Creates a campaign, sends it now and waits for the run to finish. */
    private PushCampaignRunDto sendCampaign(String title) {
        UUID campaignId = createNowOnly(title).id();
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
        throw new AssertionError("run did not finish in 20 seconds");
    }

    private int pushesFor(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' "
                + "AND user_id = ? AND status <> 'CANCELLED'", Integer.class, userId);
        return n == null ? 0 : n;
    }

    private static LocalDate today() {
        return IstClock.dateOf(Instant.now());
    }

    // ------------------------------------------------------------------ the default

    @Test
    void theDefaultIsOneAPerson_soASecondCampaignSkipsEveryoneTheFirstReached() {
        List<UUID> users = eligibleUsers(3);

        PushCampaignRunDto first = sendCampaign("First");
        PushCampaignRunDto second = sendCampaign("Second");

        assertThat(first.queuedCount()).isEqualTo(3);
        assertThat(second.queuedCount()).isZero();
        assertThat(second.skippedCapCount()).isEqualTo(3);
        users.forEach(u -> assertThat(pushesFor(u)).isEqualTo(1));
    }

    // ------------------------------------------------------------------ several a day

    @Test
    void withALimitOfTwo_twoCampaignsReachEveryone_andAThirdIsCapped() {
        List<UUID> users = eligibleUsers(3);
        setLimit(2);

        PushCampaignRunDto first = sendCampaign("First");
        PushCampaignRunDto second = sendCampaign("Second");
        PushCampaignRunDto third = sendCampaign("Third");

        assertThat(first.queuedCount()).isEqualTo(3);
        assertThat(second.queuedCount()).isEqualTo(3);
        assertThat(third.queuedCount()).isZero();
        assertThat(third.skippedCapCount()).isEqualTo(3);
        users.forEach(u -> {
            assertThat(pushesFor(u)).isEqualTo(2);
            assertThat(dailyCap.used(u, today())).isEqualTo(2);
        });
    }

    @Test
    void theLimitIsPerPerson_soSomeoneWithASlotLeftStillGetsItWhileAFullPersonIsSkipped() {
        List<UUID> users = eligibleUsers(2);
        setLimit(2);
        // The first person has already used both slots on other campaigns today.
        dailyCap.claim(users.get(0), today(), UUID.randomUUID(), 2);
        dailyCap.claim(users.get(0), today(), UUID.randomUUID(), 2);

        PushCampaignRunDto run = sendCampaign("Mixed");

        assertThat(run.queuedCount()).isEqualTo(1);
        assertThat(run.skippedCapCount()).isEqualTo(1);
        assertThat(pushesFor(users.get(0))).isZero();
        assertThat(pushesFor(users.get(1))).isEqualTo(1);
    }

    @Test
    void oneCampaignNeverTakesASecondSlotFromTheSamePerson_evenWhenItRunsAgain() {
        List<UUID> users = eligibleUsers(2);
        setLimit(5);
        UUID campaignId = createNowOnly("Repeat").id();

        CampaignEnqueuer.PageResult first = enqueuer.enqueuePage(campaignId, "Repeat", "Body text.", today(), users);
        CampaignEnqueuer.PageResult again = enqueuer.enqueuePage(campaignId, "Repeat", "Body text.", today(), users);

        assertThat(first.queued()).isEqualTo(2);
        assertThat(again.queued()).isZero();
        assertThat(again.skippedAlreadyQueued()).isEqualTo(2);
        assertThat(again.skippedCap()).isZero();
        users.forEach(u -> {
            assertThat(dailyCap.used(u, today())).isEqualTo(1);
            assertThat(pushesFor(u)).isEqualTo(1);
        });
    }

    // ------------------------------------------------------------------ changing the limit

    @Test
    void loweringTheLimitStopsFurtherPushesButTakesNothingBackFromWhoHasThem() {
        List<UUID> users = eligibleUsers(2);
        setLimit(3);
        sendCampaign("First");
        sendCampaign("Second");

        setLimit(1);
        PushCampaignRunDto third = sendCampaign("Third");

        assertThat(third.queuedCount()).isZero();
        assertThat(third.skippedCapCount()).isEqualTo(2);
        users.forEach(u -> assertThat(pushesFor(u)).isEqualTo(2));
    }

    // A cancel frees a LOW slot while a person's other push keeps a higher one. Lowering the limit
    // after that must still count what the person holds, not just look for a free slot number: slot
    // 1 is free here, but the person already has one push against a limit of one.
    @Test
    void aFreedLowSlotNeverLetsSomeoneExceedALimitThatWasLoweredAfterwards() {
        List<UUID> users = eligibleUsers(2);
        setLimit(2);
        UUID cancelled = createNowOnly("Cancelled").id();
        enqueuer.enqueuePage(cancelled, "Cancelled", "Body text.", today(), users);   // slot 1
        PushCampaignRunDto kept = sendCampaign("Kept");                                // slot 2
        assertThat(kept.queuedCount()).isEqualTo(2);
        cancellation.cancelPending(cancelled);                                         // slot 1 is free again
        users.forEach(u -> assertThat(dailyCap.used(u, today())).isEqualTo(1));

        setLimit(1);
        PushCampaignRunDto next = sendCampaign("Next");

        assertThat(next.queuedCount()).isZero();
        assertThat(next.skippedCapCount()).isEqualTo(2);
        users.forEach(u -> {
            assertThat(pushesFor(u)).isEqualTo(1);
            assertThat(dailyCap.used(u, today())).isEqualTo(1);
        });
    }

    @Test
    void aFreedLowSlotIsStillUsedWhileThePersonIsUnderTheLimit() {
        List<UUID> users = eligibleUsers(2);
        setLimit(3);
        UUID cancelled = createNowOnly("Cancelled").id();
        enqueuer.enqueuePage(cancelled, "Cancelled", "Body text.", today(), users);   // slot 1
        sendCampaign("Kept");                                                          // slot 2
        cancellation.cancelPending(cancelled);                                         // slot 1 free, slot 2 held

        PushCampaignRunDto next = sendCampaign("Next");

        assertThat(next.queuedCount()).isEqualTo(2);
        users.forEach(u -> assertThat(pushesFor(u)).isEqualTo(2));
    }

    @Test
    void raisingTheLimitOpensTheExtraSlotsAtOnce() {
        List<UUID> users = eligibleUsers(2);
        sendCampaign("First");
        assertThat(sendCampaign("Blocked").skippedCapCount()).isEqualTo(2);

        setLimit(2);
        PushCampaignRunDto unblocked = sendCampaign("Unblocked");

        assertThat(unblocked.queuedCount()).isEqualTo(2);
        users.forEach(u -> assertThat(pushesFor(u)).isEqualTo(2));
    }

    // ------------------------------------------------------------------ the emergency brake

    @Test
    void cancellingOneCampaignGivesBackExactlyItsSlot() {
        List<UUID> users = eligibleUsers(2);
        setLimit(2);
        UUID wrong = createNowOnly("Wrong").id();
        enqueuer.enqueuePage(wrong, "Wrong", "Body text.", today(), users);
        PushCampaignRunDto kept = sendCampaign("Kept");
        assertThat(kept.queuedCount()).isEqualTo(2);
        users.forEach(u -> assertThat(dailyCap.used(u, today())).isEqualTo(2));

        CampaignCancellation.Result result = cancellation.cancelPending(wrong);

        assertThat(result.cancelled()).isEqualTo(2);
        assertThat(result.released()).isEqualTo(2);
        users.forEach(u -> {
            assertThat(dailyCap.used(u, today())).isEqualTo(1);
            assertThat(pushesFor(u)).isEqualTo(1);
        });
        // The freed slot is usable again by a corrected campaign.
        assertThat(sendCampaign("Corrected").queuedCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ the race

    @Test
    void manyCallersRacingForOnePersonNeverExceedTheLimit() throws Exception {
        UUID user = UUID.randomUUID();
        int limit = 3;
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            UUID campaignId = UUID.randomUUID();
            results.add(pool.submit(() -> {
                start.await();
                return dailyCap.claim(user, today(), campaignId, limit);
            }));
        }
        start.countDown();
        int won = 0;
        for (Future<Boolean> f : results) {
            if (f.get(20, TimeUnit.SECONDS)) {
                won++;
            }
        }
        pool.shutdownNow();

        assertThat(won).isEqualTo(limit);
        assertThat(dailyCap.used(user, today())).isEqualTo(limit);
    }

    @Test
    void theSameCampaignRacingWithItselfTakesOneSlot() throws Exception {
        UUID user = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        int callers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return dailyCap.claim(user, today(), campaignId, 5);
            }));
        }
        start.countDown();
        int won = 0;
        for (Future<Boolean> f : results) {
            if (f.get(20, TimeUnit.SECONDS)) {
                won++;
            }
        }
        pool.shutdownNow();

        assertThat(won).isEqualTo(1);
        assertThat(dailyCap.used(user, today())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the bounds

    @Test
    void theServiceRefusesALimitOutsideOneToTen_andChangesNothing() {
        for (int bad : new int[] {0, -1, 11, 100}) {
            assertThatThrownBy(() -> setLimit(bad)).as("limit " + bad).isInstanceOf(ApiException.class);
        }
        assertThat(settingsStore.dailyLimitPerPerson()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesALimitOutsideOneToTenToo() {
        assertThatThrownBy(() -> jdbc.update("UPDATE push_campaign_settings SET daily_limit_per_person = 11 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE push_campaign_settings SET daily_limit_per_person = 0 WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thereIsExactlyOneSettingsRow() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO push_campaign_settings (id) VALUES (2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseItselfRefusesASecondSlotForTheSameCampaign() {
        UUID user = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        jdbc.update("INSERT INTO custom_push_daily_cap (user_id, day_ist, slot, campaign_id) VALUES (?, ?, 1, ?)",
                user, today(), campaign);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO custom_push_daily_cap (user_id, day_ist, slot, campaign_id) VALUES (?, ?, 2, ?)",
                user, today(), campaign)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
