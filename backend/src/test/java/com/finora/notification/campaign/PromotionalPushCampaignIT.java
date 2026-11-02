package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.PushCampaignDtos.PushCampaignDto;
import com.finora.dto.PushCampaignDtos.PushCampaignSaveRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestRequest;
import com.finora.dto.PushCampaignDtos.PushCampaignTestResultDto;
import com.finora.entity.FeatureFlag;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
import com.finora.repository.FeatureFlagRepository;
import com.finora.repository.UserRepository;
import com.finora.service.FeatureFlagService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A push campaign's purpose (V305, found 2026-11-02): every campaign went out as FINANCIAL, the
 * category users can only switch off, so a promotional campaign reached people who never agreed to
 * marketing. A PROMOTIONAL campaign goes out as MARKETING and reaches only users who turned it on;
 * an OPERATIONAL one is unchanged. Real database and real runner; the audience is global, so every
 * other device is revoked first, as PushCampaignIT does.
 */
class PromotionalPushCampaignIT extends AbstractIntegrationTest {

    @Autowired private PushCampaignService service;
    @Autowired private PushCampaignRunRepository runs;
    @Autowired private PushCampaignRepository campaigns;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private FeatureFlagService featureFlagService;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID adminId;

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("UPDATE device_tokens SET revoked_at = now() WHERE revoked_at IS NULL");
        adminId = user(User.SCOPE_ADMIN).getId();
        FeatureFlag flag = featureFlagRepository.findByKey(PushCampaignService.FEATURE_FLAG).orElseThrow();
        if (!flag.isEnabled()) {
            featureFlagService.setEnabled(adminId, flag.getId(), true);
        }
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

    private User user(String scope) {
        User user = new User();
        user.setEmail("promo-campaign-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Promotional Campaign IT User");
        user.setStatus(User.STATUS_ACTIVE);
        user.setAccountScope(scope);
        return userRepository.save(user);
    }

    /** An ACTIVE end user with a live device; {@code financialPush}/{@code marketingPush} null = never set. */
    private UUID withDevice(Boolean financialPush, Boolean marketingPush) {
        UUID id = user(User.SCOPE_USER).getId();
        deviceTokenService.register(id, "ANDROID", "promo-it-token-" + UUID.randomUUID());
        if (financialPush != null) preference(id, "FINANCIAL", financialPush);
        if (marketingPush != null) preference(id, "MARKETING", marketingPush);
        return id;
    }

    private void preference(UUID userId, String category, boolean enabled) {
        jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                + "VALUES (gen_random_uuid(), ?, ?, 'PUSH', ?)", userId, category, enabled);
    }

    private PushCampaignDto create(CampaignPurpose purpose) {
        return service.create(adminId, new PushCampaignSaveRequest("IT promo", "Diwali offer", "Plus for less this week.",
                AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY, null, null, null, null, purpose));
    }

    private void sendNowAndWait(UUID campaignId) {
        UUID runId = service.sendNow(adminId, campaignId).id();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (runs.findById(runId).orElseThrow().getStatus() != RunStatus.RUNNING) {
                return;
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

    /** The category of each CUSTOM_PUSH row queued for this user (empty when none). */
    private List<String> queued(UUID userId) {
        return jdbc.queryForList("SELECT category FROM notifications WHERE type = 'CUSTOM_PUSH' AND user_id = ?",
                String.class, userId);
    }

    @Test
    void aPromotionalCampaign_reachesOnlyUsersWhoTurnedMarketingOn_asMarketing() {
        UUID optedIn = withDevice(null, true);
        UUID neverAsked = withDevice(null, null);
        UUID turnedOff = withDevice(null, false);
        UUID financialOffMarketingOn = withDevice(false, true);

        PushCampaignDto campaign = create(CampaignPurpose.PROMOTIONAL);
        assertThat(service.audienceCount(AudienceType.ALL_WITH_DEVICE, CampaignPurpose.PROMOTIONAL).count()).isEqualTo(2);
        sendNowAndWait(campaign.id());

        assertThat(queued(optedIn)).containsExactly("MARKETING");
        assertThat(queued(financialOffMarketingOn)).containsExactly("MARKETING");
        assertThat(queued(neverAsked)).isEmpty();
        assertThat(queued(turnedOff)).isEmpty();
        assertThat(runs.findAll()).allSatisfy(r -> assertThat(r.getPurposeSnapshot()).isEqualTo(CampaignPurpose.PROMOTIONAL));
    }

    @Test
    void anOperationalCampaign_isUnchanged_financialToEveryoneWhoHasNotSwitchedItOff() {
        UUID neverAsked = withDevice(null, null);
        UUID marketingOn = withDevice(null, true);
        UUID financialOff = withDevice(false, true);

        PushCampaignDto campaign = create(CampaignPurpose.OPERATIONAL);
        sendNowAndWait(campaign.id());

        assertThat(queued(neverAsked)).containsExactly("FINANCIAL");
        assertThat(queued(marketingOn)).containsExactly("FINANCIAL");
        assertThat(queued(financialOff)).isEmpty();
    }

    @Test
    void aCampaignSavedWithoutAPurpose_isOperational_andAnEditWithoutOneKeepsItsOwn() {
        PushCampaignDto created = create(null);
        assertThat(created.purpose()).isEqualTo(CampaignPurpose.OPERATIONAL);

        PushCampaignDto promo = create(CampaignPurpose.PROMOTIONAL);
        PushCampaignDto edited = service.update(adminId, promo.id(), new PushCampaignSaveRequest("Renamed",
                "Diwali offer", "Plus for less this week.", AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY,
                null, null, null));
        assertThat(edited.purpose()).isEqualTo(CampaignPurpose.PROMOTIONAL);
        assertThat(service.clone(adminId, promo.id()).purpose()).isEqualTo(CampaignPurpose.PROMOTIONAL);

        PushCampaignDto switched = service.update(adminId, promo.id(), new PushCampaignSaveRequest("Renamed",
                "Diwali offer", "Plus for less this week.", AudienceType.ALL_WITH_DEVICE, ScheduleKind.NOW_ONLY,
                null, null, null, null, CampaignPurpose.OPERATIONAL));
        assertThat(switched.purpose()).isEqualTo(CampaignPurpose.OPERATIONAL);
        assertThat(campaigns.findById(promo.id()).orElseThrow().getPurpose()).isEqualTo(CampaignPurpose.OPERATIONAL);
    }

    @Test
    void aPromotionalTest_isSentOnlyToAnAccountThatTurnedMarketingOn() {
        UUID neverAsked = withDevice(null, null);
        UUID optedIn = withDevice(null, true);
        PushCampaignDto campaign = create(CampaignPurpose.PROMOTIONAL);

        PushCampaignTestResultDto refused = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(neverAsked, null));
        PushCampaignTestResultDto sent = service.sendTest(adminId, campaign.id(), new PushCampaignTestRequest(optedIn, null));

        assertThat(refused.queued()).isFalse();
        assertThat(refused.detail()).contains("offers and news");
        assertThat(queued(neverAsked)).isEmpty();
        assertThat(sent.queued()).isTrue();
        assertThat(queued(optedIn)).containsExactly("MARKETING");
    }
}
