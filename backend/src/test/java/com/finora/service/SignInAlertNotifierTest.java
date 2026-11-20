package com.finora.service;

import com.finora.config.RequestMetadata;
import com.finora.entity.User;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignInAlertNotifierTest {

    private NotificationService notificationService;
    private RequestMetadata requestMetadata;
    private com.finora.support.ClientIdentity clientIdentity;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        requestMetadata = mock(RequestMetadata.class);
        clientIdentity = mock(com.finora.support.ClientIdentity.class);
        when(clientIdentity.platform()).thenReturn(com.finora.entity.ClientPlatform.WEB);
        sessionId = UUID.randomUUID();
        when(requestMetadata.device()).thenReturn("Windows");
        when(requestMetadata.browser()).thenReturn("Chrome");
        when(requestMetadata.ip()).thenReturn("203.0.113.7");
    }

    private User user(boolean emailVerified) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setEmail("owner@example.com");
        user.setEmailVerified(emailVerified);
        return user;
    }

    private NotificationRequest requested() {
        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(request.capture());
        return request.getValue();
    }

    @Test
    void asksForASecurityAlertOnBothChannels_keyedOnTheSession() {
        User user = user(true);

        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true).signedIn(user, sessionId);

        NotificationRequest request = requested();
        assertThat(request.userId()).isEqualTo(user.getId());
        assertThat(request.type()).isEqualTo(NotificationType.NEW_SIGN_IN);
        assertThat(request.category()).isEqualTo(NotificationCategory.SECURITY);
        assertThat(request.priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(request.notificationKey()).isEqualTo("NEW_SIGN_IN_" + sessionId);
        assertThat(request.channels()).containsExactlyInAnyOrder(NotificationChannel.EMAIL, NotificationChannel.PUSH);
        assertThat(request.params())
                .containsEntry("device", "Windows")
                .containsEntry("browser", "Chrome")
                .containsEntry("ip", "203.0.113.7")
                .containsKey("time");
        assertThat(request.params().get("time")).endsWith(" IST");
    }

    @Test
    void anUnverifiedAddressIsNotEmailed() {
        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true).signedIn(user(false), sessionId);

        assertThat(requested().channels()).containsExactly(NotificationChannel.PUSH);
    }

    /** A request with nothing to work a label out from still sends, with the gap said plainly. */
    @Test
    void whatTheRequestDidNotCarryIsCalledUnknown_neverLeftAsABlankOrAPlaceholder() {
        when(requestMetadata.device()).thenReturn(null);
        when(requestMetadata.browser()).thenReturn("  ");
        when(requestMetadata.ip()).thenReturn(null);

        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true).signedIn(user(true), sessionId);

        assertThat(requested().params())
                .containsEntry("device", "Unknown")
                .containsEntry("browser", "Unknown")
                .containsEntry("ip", "Unknown");
    }

    /**
     * The phone app sends no browser user agent. Worked out from that alone, a sign-in from the app
     * read "Device: Other. Browser: Other", which tells the owner nothing.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"MOBILE_ANDROID, Android phone", "MOBILE_IOS, iPhone"})
    void aSignInFromThePhoneAppIsNamedAsTheApp_notAsAnUnknownBrowser(
            com.finora.entity.ClientPlatform platform, String expectedDevice) {
        when(clientIdentity.platform()).thenReturn(platform);
        when(requestMetadata.device()).thenReturn("Other");
        when(requestMetadata.browser()).thenReturn("Other");

        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true).signedIn(user(true), sessionId);

        assertThat(requested().params())
                .containsEntry("device", expectedDevice)
                .containsEntry("browser", "Fynora app");
    }

    /** The platform is a label on a message. Not being able to read it must not cost the alert. */
    @Test
    void whenThePlatformCannotBeRead_theAlertStillGoes_withTheBrowsersOwnLabels() {
        when(clientIdentity.platform()).thenThrow(new IllegalStateException("no request bound"));

        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true).signedIn(user(true), sessionId);

        assertThat(requested().params()).containsEntry("device", "Windows").containsEntry("browser", "Chrome");
    }

    @Test
    void switchedOff_itAsksForNothing() {
        new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, false).signedIn(user(true), sessionId);

        verify(notificationService, never()).request(any());
    }

    /** The alert decorates a sign-in. Nothing that goes wrong in it may reach the sign-in. */
    @Test
    void aFailureWhileBuildingTheAlertNeverReachesTheSignIn() {
        when(requestMetadata.device()).thenThrow(new IllegalStateException("no request bound"));
        SignInAlertNotifier notifier = new SignInAlertNotifier(notificationService, requestMetadata, clientIdentity, true);

        assertThatCode(() -> notifier.signedIn(user(true), sessionId)).doesNotThrowAnyException();

        org.mockito.Mockito.doReturn("Mac").when(requestMetadata).device();
        when(notificationService.request(any())).thenThrow(new IllegalStateException("queue down"));
        assertThatCode(() -> notifier.signedIn(user(true), sessionId)).doesNotThrowAnyException();
    }

    /** India time, written the way a person reads it. 16:35 UTC is 10:05 pm in India. */
    @Test
    void theTimeIsIndiaTime() {
        assertThat(SignInAlertNotifier.formatted(Instant.parse("2031-03-04T16:35:00Z")))
                .isEqualTo("4 Mar 2031, 10:05 PM IST");
        assertThat(SignInAlertNotifier.formatted(Instant.parse("2031-03-04T19:00:00Z")))
                .as("past midnight in India it is the next day there")
                .isEqualTo("5 Mar 2031, 12:30 AM IST");
    }
}
