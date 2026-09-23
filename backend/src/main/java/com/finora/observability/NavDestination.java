package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/**
 * The bounded set of navigation destinations, and the wire contract for POST /api/v1/nav-events.
 *
 * <p>Mirrors {@code frontend/src/navigation/taxonomy.ts} and
 * {@code mobile/src/navigation/taxonomy.ts}; {@code scripts/check-nav-taxonomy-drift.py} fails CI
 * when the three disagree. A destination missing here is rejected at ingest and therefore silently
 * unmeasured -- which is exactly the failure that check exists to prevent, since nothing else in
 * the system would report it.
 *
 * <p>{@link #fromWire} returns empty rather than a fallback constant, deliberately. An "other"
 * bucket is where a bounded enum stops being bounded -- see
 * {@code docs/engineering/observability.md} §5, which requires every tag value to be an internal
 * identifier or a bounded enum, never customer data. The unrecognised value is the one thing a
 * stale client or an attacker controls, so it is dropped rather than recorded.
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
