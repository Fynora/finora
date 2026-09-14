# Redis integration — design

Status: proposed, not yet implemented.

## Problem

Three mechanisms in the backend are correct on one instance and silently degrade on more than
one, because each is implemented as in-process JVM state with no cross-replica coordination:

1. **`RateLimiter`** (`config/RateLimiter.java`) — a per-instance sliding-window log. Measured
   directly in `docs/investigations/performance/multi-replica-rate-limit-2026-08-16.md`: a second
   replica raised the effective login-rate ceiling from 10/60s to 18/60s in a 30-request burst.
2. **`ImportConcurrencyLimiter`** (`imports/ImportConcurrencyLimiter.java`) — a per-instance
   `Semaphore`. Same investigation: import concurrency exactly doubled (6 → 12) with a second
   replica, "each instance would enforce its own limit independently," per that class's own doc
   comment.
3. **`CacheConfig`**'s two Caffeine caches (`customBanks`, `featureFlags`) — per-instance, so a
   write on one replica evicts only that replica's copy; another replica keeps serving its own
   stale entry until its own TTL lapses (up to 10 minutes for `customBanks`).

`ADR-008` (`docs/architecture/adr/adr-008-caching-and-duplicate-upload-protection.md`) explicitly
chose in-process implementations for all three, on the stated condition: *"Redis is the correct
upgrade once a second instance exists, not before."* This spec is that upgrade, triggered by an
explicit decision to build ahead of the immediate need rather than wait for a live second-instance
incident.

## Scope

All three mechanisms move to Redis in one change. Each keeps its existing public interface
unchanged — no caller anywhere in the codebase needs to change:

- `RateLimiter.allow(String key): boolean`, constructed `(maxRequests, windowSeconds)` — 17 call
  sites in `RateLimitFilter` untouched.
- `ImportConcurrencyLimiter.runGated(Callable<T> work): T` — `ImportController`'s call site
  untouched.
- `CacheConfig`'s `CacheManager` bean — `BankManagementService`/`FeatureFlagService`'s existing
  `@Cacheable`/`@CacheEvict` annotations untouched; only the bean implementation changes.

Explicitly out of scope: a distributed lock/semaphore library (Redisson) or a rate-limiting
library (Bucket4j) — both would add a second dependency doing what a single Lua script and a
counter already do, for three call sites total. Also out of scope: moving anything else onto
Redis speculatively (sessions, pub/sub, job queue) — nothing else in the audit that produced this
spec found an in-process mechanism that doesn't already have a correct DB-level answer (the import
job queue's `FOR UPDATE SKIP LOCKED` claim, the webhook idempotency ledger's unique-key insert,
etc. — see the audit notes this spec is grounded in).

## Design principle: fail-open, Redis-optional at boot — except the import limiter

Every component checks Redis reachability per-operation and degrades rather than throwing, the
same `isConfigured()` graceful-degradation shape already established for every other external
dependency in this codebase (`SetuConsentGateway`, `RazorpayProperties`,
`PhoneVerificationProvider`). But they don't all degrade the same way, because they protect
different kinds of things:

- **Rate limiter**: Redis unreachable → **allow** the request (same posture `JwtAuthFilter`
  already documents for a malformed token — a defensive layer's own failure must not take down
  what it defends). Rate limiting is a *protective* control, not a *correctness* control — a
  wrongly-allowed request during a rare outage window costs nothing structural.
- **Cache**: Redis unreachable → treated as a cache miss (compute fresh), not an exception. Needs
  an explicit `CacheErrorHandler` bean — Spring's caching abstraction does not do this by default;
  a broken `CacheManager` otherwise turns every `@Cacheable` call into a thrown exception, strictly
  worse than having no cache.
- **Import concurrency limiter**: Redis unreachable → **falls back to the existing local
  `Semaphore`**, not fail-open. This one enforces a resource-protection boundary (memory, DB pool,
  OCR/LLM capacity), not merely a protective control, and fail-open here compounds with the rate
  limiter *also* failing open at the same moment — during a full Redis outage that would leave
  nothing at all bounding an import burst except raw Tomcat thread pool capacity. The local
  fallback instead guarantees the worst case during any Redis outage is never worse than this
  component's pre-Redis production behavior: bounded per replica, at the same `maxConcurrent`
  ceiling as today, using the exact `Semaphore` logic already running in production. Fleet-wide
  correctness genuinely is lost during the outage (each replica enforces independently again,
  `R × maxConcurrent` fleet-wide) — but that is a known, already-accepted gap this system has run
  with in production, not a new failure mode introduced by this migration.

This means the change can deploy safely **before** Redis is provisioned in production: with Redis
absent, every component behaves exactly as it does today (single-instance semantics, degraded but
never broken). Once Redis is wired up (`REDIS_URL` set), the same code becomes genuinely
distributed with no further deploy needed.

`ProductionConfigValidator` does **not** hard-fail boot on a missing/unreachable Redis — that would
defeat the point above. It logs a prominent boot-time warning instead.

## Component 1: Rate limiter

Sliding-window log, same algorithm `RateLimiter` already implements, moved into Redis via one
atomic Lua script per `allow()` call (a single `EVAL`, so no multi-step race between replicas or
even within one instance):

```
key = "ratelimit:{limiterName}:{clientKey}"
ZREMRANGEBYSCORE key -inf (now - windowSeconds)   -- drop expired entries
ZCARD key                                          -- count what's left
if count >= maxRequests:
    return 0   -- reject; nothing new to persist about a rejected request, same as today
ZADD key now <unique-member>                       -- record this request
EXPIRE key windowSeconds                           -- self-cleaning; no manual sweep needed
return 1   -- allow
```

`limiterName` is a new constructor parameter (login/register/refresh/forgotPassword/...,
one per existing `RateLimitFilter` field) so the 17 limiters can't collide on the same Redis
keyspace even when two different limiters see the same client key (e.g. the same IP hitting both
`/auth/login` and `/auth/register`).

Deleted entirely: `evictExpired`, `lastSweepEpochSeconds`, `trackedKeys()`, the whole
`ConcurrentHashMap`-based log and its manual sweep — `EXPIRE` replaces all of it.

`RateLimitFilter` gains a `StringRedisTemplate` (or equivalent Lettuce-backed client) dependency,
constructor-injected, passed to each `RateLimiter` instance it builds.

## Component 2: Import concurrency limiter

The one component where Redis doesn't get crash-recovery for free the way `Semaphore` does from
the JVM's own lifecycle (a crashed JVM releases every permit it held; nothing about Redis does
that automatically). An earlier draft of this design used a single `INCR`/`DECR` counter with an
`EXPIRE ... NX` safety net; that design had a real bug, found by tracing exactly what happens if
the shared TTL fires while a genuinely-still-running import still holds a slot — the counter
cannot tell "my slot expired because I leaked" from "my slot expired but I'm still legitimately
running," so a late `DECR` can either drive the counter negative or decrement a *different, newer*
batch's count, silently admitting more than `maxConcurrent` at once. A single counter with a
single shared TTL cannot distinguish generations of holders.

**Design: a lease set, not a counter** — the same sliding-window-log shape already used for the
rate limiter above, so this reuses one mental model rather than introducing a second:

```
key = "import:concurrency:active"

acquire (one atomic script):
    ZADD key NX <now> <leaseId>                        -- leaseId: a fresh UUID, unique per acquire
    ZREMRANGEBYSCORE key -inf (now - safetyTtlSeconds)  -- prune anything stale, every acquire
    count = ZCARD key
    if count > maxConcurrent:
        ZREM key <leaseId>
        reject (ErrorCode.IMPORT_SYSTEM_BUSY, unchanged)
    else:
        proceed, holding <leaseId>

release (in a finally block, mirrors permits.release() today):
    ZREM key <leaseId>
```

`safetyTtlSeconds` is its own value, not borrowed from `ImportJobStore.IN_FLIGHT_TIMEOUT` — that
30-minute figure is justified specifically for the *async job queue* ("recovering early re-runs a
real import, which risked double-importing data before Phase 2's idempotency constraints existed;
recovering late just costs a delay"). This gate protects something different: a synchronous
request thread doing the parse itself (`ImportController.stage()`/`stagePdf()`), which by
definition can't run anywhere near 30 minutes — nobody would tolerate that as an HTTP response
time, and `ImportConcurrencyLimiter`'s whole reason for existing is to keep that request-thread
work bounded. Pruning a lease "too early" here has a much smaller downside than in the async case:
no risk of double-importing anything (this isn't gating the import itself, only how many can run
at once), just a brief, bounded overshoot of `maxConcurrent` if a lease is pruned while its import
is still genuinely finishing. A short TTL is the better trade in both directions — faster self-heal
after a real crash, smaller overshoot window if pruned early. Proposed: `app.import.concurrency
-lease-ttl-seconds`, default **300** (5 minutes) — generous against any realistic synchronous parse
duration, an order of magnitude tighter than the async queue's own timeout because it's guarding a
fundamentally shorter-lived thing.

This shape gives structural guarantees the counter design didn't, rather than carefully-sequenced
ones that need re-verifying by hand:

- **No shared TTL to race on** — a leaked lease (crashed holder) is pruned individually by its own
  age on the next acquire; nothing resets or deletes any *other* lease when that happens.
- **Redis restart self-recovers** — the set is simply gone after restart; an in-flight import's
  `leaseId` no longer exists anywhere, and `ZREM` on a missing member is a harmless no-op (returns
  0, never errors). New acquires start from an empty set.
- **No decrement arithmetic exists**, so there is nothing to underflow — `ZCARD` is a set
  cardinality, and set cardinality cannot go negative, by construction, under any interleaving of
  concurrent acquires/releases/restarts/partitions.
- **A crashed import finishing after its lease was pruned cannot corrupt accounting** — its
  `ZREM <leaseId>` either removes its own now-irrelevant entry or no-ops if already pruned; it can
  never touch a different import's entry, because releases are keyed by unique lease id, never by
  position or count.

## Redis-unreachable fallback: the existing local `Semaphore`, not fail-open

Unlike the rate limiter and cache, a Redis outage here does **not** fail open. `runGated()` tries
the acquire script above first; on any Redis connection failure, it falls through to the exact
`Semaphore.tryAcquire()`/`release()` logic already in production today, at the same
`maxConcurrent` ceiling — reusing the tested class as-is rather than writing new fallback logic.

One implementation detail this requires precisely: **the acquire path records which mechanism
actually granted the permit** (Redis lease vs. local semaphore), captured once at acquire time,
and the `finally` block releases through that *same* mechanism — never "acquired via Redis lease,
released via local semaphore" or vice versa, even if Redis's reachability changes between the
acquire and release of one long-running import. Concretely: `runGated` returns/holds an internal
`Permit` (sealed: `RedisLease(leaseId)` or `LocalPermit`) from its acquire step, and the `finally`
block pattern-matches on which one it got.

The fleet-wide correctness this fallback gives up during an outage (`R × maxConcurrent` instead of
one global `maxConcurrent`) is the same gap this component's pre-Redis-migration production
behavior already had — not a new failure mode, and never worse than what has already been running.

## Component 3: Cache

`CacheConfig`'s `@Bean CacheManager cacheManager()` swaps `CaffeineCacheManager` for Spring Data
Redis's `RedisCacheManager`, same two cache names and TTLs (`customBanks` 10 min, `featureFlags`
60s) via `RedisCacheConfiguration.entryTtl(...)`. `BankManagementService`'s explicit
`@CacheEvict(cacheNames = CacheConfig.CUSTOM_BANKS_CACHE, key = "'all'")` and
`FeatureFlagService`'s explicit `cache.evict(flagKey)` inside `AfterCommit.run` need no changes —
both already go through the `CacheManager`/`Cache` abstraction, not Caffeine directly.

`sync = true` (both `@Cacheable` annotations already set this, per `CacheConfig`'s own doc comment
on why it's required) needs Spring Data Redis's locking cache writer
(`RedisCacheWriter.lockingRedisCacheWriter(...)`) to preserve the same atomic-per-key,
concurrent-callers-block-behind-the-first-load guarantee Caffeine's `sync=true` gives today. Exact
API shape against the pinned Spring Data Redis version gets confirmed during implementation, not
asserted here.

New `CacheErrorHandler` bean (`CachingConfigurer` or a plain `@Bean`) catching get/put/evict
failures from the Redis connection, logging once at warn (rate-limited, not per-call — the same
log-flood concern the rate limiter's own fail-open path has), and treating a failed `get` as a
cache miss.

## Infrastructure

- `pom.xml`: add `spring-boot-starter-data-redis` (Lettuce client, Spring Boot's default).
- `application.yml`: `spring.data.redis.url: ${REDIS_URL:}` — Railway's Redis addon convention.
  Host/port/password as a documented fallback shape if that assumption turns out wrong once a real
  instance is provisioned (not yet confirmed against an actual Railway Redis addon).
- `docker-compose.yml`: `redis:7-alpine` service alongside the existing `postgres` one, for local
  dev parity. (Local development also has a real Homebrew-installed Redis 8.10.1 available
  directly, confirmed reachable at `127.0.0.1:6379` — either works for dev; compose is for
  consistency across machines and CI.)
- `AbstractIntegrationTest`: gains a Redis Testcontainer, same singleton-container-per-JVM pattern
  already used for Postgres (see that class's own doc comment on why: real infrastructure catches
  bugs a mock can't, and a container-per-test-class pattern already burned this codebase once via
  Spring's context cache holding a stale port).

## Testing

- Lua-script/lease-set logic: tested against the Testcontainer Redis, not mocked — same reasoning
  as every other correctness-critical concurrency mechanism in this codebase
  (`ImportSessionRepository.claimForConfirmation`, `AccountAggregatorLinkRepository
  .claimStatusTransition`) got a real-Postgres IT test rather than a mock-based one, because
  atomicity is exactly the property a mock cannot prove.
- A genuine multi-thread race test for the import lease set (N threads racing acquire on one key,
  asserting the limiter never admits more than `maxConcurrent` concurrently, and that `ZCARD`
  matches the actual number of still-held leases at every point) — same shape as
  `AccountAggregatorLinkRepositoryIT`'s 12-thread claim race from this session's earlier work.
- Sliding-window correctness under real concurrent requests for the rate limiter (burst just under
  the limit succeeds, burst over it rejects the excess, window boundary doesn't double-count).
- The five failure modes raised in review, each as its own test against the real Testcontainer
  Redis (not simulated in a mock):
  - A lease whose holder never releases (simulating a crash) is pruned by a *later* acquire's own
    `ZREMRANGEBYSCORE`, without disturbing any other still-live lease's membership.
  - Killing and restarting the Testcontainer mid-test, with leases held at kill time: post-restart
    acquires succeed normally, and a post-restart release of a pre-restart lease id is a no-op, not
    an error.
  - A release call for a lease id that was never acquired (simulating a partition-induced
    duplicate/out-of-order call) — `ZREM` on a missing member — asserted to leave `ZCARD` unchanged
    and never negative.
  - `N` concurrent acquire+release cycles well past `maxConcurrent`, asserted to never let `ZCARD`
    exceed `maxConcurrent` at any sampled point and to return to 0 once all releases complete.
  - A lease released *after* its own safety TTL has already caused it to be pruned by another
    acquire — asserted to not affect any lease acquired after the prune.
- Import-limiter fallback correctness specifically: point the client at an unreachable Redis and
  assert the local `Semaphore` path takes over at the *same* `maxConcurrent` ceiling as the Redis
  path (not fail-open); assert a permit acquired via the Redis lease releases via the Redis path
  and a permit acquired via the local fallback releases via the local semaphore, never crossed.
- Rate limiter / cache fail-open behavior: point the client at an unreachable Redis and assert
  allow / cache-miss respectively, rather than a thrown exception.
- Cache `sync = true` stampede behavior: concurrent callers missing the same key exercise the
  underlying load exactly once.

## Non-goals / explicitly deferred

- No change to the webhook idempotency ledger, the import job queue's `SKIP LOCKED` claim, or any
  other already-DB-backed-and-proven-correct mechanism found during the audit — those don't need
  Redis and moving them would be a regression in simplicity for no correctness gain.
- No Redis-backed session store — access tokens are already stateless JWTs and refresh-token
  session state is already correctly DB-backed (`refresh_tokens` table); nothing here needs Redis.
- No pub/sub cross-replica cache invalidation (i.e. evicting *other* replicas' Caffeine caches
  directly) — moot once the cache itself lives in Redis rather than in each replica's JVM.
