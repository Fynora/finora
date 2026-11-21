package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.EmailLoginOtpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignInCodePauseTest {

    private EmailLoginOtpRepository repository;
    private NotificationService notificationService;
    private RequestMetadata requestMetadata;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(EmailLoginOtpRepository.class);
        notificationService = mock(NotificationService.class);
        requestMetadata = mock(RequestMetadata.class);
        when(requestMetadata.ip()).thenReturn("203.0.113.7");
        user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    private SignInCodePause pause(int max) {
        return new SignInCodePause(repository, notificationService, requestMetadata, max);
    }

    /** One under the limit is not paused; the limit itself is. */
    @Test
    void pausesAtTheLimit_notOneBefore() {
        assertThat(pause(10).pausedAt(9)).isFalse();
        assertThat(pause(10).pausedAt(10)).isTrue();
        assertThat(pause(10).pausedAt(11)).isTrue();
    }

    @Test
    void aLimitOfZeroOrLessNeverPauses_andNeverReadsTheCount() {
        assertThat(pause(0).pausedAt(1_000)).isFalse();
        assertThat(pause(-1).pausedAt(1_000)).isFalse();
        assertThat(pause(0).isPaused(user.getId())).isFalse();
        verify(repository, never()).sumAttemptCountByUserIdSince(any(), any());
    }

    /** The window is the 24 hours the retention sweep keeps the rows for. */
    @Test
    void countsTheLastTwentyFourHours() {
        when(repository.sumAttemptCountByUserIdSince(eq(user.getId()), any())).thenReturn(10L);

        assertThat(pause(10).isPaused(user.getId())).isTrue();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repository).sumAttemptCountByUserIdSince(eq(user.getId()), since.capture());
        assertThat(since.getValue()).isCloseTo(Instant.now().minus(Duration.ofHours(24)), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void theRefusalIsATooManyRequests_thatSaysWhatToDo() {
        var refusal = pause(10).refusal();

        assertThat(refusal.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(refusal.getCode()).isEqualTo(ErrorCode.AUTH_OTP_RESEND_COOLDOWN);
        assertThat(refusal.getMessage()).contains("paused").contains("password");
    }

    @Test
    void theWrongCodeThatReachesTheLimitTellsTheOwner() {
        pause(10).wrongCodeCounted(user, 9);

        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(request.capture());
        assertThat(request.getValue().userId()).isEqualTo(user.getId());
        assertThat(request.getValue().type()).isEqualTo(NotificationType.SIGN_IN_CODE_PAUSED);
        assertThat(request.getValue().category()).isEqualTo(NotificationCategory.SECURITY);
        assertThat(request.getValue().channels())
                .containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.PUSH);
        assertThat(request.getValue().params())
                .containsEntry("count", "10")
                .containsEntry("ip", "203.0.113.7")
                .containsKey("time");
    }

    @Test
    void wrongCodesBeforeTheLimitTellNobody() {
        pause(10).wrongCodeCounted(user, 0);
        pause(10).wrongCodeCounted(user, 8);

        verify(notificationService, never()).request(any());
    }

    /** The count dips under the limit as old wrong codes age out and one more crosses it again.
     *  The key carries the day, so the queue drops the repeats. */
    @Test
    void crossingTheLimitTwiceInADayUsesTheSameKey() {
        pause(10).wrongCodeCounted(user, 9);
        pause(10).wrongCodeCounted(user, 9);

        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService, times(2)).request(request.capture());
        assertThat(request.getAllValues().get(0).notificationKey())
                .isEqualTo(request.getAllValues().get(1).notificationKey())
                .startsWith("SIGN_IN_CODE_PAUSED_" + user.getId() + "_");
    }

    @Test
    void switchedOff_aWrongCodeTellsNobody() {
        pause(0).wrongCodeCounted(user, 9);

        verify(notificationService, never()).request(any());
    }

    /** The wrong code is already refused. A failed alert must not turn that into a server error. */
    @Test
    void aFailureWhileTellingTheOwnerNeverReachesTheSignIn() {
        when(notificationService.request(any())).thenThrow(new IllegalStateException("queue down"));
        assertThatCode(() -> pause(10).wrongCodeCounted(user, 9)).doesNotThrowAnyException();

        when(requestMetadata.ip()).thenThrow(new IllegalStateException("no request bound"));
        assertThatCode(() -> pause(10).wrongCodeCounted(user, 9)).doesNotThrowAnyException();
    }
}
