#!/usr/bin/env python3
"""Fails when the two clients' nav taxonomies, or the backend's NavDestination enum, disagree.

Three copies exist because there is no shared package between frontend and mobile, and the backend
needs the enum in Java to validate the nav-events ingest endpoint. This script is what makes three
copies safe: a destination added to one and not the others is a silent measurement gap, not a build
error -- it simply stops being counted, with nothing anywhere reporting a problem.

Run with --self-test to check the parsers against inline fixtures without touching the repo.
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
    # The backend enum lands in a later task than the clients' taxonomy. Until it exists this check
    # verifies the two clients only, rather than failing on a file that is not due yet.
    backend = parse_java(BACKEND.read_text()) if BACKEND.exists() else None

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

    if backend is not None:
        web_ids, backend_ids = sorted(i for i, _ in web), sorted(backend)
        if web_ids != backend_ids:
            failures.append(
                "clients and backend NavDestination disagree.\n"
                f"  only in clients: {sorted(set(web_ids) - set(backend_ids))}\n"
                f"  only in backend: {sorted(set(backend_ids) - set(web_ids))}\n"
                "  a destination missing from the backend enum is REJECTED at ingest and silently "
                "unmeasured"
            )

    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    if failures:
        return 1

    scope = "web, mobile and backend" if backend is not None else "web and mobile (backend enum not present yet)"
    print(f"nav taxonomy consistent across {scope} ({len(web)} destinations)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
