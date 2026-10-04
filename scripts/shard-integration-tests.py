#!/usr/bin/env python3
"""Splits the backend's *IT classes across CI shards, and proves afterwards that none went missing.

WHY THIS EXISTS
---------------
The integration tests are most of the backend job's time (measured 2026-10-04 on main: 8m22s of a
12m39s `./mvnw verify`, 1,778 tests in 304 classes), and they cannot go faster inside one JVM:
AbstractIntegrationTest is @Isolated because every subclass shares one Postgres, so those classes
run strictly one at a time. ci.yml therefore runs them on several runners at once instead -- each
shard is its own VM with its own Testcontainers Postgres, so @Isolated still holds within it.

Splitting is only safe if every class still runs exactly once. A class that matches no shard would
be silently skipped while every shard stays green -- the same "smaller number nobody looked at"
shape scripts/summarize-surefire.py exists for. So this script is both halves:

  --shards N --index I     print shard I's classes (1-based), comma-separated, for failsafe's
                           -Dit.test. Classes are sorted by fully qualified name and dealt
                           round-robin, so the split is deterministic and needs no timing data.
  --check-reports DIR      compare the *IT classes that produced a report in DIR (the merged
                           reports of every shard) against every *IT class in the source tree,
                           and fail on any class missing or reported twice.
  --self-test              check the split itself on the real source tree: every class in exactly
                           one shard, no shard empty, sizes within one of each other.

The source of truth is the file system: backend/src/test/java/**/*IT.java, the same pattern as
failsafe's <include> in backend/pom.xml. The class name is the file name, which holds for all of
them today (304 files, 304 classes in the 2026-10-04 run), and none is abstract or uses @Nested --
if either ever changes, --check-reports fails rather than guessing.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = REPO_ROOT / "backend" / "src" / "test" / "java"


def all_it_classes():
    classes = []
    for path in TEST_ROOT.rglob("*IT.java"):
        rel = path.relative_to(TEST_ROOT).with_suffix("")
        classes.append(".".join(rel.parts))
    return sorted(classes)


def shard(classes, shards, index):
    return [c for i, c in enumerate(classes) if i % shards == index - 1]


def reported_it_classes(report_dir):
    counts = {}
    for xml in sorted(Path(report_dir).glob("TEST-*.xml")):
        name = ET.parse(xml).getroot().get("name", "")
        if name.endswith("IT"):
            counts[name] = counts.get(name, 0) + 1
    return counts


def check_reports(report_dir):
    expected = set(all_it_classes())
    if not Path(report_dir).is_dir() or not any(Path(report_dir).glob("TEST-*.xml")):
        print(f"BLOCKED: no test reports at {report_dir} -- no shard's results arrived.")
        return 1
    # ci.yml merges every shard's reports into one directory, where two reports of the same class
    # share a file name and the later one overwrites the earlier -- so "reported more than once"
    # below can only catch a duplicate within one directory, never across shards. What prevents a
    # cross-shard duplicate is shard() itself, which --self-test proves disjoint.
    counts = reported_it_classes(report_dir)
    missing = sorted(expected - counts.keys())
    extra = sorted(counts.keys() - expected)
    twice = sorted(c for c, n in counts.items() if n > 1)
    print(f"shard-integration-tests: {len(expected)} *IT classes in the source tree, "
          f"{len(counts)} reported across all shards.")
    for label, names in (("MISSING (no report from any shard)", missing),
                         ("REPORTED BUT NOT IN THE SOURCE TREE", extra),
                         ("REPORTED MORE THAN ONCE", twice)):
        if names:
            print(f"{label}: {len(names)}")
            for n in names:
                print(f"  {n}")
    if missing or twice:
        print("BLOCKED: the shards did not run every *IT class exactly once.")
        return 1
    return 0


def self_test(shards):
    classes = all_it_classes()
    if not classes:
        print("self-test FAILED: no *IT classes found under", TEST_ROOT)
        return 1
    parts = [shard(classes, shards, i) for i in range(1, shards + 1)]
    flat = [c for p in parts for c in p]
    problems = []
    if sorted(flat) != classes:
        problems.append("the shards' union is not exactly the full class list")
    if len(flat) != len(set(flat)):
        problems.append("a class is in more than one shard")
    if any(not p for p in parts):
        problems.append("a shard is empty")
    if max(map(len, parts)) - min(map(len, parts)) > 1:
        problems.append("shard sizes differ by more than one")
    if problems:
        print("self-test FAILED: " + "; ".join(problems))
        return 1
    print(f"self-test ok: {len(classes)} classes in {shards} shards of "
          f"{', '.join(str(len(p)) for p in parts)}.")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--shards", type=int, default=3)
    ap.add_argument("--index", type=int)
    ap.add_argument("--check-reports", metavar="DIR")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.shards < 1:
        ap.error("--shards must be at least 1")
    if args.self_test:
        return self_test(args.shards)
    if args.check_reports:
        return check_reports(args.check_reports)
    if args.index is None or not 1 <= args.index <= args.shards:
        ap.error(f"--index must be between 1 and {args.shards}")
    classes = shard(all_it_classes(), args.shards, args.index)
    if not classes:
        print(f"shard {args.index}/{args.shards} is empty", file=sys.stderr)
        return 1
    print(",".join(classes))
    return 0


if __name__ == "__main__":
    sys.exit(main())
