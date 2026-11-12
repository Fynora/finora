package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RefreshToken;
import com.finora.entity.SessionKind;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The "your devices" list in Settings, against real Postgres: it shows each session once, and
 * only sessions that could still refresh.
 *
 * <p>Both were wrong before (break-test 2026-11-12): a phone refresh retried inside the reuse window
 * left several live rows of one session, each listed as its own device, and a web session already
 * ended by the 24-hour idle limit stayed listed until its row expired, up to 30 days later.
 */
class DeviceListIT extends AbstractIntegrationTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User createUser() {
        User user = new User();
        user.setEmail("device-list-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Device List Test User");
        return userRepository.save(user);
    }

    private List<UUID> listedSessions(UUID userId) {
        return refreshTokenService.listActiveSessions(userId).stream().map(RefreshToken::getSessionId).toList();
    }

    private long liveRows(UUID sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE session_id = ? AND revoked_at IS NULL", Long.class, sessionId);
    }

    private long rowsFromParent(String rawParent) {
        UUID parentId = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawParent)).orElseThrow().getId();
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE parent_token_id = ?", Long.class, parentId);
    }

    @Test
    void aRetriedPhoneRefreshStillShowsThePhoneOnce_andSigningItOutEndsEveryRow() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(
                user.getId(), Instant.now(), UUID.randomUUID(), SessionKind.MOBILE);
        RefreshTokenService.IssuedToken laptop = refreshTokenService.issue(user.getId());

        // The first refresh reached the server but its answer was lost; the app retries the same
        // token inside the reuse window and keeps the second answer.
        refreshTokenService.rotate(phone.rawToken());
        RefreshTokenService.RotationResult kept = refreshTokenService.rotate(phone.rawToken());
        assertThat(liveRows(phone.sessionId())).as("the reuse-window path leaves two live rows").isEqualTo(2);
        assertThat(rowsFromParent(phone.rawToken())).as("both record the token they replaced").isEqualTo(2);
        assertThat(listedSessions(user.getId())).containsExactlyInAnyOrder(phone.sessionId(), laptop.sessionId());

        // Later refreshes carry on from the kept token; the leftover stays hidden.
        refreshTokenService.rotate(kept.newToken().rawToken());
        assertThat(liveRows(phone.sessionId())).isEqualTo(2);
        List<RefreshToken> listed = refreshTokenService.listActiveSessions(user.getId());
        assertThat(listed).extracting(RefreshToken::getSessionId)
                .containsExactlyInAnyOrder(phone.sessionId(), laptop.sessionId());

        // "Sign out this device" on the one row shown for the phone ends both rows.
        UUID shownForPhone = listed.stream().filter(rt -> rt.getSessionId().equals(phone.sessionId()))
                .findFirst().orElseThrow().getId();
        refreshTokenService.revokeSession(user.getId(), shownForPhone);
        assertThat(liveRows(phone.sessionId())).isZero();
        assertThat(listedSessions(user.getId())).containsExactly(laptop.sessionId());
    }

    /**
     * The case the leftover rule must not hide: a copy of the token replayed inside the reuse
     * window, after which both copies keep refreshing. Reuse detection never fires for it (see
     * RefreshTokenService's class comment), and a phone session has no absolute cap, so the second
     * entry in the owner's list is what shows it.
     */
    @Test
    void aReplayedCopyThatIsInUseShowsTheSessionTwice() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(
                user.getId(), Instant.now(), UUID.randomUUID(), SessionKind.MOBILE);

        RefreshTokenService.RotationResult owner = refreshTokenService.rotate(phone.rawToken());
        RefreshTokenService.RotationResult copy = refreshTokenService.rotate(phone.rawToken());
        assertThat(listedSessions(user.getId())).as("before either copy is used again, one entry")
                .containsExactly(phone.sessionId());

        refreshTokenService.rotate(owner.newToken().rawToken());
        refreshTokenService.rotate(copy.newToken().rawToken());

        assertThat(listedSessions(user.getId())).as("both copies in use: listed twice")
                .containsExactly(phone.sessionId(), phone.sessionId());
    }

    @Test
    void aWebSessionPastTheIdleLimitIsNotListed_becauseItCanNoLongerRefresh() {
        User user = createUser();
        RefreshTokenService.IssuedToken idle = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken recent = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(
                user.getId(), Instant.now(), UUID.randomUUID(), SessionKind.MOBILE);
        jdbcTemplate.update("UPDATE refresh_tokens SET created_at = now() - interval '25 hours', "
                + "last_seen_at = now() - interval '25 hours' WHERE token_hash = ?", TokenHasher.sha256(idle.rawToken()));
        jdbcTemplate.update("UPDATE refresh_tokens SET created_at = now() - interval '89 days', "
                + "last_seen_at = now() - interval '89 days' WHERE token_hash = ?", TokenHasher.sha256(phone.rawToken()));

        assertThat(listedSessions(user.getId()))
                .containsExactlyInAnyOrder(recent.sessionId(), phone.sessionId());

        // The list agrees with refresh: the session it leaves out is the one refresh refuses.
        ApiException refused = catchThrowableOfType(ApiException.class, () -> refreshTokenService.rotate(idle.rawToken()));
        assertThat(refused).isNotNull();
        assertThat(refused.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_IDLE);
    }

    @Test
    void aWebSessionPastTheThirtyDayCapIsNotListed() {
        User user = createUser();
        RefreshTokenService.IssuedToken old = refreshTokenService.issue(user.getId());
        jdbcTemplate.update("UPDATE refresh_tokens SET session_started_at = now() - interval '31 days' WHERE token_hash = ?",
                TokenHasher.sha256(old.rawToken()));

        assertThat(listedSessions(user.getId())).isEmpty();
        ApiException refused = catchThrowableOfType(ApiException.class, () -> refreshTokenService.rotate(old.rawToken()));
        assertThat(refused.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_MAX_AGE);
    }
}
