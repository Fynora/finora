package com.finora.support;

import com.finora.exception.ApiException;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportSubmissionLimiterTest {

    private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

    private SupportTicketRepository tickets;
    private FeedbackEntryRepository feedback;
    private SupportSubmissionLimiter limiter;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tickets = mock(SupportTicketRepository.class);
        feedback = mock(FeedbackEntryRepository.class);
        limiter = new SupportSubmissionLimiter(tickets, feedback, 10, 20, new MutableClock(NOW));
    }

    @Test
    void oneBelowTheTicketCeilingIsAllowed_exactlyAtItIsRefused() {
        when(tickets.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(9L);
        assertThatCode(() -> limiter.requireTicketAllowance(userId)).doesNotThrowAnyException();

        when(tickets.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(10L);
        assertThatThrownBy(() -> limiter.requireTicketAllowance(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).contains("10 support tickets in 24 hours");
                });
    }

    @Test
    void oneBelowTheFeedbackCeilingIsAllowed_exactlyAtItIsRefused() {
        when(feedback.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(19L);
        assertThatCode(() -> limiter.requireFeedbackAllowance(userId)).doesNotThrowAnyException();

        when(feedback.countByUserIdAndCreatedAtAfter(eq(userId), any())).thenReturn(20L);
        assertThatThrownBy(() -> limiter.requireFeedbackAllowance(userId))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).contains("20 pieces of feedback in 24 hours");
                });
    }

    @Test
    void theCountLooksBackExactlyTwentyFourHours_forTheAskingAccountOnly() {
        when(tickets.countByUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);
        when(feedback.countByUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);

        limiter.requireTicketAllowance(userId);
        limiter.requireFeedbackAllowance(userId);

        ArgumentCaptor<Instant> ticketSince = ArgumentCaptor.forClass(Instant.class);
        verify(tickets).countByUserIdAndCreatedAtAfter(eq(userId), ticketSince.capture());
        assertThat(ticketSince.getValue()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        ArgumentCaptor<Instant> feedbackSince = ArgumentCaptor.forClass(Instant.class);
        verify(feedback).countByUserIdAndCreatedAtAfter(eq(userId), feedbackSince.capture());
        assertThat(feedbackSince.getValue()).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void theTwoCeilingsAreIndependent_aFullTicketQuotaNeverBlocksFeedback() {
        when(tickets.countByUserIdAndCreatedAtAfter(any(), any())).thenReturn(10L);
        when(feedback.countByUserIdAndCreatedAtAfter(any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> limiter.requireTicketAllowance(userId)).isInstanceOf(ApiException.class);
        assertThatCode(() -> limiter.requireFeedbackAllowance(userId)).doesNotThrowAnyException();
    }
}
