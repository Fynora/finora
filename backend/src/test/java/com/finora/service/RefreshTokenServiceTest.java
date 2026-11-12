package com.finora.service;

import com.finora.config.ClientIpResolver;
import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.entity.SessionKind;
import com.finora.exception.ApiException;
import com.finora.observability.AuthMetrics;
import com.finora.repository.RefreshTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
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

    /** The production limits: web 24 h idle and 30 days, phone 90 days idle and no cap. */
    private RefreshTokenService serviceWithRealLimits() {
        JwtProperties limits = new JwtProperties();
        limits.setIdleTimeoutMs(86_400_000L);
        limits.setAbsoluteSessionMs(2_592_000_000L);
        limits.setMobileIdleTimeoutMs(7_776_000_000L);
        limits.setMobileAbsoluteSessionMs(0);
        return new RefreshTokenService(refreshTokenRepository, limits, mock(HttpServletRequest.class),
                mock(ClientIpResolver.class), mock(AuthMetrics.class));
    }

    /** A live row of {@code session}, created and last seen {@code idle} ago, signed in {@code age} ago. */
    private RefreshToken row(UUID session, SessionKind kind, Duration idle, Duration age) {
        RefreshToken rt = tokenWithId(UUID.randomUUID());
        rt.setSessionId(session);
        rt.setSessionKind(kind);
        Instant now = Instant.now();
        ReflectionTestUtils.setField(rt, "createdAt", now.minus(idle));
        rt.setLastSeenAt(now.minus(idle));
        rt.setSessionStartedAt(now.minus(age));
        return rt;
    }

    private void repositoryReturns(UUID userId, RefreshToken... rows) {
        when(refreshTokenRepository.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(eq(userId), any(Instant.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    void listActiveSessions_leavesOutSessionsTheIdleLimitOrTheCapHasAlreadyEnded() {
        UUID userId = UUID.randomUUID();
        RefreshToken webToday = row(UUID.randomUUID(), SessionKind.WEB, Duration.ofHours(23), Duration.ofDays(2));
        RefreshToken webIdle = row(UUID.randomUUID(), SessionKind.WEB, Duration.ofHours(25), Duration.ofDays(2));
        RefreshToken webPastCap = row(UUID.randomUUID(), SessionKind.WEB, Duration.ofHours(1), Duration.ofDays(31));
        RefreshToken phone89 = row(UUID.randomUUID(), SessionKind.MOBILE, Duration.ofDays(89), Duration.ofDays(400));
        RefreshToken phone91 = row(UUID.randomUUID(), SessionKind.MOBILE, Duration.ofDays(91), Duration.ofDays(91));
        repositoryReturns(userId, webToday, webIdle, webPastCap, phone89, phone91);

        // Exactly the rows rotate() would still honour: 23 h web, and a phone unused 89 days even
        // though it signed in 400 days ago (phone sessions have no cap).
        assertThat(serviceWithRealLimits().listActiveSessions(userId)).containsExactly(webToday, phone89);
    }

    /** Two children of one parent token: what a refresh answered twice inside the reuse window leaves. */
    private void siblings(RefreshToken... rows) {
        UUID parent = UUID.randomUUID();
        for (RefreshToken rt : rows) {
            rt.setParentTokenId(parent);
            when(refreshTokenRepository.existsByParentTokenIdAndIdNot(parent, rt.getId())).thenReturn(true);
        }
    }

    @Test
    void listActiveSessions_hidesARetryLeftover_andKeepsTheSessionsMostRecentlySeenRow() {
        UUID userId = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        // The phone's refresh was answered twice; it kept the newer token and rotated on since.
        RefreshToken leftover = row(phone, SessionKind.MOBILE, Duration.ofMinutes(30), Duration.ofDays(5));
        RefreshToken kept = row(phone, SessionKind.MOBILE, Duration.ofMinutes(30).minusSeconds(2), Duration.ofDays(5));
        siblings(leftover, kept);
        RefreshToken current = row(phone, SessionKind.MOBILE, Duration.ofMinutes(2), Duration.ofDays(5));
        current.setParentTokenId(kept.getId());
        RefreshToken laptop = row(UUID.randomUUID(), SessionKind.WEB, Duration.ofMinutes(5), Duration.ofDays(1));
        laptop.setParentTokenId(UUID.randomUUID());
        repositoryReturns(userId, current, laptop, leftover);

        assertThat(serviceWithRealLimits().listActiveSessions(userId)).containsExactly(current, laptop);
    }

    @Test
    void listActiveSessions_showsASessionTwice_whenBothCopiesOfItsTokenAreInUse() {
        UUID userId = UUID.randomUUID();
        UUID phone = UUID.randomUUID();
        // A copy replayed inside the reuse window, then BOTH chains rotated on: neither head has a
        // sibling, so the owner sees the session twice -- the one sign of such a copy.
        RefreshToken ownersHead = row(phone, SessionKind.MOBILE, Duration.ofMinutes(3), Duration.ofDays(5));
        ownersHead.setParentTokenId(UUID.randomUUID());
        RefreshToken copysHead = row(phone, SessionKind.MOBILE, Duration.ofMinutes(9), Duration.ofDays(5));
        copysHead.setParentTokenId(UUID.randomUUID());
        repositoryReturns(userId, ownersHead, copysHead);

        assertThat(serviceWithRealLimits().listActiveSessions(userId)).containsExactly(ownersHead, copysHead);
    }

    @Test
    void listActiveSessions_rowsWithNoRecordedParentAreNeverTreatedAsLeftovers() {
        UUID userId = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        // Minted before V314: nothing says how they relate, so both stay listed as before.
        RefreshToken a = row(session, SessionKind.WEB, Duration.ofMinutes(1), Duration.ofDays(1));
        RefreshToken b = row(session, SessionKind.WEB, Duration.ofMinutes(20), Duration.ofDays(1));
        repositoryReturns(userId, a, b);

        assertThat(serviceWithRealLimits().listActiveSessions(userId)).containsExactly(a, b);
        verify(refreshTokenRepository, never()).existsByParentTokenIdAndIdNot(any(), any());
    }

    @Test
    void listActiveSessions_ofTwoLeftoverSiblings_theOneWithNoLastSeenTimeIsTheOneHidden() {
        UUID userId = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        RefreshToken seen = row(session, SessionKind.WEB, Duration.ofMinutes(20), Duration.ofDays(1));
        RefreshToken unseen = row(session, SessionKind.WEB, Duration.ofMinutes(1), Duration.ofDays(1));
        unseen.setLastSeenAt(null);
        siblings(seen, unseen);
        repositoryReturns(userId, unseen, seen);

        assertThat(serviceWithRealLimits().listActiveSessions(userId)).containsExactly(seen);
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
