package com.finora.invite;

import com.finora.activation.ReportingDay;
import com.finora.entity.ClientPlatform;
import com.finora.observability.InvitePromptMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The invite-a-friend card after a statement import: when it is shown, and what is recorded about it.
 * Kept on the server so web and mobile agree -- a card dismissed on the phone does not come back on
 * the web.
 *
 * <h2>When the card is shown</h2>
 * <ol>
 *   <li><b>The first time:</b> on the person's next import that saves at least one transaction,
 *       whenever that is. For someone new that is their first import. Someone who imported before
 *       this card existed sees it on their next import after it ships (Sid, 2026-11-12: existing
 *       users included). A held statement saves nothing (its confirm is refused with IMPORT_016), and
 *       neither does a refused one, so neither shows it.</li>
 *   <li><b>Once more,</b> only after "Not now" (or after a showing that got no answer): on the first
 *       successful import at least {@link #QUIET_DAYS_AFTER_NOT_NOW} days later. 180 days is about
 *       six monthly statements -- long enough not to nag someone who said no, and by then they have
 *       half a year of their own money in Fynora to judge it by. After that second showing, never
 *       again ({@link #MAX_SHOWINGS}).</li>
 *   <li><b>Never</b> again once Share was tapped.</li>
 * </ol>
 */
@Service
public class InvitePromptService {

    /** After "Not now", the card waits at least this many days before it may be shown again. */
    public static final int QUIET_DAYS_AFTER_NOT_NOW = 180;
    /** The card is shown at most this many times to one person, ever. Matches V315's CHECK. */
    public static final int MAX_SHOWINGS = 2;
    /** A showing this recent counts as "shown recently" (InvitePromptResponse.shownRecently). */
    public static final int RECENT_DAYS = 7;
    /** One confirm makes at most one statement import per account section; no request names more. */
    public static final int MAX_IDS = 20;

    private final InvitePromptRepository repository;
    private final ReportingDay reportingDay;
    private final InvitePromptMetrics metrics;

    public InvitePromptService(InvitePromptRepository repository, ReportingDay reportingDay,
                               InvitePromptMetrics metrics) {
        this.repository = repository;
        this.reportingDay = reportingDay;
        this.metrics = metrics;
    }

    /**
     * Whether to show the card for {@code statementImportIds}, the statement imports one confirm just
     * made (one, or one per account of a multi-account statement). Ids that are not this user's, or
     * that saved nothing, are ignored.
     */
    @Transactional(readOnly = true)
    public InvitePromptDto.InvitePromptResponse check(UUID userId, Collection<UUID> statementImportIds) {
        LocalDate today = reportingDay.today();
        Optional<InvitePrompt> state = repository.findById(userId);
        boolean shownRecently = state.map(InvitePrompt::getLastShownOn)
                .map(day -> day.isAfter(today.minusDays(RECENT_DAYS)))
                .orElse(false);
        return new InvitePromptDto.InvitePromptResponse(shouldShow(userId, statementImportIds, state, today),
                shownRecently);
    }

    private boolean shouldShow(UUID userId, Collection<UUID> statementImportIds, Optional<InvitePrompt> state,
                               LocalDate today) {
        if (statementImportIds == null || statementImportIds.isEmpty()) return false;
        Set<UUID> successful = new HashSet<>(repository.successfulImportIds(userId));
        Set<UUID> given = new HashSet<>(statementImportIds);
        if (given.stream().noneMatch(successful::contains)) return false;

        int timesShown = state.map(InvitePrompt::getTimesShown).orElse(0);
        if (state.map(InvitePrompt::getSharedOn).isPresent()) return false;
        if (timesShown == 0) return true;
        if (timesShown >= MAX_SHOWINGS) return false;
        LocalDate lastShown = state.get().getLastShownOn();
        return lastShown != null && !lastShown.isAfter(today.minusDays(QUIET_DAYS_AFTER_NOT_NOW));
    }

    /** Records what the app reported, and counts it (aggregate only -- see InvitePromptMetrics). */
    @Transactional
    public void record(UUID userId, InvitePromptEvent event, ClientPlatform platform) {
        LocalDate today = reportingDay.today();
        switch (event) {
            case SHOWN -> {
                // A same-day repeat is not a new showing, so it is not counted either.
                if (repository.recordShown(userId, today) == 0) return;
            }
            case SHARE_TAPPED -> repository.recordShareTapped(userId, today);
            case DISMISSED -> repository.recordDismissed(userId, today);
        }
        metrics.event(event, platform);
    }
}
