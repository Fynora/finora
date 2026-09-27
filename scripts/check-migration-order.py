#!/usr/bin/env python3
"""Fails when a branch adds a Flyway migration whose version is not above main's highest.

WHY THIS EXISTS
---------------
On 2026-09-27 #1798 (V236) merged and deployed to Production before #1811 (V235) merged. Flyway
runs with outOfOrder=false (the default -- nothing in backend/ sets it), so once production's
history held 236, every later deploy failed validation with

    Validate failed: Detected resolved migration not applied to database: 235

and nothing could ship until the file was renamed V236_1 (#1818). CI could not see it coming: every
job migrates a FRESH database, where 235-then-236 applies cleanly. Order relative to what is
already deployed is never tested anywhere else. Production is deployed from main, so main's
highest version is the floor every new migration must clear.

WHAT IT CHECKS
--------------
Every migration file the branch ADDS (added, copied, or the new side of a rename) relative to
merge-base(<base>, <head>) must have a version strictly greater than the highest version in
<base>'s tree. <base> is origin/main as fetched when the check runs, not the PR's base at creation:
a PR that was fine when opened must fail once a higher version lands on main.

Diffing from the merge base, rather than comparing the two trees, is what keeps a branch cut
before a rename landed on main from being blamed for "adding" the old name back: squash-merging it
applies only its own diff, which does not contain that file.

A rename counts as an add of the new name. That is what lets #1818's V235 -> V236_1 pass (236.1 is
above 236) while a rename to a version at or below main's highest still fails.

Versions are parsed and compared the way Flyway's MigrationVersion does: `_` and `.` both separate
parts, each part is an integer, parts compare numerically left to right, and trailing zero parts
are insignificant (V236_0 == V236). Same filename pattern as FlywayMigrationVersionUniquenessTest.

Usage: check-migration-order.py [--base origin/main] [--head HEAD]
"""

import argparse
import re
import subprocess
import sys
from pathlib import PurePosixPath

MIGRATION_DIR = "backend/src/main/resources/db/migration"

VERSIONED_MIGRATION = re.compile(r"V([0-9]+(?:[._][0-9]+)*)__.*\.sql")


def parse_version(filename):
    """Flyway version of a versioned-migration basename as a comparable int tuple, or None.

    Trailing zero parts are dropped so that V236_0 and V236 compare equal, as they do in Flyway."""
    m = VERSIONED_MIGRATION.fullmatch(filename)
    if not m:
        return None
    parts = [int(p) for p in re.split(r"[._]", m.group(1))]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def format_version(version):
    return ".".join(str(p) for p in version)


def rename_suggestions(max_version):
    """The next integer version, and a sub-version just above max for when that integer is taken."""
    next_major = f"V{max_version[0] + 1}"
    if len(max_version) == 1:
        sub = f"V{max_version[0]}_1"
    else:
        sub = "V" + "_".join(str(p) for p in max_version[:-1] + (max_version[-1] + 1,))
    return next_major, sub


def highest(paths):
    """(version, basename) of the highest versioned migration among paths, or None."""
    best = None
    for path in paths:
        name = PurePosixPath(path).name
        version = parse_version(name)
        if version is not None and (best is None or version > best[0]):
            best = (version, name)
    return best


def violations(base_paths, added_paths):
    """Every added migration whose version is <= the highest in base_paths.

    Returns (base_highest, [(path, version), ...]). Pure, so the self-test can drive it directly."""
    top = highest(base_paths)
    if top is None:
        return None, []
    bad = []
    for path in added_paths:
        version = parse_version(PurePosixPath(path).name)
        if version is not None and version <= top[0]:
            bad.append((path, version))
    return top, bad


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def base_migrations(base):
    return git("ls-tree", "-r", "--name-only", base, "--", MIGRATION_DIR).splitlines()


def added_migrations(base, head):
    """Paths the branch adds under MIGRATION_DIR since its merge base with `base`."""
    merge_base = git("merge-base", base, head).strip()
    out = git("diff", "--name-status", "-M", "--diff-filter=ACR", merge_base, head, "--",
              MIGRATION_DIR)
    added = []
    for line in out.splitlines():
        fields = line.split("\t")
        # A <path>, or R<score>/C<score> <old> <new>: the last field is always the path on head.
        added.append(fields[-1])
    return added


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="origin/main",
                        help="ref whose highest migration version is the floor (default origin/main)")
    parser.add_argument("--head", default="HEAD", help="the branch being checked (default HEAD)")
    args = parser.parse_args(argv)

    try:
        base_paths = base_migrations(args.base)
        added = added_migrations(args.base, args.head)
    except subprocess.CalledProcessError as e:
        # Fail closed: a guard that cannot resolve its refs must not report a pass.
        print(f"check-migration-order: git {' '.join(e.cmd[1:])} failed:\n{e.stderr}", file=sys.stderr)
        return 2

    top, bad = violations(base_paths, added)
    if top is None:
        print(f"check-migration-order: no versioned migrations found in {args.base}:{MIGRATION_DIR}"
              " -- refusing to pass vacuously.", file=sys.stderr)
        return 2

    top_version, top_name = top
    counted = [p for p in added if parse_version(PurePosixPath(p).name) is not None]
    if not bad:
        print(f"Migration order OK: {len(counted)} added migration(s), all above "
              f"{args.base}'s highest version {format_version(top_version)} ({top_name}).")
        return 0

    next_major, sub = rename_suggestions(top_version)
    for path, version in bad:
        name = PurePosixPath(path).name
        description = name.split("__", 1)[1]
        relation = "equal to" if version == top_version else "below"
        print(f"::error file={path}::{name} has Flyway version {format_version(version)}, {relation} "
              f"{args.base}'s highest version {format_version(top_version)} ({top_name}). "
              f"Main deploys to production, and once {format_version(top_version)} is applied there, "
              f"Flyway (outOfOrder=false) refuses every deploy after this merges. Rename it to "
              f"{next_major}__{description} -- or {sub}__{description} if {next_major} is already "
              f"taken by another open PR.")
    sys.stdout.flush()
    print(f"\n{len(bad)} migration(s) not above {args.base}'s highest version "
          f"{format_version(top_version)}. See the errors above.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
