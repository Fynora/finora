package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
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
 * Audit F-05 (2026-11-20). A refresh token presented twice inside the reuse window is answered
 * twice, so the session then holds two tokens. A client that was only retrying keeps one of them
 * and never presents the other. When BOTH are presented, two different holders exist, and one of
 * them is not the owner. Before this, the two went on rotating side by side and nothing noticed.
 *
 * <p>Against real Postgres, because what matters is which rows are live after rotate()'s own
 * transaction commits, and because one case is two refreshes at the same moment.
 */
class RefreshTokenBothCopiesInUseIT extends AbstractIntegrationTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private User createUser() {
        User user = new User();
        user.setEmail("both-copies-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Both Copies Test User");
        return userRepository.save(user);
    }

    private boolean isLive(UUID sessionId) {
        return refreshTokenRepository.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, Instant.now());
    }

    private String rotate(String rawToken) {
        return refreshTokenService.rotate(rawToken).newToken().rawToken();
    }

    private ApiException refused(String rawToken) {
        return catchThrowableOfType(ApiException.class, () -> refreshTokenService.rotate(rawToken));
    }

    @Test
    void whenBothCopiesAreUsed_theSecondIsRefusedAndEverySessionEnds() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken laptop = refreshTokenService.issue(user.getId());

        String ownersCopy = rotate(phone.rawToken());
        String thiefsCopy = rotate(phone.rawToken()); // the same token again, inside the window

        String ownersNext = rotate(ownersCopy);
        ApiException rejected = refused(thiefsCopy);

        assertThat(rejected).as("the second copy to be used must not get a token").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);
        assertThat(isLive(phone.sessionId())).as("the session with two holders is over").isFalse();
        assertThat(isLive(laptop.sessionId()))
                .as("the same response as any other stolen token: every device signs in again")
                .isFalse();

        ApiException ownerLater = refused(ownersNext);
        assertThat(ownerLater).isNotNull();
        assertThat(ownerLater.getCode())
                .as("the other holder is told its session ended, not accused a second time")
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    /** It does not matter which of the two is used first, or how far each has been rotated. */
    @Test
    void theOrderTheCopiesAreUsedInDoesNotMatter() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());

        String first = rotate(phone.rawToken());
        String second = rotate(phone.rawToken());

        rotate(rotate(rotate(second)));
        ApiException rejected = refused(first);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);
        assertThat(isLive(phone.sessionId())).isFalse();
    }

    /** What the window is for. The client got two answers, kept one, and carries on for as long as
     *  it likes. The copy it never stored changes nothing. */
    @Test
    void aRetryThatKeepsOneCopyCarriesOnUndisturbed() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());

        rotate(phone.rawToken());                       // the answer that was lost
        String kept = rotate(phone.rawToken());         // the retry's answer
        String later = rotate(rotate(rotate(kept)));

        assertThat(isLive(phone.sessionId())).isTrue();
        assertThat(refused(later)).as("still an ordinary, usable session").isNull();
    }

    /** Two lost answers in a row: three copies, one kept. */
    @Test
    void aRetryAnsweredThreeTimesStillKeepsOne() {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());

        rotate(phone.rawToken());
        rotate(phone.rawToken());
        String kept = rotate(phone.rawToken());

        assertThat(refused(rotate(kept))).isNull();
        assertThat(isLive(phone.sessionId())).isTrue();
    }

    /**
     * Both copies presented at the same moment. Each request locks only its own row, so without
     * more, neither would see the other's rotation, both would succeed, and their successors
     * share no parent: never noticed again. The first is held open before it commits, the way
     * RefreshTokenRotationConcurrencyIT does it.
     */
    @Test
    void bothCopiesUsedAtTheSameMoment_oneIsStillRefused() throws Exception {
        User user = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        String first = rotate(phone.rawToken());
        String second = rotate(phone.rawToken());

        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> one = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refreshTokenService.rotate(first);
            rotated.countDown();
            try {
                commit.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();

        Future<ApiException> two = pool.submit(() -> refused(second));
        Thread.sleep(300); // the second is now running against the uncommitted first
        commit.countDown();
        one.get(10, TimeUnit.SECONDS);
        ApiException rejected = two.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(rejected).as("the copy that came second must be refused").isNotNull();
        assertThat(rejected.getCode()).isEqualTo(ErrorCode.AUTH_SESSION_REVOKED);
        assertThat(isLive(phone.sessionId())).isFalse();
    }

    /** One user's two copies are that user's business only. */
    @Test
    void anotherUsersSessionsAreNotTouched() {
        User user = createUser();
        User bystander = createUser();
        RefreshTokenService.IssuedToken phone = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken bystanders = refreshTokenService.issue(bystander.getId());

        String first = rotate(phone.rawToken());
        String second = rotate(phone.rawToken());
        rotate(first);
        refused(second);

        assertThat(isLive(bystanders.sessionId())).isTrue();
    }
}
