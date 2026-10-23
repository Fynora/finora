package com.finora.support;

import com.finora.exception.ApiException;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.SupportTicketRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A per-account daily ceiling on tickets and on feedback.
 *
 * <p>Neither endpoint sits behind {@code RateLimitFilter} (which covers only a handful of
 * endpoints, by design), so before this nothing stopped one account, a script or a client retry
 * loop from saving thousands of rows, each of which also emails every admin. This counts the
 * account's own rows from the last 24 hours and refuses past the ceiling with a 429 and a message
 * the apps already show inline.
 *
 * <p><b>Per account, from the database, on purpose.</b> The filter's limiters are per IP, which
 * punishes a whole office behind one NAT and does nothing about one account on many IPs; and an
 * in-memory counter forgets on every deploy and would not agree across replicas. The rows are the
 * count: it survives restarts and cannot disagree with what is actually saved. Tickets use
 * {@code idx_support_tickets_by_user}. Feedback has no user index, so its count walks the last day
 * of {@code idx_feedback_entries_recent}, which is small at this table's volume.
 *
 * <p>It is a soft ceiling: two requests landing in the same instant can both pass the check, so an
 * account can exceed it by the number of requests in flight. That is fine for what it protects
 * against, which is volume and not exactness.
 *
 * <p>The defaults (10 tickets, 20 feedback entries per 24 hours) are far above what a person does:
 * a customer raising ten problems in a day is not a customer with ten problems. Both are
 * properties, so a genuine need is a config change and not a deploy.
 */
@Component
public class SupportSubmissionLimiter {

    private static final Duration WINDOW = Duration.ofHours(24);

    private final SupportTicketRepository tickets;
    private final FeedbackEntryRepository feedback;
    private final int maxTicketsPerDay;
    private final int maxFeedbackPerDay;
    private final Clock clock;

    @Autowired
    public SupportSubmissionLimiter(SupportTicketRepository tickets, FeedbackEntryRepository feedback,
                                    @Value("${app.support.max-tickets-per-day:10}") int maxTicketsPerDay,
                                    @Value("${app.support.max-feedback-per-day:20}") int maxFeedbackPerDay) {
        this(tickets, feedback, maxTicketsPerDay, maxFeedbackPerDay, Clock.systemUTC());
    }

    SupportSubmissionLimiter(SupportTicketRepository tickets, FeedbackEntryRepository feedback,
                             int maxTicketsPerDay, int maxFeedbackPerDay, Clock clock) {
        this.tickets = tickets;
        this.feedback = feedback;
        this.maxTicketsPerDay = maxTicketsPerDay;
        this.maxFeedbackPerDay = maxFeedbackPerDay;
        this.clock = clock;
    }

    /** Throws a 429 when this account has already raised its daily ceiling of tickets. */
    public void requireTicketAllowance(UUID userId) {
        long raised = tickets.countByUserIdAndCreatedAtAfter(userId, since());
        if (raised >= maxTicketsPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have reached the limit of " + maxTicketsPerDay + " support tickets in 24 hours. "
                            + "Please try again later.");
        }
    }

    /** Throws a 429 when this account has already sent its daily ceiling of feedback. */
    public void requireFeedbackAllowance(UUID userId) {
        long sent = feedback.countByUserIdAndCreatedAtAfter(userId, since());
        if (sent >= maxFeedbackPerDay) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have reached the limit of " + maxFeedbackPerDay + " pieces of feedback in 24 hours. "
                            + "Thank you for sharing it; please try again later.");
        }
    }

    private Instant since() {
        return clock.instant().minus(WINDOW);
    }
}
