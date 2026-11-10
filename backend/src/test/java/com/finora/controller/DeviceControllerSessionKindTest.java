package com.finora.controller;

import com.finora.dto.DeviceSessionDto;
import com.finora.entity.RefreshToken;
import com.finora.entity.SessionKind;
import com.finora.security.CurrentUser;
import com.finora.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The device list states each session's own end date: a phone session has no fixed one. */
class DeviceControllerSessionKindTest {

    @Test
    void eachSessionShowsTheCapForItsOwnKind() {
        RefreshTokenService service = mock(RefreshTokenService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        UUID userId = UUID.randomUUID();
        when(currentUser.id()).thenReturn(userId);

        Instant started = Instant.now().minus(Duration.ofDays(1));
        RefreshToken web = new RefreshToken();
        web.setSessionStartedAt(started);
        web.setSessionKind(SessionKind.WEB);
        RefreshToken phone = new RefreshToken();
        phone.setSessionStartedAt(started);
        phone.setSessionKind(SessionKind.MOBILE);
        when(service.listActiveSessions(userId)).thenReturn(List.of(web, phone));
        when(service.absoluteSessionMsFor(SessionKind.WEB)).thenReturn(Duration.ofDays(30).toMillis());
        when(service.absoluteSessionMsFor(SessionKind.MOBILE)).thenReturn(0L);

        DeviceController controller = new DeviceController(service, currentUser);
        List<DeviceSessionDto> sessions = controller.list(mock(HttpServletRequest.class)).data();

        assertThat(sessions.get(0).sessionExpiresAt()).isEqualTo(started.plus(Duration.ofDays(30)));
        assertThat(sessions.get(1).sessionExpiresAt()).isNull();
    }
}
