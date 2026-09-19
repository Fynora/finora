#!/usr/bin/env python3
"""Classifies a PR/push's changed files into named areas, for ci.yml's job-level path filtering.

WHY THIS EXISTS
---------------
ci.yml's Backend and User frontend jobs used to bundle repo-wide safety checks (the customer PII
scanner, the cross-platform auth-policy check, contact-address and reporting-period-label
consistency across all three clients) alongside their own build. Filtering either of those two
jobs by path would have silently skipped a check that scans every app regardless of which one a
given PR touched -- exactly the "a check went quietly vacant" failure shape this repo has already
shipped real incidents from -- so both stayed unconditional until ci.yml's repo-hygiene job
existed to hold those checks unconditionally instead. With that job in place, this script gates
every build/test job in the file except secret-scan: admin-portal, mobile, duplication-scan
(jscpd), reconciliation-benchmark, smoke, openapi-contract-check, backend, and frontend.
secret-scan stays unconditional on purpose -- it needs to see every push/PR's diff regardless of
which paths changed (see that job's own comment), not a subset any area-based filter could
narrow. repo-hygiene itself is also unconditional, by construction: it's the thing every other
job's filtering safety depends on, so it can't be gated by the same mechanism it makes possible.

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
    # frontend/public/.well-known/ is the web app's half of the mobile app-links setup (the files
    # iOS/Android fetch to confirm the app may open emailed https links) -- mobile's
    # src/lib/appLinks.seam.test.ts reads them and fails if they drift from app.config.ts. Without
    # this prefix a PR that edited only those files would skip the one job that checks them.
    "mobile": ("mobile/", "frontend/public/.well-known/"),
    # Whole-directory, not just admin-portal/src -- .jscpd.json's own path config only scans
    # frontend/src and admin-portal/src, but gating jscpd on the broader "did frontend/ change at
    # all" is the safe direction to be imprecise in: it can only make jscpd run a little more
    # often than the strict minimum, never less.
    "frontend": ("frontend/",),
    "backend": ("backend/",),
    # The files that change what the backend CONTAINER is, as opposed to what the Java code does.
    # `backend` above already covers every one of these paths, but it also fires for a one-line
    # change to a service class -- and building the whole image (a full `mvn package` inside
    # Docker) for that costs several runner-minutes to verify nothing the change could have
    # affected. This narrower area gates ci.yml's backend-image job on the things that can
    # actually break an image build: the Dockerfile itself (base-image digests, apk packages),
    # its entrypoint, the deploy config that selects the Dockerfile builder, and pom.xml (a
    # dependency or plugin change is the one source edit that can make `mvn package` fail where
    # `mvn test` passed). Prefix-matched like every other entry, so backend/Dockerfile also
    # matches a future backend/Dockerfile.something.
    "backend_image": (
        "backend/Dockerfile",
        "backend/.dockerignore",
        "backend/docker-entrypoint.sh",
        "backend/railway.json",
        "backend/pom.xml",
        # The job's own check script. Without this, a PR that edited only the script -- the file
        # that decides whether the image job passes -- would skip the job that runs it: the same
        # "a check's own config changed and the check did not run" gap SHARED_CONFIG_FILES below
        # was created for.
        "scripts/check-backend-image.sh",
    ),
    "e2e": ("e2e/",),
}

# Any changed path under one of these forces every area to `true`, regardless of what else
# changed -- see the module docstring's second reason above.
ALWAYS_RUN_EVERYTHING_IF_CHANGED = (
    ".github/workflows/ci.yml",
    "scripts/detect-changed-areas.py",
)

# Files that sit outside every AREAS prefix but are read by more than one filtered job -- an exact
# match here sets a separate `shared_config` output that every filtered job's `if:` in ci.yml ORs
# in alongside its own area, rather than being folded into any one area's prefix list. Found by
# testing this script against a real historical commit that only touched .jscpd.json: the naive
# prefix-only version below classified it as "nothing changed" and would have skipped
# duplication-scan on a change to duplication-scan's own config.
SHARED_CONFIG_FILES = (
    ".jscpd.json",                                 # duplication-scan's own config
    "package.json",                                # root -- `npm run dupes` runs from here
    "package-lock.json",                           # root -- same
    "scripts/check-dependency-advisories.py",       # called by both the admin-portal and mobile jobs
    "scripts/check-reconciliation-benchmark.py",    # the reconciliation-benchmark job's own gate script
)


def classify(changed_paths):
    """changed_paths: an iterable of repo-relative path strings (forward slashes, as git diff
    --name-only produces). Returns {area_name: bool}, area names being every key in AREAS plus
    "shared_config". Pure function, no git/subprocess -- the self-test below exercises this
    directly with synthetic paths, the same "test the mechanism with synthetic data" shape every
    other guard script in this repo already uses."""
    changed = list(changed_paths)

    if any(p in ALWAYS_RUN_EVERYTHING_IF_CHANGED for p in changed):
        return {area: True for area in (*AREAS, "shared_config")}

    return {
        **{
            area: any(p.startswith(prefix) for p in changed for prefix in prefixes)
            for area, prefixes in AREAS.items()
        },
        "shared_config": any(p in SHARED_CONFIG_FILES for p in changed),
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


NOTHING_CHANGED = {"admin_portal": False, "mobile": False, "frontend": False, "backend": False,
                    "backend_image": False, "e2e": False, "shared_config": False}
EVERYTHING_CHANGED = {area: True for area in NOTHING_CHANGED}


def self_test():
    cases = [
        ("backend-only change", ["backend/src/main/java/com/finora/entity/Transaction.java"],
         {**NOTHING_CHANGED, "backend": True}),
        # backend_image is deliberately narrower than backend: the Java-only case above must NOT
        # build the container image (it asserts backend_image stays false), while each file that
        # actually shapes the image must.
        ("the Dockerfile sets backend_image as well as backend", ["backend/Dockerfile"],
         {**NOTHING_CHANGED, "backend": True, "backend_image": True}),
        ("the container entrypoint sets backend_image as well as backend",
         ["backend/docker-entrypoint.sh"],
         {**NOTHING_CHANGED, "backend": True, "backend_image": True}),
        ("the deploy config that selects the Dockerfile builder sets backend_image",
         ["backend/railway.json"],
         {**NOTHING_CHANGED, "backend": True, "backend_image": True}),
        ("pom.xml sets backend_image -- a dependency change can break `mvn package` alone",
         ["backend/pom.xml"],
         {**NOTHING_CHANGED, "backend": True, "backend_image": True}),
        ("the image job's own check script sets backend_image alone, not backend",
         ["scripts/check-backend-image.sh"],
         {**NOTHING_CHANGED, "backend_image": True}),
        ("mobile-only change", ["mobile/src/screens/LedgerScreen.tsx"],
         {**NOTHING_CHANGED, "mobile": True}),
        ("an app-links association file is read by mobile's tests too, so it sets mobile as well as frontend",
         ["frontend/public/.well-known/assetlinks.json"],
         {**NOTHING_CHANGED, "mobile": True, "frontend": True}),
        ("admin-portal and e2e together", ["admin-portal/src/pages/Users.tsx", "e2e/tests/workflow/smoke.spec.ts"],
         {**NOTHING_CHANGED, "admin_portal": True, "e2e": True}),
        ("docs-only change touches nothing", ["docs/engineering/openapi-contracts.md"],
         NOTHING_CHANGED),
        ("ci.yml itself forces every area true even with an otherwise-narrow diff",
         [".github/workflows/ci.yml", "mobile/src/screens/LedgerScreen.tsx"],
         EVERYTHING_CHANGED),
        ("this script changing also forces everything true",
         ["scripts/detect-changed-areas.py"],
         EVERYTHING_CHANGED),
        ("no changed paths at all -- e.g. an empty diff -- flags nothing",
         [], NOTHING_CHANGED),
        # A prefix guard, not a substring match: a hypothetical top-level file or directory whose
        # name merely STARTS WITH an area's name (e.g. "mobile-web/") must not be misclassified as
        # that area just because the string "mobile/" is a prefix of "mobile-web/x" -- wait, it
        # actually would be, since "mobile-web/x".startswith("mobile/") is False (the '-' breaks
        # the match at the '/' boundary) -- this case exists to pin that down explicitly rather
        # than rely on it being obviously true.
        ("a similarly-named sibling directory is not misclassified",
         ["mobile-web/README.md"],
         NOTHING_CHANGED),
        # The actual gap found by running this script against a real historical commit
        # (7a8f0887, which only touched .jscpd.json) before this case and SHARED_CONFIG_FILES
        # existed: a prefix-only classifier said nothing changed, which would have skipped
        # duplication-scan on a change to duplication-scan's own config.
        (".jscpd.json alone sets shared_config, not any directory area",
         [".jscpd.json"],
         {**NOTHING_CHANGED, "shared_config": True}),
        ("a shared script both admin-portal and mobile call sets shared_config",
         ["scripts/check-dependency-advisories.py"],
         {**NOTHING_CHANGED, "shared_config": True}),
        ("shared_config and an unrelated area can both be true from one diff",
         [".jscpd.json", "backend/src/main/java/com/finora/entity/Account.java"],
         {**NOTHING_CHANGED, "backend": True, "shared_config": True}),
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
        write_github_output({area: True for area in (*AREAS, "shared_config")})
        return 0

    write_github_output(classify(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
