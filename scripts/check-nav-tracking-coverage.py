#!/usr/bin/env python3
"""Fails when a client navigates to a taxonomy destination without reporting it.

This is the companion to check-nav-taxonomy-drift.py, and it exists because of a bug that shipped
twice. `destination_opened` documents itself as "a destination was opened, however it was reached",
but the original instrumentation only covered the sidebar, the tab bar and the FAB. Every
in-content link -- a dashboard card, an empty-state CTA, a "View all" -- opened a destination and
recorded nothing.

That failure is invisible in a way a missing destination is not. A destination nothing ever emits
reads as a permanent zero, which is at least conspicuous. A destination counted on one path and not
another just reads as a smaller number, and the four-week baseline it biases cannot be recollected
after the window closes.

Coverage is judged by proximity: a trackNavigation call on the navigating line, or within
CONTEXT_LINES above it, counts as reporting that navigation. That is a heuristic, deliberately --
the alternative is parsing TSX, and a check nobody can read is worse than one that occasionally
asks for an EXCLUSIONS entry.

Run with --self-test to check the matchers against inline fixtures without touching the repo.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "frontend/src"
MOBILE = ROOT / "mobile/src"
TAXONOMY = ROOT / "frontend/src/navigation/taxonomy.ts"

CONTEXT_LINES = 6
MAX_JSX_TAG_LINES = 12

# Web routes that are taxonomy destinations. Routes absent here (/app/journey, /app/wrapped,
# /app/imports/:jobId) are real screens that the taxonomy deliberately does not name.
WEB_ROUTES = {
    "/app": "home",
    "/app/accounts": "accounts",
    "/app/transactions": "transactions",
    "/app/import": "import-statement",
    "/app/statements": "statement-history",
    "/app/financial-memory": "financial-memory",
    "/app/budgets": "budgets",
    "/app/goals": "goals",
    "/app/investments": "investments",
    "/app/insights": "insights",
    "/app/reports": "reports",
    "/app/reports/advanced": "advanced-reports",
    "/app/profile": "profile",
    "/app/billing": "subscription",
    "/app/referrals": "referrals",
    "/app/settings": "settings",
    "/app/support": "support",
}

# Mobile screen names (navigation/AppTabs.tsx) that are taxonomy destinations.
MOBILE_SCREENS = {
    "Home": "home",
    "Transactions": "transactions",
    "Import": "import-statement",
    "Insights": "insights",
    "Accounts": "accounts",
    "CategoryReview": "review-categories",
    "Statements": "statement-history",
    "Budgets": "budgets",
    "Subscription": "subscription",
    "Reports": "reports",
    "AdvancedReports": "advanced-reports",
    "Fyn": "ask-fyn",
    "Goals": "goals",
    "Investments": "investments",
    "FinancialMemory": "financial-memory",
    "Profile": "profile",
    "Settings": "settings",
    "Referrals": "referrals",
    "SupportTickets": "support",
}

# Navigations that are deliberately NOT reported, each keyed by a snippet that must still match
# exactly once in that file -- a stale exclusion fails the check rather than quietly permitting a
# new gap. Two kinds qualify, and only these two:
#
#   * a redirect, where a completed action decides the destination rather than the person, and
#   * an arrival from outside the app (a push notification, an OS share sheet).
#
# Neither has an affordance behind it, so no NavEntryPointId describes it honestly, and counting
# either would credit a destination with opens nobody navigated to.
EXCLUSIONS = [
    ("frontend/src/pages/AccountAggregatorConfirm.tsx",
     "// Deliberately NOT reported to navigation analytics (same for handleConfirmNew below): this"),
    ("frontend/src/pages/AccountAggregatorConfirm.tsx",
     "await accountAggregatorApi.confirmNewAccount(linkId);"),
    ("frontend/src/pages/VerifyPhone.tsx",
     "// Not reported to navigation analytics -- nor are the two post-verification redirects below."),
    ("frontend/src/pages/VerifyPhone.tsx", "const completed = await phoneChangeApi.complete(changeSessionId);"),
    ("frontend/src/pages/VerifyPhone.tsx", "await phoneApi.verify(idToken);"),
    ("mobile/src/navigation/usePushNotificationNavigation.ts",
     "// Not reported to navigation analytics: a notification tap enters from outside the app, so"),
    ("mobile/src/navigation/useShareIntentDeepLink.ts",
     "// Untracked, same reason as usePushNotificationNavigation: an OS share sheet is not an"),
    ("mobile/src/navigation/useShareIntentDeepLink.ts",
     "// Untracked, same reason as the error branch above: an OS share sheet is not an in-app"),
    ("mobile/src/navigation/AppTabs.tsx", "onAddTransaction={"),
]

WEB_NAV = re.compile(r"""(?:to|href)=["'](/app[a-z/\-]*)(?:\?[^"']*)?["']|navigate\(\s*[`"'](/app[a-z/\-]*)""")
MOBILE_NAV = re.compile(r"""navigate\(\s*["']More["']\s*,\s*\{\s*screen:\s*["']([A-Za-z]+)["']""")
MOBILE_NAV_DIRECT = re.compile(r"""navigate\(\s*["']([A-Za-z]+)["']""")
TAXONOMY_ID = re.compile(r"id: '([a-z-]+)'")


def web_destinations(line):
    out = []
    for m in WEB_NAV.finditer(line):
        dest = WEB_ROUTES.get(m.group(1) or m.group(2))
        if dest:
            out.append(dest)
    return out


def jsx_attribute(line):
    """True when the navigation is a `to=`/`href=` prop rather than a navigate() statement."""
    return bool(re.search(r"""(?:to|href)=["']/app""", line))


def reach(lines, i):
    """The lines a trackNavigation call for the navigation on line `i` could plausibly live on.

    Backwards by CONTEXT_LINES, to catch a handler that reports before it navigates. Forwards only
    to the end of the JSX tag, because a `to=` prop is conventionally written above the `onClick`
    that reports it -- and stopping at the tag's `>` is what keeps the forward look from finding an
    unrelated call in the next element and calling this one covered.
    """
    window = lines[max(0, i - CONTEXT_LINES):i + 1]
    if jsx_attribute(lines[i]) and ">" not in lines[i]:
        for line in lines[i + 1:i + 1 + MAX_JSX_TAG_LINES]:
            window.append(line)
            if ">" in line:
                break
    return window


def mobile_destinations(line):
    names = [m.group(1) for m in MOBILE_NAV.finditer(line)]
    if not names:
        names = [m.group(1) for m in MOBILE_NAV_DIRECT.finditer(line)]
    return [MOBILE_SCREENS[n] for n in names if n in MOBILE_SCREENS]


def scan(root, matcher, excluded_windows):
    """Every navigation in `root` with no trackNavigation call in reach of it."""
    gaps = []
    for path in sorted(root.rglob("*.ts*")):
        if ".test." in path.name:
            continue
        rel = str(path.relative_to(ROOT))
        lines = path.read_text().splitlines()
        for i, line in enumerate(lines):
            for dest in matcher(line):
                window = reach(lines, i)
                if any("trackNavigation" in w for w in window):
                    continue
                if any(snippet in w for f, snippet in excluded_windows
                       if f == rel for w in window):
                    continue
                gaps.append((rel, i + 1, dest, line.strip()[:90]))
    return gaps


def self_test():
    assert web_destinations('<Link to="/app/goals" className="x">') == ["goals"]
    assert web_destinations("void navigate('/app/import', { state })") == ["import-statement"]
    assert web_destinations('<Link to="/app/journey">') == []
    assert web_destinations('to="/app/settings?tab=bank-sync"') == ["settings"]
    assert mobile_destinations("navigation.navigate('More', { screen: 'Goals' })") == ["goals"]
    assert mobile_destinations("navigation.navigate('Import')") == ["import-statement"]
    assert mobile_destinations("navigation.navigate('Journey')") == []
    # A 'More' navigation must resolve to the nested screen, never to the stack name itself.
    assert mobile_destinations("navigate('More', { screen: 'Wrapped' })") == []
    # A `to=` prop reaches forward to the end of its own tag, and no further.
    multi = ["<Link", '  to="/app/goals"', "  onClick={() => trackNavigation('goals', 'contextual')}", ">"]
    assert any("trackNavigation" in w for w in reach(multi, 1))
    closed = ['<Link to="/app/goals" className="x">', "</Link>", "<b onClick={() => trackNavigation('x', 'y')} />"]
    assert not any("trackNavigation" in w for w in reach(closed, 0))
    print("self-test OK")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    failures = []

    taxonomy = set(TAXONOMY_ID.findall(TAXONOMY.read_text()))
    if not taxonomy:
        failures.append(f"parsed no destinations from {TAXONOMY} -- the parser or the file changed")
    for name, table in (("WEB_ROUTES", WEB_ROUTES), ("MOBILE_SCREENS", MOBILE_SCREENS)):
        unknown = sorted(set(table.values()) - taxonomy)
        if unknown:
            failures.append(f"{name} maps to ids that are not in the taxonomy: {unknown}")

    # A stale exclusion is a silent permission slip, so verify each one still matches its file.
    for rel, snippet in EXCLUSIONS:
        path = ROOT / rel
        if not path.exists():
            failures.append(f"EXCLUSIONS names a file that no longer exists: {rel}")
            continue
        found = path.read_text().count(snippet)
        if found != 1:
            failures.append(
                f"EXCLUSIONS entry for {rel} matched {found} times, expected 1: {snippet!r}\n"
                "  the code moved -- re-check whether that navigation should now be reported"
            )

    gaps = scan(WEB, web_destinations, EXCLUSIONS) + scan(MOBILE, mobile_destinations, EXCLUSIONS)
    for rel, line, dest, code in gaps:
        failures.append(
            f"{rel}:{line} opens '{dest}' without a trackNavigation call in reach\n"
            f"  {code}\n"
            "  add trackNavigation('<destination>', '<entry point>') to the handler, or add an "
            "EXCLUSIONS entry with the reason it is not a navigation anyone chose"
        )

    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    if failures:
        return 1

    print(f"nav tracking covers every navigation to the {len(taxonomy)} taxonomy destinations "
          f"({len(EXCLUSIONS)} documented exclusions)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
