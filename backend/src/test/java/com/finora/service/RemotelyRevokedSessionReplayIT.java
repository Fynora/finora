package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RefreshToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.util.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A device the owner signed out from somewhere else still holds its refresh token, and the next
 * time it is opened it presents that token to /auth/refresh -- it has no way to know the session
 * ended. That replay is expected, not suspicious, and must be answered by rejecting that one
 * session. Treating it as a stolen token signs the owner out of every device, including the one
 * they used to remove the old phone.
 *
 * <p>Against real Postgres, because the outcome that matters is which rows are still live after
 * rotate()'s own transaction commits.
 */
class RemotelyRevokedSessionReplayIT extends AbstractIntegrationTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private User createUser() {
        User user = new User();
        user.setEmail("remote-revoke-replay-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Remote Revoke Replay Test User");
        return userRepository.save(user);
    }

    private boolean isLive(UUID sessionId) {
        return refreshTokenRepository.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, Instant.now());
    }

    private UUID tokenRowId(String rawToken) {
        return refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawToken)).orElseThrow().getId();
    }

    @Test
    void replayFromADeviceSignedOutFromTheDeviceListEndsOnlyThatDevice() {
        User user = createUser();
        RefreshTokenService.IssuedToken oldPhone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());

        // DeviceController's DELETE /users/me/devices/{id}: the id is the token row the list showed.
        refreshTokenService.revokeSession(user.getId(), tokenRowId(oldPhone.rawToken()));

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(oldPhone.rawToken()));

        assertThat(isLive(owner.sessionId()))
                .as("the device that removed the old phone must stay signed in when the old phone "
                        + "is opened again")
                .isTrue();
        assertThat(rejected).as("the removed device must not get a new token").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(isLive(oldPhone.sessionId())).isFalse();
    }

    @Test
    void replayFromADeviceSignedOutByAPasswordChangeEndsOnlyThatDevice() {
        User user = createUser();
        RefreshTokenService.IssuedToken otherDevice = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());

        // PasswordChangeService / EmailChangeService / PhoneChangeService, "sign out other devices".
        refreshTokenService.revokeAllOtherSessionsForUser(user.getId(), owner.sessionId());

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(otherDevice.rawToken()));

        assertThat(isLive(owner.sessionId()))
                .as("the device that changed the password chose to stay signed in; another "
                        + "device opening later must not undo that")
                .isTrue();
        assertThat(rejected).as("the signed-out device must not get a new token").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(isLive(otherDevice.sessionId())).isFalse();
    }

    /**
     * The rule the two tests above must not weaken: a token retired by ROTATION and presented
     * again outside the grace window is still the theft signal and still ends every session.
     */
    @Test
    void aRotatedTokenReplayedOutsideTheGraceWindowStillEndsEverySession() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken laptop = refreshTokenService.issue(user.getId());

        refreshTokenService.rotate(phone.rawToken());
        RefreshToken retired = refreshTokenRepository.findByTokenHash(
                TokenHasher.sha256(phone.rawToken())).orElseThrow();
        // Push the rotation well past app.jwt.refresh-reuse-grace-ms rather than sleeping.
        retired.setRotatedAt(Instant.now().minusSeconds(3600));
        retired.setRevokedAt(retired.getRotatedAt());
        refreshTokenRepository.save(retired);

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(phone.rawToken()));

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);
        assertThat(isLive(phone.sessionId())).isFalse();
        assertThat(isLive(laptop.sessionId()))
                .as("theft detection must still sign out every device")
                .isFalse();
    }

    /**
     * Password reset (and the admin and theft responses) end every session at once. The user then
     * signs in again, and the phone they had not opened since still holds its old token.
     */
    @Test
    void replayAfterSignOutEverywhereDoesNotEndASessionStartedSince() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());

        refreshTokenService.revokeAllForUser(user.getId());
        RefreshTokenService.IssuedToken freshLogin = refreshTokenService.issue(user.getId());

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(phone.rawToken()));

        assertThat(isLive(freshLogin.sessionId()))
                .as("the sign-in made after the reset must survive the phone being opened")
                .isTrue();
        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(isLive(phone.sessionId())).isFalse();
    }

    /**
     * A device that signed itself out discarded its token. Presenting it again means someone else
     * kept a copy, so the strict rule stays.
     */
    @Test
    void aLoggedOutTokenPresentedAgainStillEndsEverySession() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken laptop = refreshTokenService.issue(user.getId());

        refreshTokenService.revoke(phone.rawToken());

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(phone.rawToken()));

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);
        assertThat(isLive(laptop.sessionId())).isFalse();
    }

    /**
     * The device list is loaded, then the listed device refreshes before the owner taps "sign
     * out". The id the owner sends now names a row rotation already retired; the session lives on
     * in its successor and has to end anyway.
     */
    @Test
    void signingOutADeviceWhoseTokenRotatedSinceTheListLoadedStillEndsIt() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());
        UUID listedRowId = tokenRowId(phone.rawToken());

        RefreshTokenService.RotationResult refreshed = refreshTokenService.rotate(phone.rawToken());

        refreshTokenService.revokeSession(user.getId(), listedRowId);

        assertThat(isLive(phone.sessionId()))
                .as("signing a device out must end its session, not only the row the list showed")
                .isFalse();
        assertThat(isLive(owner.sessionId())).isTrue();

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(refreshed.newToken().rawToken()));
        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(isLive(owner.sessionId())).isTrue();
    }

    /**
     * The device refreshes, its response is lost, the owner signs it out, and the device retries
     * the token it presented a moment ago. That row was retired by rotation, not by the remote
     * revocation, and is inside the grace window -- still a device that has not heard yet.
     */
    @Test
    void aRetryInsideTheGraceWindowAfterTheSessionWasSignedOutEndsOnlyThatDevice() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());

        RefreshTokenService.RotationResult refreshed = refreshTokenService.rotate(phone.rawToken());
        refreshTokenService.revokeSession(user.getId(), tokenRowId(refreshed.newToken().rawToken()));

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.rotate(phone.rawToken()));

        assertThat(isLive(owner.sessionId())).isTrue();
        assertThat(rejected).as("a signed-out session must never mint a token through the grace window")
                .isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
        assertThat(isLive(phone.sessionId())).isFalse();
    }

    /**
     * "Sign out this device" arriving while that device's refresh is mid-transaction: the refresh
     * has retired the listed row and written a successor, but not committed. Held open
     * deterministically, the same way RefreshTokenRotationConcurrencyIT does it for logout.
     */
    @Test
    void signingOutADeviceDuringItsUncommittedRefreshStillEndsIt() throws Exception {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());
        UUID listedRowId = tokenRowId(phone.rawToken());

        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> refresh = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refreshTokenService.rotate(phone.rawToken());
            rotated.countDown();
            try {
                commit.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> signOut = pool.submit(() -> refreshTokenService.revokeSession(user.getId(), listedRowId));
        Thread.sleep(300); // the sign-out is now running against the uncommitted refresh
        commit.countDown();
        refresh.get(10, TimeUnit.SECONDS);
        signOut.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(isLive(phone.sessionId()))
                .as("the successor the refresh wrote must be ended by the sign-out too")
                .isFalse();
        assertThat(isLive(owner.sessionId())).isTrue();
    }

    /**
     * Both of the above at once: the listed row was retired by an earlier refresh, and the
     * device is mid-way through refreshing its SUCCESSOR when the owner taps "sign out". Locking
     * the listed row does not wait for that refresh, since it holds a different row.
     */
    @Test
    void signingOutWithAStaleIdWhileTheSuccessorIsMidRefreshStillEndsIt() throws Exception {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken owner = refreshTokenService.issue(user.getId());
        UUID listedRowId = tokenRowId(phone.rawToken());
        String successor = refreshTokenService.rotate(phone.rawToken()).newToken().rawToken();

        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> refresh = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refreshTokenService.rotate(successor);
            rotated.countDown();
            try {
                commit.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();

        Future<?> signOut = pool.submit(() -> refreshTokenService.revokeSession(user.getId(), listedRowId));
        Thread.sleep(300); // the sign-out is now running against the uncommitted refresh
        commit.countDown();
        refresh.get(10, TimeUnit.SECONDS);
        signOut.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(isLive(phone.sessionId()))
                .as("the token the in-flight refresh minted must be ended too")
                .isFalse();
        assertThat(isLive(owner.sessionId())).isTrue();
    }

    @Test
    void signingOutADeviceOfAnotherUserIsNotFound() {
        User owner = createUser();
        User other = createUser();
        RefreshTokenService.IssuedToken othersPhone = refreshTokenService.issue(other.getId());

        ApiException rejected = catchThrowableOfType(ApiException.class,
                () -> refreshTokenService.revokeSession(owner.getId(), tokenRowId(othersPhone.rawToken())));

        assertThat(rejected).isNotNull();
        assertThat(rejected.getStatus().value()).isEqualTo(404);
        assertThat(isLive(othersPhone.sessionId())).isTrue();
    }
}
