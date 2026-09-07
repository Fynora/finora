#!/usr/bin/env python3
"""Classifies a PR/push's changed files into named areas, for ci.yml's job-level path filtering.

WHY THIS EXISTS
---------------
ci.yml's Backend and User frontend jobs both bundle repo-wide safety checks (the customer PII
scanner, the cross-platform auth-policy check, contact-address and reporting-period-label
consistency across all three clients) alongside their own build -- see those jobs' own comments.
Filtering EITHER of those two jobs by path would silently skip a check that scans every app
regardless of which one a given PR touched, which is exactly the "a check went quietly vacant"
failure shape this repo has already shipped real incidents from. So this script -- and the `if:`
conditions in ci.yml that consume its output -- only ever gates jobs confirmed to have no
cross-cutting steps: admin-portal, mobile, duplication-scan (jscpd), reconciliation-benchmark,
smoke, and openapi-contract-check. Backend, frontend, and secret-scan always run, unconditionally,
on purpose.

Not a third-party GitHub Action (e.g. dorny/paths-filter): this repo's own stated policy, on the
one third-party action it does use, is that gitleaks/gitleaks-action is deliberately the ONLY
exception to "everything else stays actions/*, github/*, or hand-written" -- see ci.yml's
secret-scan job comment. Adding a second third-party action to save writing ~80 lines of Python
would go against a policy this repo has already written down, not just skip an opportunity to
follow it.

WHY A SEPARATE "EVERYTHING CHANGED" ESCAPE HATCH
--------------------------------------------------
Two situations must never be classified narrowly, or a real change could ship with its own
validation silently skipped:

  1. ci.yml or this script itself changed. A path-filtering bug is exactly the kind of change
     that needs every job to actually run, to prove the new filtering didn't just break something
     while looking clean.
  2. The diff range can't be computed at all -- workflow_dispatch has no meaningful before/after,
     and a force-push or a branch's first-ever push can leave `before` pointing at a commit this
     checkout never fetched (see BASE_UNAVAILABLE below). Fail toward running MORE checks, not
     fewer, the same direction every other guard script in this repo already fails.
"""

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Order doesn't matter for correctness (each area is matched independently against every changed
# path), but keeping it alongside the `if:` conditions in ci.yml that read these same names makes
# the two easy to eyeball against each other.
AREAS = {
    "admin_portal": ("admin-portal/",),
    "mobile": ("mobile/",),
    # Whole-directory, not just admin-portal/src -- .jscpd.json's own path config only scans
    # frontend/src and admin-portal/src, but gating jscpd on the broader "did frontend/ change at
    # all" is the safe direction to be imprecise in: it can only make jscpd run a little more
    # often than the strict minimum, never less.
    "frontend": ("frontend/",),
    "backend": ("backend/",),
    "e2e": ("e2e/",),
}

# Any changed path under one of these forces every area to `true`, regardless of what else
# changed -- see the module docstring's second reason above.
ALWAYS_RUN_EVERYTHING_IF_CHANGED = (
    ".github/workflows/ci.yml",
    "scripts/detect-changed-areas.py",
)


def classify(changed_paths):
    """changed_paths: an iterable of repo-relative path strings (forward slashes, as git diff
    --name-only produces). Returns {area_name: bool}. Pure function, no git/subprocess -- the
    self-test below exercises this directly with synthetic paths, the same "test the mechanism
    with synthetic data" shape every other guard script in this repo already uses."""
    changed = list(changed_paths)

    if any(p in ALWAYS_RUN_EVERYTHING_IF_CHANGED for p in changed):
        return {area: True for area in AREAS}

    return {
        area: any(p.startswith(prefix) for p in changed for prefix in prefixes)
        for area, prefixes in AREAS.items()
    }


def changed_files_between(base_ref, head_ref):
    """Returns None (not an empty list) when the range can't be computed at all, so the caller can
    tell "confirmed nothing in these areas changed" apart from "we don't actually know" -- an
    empty list here would otherwise be indistinguishable from a real, fully-empty diff, and would
    make every downstream job skip on exactly the runs where that's least safe."""
    # A missing/unreachable base (force-push, a branch's first push, or workflow_dispatch's
    # synthetic before/after) makes git diff fail rather than silently return nothing -- checked
    # explicitly here so that failure reads as "treat as everything changed" instead of a
    # generic non-zero exit further down confusing whoever reads the Actions log.
    check = subprocess.run(
        ["git", "cat-file", "-e", base_ref],
        cwd=REPO_ROOT, capture_output=True,
    )
    if check.returncode != 0:
        return None

    result = subprocess.run(
        ["git", "diff", "--name-only", base_ref, head_ref],
        cwd=REPO_ROOT, capture_output=True, text=True,
    )
    if result.returncode != 0:
        return None
    return [line for line in result.stdout.splitlines() if line]


def write_github_output(areas):
    for name, changed in areas.items():
        print(f"{name}={'true' if changed else 'false'}")


def self_test():
    cases = [
        ("backend-only change", ["backend/src/main/java/com/finora/entity/Transaction.java"],
         {"admin_portal": False, "mobile": False, "frontend": False, "backend": True, "e2e": False}),
        ("mobile-only change", ["mobile/src/screens/LedgerScreen.tsx"],
         {"admin_portal": False, "mobile": True, "frontend": False, "backend": False, "e2e": False}),
        ("admin-portal and e2e together", ["admin-portal/src/pages/Users.tsx", "e2e/tests/workflow/smoke.spec.ts"],
         {"admin_portal": True, "mobile": False, "frontend": False, "backend": False, "e2e": True}),
        ("docs-only change touches nothing", ["docs/engineering/openapi-contracts.md"],
         {"admin_portal": False, "mobile": False, "frontend": False, "backend": False, "e2e": False}),
        ("ci.yml itself forces every area true even with an otherwise-narrow diff",
         [".github/workflows/ci.yml", "mobile/src/screens/LedgerScreen.tsx"],
         {"admin_portal": True, "mobile": True, "frontend": True, "backend": True, "e2e": True}),
        ("this script changing also forces everything true",
         ["scripts/detect-changed-areas.py"],
         {"admin_portal": True, "mobile": True, "frontend": True, "backend": True, "e2e": True}),
        ("no changed paths at all -- e.g. an empty diff -- flags nothing",
         [], {"admin_portal": False, "mobile": False, "frontend": False, "backend": False, "e2e": False}),
        # A prefix guard, not a substring match: a hypothetical top-level file or directory whose
        # name merely STARTS WITH an area's name (e.g. "mobile-web/") must not be misclassified as
        # that area just because the string "mobile/" is a prefix of "mobile-web/x" -- wait, it
        # actually would be, since "mobile-web/x".startswith("mobile/") is False (the '-' breaks
        # the match at the '/' boundary) -- this case exists to pin that down explicitly rather
        # than rely on it being obviously true.
        ("a similarly-named sibling directory is not misclassified",
         ["mobile-web/README.md"],
         {"admin_portal": False, "mobile": False, "frontend": False, "backend": False, "e2e": False}),
    ]

    failed = False
    for description, paths, expected in cases:
        actual = classify(paths)
        if actual != expected:
            failed = True
            print(f"FAILED: {description}", file=sys.stderr)
            print(f"  paths:    {paths}", file=sys.stderr)
            print(f"  expected: {expected}", file=sys.stderr)
            print(f"  actual:   {actual}", file=sys.stderr)

    if failed:
        print("detect-changed-areas self-test FAILED", file=sys.stderr)
        return 1
    print(f"detect-changed-areas self-test passed ({len(cases)} cases).")
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    args = [a for a in sys.argv[1:] if a != "--self-test"]
    if len(args) != 2:
        print("usage: detect-changed-areas.py <base-ref> <head-ref>", file=sys.stderr)
        return 2

    base_ref, head_ref = args
    changed = changed_files_between(base_ref, head_ref)
    if changed is None:
        print(
            f"Could not compute a diff between {base_ref} and {head_ref} -- "
            "treating every area as changed rather than guessing.",
            file=sys.stderr,
        )
        write_github_output({area: True for area in AREAS})
        return 0

    write_github_output(classify(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
