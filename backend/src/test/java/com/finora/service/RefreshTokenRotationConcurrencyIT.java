package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.RefreshToken;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
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
 *
 * <p><b>2026-09-15.</b> Every race in this file can resolve two structurally different ways, and
 * both are legitimate: a genuine overlap, where the loser's own {@code save()} loses a version
 * comparison and throws {@link ObjectOptimisticLockingFailureException}; or a full serialization,
 * where the loser's READ happens only after the winner has already committed, so the loser's
 * {@code rotate()} instead finds the row already revoked and takes its own reuse-detection path
 * -- {@code revokeAllForUser} + {@link ApiException} -- neither of which is a version conflict.
 * Treating only the first shape as valid is what produced the original CI failure below; the same
 * gap, unfixed, would also make {@link #twoConcurrentRotationsOfTheSameTokenOnlyOneSucceeds} flake
 * under that ordering, since the loser's own {@code ApiException} was uncaught by
 * {@link #raceRotate} and reuse-detection's {@code revokeAllForUser} revokes every session for the
 * user -- including the winner's freshly-minted one, not only the stale loser's.
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
        AtomicInteger foundAlreadyRevoked = new AtomicInteger();

        List<Future<?>> futures = List.of(
                pool.submit(() -> raceRotate(rawToken, ready, go, succeeded, conflicted, foundAlreadyRevoked)),
                pool.submit(() -> raceRotate(rawToken, ready, go, succeeded, conflicted, foundAlreadyRevoked)));

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
        assertThat(conflicted.get() + foundAlreadyRevoked.get())
                .as("the loser must always resolve to a definite outcome -- either a genuine "
                        + "version conflict, or (if it read only after the winner had already "
                        + "committed) its own reuse-detection response")
                .isEqualTo(1);

        List<RefreshToken> allForUser = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId());
        if (foundAlreadyRevoked.get() == 1) {
            // The loser's read happened only after the winner had already committed, so the
            // loser's rotate() found the row already revoked and took its own reuse-detection
            // path: a stolen/replayed token looks identical to this artificial race from the
            // server's point of view, so it correctly revokes EVERY session for the user,
            // including the winner's freshly-minted one -- not a weaker outcome than "only the
            // winner survives", a stronger, safety-first one. Verified deterministically (no
            // timing luck) by calling rotate() to completion and then rotate() again with the
            // SAME original raw token: the second call throws exactly this reuse-detection
            // ApiException and leaves zero live sessions for the user.
            assertThat(allForUser)
                    .as("the loser's reuse-detection response must have swept every session for "
                            + "this user, including the winner's just-minted one")
                    .isEmpty();
        } else {
            assertThat(allForUser)
                    .as("only the winner's newly-issued token should be live -- not two")
                    .hasSize(1);
        }
    }

    private void raceRotate(String rawToken, CountDownLatch ready, CountDownLatch go,
            AtomicInteger succeeded, AtomicInteger conflicted, AtomicInteger foundAlreadyRevoked) {
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
        } catch (ApiException e) {
            // See this class's own 2026-09-15 doc comment: the loser can instead read only after
            // the winner has already committed, in which case rotate() takes its reuse-detection
            // path rather than hitting a version conflict. Narrowed to this specific code so an
            // unrelated ApiException (expired token, idle/absolute session limits) still fails
            // the test loudly instead of being silently miscounted as this outcome.
            if (e.getCode() != ErrorCode.AUTH_SESSION_REVOKED) {
                throw e;
            }
            foundAlreadyRevoked.incrementAndGet();
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
     *  luck to ever actually be exercised by CI.
     *
     *  <p><b>2026-09-15 correction.</b> A CI run on main (triggered by PR #1556's merge) failed
     *  this test's original assertion,
     *  which required {@code revokeConflicted == 1} unconditionally whenever {@code rotateSucceeded
     *  == 1}. That assumed only two outcomes: either the two writers never touch the same row at
     *  all (bulk revoke wins outright), or they genuinely contend for THE SAME row and the loser
     *  must get {@link ObjectOptimisticLockingFailureException}. A third, legitimate outcome exists
     *  and was not accounted for: rotate() can complete AND COMMIT in full -- including {@link
     *  #issue}'s insert of device A's brand-new token row, carrying the SAME {@code sessionId} --
     *  before revokeAllOtherSessionsForUser's own read even runs. Proven deterministically (no
     *  racing, no timing luck) by calling {@code rotate()} to completion and then {@code
     *  revokeAllOtherSessionsForUser} straight after it in one thread: the bulk revoke throws
     *  nothing, and the freshly-ROTATED row for that same session ends up revoked instead of the
     *  pre-rotation one -- because {@code findByUserIdAndRevokedAtIsNull} naturally picks up
     *  whichever row is live at read time. That is not data loss: the session is still genuinely
     *  revoked either way, just via a different row than the one rotate() itself touched. The
     *  invariant that actually matters, and is what this test checks below, is not "revoke must
     *  always report a conflict" but "the bulk revoke must never let device A's session survive as
     *  live without EITHER reporting a conflict on the row rotate() touched OR genuinely revoking
     *  whatever row is live at the time it read." A version-conflict widened well past any real
     *  scheduling window (an explicit delay between revokeAllOtherSessionsForUser's own read and its
     *  save) still threw correctly -- confirming @Version itself has no gap here; the prior
     *  assertion was checking the wrong thing, not catching a real bug.
     *
     *  <p>The same review surfaced a second, symmetric gap while checking this test's own
     *  robustness (not yet observed in CI, but reachable by the mirror-image ordering): if the
     *  bulk revoke instead completes AND COMMITS in full before rotate()'s own read ever runs,
     *  {@code rotate()} does not throw {@link ObjectOptimisticLockingFailureException} at all --
     *  it finds the row already revoked and takes its OWN reuse-detection path ({@code
     *  revokeAllForUser} + {@link ApiException}), which the original {@code rotateFuture} lambda
     *  did not catch. Left alone, that ordering would have failed {@code rotateFuture.get()} with
     *  an uncaught {@code ExecutionException} instead of a clean assertion -- a second source of
     *  CI flakiness in this same test, just not yet the one that fired. Proven deterministically
     *  the same way: calling {@code revokeAllOtherSessionsForUser} to completion and then {@code
     *  rotate()} straight after throws exactly this {@link ApiException}, and leaves ZERO live
     *  sessions for the user -- rotate()'s reuse-detection sweeps every session, including
     *  currentDevice's, which is a stronger outcome than what the bulk revoke itself asked for. */
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
        AtomicInteger rotateFoundAlreadyRevoked = new AtomicInteger();
        AtomicInteger revokeConflicted = new AtomicInteger();

        Future<?> rotateFuture = pool.submit(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                refreshTokenService.rotate(otherDevice.rawToken());
                rotateSucceeded.incrementAndGet();
            } catch (ObjectOptimisticLockingFailureException e) {
                rotateConflicted.incrementAndGet();
            } catch (ApiException e) {
                // The mirror image of the "rotate() commits before revoke() reads" outcome
                // below: the bulk revoke can instead complete AND COMMIT in full before
                // rotate()'s own read ever runs. rotate() then finds the row already revoked
                // and takes its own reuse-detection path (revokeAllForUser + throw) rather than
                // hitting a version conflict -- confirmed deterministically (no timing luck) by
                // calling revokeAllOtherSessionsForUser() to completion and then rotate()
                // straight after: rotate() throws exactly this ApiException, not
                // ObjectOptimisticLockingFailureException. Verified separately from this race.
                //
                // Narrowed to this specific error code rather than any ApiException: rotate()
                // can also throw AUTH_TOKEN_EXPIRED or the idle/absolute-session codes for
                // reasons that have nothing to do with this race, and letting those masquerade
                // as "the bulk revoke won" would hide a genuinely different bug instead of
                // reporting it.
                if (e.getCode() != ErrorCode.AUTH_SESSION_REVOKED) {
                    throw e;
                }
                rotateFoundAlreadyRevoked.incrementAndGet();
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

        // Exactly one of the three outcomes below must have happened -- @Version (or, for the
        // fully-serialized ordering, rotate()'s own reuse-detection) makes the loser throw
        // instead of silently racing, regardless of which side loses or how.
        assertThat(rotateSucceeded.get() + rotateConflicted.get() + rotateFoundAlreadyRevoked.get())
                .as("rotate() must always resolve to a definite outcome")
                .isEqualTo(1);
        if (rotateFoundAlreadyRevoked.get() == 1) {
            // The bulk revoke fully committed before rotate() ever read: rotate()'s reuse-
            // detection response (revokeAllForUser) sweeps EVERY active session for the user,
            // including currentDevice's -- a stronger outcome than the "other sessions only"
            // the bulk revoke itself asked for, not a weaker one. No session survives live.
            assertThat(revokeConflicted.get())
                    .as("the bulk revoke fully committed before rotate() read at all -- it cannot "
                            + "also have hit a conflict")
                    .isZero();
            assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId()))
                    .as("rotate()'s reuse-detection response must have swept every session for "
                            + "this user, including the one the bulk revoke itself spared")
                    .isEmpty();
        } else if (rotateSucceeded.get() == 1) {
            List<UUID> liveSessionIds = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId())
                    .stream().map(RefreshToken::getSessionId).toList();
            if (revokeConflicted.get() == 1) {
                // Genuine overlap on the SAME row rotate() touched: the bulk revoke correctly
                // lost and reported a conflict instead of silently overwriting it, so rotate()'s
                // own result stands and the session it just rotated is still live.
                assertThat(liveSessionIds)
                        .as("rotate() won a genuine row conflict -- the session it just rotated "
                                + "must genuinely still be live, not silently revoked underneath "
                                + "it by the losing bulk-revoke writer")
                        .contains(otherDevice.sessionId());
            } else {
                // No conflict is ALSO a valid outcome: rotate() can complete and commit in full
                // -- including issue()'s insert of device A's new token row, carrying the same
                // sessionId -- before the bulk revoke's own read ever runs. The bulk revoke then
                // legitimately revokes the freshly-rotated row instead of the pre-rotation one;
                // that is not data loss, the session ends up revoked either way. See this test's
                // own doc comment (2026-09-15 correction) for how this was verified
                // deterministically, separately from this race.
                assertThat(liveSessionIds)
                        .as("no conflict was reported, so the bulk revoke must have actually "
                                + "revoked the (possibly just-rotated) row for this session -- "
                                + "the one outcome that is never acceptable is silence: no "
                                + "conflict AND the session still surviving as live")
                        .doesNotContain(otherDevice.sessionId());
            }
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
