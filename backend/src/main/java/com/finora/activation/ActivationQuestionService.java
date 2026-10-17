package com.finora.activation;

import com.finora.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * "What stopped you?" (Gate 2a spec §4): which question the dashboard shows, and whether an answer
 * is accepted. "Asked" means answered or skipped -- the apps cannot tell whether someone read a card
 * they did not touch -- so every limit counts {@code activation_answers} rows:
 *
 * <ul>
 *   <li>at most one answer or skip per user per day, of any moment;</li>
 *   <li>a moment answered or skipped is not asked again for 30 days.</li>
 * </ul>
 */
@Service
public class ActivationQuestionService {

    /** A moment answered or skipped is not asked again until this many days have passed. */
    static final int MOMENT_QUIET_DAYS = 30;
    /** The dashboard NOT_CONFIRMED card is about a read that expired this recently, so an old expiry
     *  never brings it back. */
    static final Duration RECENT_EXPIRY = Duration.ofDays(7);

    private final ActivationAnswerRepository answers;
    private final ImportFunnelDayRepository funnelDays;
    private final ActivationFacts facts;
    private final ReportingDay reportingDay;

    public ActivationQuestionService(ActivationAnswerRepository answers, ImportFunnelDayRepository funnelDays,
                                     ActivationFacts facts, ReportingDay reportingDay) {
        this.answers = answers;
        this.funnelDays = funnelDays;
        this.facts = facts;
        this.reportingDay = reportingDay;
    }

    /**
     * The one dashboard question this user should see now, if any. NOT_CONFIRMED (a concrete, recent
     * loss) comes before NO_FILE. REFUSED is asked only on the import screen, never here.
     */
    @Transactional(readOnly = true)
    public Optional<ActivationMoment> nextDashboardQuestion(UUID userId) {
        LocalDate today = reportingDay.today();
        if (answers.existsByUserIdAndAnswerDate(userId, today)) return Optional.empty();

        if (!blocked(userId, ActivationMoment.NOT_CONFIRMED, today)
                && facts.hasUnconfirmedExpirySince(userId, reportingDay.now().minus(RECENT_EXPIRY))) {
            return Optional.of(ActivationMoment.NOT_CONFIRMED);
        }
        if (!blocked(userId, ActivationMoment.NO_FILE, today)
                && funnelDays.existsByUserIdAndEventAndFunnelDateBefore(userId, FunnelEvent.IMPORT_SCREEN_OPENED, today)
                && !funnelDays.existsByUserIdAndEvent(userId, FunnelEvent.FILE_CHOSEN)
                && !facts.hasEverImported(userId)) {
            return Optional.of(ActivationMoment.NO_FILE);
        }
        return Optional.empty();
    }

    /**
     * Whether {@code moment} may be asked now: nothing answered or skipped today, and this moment not
     * within its 30 quiet days. For the import-screen questions, whose moment the client knows
     * (spec §4.2): asking first means nobody taps an answer that {@link #answer} would then refuse.
     * Exactly the condition {@link #answer} checks, so the two can never disagree.
     */
    @Transactional(readOnly = true)
    public boolean isAskable(UUID userId, ActivationMoment moment) {
        LocalDate today = reportingDay.today();
        return !answers.existsByUserIdAndAnswerDate(userId, today) && !blocked(userId, moment, today);
    }

    /**
     * Records an answer or skip. Refused with 400 for an answer from another moment's list, and with
     * 409 when the limits do not allow one now (the clients then hide the question silently).
     */
    @Transactional
    public void answer(UUID userId, ActivationMoment moment, ActivationAnswer answer) {
        if (!answer.allowedFor(moment)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That answer does not belong to this question.");
        }
        if (!isAskable(userId, moment)) {
            throw new ApiException(HttpStatus.CONFLICT, "This question is not being asked now.");
        }
        LocalDate today = reportingDay.today();
        try {
            // Flushed here so a concurrent answer for the same day (V276's one-per-day key) surfaces
            // as the 409 it is, not as a 500 at commit.
            answers.saveAndFlush(new ActivationAnswerEntry(userId, moment, answer, today));
        } catch (DataIntegrityViolationException alreadyAnsweredToday) {
            throw new ApiException(HttpStatus.CONFLICT, "This question is not being asked now.");
        }
    }

    private boolean blocked(UUID userId, ActivationMoment moment, LocalDate today) {
        return answers.existsByUserIdAndMomentAndAnswerDateAfter(userId, moment, today.minusDays(MOMENT_QUIET_DAYS));
    }
}
