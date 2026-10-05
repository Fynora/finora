package com.finora.service;

import com.finora.config.ClientIpResolver;
import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.exception.ApiException;
import com.finora.observability.AuthMetrics;
import com.finora.repository.RefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers the device-management additions to RefreshTokenService — listActiveSessions and
 * revokeSession. The rest of this service (issue/rotate/revoke*) has no dedicated unit test of
 * its own; it's exercised indirectly through AuthServiceLoginTest/PasswordChangeServiceTest/
 * AuthFlowIT, which is why this file only covers the two new methods.
 */
class RefreshTokenServiceTest {

    private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
    private final RefreshTokenService service = new RefreshTokenService(
            refreshTokenRepository, mock(JwtProperties.class), mock(HttpServletRequest.class),
            mock(ClientIpResolver.class), mock(AuthMetrics.class));

    private RefreshToken tokenWithId(UUID id) {
        RefreshToken rt = new RefreshToken();
        ReflectionTestUtils.setField(rt, "id", id);
        return rt;
    }

    @Test
    void listActiveSessions_returnsWhatTheRepositoryReturns_forTheGivenUser() {
        UUID userId = UUID.randomUUID();
        List<RefreshToken> sessions = List.of(tokenWithId(UUID.randomUUID()), tokenWithId(UUID.randomUUID()));
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(eq(userId), any(Instant.class)))
                .thenReturn(sessions);

        assertThat(service.listActiveSessions(userId)).isEqualTo(sessions);
    }

    @Test
    void revokeSession_ownedByTheUser_endsEveryLiveRowOfThatSessionAsRemote() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        RefreshToken listed = tokenWithId(tokenId);
        listed.setSessionId(sessionId);
        when(refreshTokenRepository.findByIdAndUserId(tokenId, userId)).thenReturn(Optional.of(listed));

        service.revokeSession(userId, tokenId);

        // The whole session, not only the listed row, and stamped so the device's later replay
        // is not taken for theft.
        verify(refreshTokenRepository).endLiveRowsOfSessionRemotely(eq(userId), eq(sessionId), any(Instant.class));
        verify(refreshTokenRepository, never()).endLiveRowsOfUserRemotely(any(), any());
    }

    @Test
    void revokeSession_repeatsUntilARefreshInFlightHasNoLiveSuccessorLeft() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        RefreshToken listed = tokenWithId(tokenId);
        listed.setSessionId(sessionId);
        when(refreshTokenRepository.findByIdAndUserId(tokenId, userId)).thenReturn(Optional.of(listed));
        when(refreshTokenRepository.existsByUserIdAndSessionIdAndRevokedAtIsNull(userId, sessionId))
                .thenReturn(true, false);

        service.revokeSession(userId, tokenId);

        verify(refreshTokenRepository, times(2)).endLiveRowsOfSessionRemotely(eq(userId), eq(sessionId), any(Instant.class));
    }

    @Test
    void revokeSession_givesUpLoudlyRatherThanLoopingForever() {
        UUID userId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        RefreshToken listed = tokenWithId(tokenId);
        listed.setSessionId(UUID.randomUUID());
        when(refreshTokenRepository.findByIdAndUserId(tokenId, userId)).thenReturn(Optional.of(listed));
        when(refreshTokenRepository.existsByUserIdAndSessionIdAndRevokedAtIsNull(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.revokeSession(userId, tokenId)).isInstanceOf(IllegalStateException.class);
        verify(refreshTokenRepository, times(10)).endLiveRowsOfSessionRemotely(any(), any(), any());
    }

    @Test
    void revokeAllOtherSessions_withNoCurrentSessionSparesNothing() {
        UUID userId = UUID.randomUUID();

        service.revokeAllOtherSessionsForUser(userId, null);

        verify(refreshTokenRepository).endLiveRowsOfUserRemotely(eq(userId), any(Instant.class));
        verify(refreshTokenRepository, never()).endLiveRowsOfOtherSessionsRemotely(any(), any(), any());
    }

    /** Scoped lookup by (id, userId) together means a session id that exists but belongs to a
     *  different user surfaces identically to one that doesn't exist at all -- never leaking
     *  whether a given session id is real. */
    @Test
    void revokeSession_notOwnedByTheUser_throwsNotFound() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(refreshTokenRepository.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeSession(userId, sessionId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(refreshTokenRepository, never()).endLiveRowsOfSessionRemotely(any(), any(), any());
    }

    /**
     * Bug 14 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). Nothing previously deleted a
     * refresh_tokens row at all -- see this method's own production-code doc comment for the full
     * reasoning, including why this keys on expiresAt rather than revokedAt (deleting a revoked
     * row early would defeat rotate()'s reuse-detection for a stolen token's remaining lifetime).
     * This test pins the METHOD CALLED, not just "some rows got deleted" -- proving the fix
     * queries by expiry, not by revocation status, is the whole point of the fix.
     */
    @Test
    void sweepExpiredTokens_deletesExpiredRows_regardlessOfWhoOwnsThem() {
        RefreshToken someoneElsesExpired = tokenWithId(UUID.randomUUID());
        when(refreshTokenRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
                .thenReturn(List.of(someoneElsesExpired));

        assertThat(service.sweepExpiredTokens()).isEqualTo(1);

        verify(refreshTokenRepository).deleteAll(List.of(someoneElsesExpired));
    }

    @Test
    void sweepExpiredTokens_returnsZero_andDoesNotCallDeleteAll_whenNothingIsExpired() {
        when(refreshTokenRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
                .thenReturn(List.of());

        assertThat(service.sweepExpiredTokens()).isZero();

        verify(refreshTokenRepository, never()).deleteAll(any());
    }

    @Test
    void sweepExpiredTokens_boundsTheQueryToASinglePage() {
        when(refreshTokenRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
                .thenReturn(List.of());

        service.sweepExpiredTokens();

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> page =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(refreshTokenRepository).findByExpiresAtBeforeOrderByExpiresAtAsc(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isLessThanOrEqualTo(200);
    }
}
