package com.finora.notification.api;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.domain.NotificationPreferenceChange;
import com.finora.notification.repository.NotificationPreferenceChangeRepository;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.support.ClientIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>MARKETING on PUSH (V305, "Offers and news"): opt-in, so off until the user turns it on.
 *       PROMOTIONAL push campaigns are its only sender. Listed only for a client that asks for it,
 *       like REMINDERS.</li>
 *   <li>REMINDERS on PUSH (Gate 2b): the month-start statement reminder, listed only for a client
 *       that asks for it -- see {@link #list(UUID, Set)}.</li>
 * </ul>
 *
 * <p>The value shown is the user's own choice, or the category default when they have never made
 * one. It deliberately does not reflect the resolver's account-status suppression (a deactivated
 * account gets no FINANCIAL notifications whatever this says) -- that is not a preference.
 *
 * <p>Every change is also written to {@code notification_preference_history} (V306): when, from
 * which app, and which wording the app showed. That row, not the current value, is what shows
 * that a user agreed to "Offers and news" (DPDP Act s.6(10)).
 */
@Service
public class NotificationPreferenceService {

    /** Exposed (category, channel) pairs, in display order. */
    static final List<NotificationChannel> FINANCIAL_CHANNELS =
            List.of(NotificationChannel.EMAIL, NotificationChannel.PUSH);

    /** Gate 2b: the month-start reminder is push only (spec §6), so REMINDERS has one switch. */
    static final List<NotificationChannel> REMINDERS_CHANNELS = List.of(NotificationChannel.PUSH);
    /** V305: "Offers and news". Push only, off unless the user turns it on (the resolver's default
     *  for MARKETING); PROMOTIONAL push campaigns reach only users who did. */
    static final List<NotificationChannel> MARKETING_CHANNELS = List.of(NotificationChannel.PUSH);

    public record PreferenceView(NotificationCategory category, NotificationChannel channel,
            boolean enabled) {
    }

    private final NotificationPreferenceRepository repository;
    private final NotificationPreferenceChangeRepository history;
    private final ClientIdentity clientIdentity;

    public NotificationPreferenceService(NotificationPreferenceRepository repository,
            NotificationPreferenceChangeRepository history, ClientIdentity clientIdentity) {
        this.repository = repository;
        this.history = history;
        this.clientIdentity = clientIdentity;
    }

    /** FINANCIAL only: what every client built before REMINDERS existed asks for, and draws one
     *  switch per channel from. */
    @Transactional(readOnly = true)
    public List<PreferenceView> list(UUID userId) {
        return list(userId, Set.of(NotificationCategory.FINANCIAL));
    }

    /**
     * The exposed preferences of the requested categories, FINANCIAL first. SECURITY, with nothing
     * to change, contributes nothing. REMINDERS and MARKETING are listed only when asked for:
     * the 1.0.0 app keys its switches by channel alone and sends every change as FINANCIAL, so a
     * second PUSH row would give it a switch that toggles the wrong category.
     */
    @Transactional(readOnly = true)
    public List<PreferenceView> list(UUID userId, Set<NotificationCategory> categories) {
        Map<String, Boolean> stored = repository.findByUserId(userId).stream()
                .collect(Collectors.toMap(p -> p.getCategory() + ":" + p.getChannel(),
                        NotificationPreference::isEnabled, (a, b) -> a));
        List<PreferenceView> views = new ArrayList<>();
        for (NotificationCategory category : List.of(NotificationCategory.FINANCIAL, NotificationCategory.REMINDERS,
                NotificationCategory.MARKETING)) {
            if (!categories.contains(category)) continue;
            for (NotificationChannel channel : channelsOf(category)) {
                // Both are opt-out: no row means enabled, the same default the resolver applies --
                // except that an unset REMINDERS follows the user's FINANCIAL choice on the same
                // channel (Sid, 2026-10-25; DatabaseNotificationPreferenceResolver#unsetValue).
                Boolean own = stored.get(category + ":" + channel);
                boolean enabled = own != null ? own
                        : category == NotificationCategory.REMINDERS
                                ? stored.getOrDefault(NotificationCategory.FINANCIAL + ":" + channel, true)
                                // Marketing is opt-in: no row means off.
                                : category != NotificationCategory.MARKETING;
                views.add(new PreferenceView(category, channel, enabled));
            }
        }
        return views;
    }

    @Transactional
    public List<PreferenceView> set(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        return set(userId, category, channel, enabled, Set.of(NotificationCategory.FINANCIAL));
    }

    @Transactional
    public List<PreferenceView> set(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled, Set<NotificationCategory> listed) {
        return set(userId, category, channel, enabled, null, listed);
    }

    /**
     * Stores one choice, records it in the history, and answers with {@link #list(UUID, Set)} of
     * {@code listed}.
     *
     * @param noticeVersion which wording of the switch the app showed, or null when it does not say
     *                      (every app before V306, and every switch except "Offers and news")
     */
    @Transactional
    public List<PreferenceView> set(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled, String noticeVersion,
            Set<NotificationCategory> listed) {
        if (!channelsOf(category).contains(channel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                    "Only FINANCIAL notifications on EMAIL or PUSH, and REMINDERS and MARKETING on PUSH, can be changed");
        }
        repository.upsert(UUID.randomUUID(), userId, category.name(), channel.name(), enabled);
        // Same transaction as the change itself: a switch that saved always has its record.
        history.save(new NotificationPreferenceChange(userId, category, channel, enabled, Instant.now(),
                clientIdentity.platform(), noticeVersion));
        return list(userId, listed);
    }

    private static List<NotificationChannel> channelsOf(NotificationCategory category) {
        return switch (category) {
            case FINANCIAL -> FINANCIAL_CHANNELS;
            case REMINDERS -> REMINDERS_CHANNELS;
            case MARKETING -> MARKETING_CHANNELS;
            case SECURITY -> List.of();
        };
    }
}
