package com.finora.notification.template;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ReferralGrant;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V230's copy, rendered through the real renderer against the migrated database with exactly the
 *  params ReferralService now sends -- so a leftover {{plusCount}}/{{premiumCount}} (which the
 *  renderer deliberately leaves visible) or a stale mention of Premium or the old 3-referral reward
 *  fails here rather than in someone's inbox. */
class ReferralSingleMilestoneCopyIT extends AbstractIntegrationTest {

    @Autowired private TemplateRenderer renderer;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void friendSubscribedRendersTheOneCountTowardPlus() {
        for (NotificationChannel channel : new NotificationChannel[] {NotificationChannel.EMAIL, NotificationChannel.PUSH}) {
            RenderedMessage m = renderer.render(NotificationType.REFERRAL_FRIEND_SUBSCRIBED, channel, Map.of("count", "4"));
            String all = m.title() + " " + m.body();
            assertThat(all).as(channel.name()).contains("4/7").contains("Plus")
                    .doesNotContain("{{").doesNotContain("Premium").doesNotContain("/3");
        }
    }

    @Test
    void milestoneReachedRendersPlusWithNoOtherRewardMentioned() {
        for (NotificationChannel channel : new NotificationChannel[] {NotificationChannel.EMAIL, NotificationChannel.PUSH}) {
            RenderedMessage m = renderer.render(NotificationType.REFERRAL_MILESTONE_REACHED, channel,
                    Map.of("tier", ReferralGrant.TIER_PLUS));
            String all = m.title() + " " + m.body();
            assertThat(all).as(channel.name()).contains("Plus")
                    .doesNotContain("{{").doesNotContain("Premium").doesNotContain("PLUS")
                    .doesNotContain("any other reward");
        }
    }

    @Test
    void grantActivatedRendersAPlanNameAndAPlainDate() {
        java.time.Instant expiry = java.time.Instant.parse("2026-10-26T20:03:11.123Z");
        Map<String, String> params = Map.of(
                "tier", com.finora.service.ReferralGrantSweepService.tierDisplayName(ReferralGrant.TIER_PLUS),
                "expiresAt", com.finora.service.ReferralGrantSweepService.EXPIRY_DATE_FORMAT.format(expiry));
        for (NotificationChannel channel : new NotificationChannel[] {NotificationChannel.EMAIL, NotificationChannel.PUSH}) {
            RenderedMessage m = renderer.render(NotificationType.REFERRAL_GRANT_ACTIVATED, channel, params);
            String all = m.title() + " " + m.body();
            // 20:03 UTC on the 26th is already the 27th in India.
            assertThat(all).as(channel.name()).contains("Plus").contains("27 Oct 2026")
                    .doesNotContain("PLUS").doesNotContain("T20:03").doesNotContain("{{");
        }
    }

    @Test
    void theOldCopyIsRetiredNotDeleted() {
        Integer retired = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_templates
                WHERE type IN ('REFERRAL_FRIEND_SUBSCRIBED', 'REFERRAL_MILESTONE_REACHED') AND active = false
                """, Integer.class);
        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM notification_templates
                WHERE type IN ('REFERRAL_FRIEND_SUBSCRIBED', 'REFERRAL_MILESTONE_REACHED') AND active = true
                """, Integer.class);
        assertThat(retired).isEqualTo(4);
        assertThat(active).isEqualTo(4);
    }
}
