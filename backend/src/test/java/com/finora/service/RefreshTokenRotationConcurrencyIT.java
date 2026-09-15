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
     *  that holds against the current entity, which does have one.
     *
     *  <p>Both orderings are exercised here for real, not forced -- and correctly assert BOTH as
     *  valid outcomes. First attempt at this test asserted rotate() as the sole winner after
     *  observing 10/10 runs go that way; run 3 of a wider repeat immediately falsified that
     *  ("expected: 1 but was: 0" on rotateSucceeded) -- rotate()'s shorter critical path (one row
     *  lookup, one save) wins MOST of the time against revokeAllOtherSessionsForUser's (a
     *  full-table scan for the user, a stream/filter, a saveAll), not always. A test asserting a
     *  specific winner in a genuine race is itself a bug, not a stronger test. {@link
     *  #rotateLosesWhenTheBulkRevokeHasAlreadyCommittedFirst} additionally proves the
     *  less-frequently-hit ordering deterministically, so it is not left depending on scheduling
     *  luck to ever actually be exercised by CI. */
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
        AtomicInteger revokeConflicted = new AtomicInteger();

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
        Future<?> revokeFuture = pool.submit(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                refreshTokenService.revokeAllOtherSessionsForUser(user.getId(), currentDevice.sessionId());
            } catch (ObjectOptimisticLockingFailureException e) {
                revokeConflicted.incrementAndGet();
            }
        });

        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        rotateFuture.get(10, TimeUnit.SECONDS);
        revokeFuture.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Exactly one of the two writers must have touched this row -- @Version makes the other
        // throw instead of silently overwriting it, regardless of which one that is.
        assertThat(rotateSucceeded.get() + rotateConflicted.get())
                .as("rotate() must always resolve to a definite outcome, one or the other")
                .isEqualTo(1);
        if (rotateSucceeded.get() == 1) {
            assertThat(revokeConflicted.get())
                    .as("rotate() won -- the bulk revoke's write to this same row must be the one "
                            + "that lost and reported a conflict, not silently succeeded too")
                    .isEqualTo(1);
            assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId()))
                    .as("rotate() won -- the session it just rotated must genuinely still be live, "
                            + "not silently revoked underneath it by the losing bulk-revoke writer")
                    .extracting(RefreshToken::getSessionId)
                    .contains(otherDevice.sessionId());
        } else {
            assertThat(revokeConflicted.get())
                    .as("the bulk revoke won -- it must have actually succeeded (not also thrown), "
                            + "since rotate() already reported the conflict")
                    .isZero();
        }
    }

    /** The reverse ordering from the test above, proven deterministically rather than by hoping a
     *  race lands this way: reads the token row (exactly what rotate()'s own first line does),
     *  lets a bulk revoke run to completion and commit first, then attempts the same save() call
     *  rotate() would make next using the now-stale entity it read earlier. This is the same
     *  underlying primitive rotate() itself relies on -- {@code refreshTokenRepository.save(rt)}
     *  on an entity whose in-memory version no longer matches the committed row -- exercised
     *  directly instead of via real thread timing that this specific pairing does not naturally
     *  produce (see the test above). */
    @Test
    void rotateLosesWhenTheBulkRevokeHasAlreadyCommittedFirst() {
        User user = createUser();
        RefreshTokenService.IssuedToken currentDevice = refreshTokenService.issue(user.getId());
        RefreshTokenService.IssuedToken otherDevice = refreshTokenService.issue(user.getId());

        // Stands in for the read at the top of rotate(rawToken) -- a stale in-memory copy of the
        // row, taken before the bulk revoke below ever runs.
        RefreshToken staleRead = refreshTokenRepository.findByTokenHash(
                com.finora.util.TokenHasher.sha256(otherDevice.rawToken())).orElseThrow();

        refreshTokenService.revokeAllOtherSessionsForUser(user.getId(), currentDevice.sessionId());

        staleRead.setRevokedAt(java.time.Instant.now());
        org.junit.jupiter.api.Assertions.assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> refreshTokenRepository.saveAndFlush(staleRead),
                "rotate()'s own save() -- the exact call it makes on the row it read -- must throw "
                        + "when the bulk revoke already committed a newer version, not silently "
                        + "overwrite revokedAt back toward null-adjacent state");

        assertThat(refreshTokenRepository.findById(staleRead.getId()).orElseThrow().getRevokedAt())
                .as("the bulk revoke's write must be the one that actually stuck")
                .isNotNull();
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
