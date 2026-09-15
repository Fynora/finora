package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RefreshToken;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies, against a real Postgres instance (not a mock), the exact race
 * {@code RefreshToken}'s own {@code @Version} field's doc comment claims is closed: two concurrent
 * {@code rotate()} calls presenting the SAME still-valid raw token must not both succeed. An
 * H2/mock-based test cannot exercise this -- optimistic-lock version conflicts are a real
 * Postgres/Hibernate row-update semantic, not something a mocked repository enforces.
 */
class RefreshTokenRotationConcurrencyIT extends AbstractIntegrationTest {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("refresh-rotation-race-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Rotation Race Test User");
        return userRepository.save(user);
    }

    @Test
    void twoConcurrentRotationsOfTheSameTokenOnlyOneSucceeds() throws Exception {
        User user = createUser();
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        String rawToken = issued.rawToken();

        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        List<Future<?>> futures = List.of(
                pool.submit(() -> raceRotate(rawToken, ready, go, succeeded, conflicted)),
                pool.submit(() -> raceRotate(rawToken, ready, go, succeeded, conflicted)));

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(succeeded.get())
                .as("exactly one of two concurrent rotations of the same token must win -- "
                        + "two successes would mean two valid sibling sessions minted from one rotation")
                .isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        List<RefreshToken> allForUser = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId());
        assertThat(allForUser)
                .as("only the winner's newly-issued token should be live -- not two")
                .hasSize(1);
    }

    private void raceRotate(String rawToken, CountDownLatch ready, CountDownLatch go,
            AtomicInteger succeeded, AtomicInteger conflicted) {
        ready.countDown();
        try {
            go.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        try {
            refreshTokenService.rotate(rawToken);
            succeeded.incrementAndGet();
        } catch (ObjectOptimisticLockingFailureException e) {
            conflicted.incrementAndGet();
        }
    }

    /** Distinct scenario from the rotate()-vs-rotate() race above: this is rotate() (device A,
     *  mid-refresh) racing revokeAllOtherSessionsForUser (device B completing a password change
     *  with "sign out other devices") for device A's SAME token row. The audit's finding #22
     *  described this as a plain last-write-wins on revokedAt with no @Version -- checking whether
     *  that holds against the current entity, which does have one. */
    @Test
    void rotateRacingBulkRevokeOfOtherSessionsNeverSilentlyLosesTheRevocation() throws Exception {
        User user = createUser();
        RefreshTokenService.IssuedToken currentDevice = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken otherDevice = refreshTokenService.issue(user.getId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger rotateSucceeded = new AtomicInteger();
        AtomicInteger rotateConflicted = new AtomicInteger();

        Future<?> rotateFuture = pool.submit(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                refreshTokenService.rotate(otherDevice.rawToken());
                rotateSucceeded.incrementAndGet();
            } catch (ObjectOptimisticLockingFailureException e) {
                rotateConflicted.incrementAndGet();
            }
        });
        AtomicInteger revokeSucceeded = new AtomicInteger();
        AtomicInteger revokeConflicted = new AtomicInteger();
        Future<?> revokeFuture = pool.submit(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                refreshTokenService.revokeAllOtherSessionsForUser(user.getId(), currentDevice.sessionId());
                revokeSucceeded.incrementAndGet();
            } catch (ObjectOptimisticLockingFailureException e) {
                revokeConflicted.incrementAndGet();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        rotateFuture.get(10, TimeUnit.SECONDS);
        revokeFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Four orderings are all individually correct; what must NEVER happen is rotate()
        // reporting success (a live "otherDevice" session token exists) while that same row is
        // ALSO revoked -- which is what a plain last-write-wins on revokedAt (no @Version) would
        // produce. @Version turns "silently both happen" into "one of the two writers throws
        // instead" (whichever loses the row-lock race), so at least one side must report a
        // conflict whenever rotate() reports success.
        if (rotateSucceeded.get() == 1) {
            assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId()))
                    .as("rotate() reported success -- the session it just rotated must genuinely still "
                            + "be live, not silently revoked underneath it by the bulk-revoke writer")
                    .extracting(RefreshToken::getSessionId)
                    .contains(otherDevice.sessionId());
            assertThat(revokeConflicted.get())
                    .as("rotate() won -- the bulk revoke's write to this same row must have been the "
                            + "one to lose the race and report a conflict, not silently succeed too")
                    .isEqualTo(1);
        } else {
            assertThat(rotateConflicted.get())
                    .as("rotate() must fail loudly (version conflict), not silently succeed against "
                            + "a row the bulk revoke already won")
                    .isEqualTo(1);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
