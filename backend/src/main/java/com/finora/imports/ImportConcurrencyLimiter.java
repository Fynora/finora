package com.finora.imports;

import com.finora.config.RedisFailureLogThrottle;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Bounds how many statement imports (CSV or PDF) can actually be mid-parse at the same time.
 *
 * The problem this solves: ImportController's stage()/stagePdf() run the entire parse --
 * PDFBox text extraction, table detection, transaction normalization, categorization, duplicate
 * detection -- synchronously on whatever Tomcat request thread handled the upload, with no cap
 * at all on how many could run at once. A burst of simultaneous uploads (the brief's own example:
 * 10,000 users importing at the same moment) would let Tomcat spin up threads for all of them
 * (up to its own pool limit) and let every one of those threads simultaneously: hold a
 * multi-megabyte file in memory for PDFBox to chew on, AND compete for one of only
 * DB_POOL_MAX_SIZE=10 database connections (see application.yml) for the account/category/
 * duplicate-detection queries the pipeline needs. Past a fairly small number of genuinely
 * concurrent imports, that's a realistic path to the JVM actually running out of memory (not a
 * graceful failure -- the whole app going down, every user's request included, not just the
 * import ones) well before it's a path to correct results for 10,000 people at once.
 *
 * What this does about it: a bounded permit pool sized well under the DB connection pool (so
 * import processing alone can never starve every other endpoint of a connection).
 *
 * BH-043: this used to acquire that permit with a blocking, fair (FIFO) wait of up to
 * ACQUIRE_TIMEOUT before giving up -- a genuine in-process queue. The bug: that wait ran on
 * whatever Tomcat request thread handled the upload. With max-concurrent=6 and Tomcat's default
 * 200-thread pool, a burst of uploads could leave close to 200 request threads parked for up to
 * the full timeout each -- and those are the SAME threads that serve every other endpoint in the
 * app (login, dashboard, ledger), so an import burst degraded totally unrelated functionality.
 * That's exactly the failure mode this class exists to prevent, just relocated from the DB
 * connection pool to the Tomcat thread pool instead of actually being avoided.
 *
 * So this now does an instant, non-blocking check instead of a blocking wait: if a permit is
 * free, take it and run immediately; if not, reject immediately rather than parking the calling
 * thread at all. No thread is ever held waiting on this semaphore -- the same "reject fast"
 * pattern RateLimiter/RateLimitFilter already use elsewhere in this codebase for the same reason.
 * A rejected request gets a clear, immediate "try again shortly" response -- an ApiException
 * with ErrorCode.IMPORT_SYSTEM_BUSY (HTTP 503), handled by GlobalExceptionHandler the same as
 * every other ApiException in the app.
 *
 * This is a single-instance, in-process gate, not a distributed rate limiter -- the right scope
 * for what this actually needs to solve on a single Railway instance, rather than reaching for
 * Redis/RabbitMQ/Kafka to solve a problem a language-level semaphore already solves correctly. If
 * genuinely horizontal scaling (multiple backend instances) is ever needed, this in-process gate
 * stops being sufficient on its own (each instance would enforce its own limit independently) --
 * that's the point at which an external mechanism would earn its complexity, not before.
 */
@Component
public class ImportConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImportConcurrencyLimiter.class);

    // Scores are seconds on the Redis clock with the microseconds kept. They used to be TIME's
    // whole seconds alone, and pruning "score <= now - ttl" on whole seconds removed a lease
    // anywhere from just over ttl - 1 to ttl seconds after it was granted (measured with a 1s TTL:
    // two acquires 0.6 ms apart pruned each other whenever they straddled a second boundary).
    // Both numbers are built as exact decimal strings rather than Lua arithmetic, so no float
    // formatting decides the precision. The unit stays seconds so leases written by the previous
    // version during a deploy still compare correctly.
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local leaseId = ARGV[1]
            local safetyTtlSeconds = tonumber(ARGV[2])
            local maxConcurrent = tonumber(ARGV[3])

            local time = redis.call('TIME')
            local seconds = tonumber(time[1])
            local micros = tonumber(time[2])
            local now = string.format('%d.%06d', seconds, micros)
            local cutoff = string.format('%d.%06d', seconds - safetyTtlSeconds, micros)

            redis.call('ZADD', key, 'NX', now, leaseId)
            redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
            local count = redis.call('ZCARD', key)
            if count > maxConcurrent then
              redis.call('ZREM', key, leaseId)
              return nil
            end
            return leaseId
            """, String.class);

    private static final String LEASE_SET_KEY = "import:concurrency:active";

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long safetyTtlSeconds;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailureLogThrottle failureLog;

    // @Autowired is required now that a second (package-private, test-only) constructor exists:
    // Spring's implicit "use the only constructor" rule only applies when a class has exactly
    // one -- with two present it needs an explicit marker, or it falls back to looking for a
    // public no-arg constructor and fails with "No default constructor found" (confirmed via a
    // real failing ApplicationContext boot, not assumed).
    @org.springframework.beans.factory.annotation.Autowired
    public ImportConcurrencyLimiter(@Value("${app.import.max-concurrent:6}") int maxConcurrent,
                                     @Value("${app.import.concurrency-lease-ttl-seconds:300}") long safetyTtlSeconds,
                                     StringRedisTemplate redisTemplate) {
        // At least 1: with 0 the acquire script's own prune removes the lease it has just added,
        // so the count never includes it and the fleet-wide ceiling is never enforced.
        if (safetyTtlSeconds < 1) {
            throw new IllegalArgumentException(
                    "app.import.concurrency-lease-ttl-seconds must be at least 1, was " + safetyTtlSeconds);
        }
        // BH-043: fairness is deliberately NOT requested here (plain `new Semaphore(int)`, the
        // non-fair/default constructor). Fairness only ever mattered for ordering threads that
        // actually parked waiting on the semaphore -- and per Semaphore's own javadoc, the no-arg
        // tryAcquire() this class now uses always "barges" and grabs a free permit immediately
        // regardless of the fairness setting anyway, so a fair semaphore here would buy nothing
        // but its (real, if small) throughput cost.
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        this.safetyTtlSeconds = safetyTtlSeconds;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
        log.info("Import concurrency limiter initialized: max {} concurrent imports, rejects immediately with 'busy' once the limit is reached",
                maxConcurrent);
    }

    /** Attempts a Redis-backed lease. Returns the lease id if granted, null if the fleet-wide
     *  limit is already reached; throws a DataAccessException if Redis could not be reached. */
    String acquireRedisLease() {
        return acquireRedisLease(java.util.UUID.randomUUID().toString());
    }

    private String acquireRedisLease(String leaseId) {
        return redisTemplate.execute(ACQUIRE_SCRIPT, java.util.List.of(LEASE_SET_KEY),
                leaseId, String.valueOf(safetyTtlSeconds), String.valueOf(maxConcurrent));
    }

    void releaseRedisLease(String leaseId) {
        redisTemplate.opsForZSet().remove(LEASE_SET_KEY, leaseId);
    }

    /** Which mechanism ALSO granted this permit, on top of the local semaphore every acquire
     *  always takes first -- see acquirePermit()'s own doc for why the local semaphore is never
     *  optional. release() uses this to decide whether a Redis lease needs releasing too. */
    private sealed interface Permit {
        record RedisLease(String leaseId) implements Permit {}
        record LocalOnly() implements Permit {}
    }

    /**
     * Runs `work` immediately if a permit is currently available. Rejects immediately (no
     * blocking wait -- see class doc) with an ApiException carrying ErrorCode.IMPORT_SYSTEM_BUSY
     * once the limit is reached, whichever mechanism enforces it.
     */
    public <T> T runGated(Callable<T> work) throws Exception {
        Permit permit = acquirePermit();
        if (permit == null) {
            log.warn("Import request rejected -- no processing slot available ({}/{} slots in use)",
                    maxConcurrent - permits.availablePermits(), maxConcurrent);
            throw new ApiException(ErrorCode.IMPORT_SYSTEM_BUSY);
        }
        try {
            return work.call();
        } finally {
            releasePermit(permit);
        }
    }

    /**
     * The local semaphore is acquired FIRST, unconditionally, on every call -- not only as a
     * Redis-unreachable fallback. Earlier versions of this method tried the Redis lease first and
     * only touched the semaphore on a Redis exception, which left the two mechanisms as fully
     * independent pools: a transient timeout on ONE request (not a clean, whole-instance outage)
     * could grant it a permit from an untouched local semaphore while every already-running
     * import still held its own Redis lease, letting this single instance exceed maxConcurrent by
     * as much as 2x during any partial Redis flap -- silently defeating the entire point of this
     * limiter. Gating on the local semaphore first makes it the instance's own hard, always-
     * enforced ceiling regardless of Redis's state; the Redis lease is then layered on top,
     * additionally enforcing the TRUE fleet-wide ceiling across every replica whenever Redis is
     * reachable. Redis being reachable never loosens the local cap (the global ZSET count across
     * all replicas is still bounded by maxConcurrent), so this changes nothing about steady-state
     * behavior -- it only closes the gap during instability.
     */
    private Permit acquirePermit() {
        if (!permits.tryAcquire()) {
            return null;
        }
        String leaseId = java.util.UUID.randomUUID().toString();
        try {
            if (acquireRedisLease(leaseId) != null) {
                return new Permit.RedisLease(leaseId);
            }
            // Redis is reachable but the fleet-wide ceiling is already full -- give back the
            // local permit this attempt is not going to use after all.
            permits.release();
            return null;
        } catch (org.springframework.dao.DataAccessException e) {
            failureLog.warn("Redis unreachable for import concurrency limiter -- falling back to "
                    + "the local semaphore: {}", e.toString());
            discardLeaseOfFailedAcquire(leaseId);
            return new Permit.LocalOnly();
        } catch (RuntimeException e) {
            // Anything else is not a Redis outage to fall back from, so it propagates -- but the
            // local permit taken above must not go with it: nothing would ever release it, and
            // once maxConcurrent of them were lost every import would be refused until a restart.
            permits.release();
            throw e;
        }
    }

    /**
     * A timeout does not mean the script did not run. The command can reach Redis and execute,
     * with only its reply arriving after the client gave up (measured: a 1.5s reply delay against
     * a 1s timeout left the lease in the set, and the next import was refused with Redis healthy).
     * Nothing would release that lease, so it held a fleet-wide slot for the whole safety TTL
     * while this request ran on its local permit alone. Removing it by id is harmless if it was
     * never added. The removal goes out on the same connection after the script, so Redis applies
     * it after the script. If Redis really is down this fails too, at the cost of one more command
     * timeout on a path that has already waited one, and the safety TTL remains the backstop for
     * a lease that did get written.
     */
    private void discardLeaseOfFailedAcquire(String leaseId) {
        try {
            releaseRedisLease(leaseId);
        } catch (RuntimeException e) {
            failureLog.warn("Could not remove import concurrency lease {} after a failed acquire "
                    + "-- if it was written, it will self-heal via its safety TTL: {}", leaseId, e.toString());
        }
    }

    private void releasePermit(Permit permit) {
        // Always released: acquirePermit() above always takes the local permit first, whichever
        // branch it then goes on to return.
        permits.release();
        if (permit instanceof Permit.RedisLease redisLease) {
            try {
                releaseRedisLease(redisLease.leaseId());
            } catch (RuntimeException e) {
                // Any failure, not only a DataAccessException: this runs in runGated()'s finally,
                // so an exception here would replace the finished work's result with an error.
                failureLog.warn("Could not release an import concurrency lease "
                        + "{} -- it will self-heal via its safety TTL: {}", redisLease.leaseId(), e.toString());
            }
        }
    }
}
