package com.finora.notification.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
import com.finora.notification.api.NotificationPreferenceResolver;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The audience SQL re-states the push eligibility rule that {@code DatabaseNotificationPreferenceResolver}
 * applies in code (it has to: a count and a page walk need one set-based query, not a per-user call).
 * Two copies of a rule drift, so this checks them against each other on every account status and on
 * both preference states: for each user with a live device, being in the audience must equal "the
 * resolver says FINANCIAL push is on AND the account is ACTIVE". (ACTIVE-only is deliberately a
 * little stricter than the resolver, which does not look at DELETED.)
 */
class AudienceResolverAgreementIT extends AbstractIntegrationTest {

    @Autowired private AllWithDeviceAudience audience;
    @Autowired private NotificationPreferenceResolver preferenceResolver;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void audienceMembershipEqualsTheResolversAnswerForEveryStatusAndPreference() {
        Map<UUID, String> label = new LinkedHashMap<>();
        for (String status : List.of(User.STATUS_ACTIVE, User.STATUS_SUSPENDED, User.STATUS_DEACTIVATED,
                User.STATUS_PENDING_DELETION, User.STATUS_DELETED)) {
            for (boolean pushOff : new boolean[] {false, true}) {
                User user = new User();
                user.setEmail("agreement-it-" + UUID.randomUUID() + "@example.com");
                user.setPasswordHash("irrelevant-for-this-test");
                user.setFullName("Agreement IT User");
                user.setStatus(status);
                UUID id = userRepository.save(user).getId();
                deviceTokenService.register(id, "ANDROID", "agreement-token-" + UUID.randomUUID());
                if (pushOff) {
                    jdbc.update("INSERT INTO notification_preferences (id, user_id, category, channel, enabled) "
                            + "VALUES (gen_random_uuid(), ?, 'FINANCIAL', 'PUSH', false)", id);
                }
                label.put(id, status + (pushOff ? " + push off" : ""));
            }
        }

        Set<UUID> inAudience = new java.util.HashSet<>();
        UUID after = AudienceSql.FIRST;
        while (true) {
            List<UUID> page = audience.page(after, 500);
            if (page.isEmpty()) {
                break;
            }
            inAudience.addAll(page);
            after = page.get(page.size() - 1);
        }

        List<String> disagreements = new ArrayList<>();
        label.forEach((id, description) -> {
            boolean userIsActive = description.startsWith(User.STATUS_ACTIVE);
            boolean expected = userIsActive
                    && preferenceResolver.isEnabled(id, NotificationCategory.FINANCIAL, NotificationChannel.PUSH);
            if (inAudience.contains(id) != expected) {
                disagreements.add(description + ": audience=" + inAudience.contains(id) + " expected=" + expected);
            }
        });
        assertThat(disagreements).isEmpty();
        // And the one eligible combination really is in: the test must not pass by selecting nobody.
        assertThat(label.entrySet().stream()
                .filter(e -> e.getValue().equals(User.STATUS_ACTIVE)).map(Map.Entry::getKey))
                .allMatch(inAudience::contains);
    }
}
