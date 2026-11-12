package com.finora.service;

import com.finora.config.ClientIpResolver;
import com.finora.config.JwtProperties;
import com.finora.entity.RefreshToken;
import com.finora.entity.SessionKind;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.observability.AuthMetrics;
import com.finora.repository.RefreshTokenRepository;
import com.finora.support.ClientIdentity;
import com.finora.util.TokenHasher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phone and web sessions live by different limits (design: 2026-11-10 mobile PIN unlock).
 *
 * <p>A phone session, guarded on the device by a required PIN, ends after 90 days unused and has no
 * absolute cap. A web session keeps the 24-hour idle limit and the 30-day cap. Which kind a session
 * is gets decided once, at sign-in, and every rotation copies it: a later request can never change
 * it, so a stolen web token cannot be turned into a long phone session by sending phone headers.
 */
class RefreshTokenSessionKindTest {

    private static final Duration WEB_IDLE = Duration.ofHours(24);
    private static final Duration WEB_ABSOLUTE = Duration.ofDays(30);
    private static final Duration MOBILE_IDLE = Duration.ofDays(90);
    private static final String RAW_TOKEN = "raw-refresh-token";

    private RefreshTokenRepository repository;
    private HttpServletRequest request;
    private RefreshTokenService service;
    private UUID userId;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        request = mock(HttpServletRequest.class);
        JwtProperties props = new JwtProperties();
        props.setIdleTimeoutMs(WEB_IDLE.toMillis());
        props.setAbsoluteSessionMs(WEB_ABSOLUTE.toMillis());
        props.setMobileIdleTimeoutMs(MOBILE_IDLE.toMillis());
        props.setMobileAbsoluteSessionMs(0);
        props.setRefreshReuseGraceMs(Duration.ofSeconds(30).toMillis());
        ReflectionTestUtils.setField(props, "refreshExpirationMs", Duration.ofDays(30).toMillis());
        service = new RefreshTokenService(repository, props, request, mock(ClientIpResolver.class),
                mock(AuthMetrics.class));
        userId = UUID.randomUUID();
        when(repository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
    }

    private RefreshToken existingToken(SessionKind kind, Duration tokenAge, Duration sessionAge) {
        RefreshToken rt = new RefreshToken();
        rt.setUserId(userId);
        rt.setTokenHash(TokenHasher.sha256(RAW_TOKEN));
        rt.setExpiresAt(Instant.now().plus(Duration.ofDays(100)));
        rt.setSessionKind(kind);
        ReflectionTestUtils.setField(rt, "createdAt", Instant.now().minus(tokenAge));
        rt.setSessionStartedAt(Instant.now().minus(sessionAge));
        when(repository.findByTokenHashForUpdate(TokenHasher.sha256(RAW_TOKEN))).thenReturn(Optional.of(rt));
        return rt;
    }

    private void headers(String platform, String appLock) {
        when(request.getHeader(ClientIdentity.PLATFORM_HEADER)).thenReturn(platform);
        when(request.getHeader(ClientIdentity.APP_LOCK_HEADER)).thenReturn(appLock);
    }

    /** The row saved last -- the newly minted token on a successful rotation or sign-in. */
    private RefreshToken lastSaved() {
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        List<RefreshToken> all = saved.getAllValues();
        return all.get(all.size() - 1);
    }

    private static ErrorCode codeOf(Throwable e) {
        return ((ApiException) e).getCode();
    }

    @Test
    void mobileSessionSurvivesFiveDaysIdle() {
        existingToken(SessionKind.MOBILE, Duration.ofDays(5), Duration.ofDays(6));

        service.rotate(RAW_TOKEN);

        RefreshToken minted = lastSaved();
        assertThat(minted.getSessionKind()).isEqualTo(SessionKind.MOBILE);
        assertThat(minted.getExpiresAt())
                .as("a phone token must outlive its own 90-day idle limit, or the 30-day token "
                    + "expiry would end the session first")
                .isAfter(Instant.now().plus(MOBILE_IDLE).minus(Duration.ofMinutes(1)));
    }

    @Test
    void mobileSessionEndsAfterMobileIdle() {
        RefreshToken rt = existingToken(SessionKind.MOBILE, Duration.ofDays(91), Duration.ofDays(95));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(RefreshTokenSessionKindTest::codeOf)
                .isEqualTo(ErrorCode.AUTH_SESSION_IDLE);
        assertThat(rt.getRevokedAt()).isNotNull();
    }

    @Test
    void mobileSessionHasNoAbsoluteCapByDefault() {
        existingToken(SessionKind.MOBILE, Duration.ofHours(1), Duration.ofDays(200));

        service.rotate(RAW_TOKEN);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.MOBILE);
    }

    @Test
    void webSessionKeepsWebLimits() {
        existingToken(SessionKind.WEB, Duration.ofHours(25), Duration.ofDays(2));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(RefreshTokenSessionKindTest::codeOf)
                .isEqualTo(ErrorCode.AUTH_SESSION_IDLE);
    }

    @Test
    void webSessionKeepsTheAbsoluteCap() {
        existingToken(SessionKind.WEB, Duration.ofMinutes(5), Duration.ofDays(31));

        assertThatThrownBy(() -> service.rotate(RAW_TOKEN))
                .isInstanceOf(ApiException.class)
                .extracting(RefreshTokenSessionKindTest::codeOf)
                .isEqualTo(ErrorCode.AUTH_SESSION_MAX_AGE);
    }

    @Test
    void signInFromPinBuildIsMobile() {
        headers("MOBILE_IOS", "pin");

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.MOBILE);
    }

    @Test
    void signInFromAndroidPinBuildIsMobile() {
        headers("MOBILE_ANDROID", "pin");

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.MOBILE);
    }

    @Test
    void signInWithoutLockHeaderIsWeb() {
        // An app build from before the PIN lock: it says it is the phone app, but nothing guards
        // the session on the device, so it keeps the 24-hour rule.
        headers("MOBILE_IOS", null);

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void signInFromWebHeaderIsWeb() {
        headers("WEB", "pin");

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void signInWithAnUnknownLockValueIsWeb() {
        headers("MOBILE_IOS", "yes");

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void signInWithNoRequestContextIsWeb() {
        when(request.getHeader(any())).thenThrow(new IllegalStateException("no request bound"));

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void webSignInTokenExpiryIsUnchanged() {
        headers(null, null);

        service.issue(userId, User.SCOPE_USER);

        assertThat(lastSaved().getExpiresAt())
                .isBefore(Instant.now().plus(Duration.ofDays(30)).plus(Duration.ofMinutes(1)));
    }

    @Test
    void rotationKeepsKindEvenIfHeadersChange() {
        existingToken(SessionKind.WEB, Duration.ofMinutes(5), Duration.ofDays(1));
        headers("MOBILE_IOS", "pin");

        service.rotate(RAW_TOKEN);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void graceReplayKeepsKind() {
        RefreshToken rt = existingToken(SessionKind.MOBILE, Duration.ofMinutes(5), Duration.ofDays(1));
        rt.setRevokedAt(Instant.now().minusSeconds(2));
        rt.setRotatedAt(Instant.now().minusSeconds(2));
        when(repository.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(any(), any())).thenReturn(true);

        service.rotate(RAW_TOKEN);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.MOBILE);
    }

    @Test
    void aRowWithNoKindReadsAsWeb() {
        assertThat(new RefreshToken().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void anAdminSignInIsNeverAPhoneSession() {
        // Admin sessions stay short whatever the request claims: the phone limits exist for the
        // customer app behind its PIN, and the admin portal is web-only.
        headers("MOBILE_IOS", "pin");

        service.issue(userId, User.SCOPE_ADMIN);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }

    @Test
    void theOneArgumentIssueIsAlwaysWeb() {
        // Used where a session must stay short regardless of client -- the suspended-account screen.
        headers("MOBILE_IOS", "pin");

        service.issue(userId);

        assertThat(lastSaved().getSessionKind()).isEqualTo(SessionKind.WEB);
    }
}
