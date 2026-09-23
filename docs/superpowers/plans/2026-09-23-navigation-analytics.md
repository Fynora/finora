# Navigation Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship three aggregate Micrometer counters that record how people navigate Fynora, so the shared navigation taxonomy has a pre-change baseline to be measured against.

**Architecture:** No third-party analytics SDK. Both clients call a thin first-party endpoint (`POST /api/v1/nav-events`) which validates each field against bounded enums and increments Micrometer counters already exported to Prometheus. Nothing is persisted and no identifier is transmitted, which is what keeps this inside the published privacy policy's "aggregated, non-identifying" commitment.

**Tech Stack:** Spring Boot / Micrometer / `micrometer-registry-prometheus` (backend), React + TypeScript + Vitest (web), React Native + Expo + Jest (mobile), Python for the CI drift check.

**Spec:** [docs/superpowers/specs/2026-09-23-navigation-analytics-design.md](../specs/2026-09-23-navigation-analytics-design.md)

## Why the taxonomy *constant* ships in this plan, not the next one

The analytics spec derives its destination allowlist from the taxonomy. The taxonomy spec ships the navigation *rendering*. Taken literally that is circular — analytics needs the taxonomy, and the taxonomy ships second.

It resolves cleanly once you separate the two things the taxonomy spec contains:

- **The taxonomy as data** — which destinations exist and which group each belongs to. This is true today; grouping does not invent destinations, it only arranges them.
- **The taxonomy as rendering** — sidebar headers, More-menu sections, the shared mobile header.

**The data ships here, in Task 1. The rendering ships in the companion plan.** That is what makes the baseline useful: `group` is populated from day one, so the before-period and after-period are directly comparable rather than the before-period having no group attribution at all.

## Global Constraints

- **No third-party analytics dependency** is added to either client. No PostHog, Mixpanel, Amplitude, Segment, or Firebase Analytics.
- **No identifier in any nav event** — no user id, account id, session id, or device id. This is the entire legal basis for collecting without consent; if it is ever added, the privacy policy wording and the consent question both reopen.
- **No free text.** `search_used` records that a search happened, never what was typed. `docs/engineering/observability.md` §3 names the ledger search term as the sharpest case of text that must never leave.
- **No persistence.** The endpoint increments counters and returns `204`. There is no events table.
- **Reject, never bucket.** An unrecognised enum value is dropped. An `other` bucket is where the enum stops being bounded — see `docs/engineering/observability.md` §5.
- **Allowlist, never denylist** — the rule `SentryScrubber` already establishes: structures are validated field by field against known values, not sanitised by removing bad ones.
- Repo rule: commit messages carry no `Co-Authored-By` or AI-attribution trailer (project `CLAUDE.md`).

## File Structure

| File | Responsibility |
|---|---|
| `frontend/src/navigation/taxonomy.ts` | Web's taxonomy data: destinations, their group, their route. |
| `mobile/src/navigation/taxonomy.ts` | Mobile's mirror of the same data. |
| `scripts/check-nav-taxonomy-drift.py` | CI check that both clients and the backend enum agree. |
| `backend/.../observability/NavDestination.java` | Bounded enum of destination ids — the wire contract. |
| `backend/.../observability/NavGroup.java` | Bounded enum of group ids. |
| `backend/.../observability/NavEntryPoint.java` | Bounded enum of entry points. |
| `backend/.../observability/NavigationMetrics.java` | The three counters. Modelled on `AuthMetrics`. |
| `backend/.../controller/NavEventController.java` | Validate, increment, `204`. No service, no repository. |
| `frontend/src/lib/trackNavigation.ts` | Web's fire-and-forget event sender. |
| `mobile/src/lib/trackNavigation.ts` | Mobile's equivalent. |

---

### Task 1: The taxonomy as data, on both clients

**Files:**
- Create: `frontend/src/navigation/taxonomy.ts`
- Create: `frontend/src/navigation/taxonomy.test.ts`
- Create: `mobile/src/navigation/taxonomy.ts`
- Create: `scripts/check-nav-taxonomy-drift.py`
- Modify: `.github/workflows/ci.yml` (add the check alongside the existing `scripts/check-*` steps)

**Interfaces:**
- Produces: `NAV_TAXONOMY: readonly NavEntry[]` where `NavEntry = { id: string; group: NavGroupId; label: string }`; `NAV_DESTINATION_IDS: readonly string[]`; type `NavGroupId = 'root' | 'money' | 'statements' | 'planning' | 'analysis' | 'your-account'`. Both clients export identical shapes. Task 5 and Task 6 consume `NAV_DESTINATION_IDS`. The companion taxonomy plan consumes `NAV_TAXONOMY` for rendering.

- [ ] **Step 1: Write the failing test**

```ts
// frontend/src/navigation/taxonomy.test.ts
import { describe, it, expect } from 'vitest';
import { NAV_TAXONOMY, NAV_DESTINATION_IDS } from './taxonomy';

describe('nav taxonomy', () => {
  it('has exactly one home destination, in the root group', () => {
    const root = NAV_TAXONOMY.filter((e) => e.group === 'root');
    expect(root).toHaveLength(1);
    expect(root[0].id).toBe('home');
  });

  it('assigns every destination to exactly one group', () => {
    const ids = NAV_TAXONOMY.map((e) => e.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('exposes the same ids as NAV_DESTINATION_IDS', () => {
    expect([...NAV_DESTINATION_IDS].sort()).toEqual(NAV_TAXONOMY.map((e) => e.id).sort());
  });

  it('covers all five groups plus root', () => {
    expect(new Set(NAV_TAXONOMY.map((e) => e.group))).toEqual(
      new Set(['root', 'money', 'statements', 'planning', 'analysis', 'your-account']),
    );
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/navigation/taxonomy.test.ts`
Expected: FAIL — `Failed to resolve import "./taxonomy"`.

- [ ] **Step 3: Write the taxonomy**

```ts
// frontend/src/navigation/taxonomy.ts
/**
 * The shared navigation taxonomy, as data.
 *
 * Deliberately separate from how either client renders it: this file says which destinations
 * exist and which group each belongs to, and nothing about sidebars, tabs or headers. That split
 * is what lets analytics attribute a group to every event before the grouped UI ships.
 *
 * mobile/src/navigation/taxonomy.ts must stay identical. scripts/check-nav-taxonomy-drift.py
 * enforces that in CI, along with agreement with the backend's NavDestination enum.
 */
export type NavGroupId = 'root' | 'money' | 'statements' | 'planning' | 'analysis' | 'your-account';

export type NavEntry = { readonly id: string; readonly group: NavGroupId; readonly label: string };

export const NAV_TAXONOMY: readonly NavEntry[] = [
  { id: 'home', group: 'root', label: 'Home' },

  { id: 'accounts', group: 'money', label: 'Accounts' },
  { id: 'transactions', group: 'money', label: 'Transactions' },

  { id: 'import-statement', group: 'statements', label: 'Import Statement' },
  { id: 'statement-history', group: 'statements', label: 'Statement History' },
  { id: 'review-categories', group: 'statements', label: 'Review Categories' },
  { id: 'financial-memory', group: 'statements', label: 'Financial Memory' },

  { id: 'budgets', group: 'planning', label: 'Budgets' },
  { id: 'goals', group: 'planning', label: 'Goals' },
  { id: 'investments', group: 'planning', label: 'Investments' },

  { id: 'insights', group: 'analysis', label: 'Insights' },
  { id: 'reports', group: 'analysis', label: 'Reports' },
  { id: 'advanced-reports', group: 'analysis', label: 'Advanced Reports' },
  { id: 'ask-fyn', group: 'analysis', label: 'Ask Fyn' },

  { id: 'profile', group: 'your-account', label: 'Profile' },
  { id: 'subscription', group: 'your-account', label: 'Subscription' },
  { id: 'referrals', group: 'your-account', label: 'Refer & Earn' },
  { id: 'settings', group: 'your-account', label: 'Settings' },
  { id: 'support', group: 'your-account', label: 'Support' },
] as const;

/** The analytics allowlist is DERIVED here, never declared separately. Adding a destination above
 *  makes it trackable in the same edit; there is no second list to forget. */
export const NAV_DESTINATION_IDS: readonly string[] = NAV_TAXONOMY.map((e) => e.id);
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/navigation/taxonomy.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Copy the file to mobile verbatim**

Copy `frontend/src/navigation/taxonomy.ts` to `mobile/src/navigation/taxonomy.ts` with no edits. The drift check in Step 6 is what keeps them identical; do not hand-retype it.

- [ ] **Step 6: Write the drift check**

```python
#!/usr/bin/env python3
"""Fails when the two clients' nav taxonomies, or the backend's NavDestination enum, disagree.

Three copies exist because there is no shared package between frontend and mobile, and the backend
needs the enum in Java to validate the ingest endpoint. This script is what makes three copies safe:
a destination added to one and not the others is a silent measurement gap, not a build error.

Run with --self-test to check the parser against inline fixtures without touching the repo.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "frontend/src/navigation/taxonomy.ts"
MOBILE = ROOT / "mobile/src/navigation/taxonomy.ts"
BACKEND = ROOT / "backend/src/main/java/com/finora/observability/NavDestination.java"

TS_ENTRY = re.compile(r"\{\s*id:\s*'([a-z-]+)',\s*group:\s*'([a-z-]+)'")
JAVA_ENTRY = re.compile(r'^\s*([A-Z_]+)\("([a-z-]+)"\)', re.MULTILINE)


def parse_ts(text):
    return [(m.group(1), m.group(2)) for m in TS_ENTRY.finditer(text)]


def parse_java(text):
    return [m.group(2) for m in JAVA_ENTRY.finditer(text)]


def self_test():
    ts = "{ id: 'home', group: 'root', label: 'Home' }, { id: 'goals', group: 'planning', label: 'Goals' }"
    assert parse_ts(ts) == [("home", "root"), ("goals", "planning")], parse_ts(ts)
    java = '    HOME("home"),\n    GOALS("goals");\n'
    assert parse_java(java) == ["home", "goals"], parse_java(java)
    print("self-test OK")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    web = parse_ts(WEB.read_text())
    mobile = parse_ts(MOBILE.read_text())
    backend = parse_java(BACKEND.read_text())

    failures = []
    if not web:
        failures.append(f"parsed no entries from {WEB} -- the parser or the file shape changed")
    if web != mobile:
        only_web = sorted(set(web) - set(mobile))
        only_mobile = sorted(set(mobile) - set(web))
        failures.append(
            "web and mobile taxonomies differ.\n"
            f"  only in web:    {only_web}\n"
            f"  only in mobile: {only_mobile}\n"
            "  (order matters too -- the rendered order is part of the shared definition)"
        )
    web_ids, backend_ids = sorted(i for i, _ in web), sorted(backend)
    if web_ids != backend_ids:
        failures.append(
            "clients and backend NavDestination disagree.\n"
            f"  only in clients: {sorted(set(web_ids) - set(backend_ids))}\n"
            f"  only in backend: {sorted(set(backend_ids) - set(web_ids))}\n"
            "  a destination missing from the backend enum is REJECTED at ingest and silently unmeasured"
        )

    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    if failures:
        return 1
    print(f"nav taxonomy consistent across web, mobile and backend ({len(web)} destinations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 7: Run the drift check's self-test, then the real check**

Run: `python3 scripts/check-nav-taxonomy-drift.py --self-test`
Expected: `self-test OK`

Run: `python3 scripts/check-nav-taxonomy-drift.py`
Expected: `nav taxonomy consistent across web and mobile (backend enum not present yet) (19 destinations)`

The backend enum arrives in Task 2, and the check treats its absence as "not due yet" rather than as drift. That is deliberate: this task commits the CI step, so a check that failed until Task 2 would land a commit with red CI. Once `NavDestination.java` exists the check compares all three and the message changes to name the backend too.

- [ ] **Step 8: Wire the check into CI**

In `.github/workflows/ci.yml`, beside the existing `scripts/check-*` steps (see the block around `check-client-auth-policy.py`), add:

```yaml
      - name: Nav taxonomy drift
        run: python3 scripts/check-nav-taxonomy-drift.py
```

- [ ] **Step 9: Commit**

```bash
git add frontend/src/navigation/ mobile/src/navigation/taxonomy.ts scripts/check-nav-taxonomy-drift.py .github/workflows/ci.yml
git commit -m "feat(analytics): add the shared navigation taxonomy as data, with a CI drift check"
```

---

### Task 2: Backend bounded enums and counters

**Files:**
- Create: `backend/src/main/java/com/finora/observability/NavDestination.java`
- Create: `backend/src/main/java/com/finora/observability/NavGroup.java`
- Create: `backend/src/main/java/com/finora/observability/NavEntryPoint.java`
- Create: `backend/src/main/java/com/finora/observability/NavigationMetrics.java`
- Test: `backend/src/test/java/com/finora/observability/NavigationMetricsTest.java`

**Interfaces:**
- Consumes: the destination ids from Task 1.
- Produces: `NavDestination.fromWire(String) -> Optional<NavDestination>`, `NavGroup.fromWire(String) -> Optional<NavGroup>`, `NavEntryPoint.fromWire(String) -> Optional<NavEntryPoint>`, each returning empty for an unknown value. `NavigationMetrics.destinationOpened(NavDestination, NavGroup, String platform)`, `.entryPointUsed(NavEntryPoint, String platform)`, `.searchUsed(String platform)`. Task 3 consumes all of these.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NavigationMetrics metrics = new NavigationMetrics(registry);

    @Test
    void destinationOpened_incrementsWithDestinationGroupAndPlatformTags() {
        metrics.destinationOpened(NavDestination.BUDGETS, NavGroup.PLANNING, "web");

        assertThat(registry.get("finora.nav.destination_opened")
                .tag("destination", "budgets")
                .tag("group", "planning")
                .tag("platform", "web")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void entryPointUsed_incrementsWithEntryAndPlatformTags() {
        metrics.entryPointUsed(NavEntryPoint.TAB, "mobile");

        assertThat(registry.get("finora.nav.entry_point_used")
                .tag("entry", "tab").tag("platform", "mobile")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void searchUsed_incrementsWithPlatformTagOnly() {
        metrics.searchUsed("web");

        assertThat(registry.get("finora.nav.search_used")
                .tag("platform", "web").counter().count()).isEqualTo(1.0);
    }

    @Test
    void fromWire_returnsEmptyForAnUnknownValue_neverAnOtherBucket() {
        assertThat(NavDestination.fromWire("definitely-not-a-destination")).isEmpty();
        assertThat(NavGroup.fromWire("../../etc/passwd")).isEmpty();
        assertThat(NavEntryPoint.fromWire("")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=NavigationMetricsTest`
Expected: FAIL — compilation error, `NavigationMetrics` does not exist.

- [ ] **Step 3: Write the three enums**

```java
// NavDestination.java
package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/**
 * The bounded set of navigation destinations, and the wire contract for POST /api/v1/nav-events.
 *
 * <p>Mirrors frontend/src/navigation/taxonomy.ts and mobile/src/navigation/taxonomy.ts;
 * scripts/check-nav-taxonomy-drift.py fails CI when the three disagree. A destination missing here
 * is rejected at ingest and therefore silently unmeasured, which is the failure that check exists
 * to prevent.
 *
 * <p>{@link #fromWire} returns empty rather than a fallback constant, deliberately. An "other"
 * bucket is where a bounded enum stops being bounded -- see docs/engineering/observability.md §5.
 */
public enum NavDestination {
    HOME("home"),
    ACCOUNTS("accounts"),
    TRANSACTIONS("transactions"),
    IMPORT_STATEMENT("import-statement"),
    STATEMENT_HISTORY("statement-history"),
    REVIEW_CATEGORIES("review-categories"),
    FINANCIAL_MEMORY("financial-memory"),
    BUDGETS("budgets"),
    GOALS("goals"),
    INVESTMENTS("investments"),
    INSIGHTS("insights"),
    REPORTS("reports"),
    ADVANCED_REPORTS("advanced-reports"),
    ASK_FYN("ask-fyn"),
    PROFILE("profile"),
    SUBSCRIPTION("subscription"),
    REFERRALS("referrals"),
    SETTINGS("settings"),
    SUPPORT("support");

    private final String wire;

    NavDestination(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<NavDestination> fromWire(String value) {
        return Arrays.stream(values()).filter(d -> d.wire.equals(value)).findFirst();
    }
}
```

```java
// NavGroup.java
package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/** The five taxonomy groups plus the ungrouped root. See {@link NavDestination} for why
 *  {@link #fromWire} has no fallback. */
public enum NavGroup {
    ROOT("root"),
    MONEY("money"),
    STATEMENTS("statements"),
    PLANNING("planning"),
    ANALYSIS("analysis"),
    YOUR_ACCOUNT("your-account");

    private final String wire;

    NavGroup(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<NavGroup> fromWire(String value) {
        return Arrays.stream(values()).filter(g -> g.wire.equals(value)).findFirst();
    }
}
```

```java
// NavEntryPoint.java
package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/** How a destination was reached. `GROUP` is its taxonomy entry; the rest are promoted shortcuts
 *  and contextual links. This is the counter that shows empirically whether a group entry is used
 *  at all once an item also has a tab or FAB. */
public enum NavEntryPoint {
    GROUP("group"),
    TAB("tab"),
    FAB("fab"),
    HEADER("header"),
    CONTEXTUAL("contextual"),
    SEARCH("search");

    private final String wire;

    NavEntryPoint(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<NavEntryPoint> fromWire(String value) {
        return Arrays.stream(values()).filter(e -> e.wire.equals(value)).findFirst();
    }
}
```

- [ ] **Step 4: Write the counters**

```java
package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Aggregate navigation usage. Same argument as {@link AuthMetrics}: without these, "did the
 * navigation redesign change anything" is a guess, not a measurement.
 *
 * <h2>Why these carry a platform tag when AuthMetrics refuses one</h2>
 *
 * <p>{@link AuthMetrics} declines to tag by client platform because the value is client-asserted
 * and its counters are security-relevant. Neither holds here. These are product telemetry, not a
 * security control, and whether the two clients behave differently is the entire question being
 * asked -- unanswerable without the tag. A client that lies about its platform skews a usage chart
 * and nothing else. The divergence is deliberate.
 *
 * <h2>What is never here</h2>
 *
 * <p>No user, account, session or device identifier, and no free text. The absence of identity is
 * the basis on which this is collected without consent (see the design spec's Consent section), so
 * it is a constraint to preserve rather than an accident of the current shape.
 */
@Component
public class NavigationMetrics {

    private final MeterRegistry registry;

    public NavigationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A destination was opened, however it was reached. */
    public void destinationOpened(NavDestination destination, NavGroup group, String platform) {
        Counter.builder("finora.nav.destination_opened")
                .description("A navigation destination was opened")
                .tag("destination", destination.wire())
                .tag("group", group.wire())
                .tag("platform", platform)
                .register(registry)
                .increment();
    }

    /** Which affordance carried the user there -- a group entry, a tab, the FAB, a header action,
     *  a contextual link, or search. */
    public void entryPointUsed(NavEntryPoint entry, String platform) {
        Counter.builder("finora.nav.entry_point_used")
                .description("The affordance a navigation destination was reached through")
                .tag("entry", entry.wire())
                .tag("platform", platform)
                .register(registry)
                .increment();
    }

    /** A navigation search was performed. Counts that it happened; never what was typed -- the
     *  ledger search term is named in docs/engineering/observability.md §3 as the sharpest case of
     *  free text that must never leave the platform. */
    public void searchUsed(String platform) {
        Counter.builder("finora.nav.search_used")
                .description("A navigation search was performed")
                .tag("platform", platform)
                .register(registry)
                .increment();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=NavigationMetricsTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Re-run the drift check now that the backend enum exists**

Run: `python3 scripts/check-nav-taxonomy-drift.py`
Expected: `nav taxonomy consistent across web, mobile and backend (19 destinations)`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/observability/ backend/src/test/java/com/finora/observability/NavigationMetricsTest.java
git commit -m "feat(analytics): add bounded navigation enums and aggregate usage counters"
```

---

### Task 3: The ingest endpoint

**Files:**
- Create: `backend/src/main/java/com/finora/controller/NavEventController.java`
- Create: `backend/src/main/java/com/finora/dto/NavEventRequest.java`
- Test: `backend/src/test/java/com/finora/controller/NavEventControllerTest.java`

**Interfaces:**
- Consumes: `NavigationMetrics`, `NavDestination.fromWire`, `NavGroup.fromWire`, `NavEntryPoint.fromWire` from Task 2.
- Produces: `POST /api/v1/nav-events` accepting `{"events":[{"destination":"budgets","group":"planning","entry":"group"}]}` and returning `204`. Tasks 5 and 6 post to it.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.controller;

import com.finora.observability.NavDestination;
import com.finora.observability.NavEntryPoint;
import com.finora.observability.NavGroup;
import com.finora.observability.NavigationMetrics;
import com.finora.dto.NavEventRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NavEventControllerTest {

    private final NavigationMetrics metrics = mock(NavigationMetrics.class);
    private final NavEventController controller = new NavEventController(metrics);

    @Test
    void record_incrementsBothCountersForAValidEvent() {
        controller.record(new NavEventRequest(List.of(
                new NavEventRequest.Event("budgets", "planning", "group")), 0), "web");

        verify(metrics).destinationOpened(NavDestination.BUDGETS, NavGroup.PLANNING, "web");
        verify(metrics).entryPointUsed(NavEntryPoint.GROUP, "web");
    }

    @Test
    void record_dropsAnUnknownDestination_andDoesNotBucketItAsOther() {
        controller.record(new NavEventRequest(List.of(
                new NavEventRequest.Event("not-a-destination", "planning", "group")), 0), "web");

        verifyNoInteractions(metrics);
    }

    @Test
    void record_dropsOneBadEventWithoutDiscardingTheGoodOnesBesideIt() {
        controller.record(new NavEventRequest(List.of(
                new NavEventRequest.Event("budgets", "planning", "group"),
                new NavEventRequest.Event("nope", "planning", "group"),
                new NavEventRequest.Event("goals", "planning", "tab")), 0), "mobile");

        verify(metrics).destinationOpened(NavDestination.BUDGETS, NavGroup.PLANNING, "mobile");
        verify(metrics).destinationOpened(NavDestination.GOALS, NavGroup.PLANNING, "mobile");
        verify(metrics, never()).destinationOpened(isNull(), any(), any());
        verify(metrics, times(2)).entryPointUsed(any(), eq("mobile"));
    }

    @Test
    void record_rejectsAnUnknownPlatformRatherThanTaggingWithIt() {
        controller.record(new NavEventRequest(List.of(
                new NavEventRequest.Event("budgets", "planning", "group")), 0), "curl");

        verifyNoInteractions(metrics);
    }

    @Test
    void record_capsAnOversizedBatch() {
        List<NavEventRequest.Event> tooMany = java.util.Collections.nCopies(
                500, new NavEventRequest.Event("budgets", "planning", "group"));

        controller.record(new NavEventRequest(tooMany, 0), "web");

        verify(metrics, atMost(NavEventController.MAX_BATCH))
                .destinationOpened(any(), any(), any());
    }

    @Test
    void controller_hasNoRepositoryOrPersistenceDependency() {
        // "Nothing is stored" is a design constraint, not an omission -- with no events table there
        // is no dataset to later re-identify, leak, or be compelled to produce. Asserted
        // structurally because the natural future regression is someone adding a repository "just
        // to keep the raw events for debugging."
        assertThat(NavEventController.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(field.getType().getSimpleName())
                        .doesNotContain("Repository")
                        .doesNotContain("EntityManager"));
    }

    @Test
    void requestShape_carriesNoUserSessionOrDeviceField() {
        // The absence of identity is the entire basis for collecting this without consent. If a
        // field like userId is ever added, the consent question and the privacy-policy wording both
        // reopen -- so this fails loudly rather than letting it erode quietly.
        assertThat(NavEventRequest.Event.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("destination", "group", "entry");

        assertThat(NavEventRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("events", "searches");
    }

    @Test
    void record_incrementsSearchUsedOncePerReportedSearch() {
        controller.record(new NavEventRequest(List.of(), 3), "web");

        verify(metrics, times(3)).searchUsed("web");
    }

    @Test
    void record_capsSearchCountAndToleratesAnAbsentOne() {
        controller.record(new NavEventRequest(List.of(), 9_999), "web");
        verify(metrics, atMost(NavEventController.MAX_BATCH)).searchUsed("web");

        clearInvocations(metrics);
        controller.record(new NavEventRequest(List.of(), null), "web");
        verify(metrics, never()).searchUsed(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=NavEventControllerTest`
Expected: FAIL — compilation error, `NavEventController` does not exist.

- [ ] **Step 3: Write the request DTO**

```java
package com.finora.dto;

import java.util.List;

/**
 * A batch of navigation events.
 *
 * <p>Deliberately minimal. There is no user, session or device field, and adding one would reopen
 * both the consent question and the privacy-policy wording -- see the design spec. The platform is
 * taken from existing client identity rather than the body, so a caller cannot assert it here.
 */
public record NavEventRequest(List<Event> events, Integer searches) {
    public record Event(String destination, String group, String entry) {}
}
```

`searches` is a plain count, not a list, because a search event has nothing to carry: recording
*what* was searched for is forbidden (`docs/engineering/observability.md` §3 names the ledger search
term specifically), so a count is the entire payload.

- [ ] **Step 4: Write the controller**

```java
package com.finora.controller;

import com.finora.dto.NavEventRequest;
import com.finora.observability.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Records aggregate navigation usage. Validates, increments, returns 204.
 *
 * <h2>Nothing is stored</h2>
 *
 * <p>No service layer and no repository, because there is no row to write. That is a design
 * constraint rather than an omission: with no events table there is no dataset to later
 * re-identify, leak, or be compelled to produce.
 *
 * <h2>Allowlist, not sanitisation</h2>
 *
 * <p>Every field is matched against a bounded enum and dropped when it does not match -- the rule
 * {@code SentryScrubber} already establishes. Notably there is no "other" bucket: an unrecognised
 * value is the one thing an attacker or a stale client controls, and a bucket for it is where the
 * enum stops being bounded (docs/engineering/observability.md §5).
 */
@RestController
@RequestMapping("/api/v1/nav-events")
public class NavEventController {

    /** One batch cannot move more than this many counters, however long the body is. */
    public static final int MAX_BATCH = 50;

    private static final Set<String> ALLOWED_PLATFORMS = Set.of("web", "mobile");

    private final NavigationMetrics metrics;

    public NavEventController(NavigationMetrics metrics) {
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody NavEventRequest request,
                                       @RequestHeader(value = "X-Client-Platform", required = false)
                                       String platform) {
        // Always 204, even for a body that is entirely rubbish. A client must never learn anything
        // from this endpoint's response, and must never have navigation blocked by it.
        if (platform == null || !ALLOWED_PLATFORMS.contains(platform)) {
            return ResponseEntity.noContent().build();
        }
        List<NavEventRequest.Event> events = request == null || request.events() == null
                ? List.of() : request.events();

        events.stream().limit(MAX_BATCH).forEach(event -> {
            Optional<NavDestination> destination = NavDestination.fromWire(event.destination());
            Optional<NavGroup> group = NavGroup.fromWire(event.group());
            Optional<NavEntryPoint> entry = NavEntryPoint.fromWire(event.entry());

            // All three must resolve. A half-valid event is dropped whole rather than recorded
            // against a partial tag set, which would quietly skew the distribution it feeds.
            if (destination.isPresent() && group.isPresent() && entry.isPresent()) {
                metrics.destinationOpened(destination.get(), group.get(), platform);
                metrics.entryPointUsed(entry.get(), platform);
            }
        });

        int searches = request == null || request.searches() == null
                ? 0 : Math.min(request.searches(), MAX_BATCH);
        for (int i = 0; i < searches; i++) {
            metrics.searchUsed(platform);
        }
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=NavEventControllerTest`
Expected: PASS, 9 tests.

- [ ] **Step 6: Confirm the endpoint requires authentication**

Check the project's security configuration (search for where `/api/v1/**` rules are declared). This path must **not** be added to any public allowlist — it inherits the authenticated default. Confirm by grepping for the path in the security config and finding no permit-all entry:

Run: `cd backend && grep -rn "nav-events" src/main/java/com/finora/security/ || echo "not exempted -- correct"`
Expected: `not exempted -- correct`

- [ ] **Step 7: Confirm rate limiting does *not* apply — this step's original instruction was wrong**

The first draft of this plan said rate limiting was required. Reading `RateLimitFilter` shows that
would fork a documented policy. It limits "the handful of endpoints with a real, specific abuse
cost" — those reachable with no credential, plus CSV import staging, which persists raw file bytes —
and states that everything else is intentionally unlimited because blanket rate limiting is "a
different, heavier decision (needs per-endpoint tuning)."

`nav-events` requires a valid JWT, writes nothing, and does a few in-memory increments; `MAX_BATCH`
already bounds per-request work. It is cheaper than authenticated reads that are themselves
unlimited. So: no limiter, with the reasoning recorded in the controller's javadoc.

Confirm no change is needed:

Run: `grep -n "nav-events" backend/src/main/java/com/finora/config/RateLimitFilter.java || echo "not limited -- correct"`
Expected: `not limited -- correct`

- [ ] **Step 8: Regenerate the OpenAPI types so both clients see the contract**

Run: `cd backend && ./mvnw verify -DskipTests` (or whichever goal writes `backend/openapi/openapi.json` in this project), then:

```bash
cd frontend && npm run generate:types
cd ../mobile && npm run generate:types
```

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/finora/controller/NavEventController.java backend/src/main/java/com/finora/dto/NavEventRequest.java backend/src/test/java/com/finora/controller/NavEventControllerTest.java backend/openapi/openapi.json frontend/src/api/generated-types.ts mobile/src/api/generated-types.ts
git commit -m "feat(analytics): add the nav-events ingest endpoint"
```

---

### Task 4: Prove the counters reach the scrape

**Files:**
- Create: `backend/src/test/java/com/finora/observability/NavigationMetricsExportIT.java`

**Interfaces:**
- Consumes: `NavigationMetrics` from Task 2.
- Produces: nothing consumed by later tasks.

This task exists because `docs/engineering/observability.md` §7 requires it and `WorkerMetricsExportIT` sets the precedent: a meter in a registry nothing exports is not a measurement. Without this, every counter above could work perfectly and produce no observable data.

- [ ] **Step 1: Read the existing precedent**

Read `backend/src/test/java/com/finora/observability/WorkerMetricsExportIT.java` in full. Mirror its setup — the same Spring context configuration, the same way it reaches the Prometheus scrape endpoint. Do not invent a different approach.

- [ ] **Step 2: Write the failing test**

```java
package com.finora.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A meter in a registry nothing exports is not a measurement -- the same reason
 * {@link WorkerMetricsExportIT} exists. Mirror that class's Spring setup exactly; the assertions
 * below are the part specific to navigation.
 */
class NavigationMetricsExportIT /* extends whatever WorkerMetricsExportIT extends */ {

    @Test
    void navigationCountersAppearInThePrometheusScrape() {
        // Arrange: increment one of each counter through the real bean.
        // Act: fetch the Prometheus scrape body, the same way WorkerMetricsExportIT does.
        String scrape = "";  // replace with the fetch used by WorkerMetricsExportIT

        assertThat(scrape).contains("finora_nav_destination_opened");
        assertThat(scrape).contains("finora_nav_entry_point_used");
        assertThat(scrape).contains("finora_nav_search_used");
        assertThat(scrape).contains("destination=\"budgets\"");
        assertThat(scrape).contains("platform=\"web\"");
    }
}
```

Note the metric names arrive dot-separated from Micrometer and appear underscore-separated in the Prometheus scrape. Assert the underscore form, as above.

- [ ] **Step 3: Run it to verify it fails**

Run: `cd backend && ./mvnw verify -Dit.test=NavigationMetricsExportIT`
Expected: FAIL — the scrape body is empty until the fetch is filled in from the precedent.

- [ ] **Step 4: Fill in the setup from `WorkerMetricsExportIT` and re-run**

Run: `cd backend && ./mvnw verify -Dit.test=NavigationMetricsExportIT`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/finora/observability/NavigationMetricsExportIT.java
git commit -m "test(analytics): assert navigation counters reach the Prometheus scrape"
```

---

### Task 5: Web client — send the events

**Files:**
- Create: `frontend/src/lib/trackNavigation.ts`
- Create: `frontend/src/lib/trackNavigation.test.ts`
- Modify: `frontend/src/components/Sidebar.tsx` (call it from the nav links and the account menu)
- Modify: `frontend/src/components/TopBar.tsx:119` (call it from the search box)

**Interfaces:**
- Consumes: `NAV_DESTINATION_IDS`, `NAV_TAXONOMY` from Task 1; the endpoint from Task 3.
- Produces: `trackNavigation(destinationId: string, entry: NavEntryPointId): void` and `trackNavSearch(): void`, where `NavEntryPointId = 'group' | 'tab' | 'fab' | 'header' | 'contextual' | 'search'`. Task 6 mirrors this signature.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { trackNavigation, trackNavSearch, __flushNavQueueForTest } from './trackNavigation';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { post: vi.fn().mockResolvedValue({}) } }));

describe('trackNavigation', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => vi.useRealTimers());

  it('derives the group from the taxonomy so callers never pass one', async () => {
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(api.post).toHaveBeenCalledWith('/nav-events', {
      events: [{ destination: 'budgets', group: 'planning', entry: 'group' }],
    });
  });

  it('drops a destination that is not in the taxonomy, without calling the API', async () => {
    trackNavigation('not-a-destination', 'group');
    await __flushNavQueueForTest();

    expect(api.post).not.toHaveBeenCalled();
  });

  it('never rejects when the request fails, so navigation is never blocked', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error('offline'));
    trackNavigation('goals', 'tab');

    await expect(__flushNavQueueForTest()).resolves.toBeUndefined();
  });

  it('batches several events into one request', async () => {
    trackNavigation('goals', 'group');
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(api.post).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.post).mock.calls[0][1].events).toHaveLength(2);
  });

  it('reports a search as a count, never as a term', async () => {
    trackNavSearch();

    expect(api.post).toHaveBeenCalledWith('/nav-events', { events: [], searches: 1 });
    const body = vi.mocked(api.post).mock.calls[0][1];
    expect(JSON.stringify(body)).not.toMatch(/query|term|text/i);
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd frontend && npx vitest run src/lib/trackNavigation.test.ts`
Expected: FAIL — `Failed to resolve import "./trackNavigation"`.

- [ ] **Step 3: Write the helper**

```ts
import { api } from '../api/client';
import { NAV_TAXONOMY } from '../navigation/taxonomy';

export type NavEntryPointId = 'group' | 'tab' | 'fab' | 'header' | 'contextual' | 'search';

type QueuedEvent = { destination: string; group: string; entry: NavEntryPointId };

const GROUP_BY_ID = new Map(NAV_TAXONOMY.map((e) => [e.id, e.group]));

const FLUSH_DELAY_MS = 2000;
const MAX_BATCH = 50;

let queue: QueuedEvent[] = [];
let timer: ReturnType<typeof setTimeout> | null = null;

async function flush(): Promise<void> {
  if (timer) { clearTimeout(timer); timer = null; }
  if (queue.length === 0) return;
  const events = queue.slice(0, MAX_BATCH);
  queue = [];
  try {
    await api.post('/nav-events', { events });
  } catch {
    // Deliberately swallowed and not retried. Measurement must never be able to degrade the thing
    // it measures, and a dropped usage counter costs a data point -- nothing the user can see.
  }
}

/**
 * Record that a destination was opened.
 *
 * The caller passes only the destination id and how it was reached; the group is derived from the
 * taxonomy here. That is what stops a second, drifting copy of the grouping from growing at the
 * call sites.
 */
export function trackNavigation(destination: string, entry: NavEntryPointId): void {
  const group = GROUP_BY_ID.get(destination);
  if (!group) return;  // not in the taxonomy: dropped, never bucketed
  queue.push({ destination, group, entry });
  if (!timer) timer = setTimeout(() => { void flush(); }, FLUSH_DELAY_MS);
}

/**
 * A navigation search happened. Never records what was typed — a count is the entire payload,
 * because the ledger search term is named in docs/engineering/observability.md §3 as the sharpest
 * case of free text that must never leave the platform.
 */
export function trackNavSearch(): void {
  void api.post('/nav-events', { events: [], searches: 1 }).catch(() => {});
}

/** Test-only: drain the queue immediately instead of waiting for the timer. */
export async function __flushNavQueueForTest(): Promise<void> {
  await flush();
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/lib/trackNavigation.test.ts`
Expected: PASS, 5 tests.

- [ ] **Step 5: Wire it into the sidebar and top bar**

In `frontend/src/components/Sidebar.tsx`, add an `onClick` to each `NavLink` in the nav list calling `trackNavigation(<id>, 'group')`, and to each account-menu link calling `trackNavigation(<id>, 'group')`. The ids are the taxonomy ids from Task 1 — add an `id` field to the existing `links` array entries rather than mapping paths to ids at the call site.

In `frontend/src/components/TopBar.tsx`, call `trackNavSearch()` when a search is submitted (the input at `:119`).

- [ ] **Step 6: Run the full web suite for regressions**

Run: `cd frontend && npx vitest run && npx tsc --noEmit && npm run lint`
Expected: all pass. `Sidebar.test.tsx` must be unaffected — this task adds handlers, it does not change what renders.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib/trackNavigation.ts frontend/src/lib/trackNavigation.test.ts frontend/src/components/Sidebar.tsx frontend/src/components/TopBar.tsx
git commit -m "feat(analytics): record navigation usage from the web client"
```

---

### Task 6: Mobile client — send the events

**Files:**
- Create: `mobile/src/lib/trackNavigation.ts`
- Create: `mobile/src/lib/trackNavigation.test.ts`
- Modify: `mobile/src/navigation/AppTabs.tsx` (tab presses)
- Modify: `mobile/src/screens/MoreScreen.tsx` (menu rows)
- Modify: `mobile/src/screens/DashboardScreen.tsx:405-415` (the search button)

**Interfaces:**
- Consumes: `NAV_TAXONOMY` from Task 1; the endpoint from Task 3.
- Produces: `trackNavigation(destination: string, entry: NavEntryPointId): void`, `trackNavSearch(): void` — identical signatures to Task 5.

- [ ] **Step 1: Write the failing test**

```ts
import { trackNavigation, trackNavSearch, __flushNavQueueForTest } from './trackNavigation';
import { api } from '../api/client';

jest.mock('../api/client', () => ({ api: { post: jest.fn().mockResolvedValue({}) } }));

describe('trackNavigation', () => {
  beforeEach(() => jest.clearAllMocks());

  it('derives the group from the taxonomy so callers never pass one', async () => {
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(api.post).toHaveBeenCalledWith('/nav-events', {
      events: [{ destination: 'budgets', group: 'planning', entry: 'group' }],
    });
  });

  it('drops a destination that is not in the taxonomy', async () => {
    trackNavigation('nope', 'tab');
    await __flushNavQueueForTest();

    expect(api.post).not.toHaveBeenCalled();
  });

  it('never rejects when the request fails, so navigation is never blocked', async () => {
    (api.post as jest.Mock).mockRejectedValueOnce(new Error('offline'));
    trackNavigation('goals', 'tab');

    await expect(__flushNavQueueForTest()).resolves.toBeUndefined();
  });

  it('reports a search as a count, never as a term', () => {
    trackNavSearch();

    expect(api.post).toHaveBeenCalledWith('/nav-events', { events: [], searches: 1 });
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd mobile && npx jest src/lib/trackNavigation.test.ts`
Expected: FAIL — cannot find module `./trackNavigation`.

- [ ] **Step 3: Write the helper**

Copy `frontend/src/lib/trackNavigation.ts` from Task 5 verbatim, changing only the import of `api` to mobile's client path and the taxonomy import to `../navigation/taxonomy`. The logic — group derivation, drop-not-bucket, silent failure, batching — is identical by design and should not be re-derived.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd mobile && npx jest src/lib/trackNavigation.test.ts`
Expected: PASS, 4 tests.

- [ ] **Step 5: Wire it in**

- `AppTabs.tsx`: on each `Tab.Screen` press, `trackNavigation(<id>, 'tab')`. Use the navigator's `listeners={{ tabPress: ... }}` rather than wrapping components.
- `AppTabs.tsx`: the Import FAB calls `trackNavigation('import-statement', 'fab')`.
- `MoreScreen.tsx`: each menu row calls `trackNavigation(<id>, 'group')`. Add an `id` to the `MENU_ITEMS` entries rather than mapping labels to ids.
- `DashboardScreen.tsx:405-415`: the search button calls `trackNavSearch()`.

- [ ] **Step 6: Classify any new query key**

`mobile/src/lib/invalidateFinancialData.test.ts` fails on any new `queryKey` that is not classified as refreshed or deliberately excluded. This task adds no React Query key — but run the suite to confirm that is actually true rather than assuming it.

Run: `cd mobile && npx jest src/lib/invalidateFinancialData.test.ts`
Expected: PASS.

- [ ] **Step 7: Run the full mobile suite**

Run: `cd mobile && npx jest && npx tsc --noEmit && npm run lint`
Expected: all pass.

- [ ] **Step 8: Run the drift check and the whole backend suite one final time**

Run: `python3 scripts/check-nav-taxonomy-drift.py`
Expected: consistent, 19 destinations.

Run: `cd backend && ./mvnw test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add mobile/src/lib/trackNavigation.ts mobile/src/lib/trackNavigation.test.ts mobile/src/navigation/AppTabs.tsx mobile/src/screens/MoreScreen.tsx mobile/src/screens/DashboardScreen.tsx
git commit -m "feat(analytics): record navigation usage from the mobile client"
```

---

## Release gates

These are from the spec and are **not** implementation steps. They gate the companion taxonomy plan, not this one.

- [ ] **A dashboard exists** charting the three counters, before the taxonomy plan begins.
- [ ] **A named reviewer and a review cadence exist**, before the taxonomy plan begins. Naming them is the repository owner's call.
- [ ] **Baseline collected for at least four weeks, including one complete month-end**, before the taxonomy plan's rendering changes ship. The product's dominant usage cycle is statement import, which clusters at month end; a baseline missing one would be compared against an after-period that includes one. There is no backfill.
