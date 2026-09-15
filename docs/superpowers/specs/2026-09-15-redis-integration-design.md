# Redis integration — design

Status: implemented.

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

### Timeouts — what actually makes "fail open" fast rather than theoretical

None of the above is true without an explicit, short timeout on every Redis call. A *refused*
connection (Redis process down, port closed) fails fast on its own — but a network *partition*
(packets silently dropped, no RST) leaves a TCP `connect()` hanging until the OS-level connect
timeout, commonly 20–75 seconds depending on platform, with nothing in Spring Boot's defaults
forcing it shorter. Left unconfigured, that means every `allow()`/`runGated()`/`@Cacheable` call
**blocks the calling Tomcat request thread** for that whole window during a partition — not "fails
open," but reproduces exactly the failure mode `ImportConcurrencyLimiter`'s own BH-043 history
already describes (parked request threads degrading every unrelated endpoint sharing the pool),
just relocated from the DB connection pool to Redis. This is the one setting in this whole design
that the fail-open property structurally depends on, so it's specified explicitly rather than left
to a framework default:

- `spring.data.redis.timeout: 200ms` — the command timeout Lettuce enforces once a connection is
  established. 200ms is generous for an intra-region call to a managed Redis instance under normal
  conditions, and short enough that a stuck call fails into the catch block well before it could
  meaningfully contribute to thread-pool exhaustion.
- `spring.data.redis.lettuce.connect-timeout: 200ms` (or the equivalent Lettuce `ClientOptions`
  connect-timeout) — governs the TCP handshake itself, which is the specific step that would
  otherwise hang for tens of seconds under a partition.
- Both values apply uniformly to all three components — there's no reason for the rate limiter,
  cache, and import limiter to disagree on how long "unreachable" is allowed to take to detect.

`ProductionConfigValidator` does **not** hard-fail boot on a missing/unreachable Redis — that would
defeat the point above. It logs a prominent boot-time warning instead, and see "Ongoing health
visibility" below for why a one-time boot log isn't the whole story.

### Shared-fate risk, named explicitly

Before this change, a problem in one of the three in-process mechanisms — say, an unexpectedly
large Caffeine cache — couldn't affect the other two; each lived in its own isolated JVM data
structure. After this change, all three depend on one Redis instance, and Redis executes commands
on a single thread. A slow or misbehaving command from *any* of the three (an accidentally-large
scan, a saturated instance, a noisy neighbor if the instance is ever shared with something else)
can now add latency to the *other two* as well, correlated in a way that wasn't previously
possible. This isn't a flaw to fix — the 200ms timeouts above are the direct mitigation, bounding
how much any one subsystem's Redis trouble can cost the others — but it's a real trade this design
makes, worth stating plainly rather than discovering during an incident, matching this codebase's
own habit of naming residual risk explicitly (see `SubscriptionCancellationDispatchSweepService`'s
"Residual risk, accepted explicitly" section for the same pattern applied elsewhere).

### Ongoing health visibility, not just a boot-time log

Because a missing/unreachable Redis never fails boot, a wrong `REDIS_URL` (the exact naming
convention Railway's addon uses is not yet confirmed — see Infrastructure below) would otherwise
leave every component permanently running in degraded, pre-Redis mode with no signal beyond one
log line at startup that can easily go unnoticed. This codebase already has a surface built for
exactly this class of problem: `AdminDiagnosticsService`/`AdminHealthRegistryService` already
report whether optional infrastructure — specifically, whether a `CacheManager` bean exists — is
actually wired up. Redis reachability should report into that same existing admin-visible surface
as a live, continuously-checked status, not a one-shot log line, so a misconfiguration is something
an operator can actually discover rather than something only a log-scraper would ever catch.

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
concurrent-callers-block-behind-the-first-load guarantee Caffeine's `sync=true` gives today.
**Verified, not assumed**: this lock is implemented via Redis's own `SETNX` command — a genuine
server-side, Redis-visible lock key, not a client-local mutex. That means a standard single-JVM
multithreaded test is actually sufficient to prove this behavior works; the lock key lives in
Redis regardless of how many separate connections or JVMs are contending for it, so a test
exercising N threads against one Lettuce connection factory contends on the exact same Redis-side
key a genuinely separate replica would.

New `CacheErrorHandler` bean (`CachingConfigurer` or a plain `@Bean`) catching get/put/evict
failures from the Redis connection, logging once at warn (rate-limited, not per-call — the same
log-flood concern the rate limiter's own fail-open path has), and treating a failed `get` as a
cache miss.

**The log-rate-limiting itself must be a plain, purely local, in-JVM guard** (e.g. a single
`AtomicLong` timestamp check, "log at most once per N seconds") — never anything backed by Redis,
including this design's own new Redis-backed rate limiter. Using Redis to throttle a log message
*about Redis being unreachable* is a circular dependency: the one moment this throttle needs to
work is exactly the moment the thing it might otherwise depend on is down. Applies identically to
the rate limiter's own fail-open warning below.

## Infrastructure

- `pom.xml`: add `spring-boot-starter-data-redis` (Lettuce client, Spring Boot's default), plus
  `spring.data.redis.timeout`/`spring.data.redis.lettuce.connect-timeout` at 200ms each (see
  "Timeouts" above — not left to framework defaults).
- `application.yml`: `spring.data.redis.url: ${REDIS_URL:}` — Railway's Redis addon convention.
  **Confirmed provisioned**: a `Redis` service is already live in the `Production` environment on
  Railway (`redis-volume` persistent volume attached, `Online`), alongside the existing Postgres
  instance and `api.fynora.net`. The exact connection variable name(s) this specific service
  exposes still need confirming against its own Variables tab before this line is final — Railway's
  Redis plugin typically exposes `REDIS_URL` plus individual `REDISHOST`/`REDISPORT`/
  `REDISPASSWORD` fields, but that's asserted from general Railway conventions, not yet checked
  against this actual instance's Variables tab, and shouldn't be treated as confirmed until it is.
- `docker-compose.yml`: **`redis:7.4.2-alpine`** (exact tag, not a bare major version or `latest`)
  alongside the existing `postgres` one, for local dev parity. Pinned deliberately: local dev
  currently has a Homebrew-installed Redis 8.10.1 (with RedisJSON/RediSearch/RedisTimeSeries
  bundled) reachable directly at `127.0.0.1:6379`, which is a *different major version* than what
  compose/CI would otherwise run — every command this design uses (`ZADD`, `ZREM`, `ZCARD`,
  `EXPIRE`, Lua `EVAL`) is stable across 7 and 8, so this isn't expected to produce a behavioral
  difference, but pinning one exact version everywhere (compose, Testcontainer below, and the
  eventual production target) avoids the "works on my machine, works in CI, unknown in prod"
  drift this codebase pins hard against everywhere else.
- `AbstractIntegrationTest`: gains a Redis Testcontainer at the same pinned `7.4.2-alpine` tag,
  same singleton-container-per-JVM pattern already used for Postgres (see that class's own doc
  comment on why: real infrastructure catches bugs a mock can't, and a container-per-test-class
  pattern already burned this codebase once via Spring's context cache holding a stale port).
  **This container is never killed or restarted by any test** — doing so would reproduce that
  exact stale-reference incident for every test class that runs afterward in the same JVM, since
  they all share this one container via `@DynamicPropertySource`. Simulating a Redis outage (for
  the fail-open/fallback tests below) goes through Toxiproxy instead — a proxy sitting between the
  app and the still-alive shared container, which Testcontainers has native support for
  (`ToxiproxyContainer`) and which can be told to cut or degrade the connection on command without
  touching the underlying Redis container's lifecycle at all.

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
  - Simulating a Redis restart via Toxiproxy (cut the proxy connection, then restore it) rather
    than killing the shared Testcontainer itself — see Infrastructure above for why the container
    must stay alive for the rest of the suite. With leases held at cut time: reconnection succeeds
    normally, and a release of a lease id acquired before the cut is a no-op if Redis genuinely
    restarted underneath (data lost) or a normal successful `ZREM` if the underlying instance
    itself never actually restarted (only the network path was interrupted) — both are safe;
    neither errors.
  - A release call for a lease id that was never acquired (simulating a partition-induced
    duplicate/out-of-order call) — `ZREM` on a missing member — asserted to leave `ZCARD` unchanged
    and never negative.
  - `N` concurrent acquire+release cycles well past `maxConcurrent`, asserted to never let `ZCARD`
    exceed `maxConcurrent` at any sampled point and to return to 0 once all releases complete.
  - A lease released *after* its own safety TTL has already caused it to be pruned by another
    acquire — asserted to not affect any lease acquired after the prune.
- Import-limiter fallback correctness specifically, via Toxiproxy (same mechanism as the restart
  test above — cutting the proxy connection, not the container): assert the local `Semaphore` path
  takes over at the *same* `maxConcurrent` ceiling as the Redis path (not fail-open); assert a
  permit acquired via the Redis lease releases via the Redis path and a permit acquired via the
  local fallback releases via the local semaphore, never crossed — specifically by cutting the
  proxy connection *between* one import's acquire and its release, and confirming that import's
  own release still finds its way back to whichever mechanism actually granted it.
- Rate limiter / cache fail-open behavior, via the same Toxiproxy cut: assert allow / cache-miss
  respectively, rather than a thrown exception.
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
