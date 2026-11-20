package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.AuthResponse;
import com.finora.dto.AuthDtos.LoginRequest;
import com.finora.dto.AuthDtos.RefreshRequest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.integrations.google.login.GoogleIdentity;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Sign-up by password never proves the email belongs to the person signing up. A stranger can
 * therefore create an account under someone else's address, with a password and phone number of
 * their own, and wait for the real owner to arrive. These tests walk that sequence end to end and
 * pin what the owner's two possible actions do:
 *
 * <ul>
 *   <li>the owner tries Google sign-in, is emailed a link, opens it: the account becomes theirs
 *       and nothing the stranger set still works;</li>
 *   <li>the owner opens the sign-up email they never asked for: nothing happens, because that link
 *       only works from a session of the account it was sent for.</li>
 * </ul>
 *
 * Links are captured from the real {@link EmailProvider} calls (spied, not stubbed), so they are
 * the tokens the emails would have carried.
 */
class EmailVerificationTakeoverIT extends AbstractIntegrationTest {

    private static final String STRANGERS_PASSWORD = "SecurePass123";

    @Autowired private AuthService authService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TestRestTemplate restTemplate;
    @Autowired private com.finora.security.JwtService jwtService;
    @Autowired private com.finora.config.JwtProperties jwtProperties;

    @MockitoSpyBean private EmailProvider emailProvider;

    private static String newEmail() {
        return "takeover-it-" + UUID.randomUUID() + "@example.test";
    }

    private static String newPhone() {
        return "+919" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    /** The stranger signs up under {@code email} and verifies a phone of their own. */
    private AuthResponse strangerSignsUp(String email, String phone) {
        AuthResponse session = authService.register(new RegisterRequest(email, STRANGERS_PASSWORD, "Jane Doe", phone, null));
        jdbcTemplate.update("UPDATE users SET phone_verified = true WHERE id = ?", session.id());
        return session;
    }

    /** Every verification link emailed to {@code email} so far, oldest first. */
    private List<String> linksSentTo(String email) {
        ArgumentCaptor<String> links = ArgumentCaptor.forClass(String.class);
        verify(emailProvider, atLeastOnce()).sendEmailVerificationEmail(eq(email), links.capture());
        return links.getAllValues().stream()
                .map(link -> link.substring(link.indexOf("token=") + "token=".length()))
                .toList();
    }

    /** The owner tries Google sign-in, which is refused and emails a fresh link. Returns that link. */
    private String ownerTriesGoogleAndIsSentALink(String email, UUID userId) {
        // Straight after the stranger's sign-up, with its link minutes old and still live. This
        // helper used to move that link an hour into the past first, which hid a dead end: the
        // resend cooldown counted the sign-up link, so the owner was told to open a link that
        // refuses anyone not signed in to the account, and was sent nothing they could use.
        ApiException refused = catchThrowableOfType(
                () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")), ApiException.class);
        assertThat(refused).as("Google sign-in into an unverified account is refused").isNotNull();
        List<String> links = linksSentTo(email);
        return links.get(links.size() - 1);
    }

    @Test
    void openingTheGoogleConflictLink_leavesNothingTheStrangerSetWorking() {
        String email = newEmail();
        String phone = newPhone();
        AuthResponse strangersSession = strangerSignsUp(email, phone);
        UUID userId = strangersSession.id();

        String conflictLink = ownerTriesGoogleAndIsSentALink(email, userId);

        // The owner is not signed in to anything when they open it.
        assertThat(authService.verifyEmail(conflictLink, null).message()).isNotBlank();

        User account = userRepository.findById(userId).orElseThrow();
        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.getPhoneNumber()).as("the stranger's phone number is gone").isNull();
        assertThat(account.isPhoneVerified()).isFalse();
        assertThat(account.isGoogleAccount()).as("the account now signs in with Google").isTrue();
        assertThat(userRepository.findByPhoneNumberAndAccountScope(phone, User.SCOPE_USER))
                .as("phone sign-in can no longer find the account by the stranger's number").isEmpty();

        ApiException passwordRefused = catchThrowableOfType(
                () -> authService.login(new LoginRequest(email, STRANGERS_PASSWORD, null)), ApiException.class);
        assertThat(passwordRefused).as("the stranger's password no longer signs in").isNotNull();
        assertThat(passwordRefused.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ApiException sessionRefused = catchThrowableOfType(
                () -> authService.refresh(new RefreshRequest(strangersSession.refreshToken())), ApiException.class);
        assertThat(sessionRefused).as("the session the stranger opened at sign-up is ended").isNotNull();
        assertThat(refreshTokenService.userOfLiveSession(strangersSession.refreshToken())).isEmpty();

        AuthResponse ownersSession = authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe"));
        assertThat(ownersSession.id()).as("the owner signs in to the same account").isEqualTo(userId);
        assertThat(ownersSession.phoneVerified()).as("and is asked to verify a phone of their own").isFalse();

        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(userId).stream().map(AuditLog::getAction))
                .contains("ACCOUNT_HANDED_TO_PROVEN_EMAIL_OWNER", "EMAIL_VERIFIED");
    }

    /**
     * The owner tries Google within minutes of the stranger's sign-up. The sign-up link in their
     * inbox is live and recent, but it is no use to them, so it must not stand in for the link
     * this attempt owes them. The cooldown still holds for the link that IS theirs: a second try
     * straight away sends nothing more.
     */
    @Test
    void googleSignIn_minutesAfterSignUp_sendsALinkTheOwnerCanUse_andThenHoldsTheCooldown() {
        String email = newEmail();
        UUID userId = strangerSignsUp(email, newPhone()).id();
        assertThat(linksSentTo(email)).as("the sign-up link").hasSize(1);

        ApiException first = catchThrowableOfType(
                () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")), ApiException.class);
        assertThat(first.getMessage()).contains("We've sent a new verification link");
        assertThat(linksSentTo(email)).as("a second link, for the owner").hasSize(2);

        ApiException second = catchThrowableOfType(
                () -> authService.loginWithGoogle(new GoogleIdentity(email, "Jane Doe")), ApiException.class);
        assertThat(second.getMessage()).contains("a few minutes ago");
        assertThat(linksSentTo(email)).as("nothing more is sent inside the cooldown").hasSize(2);

        // The link they were pointed at works signed out, and hands the account over.
        assertThat(authService.verifyEmail(linksSentTo(email).get(1), null).message()).isNotBlank();
        assertThat(userRepository.findById(userId).orElseThrow().isGoogleAccount()).isTrue();
    }

    @Test
    void theSignUpLink_doesNothingWithoutASessionOfThatAccount_andStillWorksWithOne() {
        String email = newEmail();
        String phone = newPhone();
        AuthResponse strangersSession = strangerSignsUp(email, phone);
        UUID userId = strangersSession.id();
        String signUpLink = linksSentTo(email).get(0);

        ApiException signedOut = catchThrowableOfType(() -> authService.verifyEmail(signUpLink, null), ApiException.class);
        assertThat(signedOut).as("the inbox owner, signed out, cannot use the sign-up link").isNotNull();
        assertThat(signedOut.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID someoneElse = authService.register(
                new RegisterRequest(newEmail(), STRANGERS_PASSWORD, "John Roe", newPhone(), null)).id();
        ApiException otherAccount = catchThrowableOfType(
                () -> authService.verifyEmail(signUpLink, someoneElse), ApiException.class);
        assertThat(otherAccount).as("nor can a session of a different account").isNotNull();
        assertThat(otherAccount.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(userRepository.findById(userId).orElseThrow().isEmailVerified())
                .as("two refusals verify nothing").isFalse();

        // The refusals did not spend the link: the person who signed up can still use it.
        assertThat(authService.verifyEmail(signUpLink, userId).message()).isNotBlank();
        User account = userRepository.findById(userId).orElseThrow();
        assertThat(account.isEmailVerified()).isTrue();
        assertThat(account.getPhoneNumber()).as("a sign-up link changes nothing else").isNotNull();
        assertThat(account.isPhoneVerified()).isTrue();
        assertThat(authService.login(new LoginRequest(email, STRANGERS_PASSWORD, null)).id())
                .as("their own password still works").isEqualTo(userId);
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(userId).stream().map(AuditLog::getAction))
                .doesNotContain("ACCOUNT_HANDED_TO_PROVEN_EMAIL_OWNER");
    }

    @Test
    void overHttp_theSignUpLinkIsRefusedBare_acceptedWithTheRefreshCookie_andWithTheAccessToken() {
        String cookieEmail = newEmail();
        AuthResponse cookieSession = strangerSignsUp(cookieEmail, newPhone());
        String cookieLink = linksSentTo(cookieEmail).get(0);

        ResponseEntity<String> bare = postVerify(cookieLink, new HttpHeaders());
        assertThat(bare.getStatusCode()).as("403, never 401: clients read a 401 as an expired session")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bare.getBody()).contains("Sign in to Fynora on this device");
        assertThat(userRepository.findById(cookieSession.id()).orElseThrow().isEmailVerified()).isFalse();

        // What a browser sends on a page opened from the email: the refresh cookie, no access token.
        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add(HttpHeaders.COOKIE, "finora_refresh_token=" + cookieSession.refreshToken());
        assertThat(postVerify(cookieLink, withCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(cookieSession.id()).orElseThrow().isEmailVerified()).isTrue();

        // What the phone app sends: its stored access token.
        String bearerEmail = newEmail();
        AuthResponse bearerSession = strangerSignsUp(bearerEmail, newPhone());
        HttpHeaders withBearer = new HttpHeaders();
        withBearer.setBearerAuth(bearerSession.token());
        assertThat(postVerify(linksSentTo(bearerEmail).get(0), withBearer).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(bearerSession.id()).orElseThrow().isEmailVerified()).isTrue();
    }

    /** The phone app sends the access token it has in storage, and opened from an email that token
     *  has usually expired. It still shows which account the app is signed in to, as long as this
     *  server signed it and its session is open. */
    @Test
    void overHttp_anExpiredAccessTokenCounts_onlyWhileItsSessionIsOpen_andOnlyIfThisServerSignedIt() {
        String email = newEmail();
        AuthResponse session = strangerSignsUp(email, newPhone());
        String link = linksSentTo(email).get(0);
        UUID sessionId = jwtService.extractSessionId(session.token());

        HttpHeaders forged = new HttpHeaders();
        forged.setBearerAuth(expiredToken(session.id(), sessionId, "a-different-signing-key-of-at-least-32-chars"));
        assertThat(postVerify(link, forged).getStatusCode())
                .as("a token signed with another key shows nothing").isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders endedSession = new HttpHeaders();
        endedSession.setBearerAuth(expiredToken(session.id(), UUID.randomUUID(), jwtProperties.getSecret()));
        assertThat(postVerify(link, endedSession).getStatusCode())
                .as("nor does one whose session is not open").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userRepository.findById(session.id()).orElseThrow().isEmailVerified()).isFalse();

        HttpHeaders expired = new HttpHeaders();
        expired.setBearerAuth(expiredToken(session.id(), sessionId, jwtProperties.getSecret()));
        assertThat(postVerify(link, expired).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(session.id()).orElseThrow().isEmailVerified()).isTrue();
    }

    private static String expiredToken(UUID userId, UUID sessionId, String secret) {
        java.util.Date anHourAgo = new java.util.Date(System.currentTimeMillis() - 3_600_000);
        return io.jsonwebtoken.Jwts.builder()
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .issuedAt(new java.util.Date(anHourAgo.getTime() - 900_000))
                .expiration(anHourAgo)
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void aRotatedRefreshTokenStillShowsItsSession_untilThatSessionEnds() {
        AuthResponse session = strangerSignsUp(newEmail(), newPhone());
        assertThat(refreshTokenService.userOfLiveSession(session.refreshToken())).contains(session.id());

        // The page's own start-up refresh can land before the verify call reads the cookie.
        authService.refresh(new RefreshRequest(session.refreshToken()));
        assertThat(refreshTokenService.userOfLiveSession(session.refreshToken()))
                .as("rotated away, but its session is still open").contains(session.id());

        refreshTokenService.revokeAllForUser(session.id());
        assertThat(refreshTokenService.userOfLiveSession(session.refreshToken())).isEmpty();
        assertThat(refreshTokenService.userOfLiveSession("not-a-token")).isEmpty();
        assertThat(refreshTokenService.userOfLiveSession(null)).isEmpty();
    }

    private ResponseEntity<String> postVerify(String token, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/api/v1/auth/verify-email",
                new HttpEntity<>("{\"token\": \"" + token + "\"}", headers), String.class);
    }
}
