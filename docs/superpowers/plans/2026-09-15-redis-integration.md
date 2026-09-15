# Redis Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `RateLimiter`, `ImportConcurrencyLimiter`, and `CacheConfig`'s Caffeine caches from in-process JVM state to Redis, so all three are correct across multiple backend replicas, while degrading safely (fail-open, or a local fallback for the import limiter) if Redis itself is unreachable.

**Architecture:** Three independent Redis-backed mechanisms sharing one dependency, one timeout policy, and one local log-throttle utility. Rate limiter and import limiter use atomic Lua scripts operating on Redis sorted sets (sliding-window log / lease set) with a server-authoritative clock (`redis.call('TIME')`, not each replica's own clock). Cache swaps `CaffeineCacheManager` for `RedisCacheManager`. Every component fails safely on a Redis outage — rate limiter and cache fail open, the import limiter falls back to its existing local `Semaphore`.

**Tech Stack:** Spring Boot 3.5.16, Spring Data Redis (Lettuce), Java text-block Lua scripts via `DefaultRedisScript`, Testcontainers (`GenericContainer` for Redis, `ToxiproxyContainer` for fault injection), JUnit 5, AssertJ, Mockito.

**Spec:** [docs/superpowers/specs/2026-09-15-redis-integration-design.md](../specs/2026-09-15-redis-integration-design.md)

## Global Constraints

- Every public interface named in the spec's Scope section stays byte-for-byte unchanged: `RateLimiter.allow(String key): boolean`, `ImportConcurrencyLimiter.runGated(Callable<T> work): T`, `CacheConfig`'s `CacheManager` bean name/type. No caller anywhere else in the codebase changes.
- `spring.data.redis.timeout` and `spring.data.redis.connect-timeout` are both `200ms` — set explicitly, never left to a framework default (spec's "Timeouts" section; this is the property this whole design's fail-open guarantee depends on).
- Every Redis-unreachable code path must catch `org.springframework.dao.DataAccessException` (the common superclass covering both `RedisConnectionFailureException` and `QueryTimeoutException`), never a narrower type — see Finding II-1 in the spec's review history.
- Every "Redis is unreachable" warning log goes through the shared `RedisFailureLogThrottle` (Task 4) — never anything Redis-backed, including this design's own new rate limiter (circular dependency).
- Redis version pinned to `7.4.2-alpine` everywhere it's specified in this plan (`docker-compose.yml`, Testcontainer). Local dev may also use the already-running Homebrew Redis 8.10.1 directly; both are fine for local use, only the pinned version is authoritative for CI/reproducibility.
- Real Redis in every test that touches Redis-backed logic — Testcontainers, never a mock, for the same reason this codebase already tests `ImportSessionRepository.claimForConfirmation` and `AccountAggregatorLinkRepository.claimStatusTransition` against real Postgres: atomicity is exactly the property a mock cannot prove.

---

## Task 1: Redis dependency, timeouts, and local infrastructure

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces: a `StringRedisTemplate` bean, auto-configured by Spring Boot once `spring-boot-starter-data-redis` and `spring.data.redis.*` config are present — every later task consumes this bean by constructor injection, never constructs its own.

- [ ] **Step 1: Add the Redis dependency to `pom.xml`**

Find the existing Caffeine caching dependency block (search for `<!-- Caching.`) and add the Redis starter directly after it:

```xml
    <!-- Redis. See docs/superpowers/specs/2026-09-15-redis-integration-design.md for what moved
         here from Caffeine/in-process state and why (ADR-008's own stated trigger: "once a
         second instance exists"). Lettuce (Spring Boot's default client) ships with this
         starter -- no separate Jedis/Lettuce dependency needed. -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `cd backend && mvn -q -o compile`
Expected: builds clean, no errors. (If `-o` fails because the dependency isn't in the local repo yet, drop `-o` for this one run only: `mvn -q compile`.)

- [ ] **Step 3: Add Redis connection and timeout config to `application.yml`**

Find the `spring:` block (starts with `spring:` at the top level) and add a `data.redis` section as a sibling of `application`/`profiles`/`lifecycle`:

```yaml
  data:
    redis:
      # Railway's Redis addon convention, confirmed live in Production (see the design spec's
      # "Infrastructure" section) -- exact variable name still needs confirming against that
      # service's own Variables tab before this is production-final; ${REDIS_URL:} means "empty
      # if unset", which is what makes local dev without any Redis at all still boot cleanly
      # (every component fails open/falls back -- see RedisFailureLogThrottle and each
      # component's own doc comment).
      url: ${REDIS_URL:}
      # Both 200ms, deliberately -- see this plan's Global Constraints and the design spec's
      # "Timeouts" section. A REFUSED connection fails fast on its own; a PARTITION does not,
      # and these two settings are what stop that from hanging a request thread for the OS-level
      # TCP connect timeout (20-75s) instead of failing open in milliseconds.
      timeout: 200ms
      connect-timeout: 200ms
```

- [ ] **Step 4: Add the `redis` service to `docker-compose.yml`**

Add a new service alongside the existing `postgres` one (before the `backend` service):

```yaml
  redis:
    image: redis:7.4.2-alpine
    ports:
      # 127.0.0.1-only, same reasoning as postgres above: the backend container reaches this
      # over the compose network by service name, so this mapping exists purely for a host-side
      # redis-cli/RedisInsight to connect, and nothing on the LAN needs it exposed.
      - "127.0.0.1:6379:6379"
    volumes:
      - finora_redisdata:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5
```

Add `finora_redisdata:` under the existing `volumes:` block at the bottom (alongside `finora_pgdata:`).

Add `REDIS_URL: redis://redis:6379` to the `backend` service's `environment:` block, and add a `redis: condition: service_healthy` entry alongside the existing `postgres: condition: service_healthy` under `backend`'s `depends_on:`.

- [ ] **Step 5: Verify against the real local Redis**

Homebrew Redis is already running locally (confirmed reachable at `127.0.0.1:6379` earlier this session). Run the backend directly against it:

```bash
cd backend && REDIS_URL=redis://127.0.0.1:6379 mvn -q -o spring-boot:run
```

In another terminal: `curl -s http://localhost:8080/actuator/health | python3 -m json.tool`
Expected: the JSON includes a `"redis"` entry under `components` with `"status": "UP"` — Spring Boot Actuator auto-configures this once the dependency and connection are both present, with no code written yet. This is also the first real confirmation of the exact component key name Task 3's `RedisHealthProvider` will read (`"redis"`, not something else) — note the actual key observed here for Task 3.

Stop the app (Ctrl+C) once confirmed.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml docker-compose.yml
git commit -m "feat(backend): add Redis dependency, timeouts, and local infrastructure"
```

---

## Task 2: Shared Redis Testcontainer and Toxiproxy in `AbstractIntegrationTest`

**Files:**
- Modify: `backend/src/test/java/com/finora/AbstractIntegrationTest.java`
- Test: `backend/src/test/java/com/finora/RedisTestcontainerSmokeIT.java` (new)
- Modify: `backend/pom.xml` (add `org.testcontainers:toxiproxy`, test scope)

**Interfaces:**
- Consumes: nothing new from earlier tasks.
- Produces: every `*IT` test in the suite gets a real, reachable Redis via Spring's auto-configured `StringRedisTemplate`/`RedisConnectionFactory` beans — routed through a Toxiproxy proxy so later tasks' fail-open/fallback tests can simulate an outage. `@Autowired StringRedisTemplate` is how every later IT test task reaches it; no `AbstractIntegrationTest` field is touched directly by subclasses, mirroring how `POSTGRES` already works for Postgres.

- [ ] **Step 1: Add the Toxiproxy Testcontainers module**

In `backend/pom.xml`, find the existing `testcontainers` dependencies (search for `org.testcontainers`) and add, in the same `<dependency>` block area, test scope:

```xml
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>toxiproxy</artifactId>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Write the failing smoke test**

```java
package com.finora;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the shared Redis Testcontainer + Toxiproxy wiring in AbstractIntegrationTest actually
 * works before any real component depends on it -- same role RedisTestcontainerSmokeIT's name
 * suggests: a fast, obvious failure here means "the infrastructure is broken," not "my feature
 * has a bug," which matters once six other IT classes are built on top of this.
 */
class RedisTestcontainerSmokeIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void theSharedRedisContainerIsReachableThroughTheToxiproxyRoute() {
        redisTemplate.opsForValue().set("smoke-test-key", "smoke-test-value");

        assertThat(redisTemplate.opsForValue().get("smoke-test-key")).isEqualTo("smoke-test-value");
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RedisTestcontainerSmokeIT`
Expected: FAIL — no `spring.data.redis.host`/`port` property is registered yet, so the app either can't connect or (more likely) connects to nothing at `${REDIS_URL:}` (empty) and the health/connection fails. If Spring auto-config falls back to `localhost:6379` and this accidentally passes against a stray local Redis, that's a false pass — explicitly confirm this before Step 4 by checking there's no `@DynamicPropertySource` in `AbstractIntegrationTest` yet pointing at a container.

- [ ] **Step 4: Add the Redis container, Toxiproxy, and the dynamic property wiring**

In `AbstractIntegrationTest.java`, add these imports:

```java
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
```

Add these static fields alongside `POSTGRES` (same visibility, same "never closed" `@SuppressWarnings("resource")` pattern):

```java
    // Same network for REDIS and TOXIPROXY -- Toxiproxy proxies to Redis by container network
    // alias, which only resolves if both containers share a Docker network. POSTGRES doesn't
    // need this: nothing proxies to it.
    static final Network NETWORK = Network.newNetwork();

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    @SuppressWarnings("resource")
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NETWORK);

    // Every IT test's Redis traffic routes through this proxy, always -- not just the specific
    // tests that inject a fault. There is no clean way to give some IT classes a direct
    // connection and others a proxied one within one shared, cached Spring context (the whole
    // point of the singleton-container pattern above), so routing everything through the proxy
    // uniformly is the only actually-workable option, not a compromise. setConnectionCut(false)
    // is the resting/healthy state; individual fault-injection tests toggle it and this class's
    // own @BeforeEach resets it, the same "shared state, reset before every test" discipline
    // emptyTheSharedWorkQueues() already applies to the work-queue tables below.
    static ToxiproxyContainer.ContainerProxy REDIS_PROXY;

    static {
        // (existing profile-guard block stays exactly as-is above this point)
        POSTGRES.start();
        REDIS.start();
        TOXIPROXY.start();
        REDIS_PROXY = TOXIPROXY.getProxy(REDIS, 6379);
    }
```

Add to the existing `@DynamicPropertySource static void registerPgProperties(...)` method (rename it or add a second `@DynamicPropertySource` method — either is fine; adding a second method is less disruptive to the existing method's name/history):

```java
    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS_PROXY::getContainerIpAddress);
        registry.add("spring.data.redis.port", REDIS_PROXY::getProxyPort);
        // url is unset (empty) in application.yml under the test profile's precedence, so host/
        // port here are what actually apply -- see application.yml's own ${REDIS_URL:} comment.
    }
```

Add a `@BeforeEach` resetting the proxy's health state, right alongside the existing `emptyTheSharedWorkQueues()`:

```java
    @BeforeEach
    void resetRedisProxy() {
        // A fault-injection test in one *IT class must never leave the connection cut for the
        // next class that runs in this same shared JVM -- @Isolated only guarantees no other
        // test runs CONCURRENTLY with this one, not that a PRIOR test class left things as it
        // found them. Same reasoning as emptyTheSharedWorkQueues() above, applied to the proxy's
        // health instead of the work-queue tables.
        REDIS_PROXY.setConnectionCut(false);
    }
```

- [ ] **Step 5: Run the smoke test again to verify it passes**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RedisTestcontainerSmokeIT`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/test/java/com/finora/AbstractIntegrationTest.java backend/src/test/java/com/finora/RedisTestcontainerSmokeIT.java
git commit -m "test(backend): add shared Redis Testcontainer and Toxiproxy fault injection to AbstractIntegrationTest"
```

---

## Task 3: Redis health visibility (`RedisHealthProvider`)

**Files:**
- Create: `backend/src/main/java/com/finora/health/RedisHealthProvider.java`
- Test: `backend/src/test/java/com/finora/health/RedisHealthProviderIT.java` (new)

**Interfaces:**
- Consumes: `org.springframework.boot.actuate.health.HealthEndpoint` (same bean `DatabaseHealthProvider` already consumes), constructor-injected.
- Produces: nothing consumed by later tasks — this is purely additive observability, picked up automatically by `AdminHealthRegistryService`'s existing `List<HealthProvider>` injection with zero changes to that class.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.health;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Testcontainer Redis, real Toxiproxy fault injection, real Actuator HealthEndpoint --
 * proves the "redis" component key assumption (flagged as unconfirmed in the design spec) is
 * actually correct, and proves the DOWN path works under a genuine simulated outage, not a mock.
 */
class RedisHealthProviderIT extends AbstractIntegrationTest {

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void check_reportsUp_whenRedisIsReachable() {
        RedisHealthProvider provider = new RedisHealthProvider(healthEndpoint);

        var result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDown_whenRedisIsUnreachable() {
        REDIS_PROXY.setConnectionCut(true);
        try {
            RedisHealthProvider provider = new RedisHealthProvider(healthEndpoint);

            var result = provider.check();

            assertThat(result.status()).isIn(HealthStatus.DOWN, HealthStatus.DEGRADED);
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RedisHealthProviderIT`
Expected: FAIL — `RedisHealthProvider` doesn't exist yet (compile error).

- [ ] **Step 3: Write `RedisHealthProvider`**

```java
package com.finora.health;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Wraps the same Actuator HealthEndpoint bean DatabaseHealthProvider already uses, reading the
 * "redis" component Spring Boot Actuator auto-configures once spring-boot-starter-data-redis and
 * a reachable connection are both present -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md's "Ongoing health visibility" section for why this
 * exists: the design deliberately never hard-fails boot on a missing/unreachable Redis, so a
 * wrong REDIS_URL would otherwise be silently invisible past one boot-time log line. Redis was
 * explicitly named in HealthProvider's own class doc as infrastructure this codebase didn't have
 * a provider for yet -- this is that provider, now that it exists.
 */
@Component
public class RedisHealthProvider implements HealthProvider {

    private final HealthEndpoint healthEndpoint;

    public RedisHealthProvider(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public String name() {
        return "Redis";
    }

    @Override
    public String category() {
        return "Platform";
    }

    @Override
    public HealthCheckResult check() {
        HealthComponent root = healthEndpoint.health();
        if (!(root instanceof CompositeHealth composite)) {
            return HealthCheckResult.degraded("Actuator health tree has no components to inspect");
        }
        HealthComponent redisComponent = composite.getComponents().get("redis");
        if (redisComponent == null) {
            return HealthCheckResult.degraded(
                    "No \"redis\" component in Actuator's health tree -- Redis may not be configured");
        }
        Status status = redisComponent.getStatus();
        String detail = "Actuator redis indicator: " + status;
        if (status == Status.UP) return HealthCheckResult.up(detail);
        if (status == Status.DOWN) return HealthCheckResult.down(detail);
        return HealthCheckResult.degraded(detail);
    }
}
```

- [ ] **Step 4: Run the test again to verify it passes**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RedisHealthProviderIT`
Expected: PASS. If the DOWN test instead reports DEGRADED because Actuator classifies a cut connection as `OUT_OF_SERVICE` rather than `DOWN`, that's fine — the test already accepts either, matching `DatabaseHealthProvider`'s own documented reasoning for not overstating an ambiguous Actuator status as a hard DOWN.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/health/RedisHealthProvider.java backend/src/test/java/com/finora/health/RedisHealthProviderIT.java
git commit -m "feat(backend): add Redis health visibility to the admin diagnostics surface"
```

---

## Task 4: `RedisFailureLogThrottle` — shared local-only log throttle

**Files:**
- Create: `backend/src/main/java/com/finora/config/RedisFailureLogThrottle.java`
- Test: `backend/src/test/java/com/finora/config/RedisFailureLogThrottleTest.java` (new)

**Interfaces:**
- Produces: `RedisFailureLogThrottle(Logger, long minIntervalMillis)` and `.warn(String message, Object... args)` — consumed by Tasks 5, 6/7, and 8, one instance per component, each with its own `Logger`.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.*;

class RedisFailureLogThrottleTest {

    @Test
    void warn_logsOnTheFirstCall() {
        Logger logger = mock(Logger.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("Redis unreachable: {}", "boom");

        verify(logger).warn("Redis unreachable: {}", "boom");
    }

    @Test
    void warn_suppressesASecondCallWithinTheThrottleWindow() {
        Logger logger = mock(Logger.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("first");
        throttle.warn("second");

        verify(logger, times(1)).warn(anyString());
    }

    @Test
    void warn_logsAgainOnceTheThrottleWindowHasPassed() {
        Logger logger = mock(Logger.class);
        Instant start = Instant.parse("2026-09-15T00:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(start);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        RedisFailureLogThrottle throttle = new RedisFailureLogThrottle(logger, 60_000, clock);

        throttle.warn("first");
        now.set(start.plusMillis(60_001));
        throttle.warn("second");

        verify(logger, times(2)).warn(anyString());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -o test -Dtest=RedisFailureLogThrottleTest`
Expected: FAIL — class doesn't exist (compile error).

- [ ] **Step 3: Write `RedisFailureLogThrottle`**

```java
package com.finora.config;

import org.slf4j.Logger;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A purely local, in-JVM "log at most once per N milliseconds" guard -- see
 * docs/superpowers/specs/2026-09-15-redis-integration-design.md's log-throttling note: this must
 * never be backed by Redis itself, including this design's own new Redis-backed rate limiter.
 * The one moment this throttle needs to work is exactly the moment the thing it might otherwise
 * depend on is down.
 *
 * <p>Same test-seam shape as {@link RateLimiter}'s own injectable {@code Clock} -- a real timer-
 * based test would either not run or not be a real test.
 */
public class RedisFailureLogThrottle {

    private final Logger log;
    private final long minIntervalMillis;
    private final Clock clock;
    private final AtomicLong lastLoggedAtMillis = new AtomicLong(Long.MIN_VALUE);

    public RedisFailureLogThrottle(Logger log, long minIntervalMillis) {
        this(log, minIntervalMillis, Clock.systemUTC());
    }

    /** Package-private test seam, same reasoning as {@link RateLimiter}'s equivalent constructor. */
    RedisFailureLogThrottle(Logger log, long minIntervalMillis, Clock clock) {
        this.log = log;
        this.minIntervalMillis = minIntervalMillis;
        this.clock = clock;
    }

    public void warn(String message, Object... args) {
        long now = clock.millis();
        long last = lastLoggedAtMillis.get();
        if (now - last >= minIntervalMillis && lastLoggedAtMillis.compareAndSet(last, now)) {
            log.warn(message, args);
        }
    }
}
```

- [ ] **Step 4: Run the tests again to verify they pass**

Run: `cd backend && mvn -q -o test -Dtest=RedisFailureLogThrottleTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/config/RedisFailureLogThrottle.java backend/src/test/java/com/finora/config/RedisFailureLogThrottleTest.java
git commit -m "feat(backend): add shared local-only log throttle for Redis-outage warnings"
```

---

## Task 5: Redis-backed `RateLimiter` and `RateLimitFilter` wiring

**Files:**
- Modify: `backend/src/main/java/com/finora/config/RateLimiter.java`
- Modify: `backend/src/main/java/com/finora/config/RateLimitFilter.java`
- Delete: `backend/src/test/java/com/finora/config/RateLimiterTest.java` (replaced — its whole premise is the deleted in-memory sweep/eviction machinery)
- Create: `backend/src/test/java/com/finora/config/RateLimiterIT.java`
- Modify: `backend/src/test/java/com/finora/config/RateLimitFilterTest.java` → rename to `RateLimitFilterIT.java`

**Interfaces:**
- Consumes: `RedisFailureLogThrottle` (Task 4), `StringRedisTemplate` (Task 1, Spring-provided).
- Produces: `RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate)` — the new constructor shape every one of `RateLimitFilter`'s 17 call sites uses. `allow(String key): boolean` is unchanged.

- [ ] **Step 1: Write the failing IT test for sliding-window correctness**

```java
package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void allow_permitsRequestsUpToTheLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(3, 60, "test-basic-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
    }

    @Test
    void allow_tracksDistinctKeysIndependently() {
        RateLimiter limiter = new RateLimiter(1, 60, "test-distinct-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-b")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
    }

    @Test
    void allow_resetsAfterTheWindowExpires() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 1, "test-window-" + System.nanoTime(), redisTemplate);

        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
        Thread.sleep(1_100);
        assertThat(limiter.allow("client-a")).isTrue();
    }

    @Test
    void allow_underConcurrentLoad_permitsExactlyMaxRequests() throws InterruptedException {
        int maxRequests = 10;
        RateLimiter limiter = new RateLimiter(maxRequests, 60, "test-concurrent-" + System.nanoTime(), redisTemplate);
        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    if (limiter.allow("shared-client")) allowedCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(allowedCount.get()).isEqualTo(maxRequests);
    }

    @Test
    void allow_twoLimitersWithDifferentNamesDoNotShareBuckets() {
        String sharedSuffix = "-" + System.nanoTime();
        RateLimiter login = new RateLimiter(1, 60, "login" + sharedSuffix, redisTemplate);
        RateLimiter register = new RateLimiter(1, 60, "register" + sharedSuffix, redisTemplate);

        assertThat(login.allow("same-ip")).isTrue();
        assertThat(register.allow("same-ip")).isTrue();
    }

    @Test
    void allow_failsOpenWhenRedisIsUnreachable() {
        // maxRequests=0 would reject every real request -- if this returns true anyway, that's
        // only explainable by the fail-open path, not by the limiter having room to spare.
        RateLimiter limiter = new RateLimiter(0, 60, "test-failopen-" + System.nanoTime(), redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            assertThat(limiter.allow("client-a")).isTrue();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RateLimiterIT`
Expected: FAIL — `RateLimiter`'s constructor doesn't accept `(int, long, String, StringRedisTemplate)` yet.

- [ ] **Step 3: Rewrite `RateLimiter`**

Replace the entire file:

```java
package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

/**
 * A sliding-window (log-based) rate limiter, Redis-backed -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md, Component 1. One atomic Lua script per allow() call
 * (a single EVAL) is what makes the trim/count/record sequence race-free across replicas and even
 * within one instance -- the exact multi-replica gap the in-process predecessor of this class had,
 * measured directly in docs/investigations/performance/multi-replica-rate-limit-2026-08-16.md.
 *
 * <p>The script asks Redis for its own clock (TIME) rather than trusting each application
 * replica's local clock -- a deliberate improvement over the design spec's own pseudocode,
 * closing a clock-skew-between-replicas class of bug for free, since Redis is the single shared
 * timing authority every replica already agrees on by construction.
 *
 * <p>Fails OPEN on any {@link DataAccessException} -- covers both a refused connection
 * ({@code RedisConnectionFailureException}) and a timed-out command ({@code
 * QueryTimeoutException}); catching only the narrower connection-failure type would leave this
 * broken under a network partition specifically, the one case the 200ms command/connect timeout
 * (application.yml) exists to catch fast. Rate limiting is a protective control, not a
 * correctness one -- a wrongly-allowed request during a rare outage costs nothing structural.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final DefaultRedisScript<Long> ALLOW_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local windowSeconds = tonumber(ARGV[1])
            local maxRequests = tonumber(ARGV[2])
            local member = ARGV[3]

            local time = redis.call('TIME')
            local now = tonumber(time[1])

            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowSeconds)
            local count = redis.call('ZCARD', key)
            if count >= maxRequests then
              return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('EXPIRE', key, windowSeconds)
            return 1
            """, Long.class);

    private final int maxRequests;
    private final long windowSeconds;
    private final String limiterName;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailureLogThrottle failureLog;

    public RateLimiter(int maxRequests, long windowSeconds, String limiterName, StringRedisTemplate redisTemplate) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.limiterName = limiterName;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit, and
     *  true (fail open) if Redis could not be reached within the configured timeout. */
    public boolean allow(String key) {
        String redisKey = "ratelimit:" + limiterName + ":" + key;
        try {
            Long result = redisTemplate.execute(ALLOW_SCRIPT, List.of(redisKey),
                    String.valueOf(windowSeconds), String.valueOf(maxRequests), UUID.randomUUID().toString());
            return result != null && result == 1L;
        } catch (DataAccessException e) {
            failureLog.warn("Redis unreachable for rate limiter '{}' -- failing open: {}", limiterName, e.toString());
            return true;
        }
    }
}
```

- [ ] **Step 4: Update `RateLimitFilter`'s two constructors**

`RateLimitFilter` has a convenience 3-arg constructor and a full `@Autowired` constructor with all the `@Value` parameters (both currently build 17 `new RateLimiter(max, window)` calls). Update both to accept a `StringRedisTemplate` and thread it — and each limiter's own name string — through every `new RateLimiter(...)` call:

- Add `StringRedisTemplate redisTemplate` as a new parameter to both constructors (last position in each parameter list).
- Change every `this.xLimiter = new RateLimiter(xMax, xWindow);` line to `this.xLimiter = new RateLimiter(xMax, xWindow, "x", redisTemplate);`, using a short, stable, lowercase-hyphenated name matching each field's own identity (e.g. `"login"`, `"register"`, `"forgot-password"`, `"identify"`, `"import-stage"`, `"password-change"`, `"phone-change"`, `"email-change"`, `"reset-password"`, `"data-export"`, `"delete-account"`, `"google"`, `"apple"`, `"mfa-verify"`, `"refresh"`, `"device-token-register"`, `"device-token-revoke"`, `"aa-link-initiate"`) — these names become part of the Redis keyspace (`ratelimit:{name}:{key}`), so keep them stable once chosen; changing one later effectively resets that limiter's live state.
- Update the convenience constructor's own delegating call (the one passing `DEFAULT_*` constants) to also pass `redisTemplate` through to the full constructor.

- [ ] **Step 5: Rewrite `RateLimitFilterTest` as `RateLimitFilterIT`**

Rename the file to `RateLimitFilterIT.java`, change the class to `extends AbstractIntegrationTest`, remove the class-level `class RateLimitFilterTest {` line and replace with `class RateLimitFilterIT extends AbstractIntegrationTest {`, add `import com.finora.AbstractIntegrationTest;` and `import org.springframework.beans.factory.annotation.Autowired;` and `import org.springframework.data.redis.core.StringRedisTemplate;`, add a field:

```java
    @Autowired
    private StringRedisTemplate redisTemplate;
```

Update `newFilter(boolean trustProxyHeaders)`'s final line from:
```java
        return new RateLimitFilter(objectMapper, clientIpResolver, testCorsConfigurationSource());
```
to:
```java
        return new RateLimitFilter(objectMapper, clientIpResolver, testCorsConfigurationSource(), redisTemplate);
```

Everything else in the file (the IP-resolution test logic, `tripsRateLimitAfterManyRequests`, etc.) needs no other changes — it was already exercising real rate-limiting behavior as an observable signal, and now does so against real Redis instead of in-process state.

- [ ] **Step 6: Delete the obsolete `RateLimiterTest.java`**

```bash
git rm backend/src/test/java/com/finora/config/RateLimiterTest.java
```

- [ ] **Step 7: Run the full set to verify everything passes**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=RateLimiterIT,RateLimitFilterIT`
Expected: PASS.

Run: `cd backend && mvn -q -o compile` (confirm no other caller of the old 2-arg `RateLimiter` constructor was missed)
Expected: clean compile.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/finora/config/RateLimiter.java backend/src/main/java/com/finora/config/RateLimitFilter.java backend/src/test/java/com/finora/config/RateLimiterIT.java backend/src/test/java/com/finora/config/RateLimitFilterIT.java
git rm --cached backend/src/test/java/com/finora/config/RateLimiterTest.java 2>/dev/null || true
git commit -m "feat(backend): move RateLimiter to Redis, fail open on outage"
```

---

## Task 6: Redis-backed `ImportConcurrencyLimiter` — lease-set acquire/release

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportConcurrencyLimiter.java`
- Test: `backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterIT.java` (new — the Redis-path correctness tests; Task 7 adds the fallback-specific tests to this same file)

This task builds only the Redis lease-set mechanism, proven correct on its own. Task 7 adds the local-`Semaphore` fallback on top of it.

**Interfaces:**
- Consumes: `RedisFailureLogThrottle` (Task 4), `StringRedisTemplate` (Task 1).
- Produces (this task): a private `acquireRedisLease(): String` (returns the lease id, or `null` on rejection) and `releaseRedisLease(String leaseId)` — internal to `ImportConcurrencyLimiter`, not yet wired into `runGated()`'s public behavior differently from today (that wiring, plus the fallback, is Task 7). This task's own test drives these two methods directly via package-private visibility.

- [ ] **Step 1: Write the failing IT tests for the lease-set mechanism**

```java
package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ImportConcurrencyLimiterIT extends AbstractIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private ImportConcurrencyLimiter newLimiter(int maxConcurrent) {
        return new ImportConcurrencyLimiter(maxConcurrent, 300, redisTemplate);
    }

    @Test
    void acquireRedisLease_grantsUpToMaxConcurrentThenRejects() {
        ImportConcurrencyLimiter limiter = newLimiter(2);

        String lease1 = limiter.acquireRedisLease();
        String lease2 = limiter.acquireRedisLease();
        String lease3 = limiter.acquireRedisLease();

        assertThat(lease1).isNotNull();
        assertThat(lease2).isNotNull();
        assertThat(lease3).isNull();
    }

    @Test
    void releaseRedisLease_freesASlotForANewAcquire() {
        ImportConcurrencyLimiter limiter = newLimiter(1);
        String lease1 = limiter.acquireRedisLease();
        assertThat(lease1).isNotNull();
        assertThat(limiter.acquireRedisLease()).isNull();

        limiter.releaseRedisLease(lease1);

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    /** The precise failure this design closes, found tracing a shared-counter design during
     *  review: a lease whose holder never releases (a simulated crash) must be pruned by a LATER
     *  acquire's own housekeeping, without disturbing a different, still-live lease. */
    @Test
    void aLeakedLeaseIsPrunedByALaterAcquireWithoutDisturbingOtherLiveLeases() throws InterruptedException {
        // maxConcurrent=1 deliberately: the only way a third acquire can succeed at all is if the
        // leaked lease was genuinely pruned, not just tolerated by having room to spare.
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 1, redisTemplate); // 1s safety TTL
        String leaked = limiter.acquireRedisLease();
        assertThat(leaked).isNotNull();
        // "leaked" is never released -- simulating a crashed holder.
        assertThat(limiter.acquireRedisLease()).isNull(); // slot genuinely full right now

        Thread.sleep(1_100); // past the 1-second safety TTL

        // The prune step inside THIS acquire is what removes "leaked" -- if it didn't, this
        // would still be null (maxConcurrent=1, one lease already occupying the only slot).
        String third = limiter.acquireRedisLease();
        assertThat(third).isNotNull();

        limiter.releaseRedisLease(third);
    }

    @Test
    void releasingALeaseThatWasNeverAcquiredIsANoOp() {
        ImportConcurrencyLimiter limiter = newLimiter(1);

        limiter.releaseRedisLease("never-acquired-" + java.util.UUID.randomUUID());

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    /** Simulates Redis having genuinely restarted and lost its data (not just a network blip) --
     *  by deleting the lease-set key directly rather than killing the shared Testcontainer, which
     *  AbstractIntegrationTest's own doc comment explicitly forbids (it would break every other
     *  *IT class sharing this JVM's cached Spring context). A real restart and a manual delete
     *  produce the identical observable state (the key is simply gone), so this is a faithful
     *  simulation of the design spec's "Redis restart self-recovers" claim, not a weaker stand-in
     *  for it. */
    @Test
    void releasingALeaseAfterRedisDataWasLostIsANoOpAndNewAcquiresStartFresh() {
        ImportConcurrencyLimiter limiter = newLimiter(1);
        String lease = limiter.acquireRedisLease();
        assertThat(lease).isNotNull();

        redisTemplate.delete("import:concurrency:active"); // simulates a Redis restart with no persistence

        // The "crashed" import's own release, arriving after the simulated restart -- must not
        // throw, and must not do anything harmful to whatever a fresh acquire does next.
        limiter.releaseRedisLease(lease);

        assertThat(limiter.acquireRedisLease()).isNotNull();
    }

    @Test
    void releasingALeaseAfterItWasAlreadyPrunedDoesNotAffectLaterLeases() throws InterruptedException {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 1, redisTemplate); // 1s safety TTL
        String pruned = limiter.acquireRedisLease();
        assertThat(pruned).isNotNull();
        Thread.sleep(1_100);
        String replacement = limiter.acquireRedisLease(); // this acquire's own prune step removes "pruned"
        assertThat(replacement).isNotNull();

        limiter.releaseRedisLease(pruned); // late release of the already-pruned lease

        assertThat(limiter.acquireRedisLease()).isNull(); // "replacement" must still hold its slot
        limiter.releaseRedisLease(replacement);
    }

    @Test
    void concurrentAcquiresNeverExceedMaxConcurrent() throws InterruptedException {
        int maxConcurrent = 5;
        ImportConcurrencyLimiter limiter = newLimiter(maxConcurrent);
        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        Set<String> granted = ConcurrentHashMap.newKeySet();

        pool.invokeAll(IntStream.range(0, attempts)
                .<Callable<Void>>mapToObj(i -> () -> {
                    String lease = limiter.acquireRedisLease();
                    if (lease != null) granted.add(lease);
                    return null;
                }).toList());
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(granted).hasSizeLessThanOrEqualTo(maxConcurrent);
        granted.forEach(limiter::releaseRedisLease);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=ImportConcurrencyLimiterIT`
Expected: FAIL — `acquireRedisLease`/`releaseRedisLease` don't exist yet, and the constructor doesn't accept `(int, long, StringRedisTemplate)`.

- [ ] **Step 3: Add the lease-set mechanism to `ImportConcurrencyLimiter`**

Modify the existing file — keep the `Semaphore permits` field, the existing `private static final Logger log = LoggerFactory.getLogger(ImportConcurrencyLimiter.class);` field, and the constructor logic exactly as-is (Task 7 wires the semaphore in as the fallback; the existing `log` field is reused below, not redeclared), and add:

```java
    private static final DefaultRedisScript<String> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local leaseId = ARGV[1]
            local safetyTtlSeconds = tonumber(ARGV[2])
            local maxConcurrent = tonumber(ARGV[3])

            local time = redis.call('TIME')
            local now = tonumber(time[1])

            redis.call('ZADD', key, 'NX', now, leaseId)
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - safetyTtlSeconds)
            local count = redis.call('ZCARD', key)
            if count > maxConcurrent then
              redis.call('ZREM', key, leaseId)
              return nil
            end
            return leaseId
            """, String.class);

    private static final String LEASE_SET_KEY = "import:concurrency:active";

    private final long safetyTtlSeconds;
    private final StringRedisTemplate redisTemplate;
    private final RedisFailureLogThrottle failureLog;
```

Add a new constructor overload (this task's tests call it directly; Task 7's production wiring reconciles this with the existing `@Value`-driven constructor):

```java
    /** Package-private: this task's own IT tests drive the Redis lease mechanism directly, ahead
     *  of Task 7 wiring the local-Semaphore fallback around it. */
    ImportConcurrencyLimiter(int maxConcurrent, long safetyTtlSeconds, StringRedisTemplate redisTemplate) {
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        this.safetyTtlSeconds = safetyTtlSeconds;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
    }

    /** Attempts a Redis-backed lease. Returns the lease id if granted, null if the limit is
     *  already reached OR Redis itself could not be reached -- callers distinguish those two
     *  cases by checking Redis reachability separately (Task 7's fallback wiring), not by this
     *  method's return value alone, since both currently return null. */
    String acquireRedisLease() {
        String leaseId = java.util.UUID.randomUUID().toString();
        return redisTemplate.execute(ACQUIRE_SCRIPT, java.util.List.of(LEASE_SET_KEY),
                leaseId, String.valueOf(safetyTtlSeconds), String.valueOf(maxConcurrent));
    }

    void releaseRedisLease(String leaseId) {
        redisTemplate.opsForZSet().remove(LEASE_SET_KEY, leaseId);
    }
```

`org.slf4j.Logger`/`org.slf4j.LoggerFactory` are already imported in the existing file (for the pre-existing `log` field) — do not add them again. Add only the genuinely new imports: `org.springframework.data.redis.core.StringRedisTemplate`, `org.springframework.data.redis.core.script.DefaultRedisScript`, `com.finora.config.RedisFailureLogThrottle`.

- [ ] **Step 4: Run the tests again to verify they pass**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=ImportConcurrencyLimiterIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportConcurrencyLimiter.java backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterIT.java
git commit -m "feat(backend): add Redis lease-set mechanism to ImportConcurrencyLimiter"
```

---

## Task 7: Local `Semaphore` fallback wiring and `runGated()`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportConcurrencyLimiter.java`
- Modify: `backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterIT.java`
- Delete: `backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterTest.java` (replaced — its premise, testing the pure in-memory `Semaphore` as the only mechanism, is superseded by the Redis-primary/local-fallback tests in `ImportConcurrencyLimiterIT`; its concurrent-tryAcquire and rejection-message assertions get folded into the IT file's fallback tests)

**Interfaces:**
- Consumes: `acquireRedisLease()`/`releaseRedisLease()` (Task 6).
- Produces: `ImportConcurrencyLimiter.runGated(Callable<T> work): T` — unchanged public signature, now Redis-primary/local-fallback internally. `ImportController`'s two call sites need no changes at all.

- [ ] **Step 1: Write the failing fallback tests**

Add to `ImportConcurrencyLimiterIT.java`:

```java
    @Test
    void runGated_usesTheRedisLeaseWhenRedisIsReachable() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(2, 300, redisTemplate);

        String result = limiter.runGated(() -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void runGated_rejectsBeyondMaxConcurrentViaRedis() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);
        java.util.concurrent.CountDownLatch holdFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> {
            try {
                limiter.runGated(() -> {
                    holdFirst.countDown();
                    releaseFirst.await();
                    return null;
                });
            } catch (Exception ignored) { }
        });
        first.start();
        holdFirst.await();

        assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                .isInstanceOf(com.finora.exception.ApiException.class);

        releaseFirst.countDown();
        first.join();
    }

    @Test
    void runGated_fallsBackToTheLocalSemaphoreWhenRedisIsUnreachable() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(2, 300, redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            String result = limiter.runGated(() -> "ok via local fallback");

            assertThat(result).isEqualTo("ok via local fallback");
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    @Test
    void runGated_localFallbackStillEnforcesMaxConcurrent() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);
        REDIS_PROXY.setConnectionCut(true);
        try {
            java.util.concurrent.CountDownLatch holdFirst = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
            Thread first = new Thread(() -> {
                try {
                    limiter.runGated(() -> {
                        holdFirst.countDown();
                        releaseFirst.await();
                        return null;
                    });
                } catch (Exception ignored) { }
            });
            first.start();
            holdFirst.await();

            assertThatThrownBy(() -> limiter.runGated(() -> "should not run"))
                    .isInstanceOf(com.finora.exception.ApiException.class);

            releaseFirst.countDown();
            first.join();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    /** The specific detail flagged during design review: a permit acquired via one mechanism must
     *  release via that SAME mechanism, even if Redis's reachability changes between acquire and
     *  release. Cutting the connection AFTER acquire (which succeeds via Redis) proves release
     *  still correctly targets Redis rather than silently no-op'ing into the local semaphore. */
    @Test
    void aPermitAcquiredViaRedisReleasesViaRedisEvenIfRedisReachabilityChangesMidRequest() throws Exception {
        ImportConcurrencyLimiter limiter = new ImportConcurrencyLimiter(1, 300, redisTemplate);

        limiter.runGated(() -> {
            // Acquired via Redis (connection was live). Cut it now, mid-request.
            REDIS_PROXY.setConnectionCut(true);
            return null;
        });
        REDIS_PROXY.setConnectionCut(false);

        // If release had gone to the wrong place, the Redis lease from above would still be held,
        // and this acquire (now that Redis is reachable again) would be rejected.
        String result = limiter.runGated(() -> "ok");
        assertThat(result).isEqualTo("ok");
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` to the file's imports.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=ImportConcurrencyLimiterIT`
Expected: FAIL — `runGated` doesn't yet try Redis first / fall back locally; it still only uses the plain `Semaphore`.

- [ ] **Step 3: Wire the fallback into `runGated()`**

Replace the existing `runGated` method and add the sealed `Permit` type:

```java
    /** Which mechanism actually granted a permit, so release() can target the same one -- see
     *  the design spec's "Redis-unreachable fallback" section for why this matters: acquiring via
     *  the Redis lease and releasing via the local semaphore (or vice versa) would silently leak
     *  capacity from whichever mechanism was bypassed. */
    private sealed interface Permit {
        record RedisLease(String leaseId) implements Permit {}
        record LocalPermit() implements Permit {}
    }

    /**
     * Runs `work` immediately if a permit is currently available. Tries the Redis lease first;
     * on any Redis DataAccessException (a refused connection OR a timed-out command -- both must
     * be caught, see this codebase's Global Constraints for why a narrower catch would defeat the
     * 200ms timeout's whole purpose), falls back to the existing local Semaphore at the same
     * maxConcurrent ceiling, reusing the exact tryAcquire()/release() logic already in production
     * rather than fail-open: this limiter enforces a resource-protection boundary, not merely a
     * protective control, and failing open here would compound with the rate limiter also
     * failing open at the same moment during a full outage.
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

    private Permit acquirePermit() {
        try {
            String leaseId = acquireRedisLease();
            return leaseId != null ? new Permit.RedisLease(leaseId) : null;
        } catch (org.springframework.dao.DataAccessException e) {
            failureLog.warn("Redis unreachable for import concurrency limiter -- falling back to "
                    + "the local semaphore: {}", e.toString());
            return permits.tryAcquire() ? new Permit.LocalPermit() : null;
        }
    }

    private void releasePermit(Permit permit) {
        if (permit instanceof Permit.RedisLease redisLease) {
            try {
                releaseRedisLease(redisLease.leaseId());
            } catch (org.springframework.dao.DataAccessException e) {
                failureLog.warn("Redis unreachable while releasing an import concurrency lease "
                        + "{} -- it will self-heal via its safety TTL: {}", redisLease.leaseId(), e.toString());
            }
        } else {
            permits.release();
        }
    }
```

Delete the old bare `runGated` method this replaces (the one that only called `permits.tryAcquire()`/`permits.release()` directly).

Reconcile the two constructors — the existing `@Value`-driven `@Component` constructor is the one Spring actually instantiates in production, so it must set every field the package-private one (Task 6) sets, or `redisTemplate`/`failureLog` are null on the real bean and every `runGated()` call NPEs. Change it from:

```java
    public ImportConcurrencyLimiter(@Value("${app.import.max-concurrent:6}") int maxConcurrent) {
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        log.info("Import concurrency limiter initialized: max {} concurrent imports, rejects immediately with 'busy' once the limit is reached",
                maxConcurrent);
    }
```

to:

```java
    public ImportConcurrencyLimiter(@Value("${app.import.max-concurrent:6}") int maxConcurrent,
                                     @Value("${app.import.concurrency-lease-ttl-seconds:300}") long safetyTtlSeconds,
                                     StringRedisTemplate redisTemplate) {
        this.permits = new Semaphore(maxConcurrent);
        this.maxConcurrent = maxConcurrent;
        this.safetyTtlSeconds = safetyTtlSeconds;
        this.redisTemplate = redisTemplate;
        this.failureLog = new RedisFailureLogThrottle(log, 60_000);
        log.info("Import concurrency limiter initialized: max {} concurrent imports, rejects immediately with 'busy' once the limit is reached",
                maxConcurrent);
    }
```

- [ ] **Step 4: Run the tests again to verify they pass**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=ImportConcurrencyLimiterIT`
Expected: PASS, all tests including Task 6's.

- [ ] **Step 5: Delete the obsolete unit test**

```bash
git rm backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterTest.java
```

- [ ] **Step 6: Run the full compile to confirm nothing else broke**

Run: `cd backend && mvn -q -o compile`
Expected: clean — `ImportController`'s two `runGated` call sites need no changes, confirmed by the interface staying identical.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportConcurrencyLimiter.java backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterIT.java
git rm --cached backend/src/test/java/com/finora/imports/ImportConcurrencyLimiterTest.java 2>/dev/null || true
git commit -m "feat(backend): fall back to the local Semaphore when Redis is unreachable"
```

---

## Task 8: Redis-backed cache (`RedisCacheManager` + `CacheErrorHandler`)

**Files:**
- Modify: `backend/src/main/java/com/finora/config/CacheConfig.java`
- Create: `backend/src/main/java/com/finora/config/RedisCacheErrorHandler.java`
- Test: `backend/src/test/java/com/finora/config/CacheConfigIT.java` (new)

**Interfaces:**
- Consumes: `RedisFailureLogThrottle` (Task 4).
- Produces: nothing consumed by later tasks — `BankManagementService`/`FeatureFlagService`'s existing `@Cacheable`/`@CacheEvict` annotations need zero changes, confirmed by this task's tests exercising them unmodified.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.config;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CacheConfigIT extends AbstractIntegrationTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void cacheManagerIsRedisBacked_putThenGetRoundTrips() {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        assertThat(cache).isNotNull();

        cache.put("test-key", "test-value");

        assertThat(cache.get("test-key", String.class)).isEqualTo("test-value");
    }

    @Test
    void getFailsOpenToACacheMissWhenRedisIsUnreachable() {
        Cache cache = cacheManager.getCache(CacheConfig.FEATURE_FLAGS_CACHE);
        assertThat(cache).isNotNull();
        cache.put("pre-outage-key", "pre-outage-value");

        REDIS_PROXY.setConnectionCut(true);
        try {
            // Must not throw -- the whole point of the CacheErrorHandler bean.
            Cache.ValueWrapper result = cache.get("pre-outage-key");
            assertThat(result).isNull();
        } finally {
            REDIS_PROXY.setConnectionCut(false);
        }
    }

    /** Proves the sync=true locking cache writer's lock is genuinely Redis-side (SETNX), not a
     *  client-local artifact -- see the design spec's "Verified, not assumed" note. A concurrent
     *  load exercised exactly once is the observable proof either way; this test's value is in
     *  confirming that's still true after the RedisCacheManager swap, not in re-deriving why. */
    @Test
    void syncTrueLoadsTheUnderlyingValueExactlyOnceUnderConcurrentMisses() throws InterruptedException {
        Cache cache = cacheManager.getCache(CacheConfig.CUSTOM_BANKS_CACHE);
        assertThat(cache).isNotNull();
        String key = "stampede-key-" + System.nanoTime();
        AtomicInteger loadCount = new AtomicInteger();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    cache.get(key, () -> {
                        loadCount.incrementAndGet();
                        Thread.sleep(200); // widen the race window so misses genuinely overlap
                        return "loaded-value";
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(loadCount.get()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=CacheConfigIT`
Expected: FAIL — `CacheManager` is still Caffeine-backed, so the "unreachable" test either doesn't fail open correctly (Caffeine has no concept of Redis being down) or the round-trip test passes for the wrong reason. Confirm the failure is real before proceeding.

- [ ] **Step 3: Write `RedisCacheErrorHandler`**

```java
package com.finora.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Treats every Redis-backed cache failure as a miss/no-op rather than letting it propagate --
 * see docs/superpowers/specs/2026-09-15-redis-integration-design.md, Component 3: a broken
 * CacheManager otherwise turns every @Cacheable call into a thrown exception, strictly worse
 * than having no cache at all. Logging goes through the same shared, purely-local
 * RedisFailureLogThrottle every other Redis-backed component uses -- never anything Redis-backed
 * itself, which would be a circular dependency on the one message that most needs to still work
 * when Redis is down.
 */
public class RedisCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheErrorHandler.class);
    private final RedisFailureLogThrottle failureLog = new RedisFailureLogThrottle(log, 60_000);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        failureLog.warn("Redis unreachable on cache get ({}) -- treating as a miss: {}",
                cache.getName(), exception.toString());
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        failureLog.warn("Redis unreachable on cache put ({}) -- write dropped: {}",
                cache.getName(), exception.toString());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        failureLog.warn("Redis unreachable on cache evict ({}) -- stale entry may persist until "
                + "its own TTL: {}", cache.getName(), exception.toString());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        failureLog.warn("Redis unreachable on cache clear ({}): {}", cache.getName(), exception.toString());
    }
}
```

- [ ] **Step 4: Rewrite `CacheConfig`**

Replace the `cacheManager()` bean and add a `CacheErrorHandler` bean:

```java
package com.finora.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * The one place named caches get registered -- see docs/superpowers/specs/
 * 2026-09-15-redis-integration-design.md, Component 3, for why this moved from Caffeine
 * (in-process, correct on one instance only) to Redis (shared, correct across replicas). Same two
 * named caches, same TTLs, same sync=true stampede-protection contract -- BankManagementService
 * and FeatureFlagService's existing @Cacheable/@CacheEvict annotations are untouched by this
 * change; only this bean's own implementation changed.
 */
@Configuration
@EnableCaching
public class CacheConfig implements org.springframework.cache.annotation.CachingConfigurer {

    public static final String CUSTOM_BANKS_CACHE = "customBanks";
    public static final String FEATURE_FLAGS_CACHE = "featureFlags";

    /** {@code CachingConfigurer} is required here, not optional -- verified against Spring's own
     *  caching docs: a plain {@code @Bean CacheErrorHandler} is never auto-wired by
     *  {@code @EnableCaching} on its own; Spring falls back to the default
     *  {@code SimpleCacheErrorHandler} (which rethrows) unless a {@code CachingConfigurer}
     *  explicitly returns the custom handler from {@link #errorHandler()} below. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer()))
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        // lockingRedisCacheWriter: the Redis-side SETNX-backed lock sync=true needs -- see the
        // design spec's "Verified, not assumed" note on why a single-JVM concurrent test is
        // sufficient proof this is genuinely distributed, not a client-local mutex.
        RedisCacheWriter writer = RedisCacheWriter.lockingRedisCacheWriter(connectionFactory);

        return RedisCacheManager.builder(writer)
                .cacheDefaults(defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(CUSTOM_BANKS_CACHE, defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration(FEATURE_FLAGS_CACHE, defaultConfig.entryTtl(Duration.ofSeconds(60)))
                .build();
    }
}
```

Note: `org.springframework.cache.CacheManager` needs importing too (`import org.springframework.cache.CacheManager;`) — the return type of the `cacheManager` bean method. `errorHandler()` above is the only place `RedisCacheErrorHandler` is wired in — no separate `@Bean` method for it, since `CachingConfigurer.errorHandler()` is itself the registration point Spring's caching infrastructure reads.

- [ ] **Step 5: Run the tests again to verify they pass**

Run: `cd backend && mvn -q -o failsafe:integration-test -Dit.test=CacheConfigIT`
Expected: PASS. If the error-handler wiring from Step 4's conditional isn't picked up, the `getFailsOpenToACacheMissWhenRedisIsUnreachable` test will fail with a thrown exception instead of a null result — that failure is the signal to add the `CachingConfigurer` implementation.

- [ ] **Step 6: Run the existing cache-consuming service tests to confirm no regression**

Run: `cd backend && mvn -q -o test -Dtest=BankManagementServiceTest,FeatureFlagServiceTest` (adjust exact test class names if they differ — search `find backend/src/test/java -iname "*BankManagement*Test.java" -o -iname "*FeatureFlag*Test.java"` first if unsure)
Expected: PASS, unchanged — these are unit tests that don't depend on which `CacheManager` implementation is wired at runtime.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/config/CacheConfig.java backend/src/main/java/com/finora/config/RedisCacheErrorHandler.java backend/src/test/java/com/finora/config/CacheConfigIT.java
git commit -m "feat(backend): move CacheConfig to Redis, fail open to a cache miss on outage"
```

---

## Task 9: Full-suite verification and spec status update

**Files:**
- Modify: `docs/superpowers/specs/2026-09-15-redis-integration-design.md` (status line only)

**Interfaces:** none — this task verifies the whole change, introduces nothing new.

- [ ] **Step 1: Run the complete backend test suite**

Run: `cd backend && mvn -q -o verify`
Expected: PASS — every unit test, every `*IT` including all new Redis-backed ones, with the shared Testcontainer/Toxiproxy infrastructure from Task 2 holding up across the whole run (the exact thing `AbstractIntegrationTest`'s own doc comment warns is fragile if container lifecycle discipline slips).

- [ ] **Step 2: Manually verify against the real local Homebrew Redis**

```bash
cd backend && REDIS_URL=redis://127.0.0.1:6379 mvn -q -o spring-boot:run
```

In another terminal, exercise each component once for real:
- `for i in $(seq 1 12); do curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"nobody@example.com","password":"wrong"}'; done` — expect the later requests to start returning `429` once the login limiter's configured max is exceeded.
- `curl -s http://localhost:8080/actuator/health | python3 -m json.tool` — expect `"redis": {"status": "UP"}`.

Stop the app, then stop the local Redis and repeat the health check:

```bash
brew services stop redis
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

Expected: the app is still reachable (didn't crash), and the `redis` component now reports `DOWN` or `DEGRADED`. Restart Redis afterward: `brew services start redis`.

- [ ] **Step 3: Update the spec's status line**

In `docs/superpowers/specs/2026-09-15-redis-integration-design.md`, change:
```
Status: proposed, not yet implemented.
```
to:
```
Status: implemented.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-09-15-redis-integration-design.md
git commit -m "docs: mark Redis integration spec as implemented"
```

- [ ] **Step 5: Push and open a PR**

```bash
git push -u origin worktree-redis-integration
gh pr create --title "feat(backend): move RateLimiter, ImportConcurrencyLimiter, and cache to Redis" --body "Implements docs/superpowers/specs/2026-09-15-redis-integration-design.md. See that spec for the full design, review history, and the two hostile-review passes that shaped the timeout, fallback, and testing decisions."
```
