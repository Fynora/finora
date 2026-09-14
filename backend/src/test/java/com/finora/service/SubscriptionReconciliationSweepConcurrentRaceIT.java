package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Pins the bug fixed alongside {@code SubscriptionCancellationDispatchSweepService}'s own
 * per-row-transaction fix (see that class's doc comment for the general reasoning). Before the fix,
 * {@code SubscriptionReconciliationSweepService.sweep()} ran its whole loop in one
 * {@code @Transactional} method, so a genuinely concurrent webhook save on ONE row's optimistic-lock
 * {@code @Version} column -- e.g. {@code RazorpayWebhookDispatcher.handleResumed}/{@code
 * handleCharged} racing this sweep -- threw {@code OptimisticLockingFailureException} uncaught,
 * rolling back the ENTIRE batch: every other legitimately-expired subscription in the same pass got
 * silently reverted too.
 *
 * <p>Simulates the race deterministically -- no {@code CyclicBarrier}, for the same reason {@code
 * MerchantConcurrentAliasRaceIT}'s own doc comment gives ("racing the race" instead of pinning it): a
 * {@code @MockitoSpyBean} hook pauses the sweep's per-row transaction right after it has loaded the
 * "raced" subscription (so its in-memory version is the OLD one) but before it saves. While paused,
 * the test bumps that row's {@code version} column directly via JDBC -- standing in for the
 * concurrent webhook's own commit, on a genuinely separate, already-committed connection -- so the
 * sweep's subsequent {@code save()} collides with a real stale-version {@code UPDATE} at the
 * database, not a simulation.
 */
class SubscriptionReconciliationSweepConcurrentRaceIT extends AbstractIntegrationTest {

    @Autowired private SubscriptionReconciliationSweepService sweepService;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private PlanRepository planRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean private SubscriptionRepository subscriptionRepository;

    private User createUser(String tag) {
        User user = new User();
        user.setEmail("reconcile-race-it-" + tag + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Reconciliation Race IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Subscription cancelledPastPeriodEnd(User user) {
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setStatus(Subscription.STATUS_CANCELLED);
        subscription.setAutoRenew(false);
        subscription.setRenewalDate(LocalDate.now().minusDays(1));
        return subscriptionRepository.save(subscription);
    }

    /**
     * Two rows are eligible this pass: {@code raced} and {@code normal}. The sweep is parked mid
     * per-row transaction on {@code raced} right after loading it; a JDBC bump of its {@code version}
     * column then stands in for a concurrent webhook committing a change to the same row before the
     * sweep's own transaction for it closes. That must only cost {@code raced} its downgrade this
     * pass -- {@code normal} must still be downgraded, and {@code sweep()} itself must not throw.
     */
    @Test
    void oneRowsConcurrentVersionConflictDoesNotRollBackTheRestOfTheBatch() throws Exception {
        Subscription raced = cancelledPastPeriodEnd(createUser("raced"));
        Subscription normal = cancelledPastPeriodEnd(createUser("normal"));

        CountDownLatch racedRowLoaded = new CountDownLatch(1);
        CountDownLatch releaseSweep = new CountDownLatch(1);
        AtomicReference<Throwable> sweepFailure = new AtomicReference<>();
        AtomicReference<Integer> sweepResult = new AtomicReference<>();

        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            // Real findById equivalent, issued directly through the EntityManager rather than via
            // invocation.callRealMethod() -- Mockito cannot call a "real" method through a spy of an
            // interface-backed Spring Data proxy (see MerchantConcurrentAliasRaceIT's own doc
            // comment for the same limitation). This runs on the sweep's own thread, inside its
            // already-open per-row transaction, so the loaded entity's version is genuinely the one
            // that transaction is holding in memory.
            Optional<Subscription> loaded = Optional.ofNullable(entityManager.find(Subscription.class, id));
            racedRowLoaded.countDown();
            assertThat(releaseSweep.await(30, TimeUnit.SECONDS))
                    .as("the sweep must actually be released, not time out")
                    .isTrue();
            return loaded;
        }).when(subscriptionRepository).findById(eq(raced.getId()));

        Thread sweepThread = new Thread(() -> {
            try {
                sweepResult.set(sweepService.sweep());
            } catch (Throwable t) {
                sweepFailure.set(t);
            }
        }, "reconciliation-sweep-race");
        sweepThread.start();

        assertThat(racedRowLoaded.await(30, TimeUnit.SECONDS))
                .as("the sweep must actually be parked mid-transaction on the raced row")
                .isTrue();

        // Stand-in for a genuinely concurrent webhook (RazorpayWebhookDispatcher.handleResumed /
        // handleCharged) committing a change to the SAME row while the sweep's own transaction for
        // it is still open -- a plain JDBC connection here, separate from the sweep thread's JPA
        // transaction, so this really does commit immediately and independently.
        int rowsTouched = jdbcTemplate.update("UPDATE subscriptions SET version = version + 1 WHERE id = ?",
                raced.getId());
        assertThat(rowsTouched).isEqualTo(1);

        releaseSweep.countDown();
        sweepThread.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(sweepThread.isAlive()).as("the sweep must have finished, not hung").isFalse();
        assertThat(sweepFailure.get())
                .as("sweep() itself must not throw -- the version conflict must be caught per-row")
                .isNull();

        assertThat(sweepResult.get())
                .as("only the OTHER (non-raced) row should have been downgraded this pass")
                .isEqualTo(1);

        Plan free = planRepository.findByCode("FREE").orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();

        Subscription normalReloaded = subscriptionRepository.findById(normal.getId()).orElseThrow();
        assertThat(normalReloaded.getPlanId())
                .as("the row NOT involved in the race must still have been downgraded")
                .isEqualTo(free.getId());
        assertThat(normalReloaded.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);

        Subscription racedReloaded = subscriptionRepository.findById(raced.getId()).orElseThrow();
        assertThat(racedReloaded.getPlanId())
                .as("the raced row must be left alone this pass -- not partially downgraded, not "
                        + "lost -- for the next scheduled sweep to retry")
                .isEqualTo(premium.getId());
        assertThat(racedReloaded.getStatus()).isEqualTo(Subscription.STATUS_CANCELLED);
    }
}
