package com.finora.notification.api;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.repository.NotificationPreferenceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user-facing half of {@code notification_preferences}: what a user can see and change about
 * their own notifications. Until this existed the table had a reader
 * ({@link DatabaseNotificationPreferenceResolver}) but no writer, so nobody could opt out of the
 * FINANCIAL emails (statement ready/held, referral rewards) that every user receives by default.
 *
 * <p>Only the preferences a user can meaningfully change are exposed:
 * <ul>
 *   <li>FINANCIAL on EMAIL and PUSH -- the two channels FINANCIAL notifications are actually sent
 *       on (StatementStatusNotifier, ReferralService, ReferralGrantSweepService).</li>
 *   <li>SECURITY is not exposed: the resolver forces it on, so a toggle would be a lie.</li>
 *   <li>MARKETING is not exposed: it is opt-in and nothing sends it (NotificationCategory's own
 *       doc comment), so there is nothing to turn off yet.</li>
 * </ul>
 *
 * <p>The value shown is the user's own choice, or the category default when they have never made
 * one. It deliberately does not reflect the resolver's account-status suppression (a deactivated
 * account gets no FINANCIAL notifications whatever this says) -- that is not a preference.
 */
@Service
public class NotificationPreferenceService {

    /** Exposed (category, channel) pairs, in display order. */
    static final List<NotificationChannel> FINANCIAL_CHANNELS =
            List.of(NotificationChannel.EMAIL, NotificationChannel.PUSH);

    public record PreferenceView(NotificationCategory category, NotificationChannel channel,
            boolean enabled) {
    }

    private final NotificationPreferenceRepository repository;

    public NotificationPreferenceService(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PreferenceView> list(UUID userId) {
        Map<NotificationChannel, Boolean> stored = repository.findByUserId(userId).stream()
                .filter(p -> p.getCategory() == NotificationCategory.FINANCIAL)
                .collect(Collectors.toMap(NotificationPreference::getChannel,
                        NotificationPreference::isEnabled));
        List<PreferenceView> views = new ArrayList<>();
        for (NotificationChannel channel : FINANCIAL_CHANNELS) {
            // FINANCIAL is opt-out: no row means enabled, same default the resolver applies.
            views.add(new PreferenceView(NotificationCategory.FINANCIAL, channel,
                    stored.getOrDefault(channel, true)));
        }
        return views;
    }

    @Transactional
    public List<PreferenceView> set(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        if (category != NotificationCategory.FINANCIAL || !FINANCIAL_CHANNELS.contains(channel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Only FINANCIAL notifications on EMAIL or PUSH can be changed");
        }
        repository.upsert(UUID.randomUUID(), userId, category.name(), channel.name(), enabled);
        return list(userId);
    }
}
