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
                           -Dit.test. See HOW THE SPLIT IS MADE below.
  --check-reports DIR      compare the *IT classes that produced a report in DIR (the merged
                           reports of every shard) against every *IT class in the source tree,
                           and fail on any class missing or reported twice.
  --self-test              check the split itself on the real source tree: every class in exactly
                           one shard, no shard empty, and every bundle (below) whole in one shard.
  --update-timings DIR     rewrite scripts/it-class-timings.json from a directory of failsafe
                           reports (every shard's backend-it-reports-N artifact, merged).

HOW THE SPLIT IS MADE
---------------------
Booting the Spring application context was about 40% of each shard's test time (measured on main,
2026-10-22: 108-124s of 265-294s, 92 boots across the three shards). Spring boots a new context for
every distinct test configuration, and caches it for any later class with the same one -- but only
within one JVM, so a configuration whose classes are dealt into all three shards is booted three
times. Round-robin by name did exactly that: all *IT classes in one JVM booted 80 times, the same
classes in three shards 92 times.

So classes are first put into BUNDLES by their context configuration, read from the source: the
class-level context annotations (@TestPropertySource, @Import, @AutoConfigure*, ...), and the
types of its @MockitoBean/@MockitoSpyBean fields. A class with its own @DynamicPropertySource or
nested configuration, or one that does not extend AbstractIntegrationTest at all, is a bundle of
its own. A bundle always lands whole in one shard. This only ever affects speed: a class put in the
wrong bundle costs an extra boot, never a skipped class -- --check-reports is what proves every
class ran.

Bundles are then spread over the shards largest first, each to the shard with the least time so
far, using each class's measured time from scripts/it-class-timings.json. A class missing from that
file (new since the last --update-timings) counts as the median class. Classes that do not extend
AbstractIntegrationTest are not @Isolated and run alongside each other, so they count at
STANDALONE_WEIGHT of their own time: on main (2026-10-22) the migration ITs' wall-clock time in
each shard was 0.27 of the sum of their class times.

The split is deterministic: the same source tree and timings file always give the same shards.

The source of truth is the file system: backend/src/test/java/**/*IT.java, the same pattern as
failsafe's <include> in backend/pom.xml. The class name is the file name, which holds for all of
them today (304 files, 304 classes in the 2026-10-04 run), and none is abstract or uses @Nested --
if either ever changes, --check-reports fails rather than guessing.
"""

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
TEST_ROOT = REPO_ROOT / "backend" / "src" / "test" / "java"
TIMINGS = Path(__file__).resolve().parent / "it-class-timings.json"
STANDALONE_WEIGHT = 0.27
SHARED_BASE = "AbstractIntegrationTest"

# Class-level annotations that change the Spring test context. Others on the class (@Tag,
# @Isolated, @DisplayName, ...) do not, and must not split an otherwise identical bundle.
CONTEXT_ANNOTATIONS = ("TestPropertySource", "Import", "ContextConfiguration", "ActiveProfiles",
                       "SpringBootTest", "DirtiesContext", "TestExecutionListeners", "MockitoBean",
                       "MockitoSpyBean", "WebAppConfiguration")


def all_it_classes():
    classes = []
    for path in TEST_ROOT.rglob("*IT.java"):
        rel = path.relative_to(TEST_ROOT).with_suffix("")
        classes.append(".".join(rel.parts))
    return sorted(classes)


def _strip_comments(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    return re.sub(r"//[^\n]*", " ", src)


def _annotation_args(text, start):
    """The balanced (...) after an annotation name at `start`, or '' if it has none."""
    i = start
    while i < len(text) and text[i].isspace():
        i += 1
    if i >= len(text) or text[i] != "(":
        return ""
    depth = 0
    for j in range(i, len(text)):
        depth += {"(": 1, ")": -1}.get(text[j], 0)
        if depth == 0:
            return text[i:j + 1]
    return text[i:]


def bundle_key(fqcn):
    """A string equal for two classes that should share a Spring context; see the module docstring."""
    simple = fqcn.rsplit(".", 1)[-1]
    src = _strip_comments((TEST_ROOT / Path(*fqcn.split("."))).with_suffix(".java").read_text())
    decl = re.search(r"\bclass\s+" + simple + r"\b([^{]*)\{", src)
    if not decl or SHARED_BASE not in decl.group(1):
        return "standalone:" + fqcn
    if "@DynamicPropertySource" in src or re.search(r"@(Test)?Configuration\b", src) or "@Bean" in src:
        return "own-config:" + fqcn
    header = src[:decl.start()]
    parts = []
    for m in re.finditer(r"@(?:[\w.]+\.)?(\w+)", header):
        if m.group(1) in CONTEXT_ANNOTATIONS:
            parts.append(m.group(1) + re.sub(r"\s+", "", _annotation_args(header, m.end())))
    fields = re.findall(r"@(MockitoBean|MockitoSpyBean)\s*(\([^)]*\))?\s+(?:(?:private|protected|public|final)\s+)*"
                        r"([\w.<>?, \[\]]+?)\s+\w+\s*[;=]", src[decl.end():])
    parts += sorted({f"{a}{re.sub(chr(32), '', args)}:{re.sub(r'[ ]', '', t)}" for a, args, t in fields})
    return "shared:" + "|".join(parts)


def load_timings():
    try:
        return json.loads(TIMINGS.read_text())
    except FileNotFoundError:
        return {}


def plan(classes, shards):
    """Every class assigned to a shard (0-based), bundles whole, balanced by measured time."""
    timings = load_timings()
    present = set(classes)
    known = [t for c, t in timings.items() if c in present]
    default = statistics.median(known) if known else 1.0
    bundles = {}
    for c in classes:
        bundles.setdefault(bundle_key(c), []).append(c)
    def cost(key, members):
        factor = STANDALONE_WEIGHT if key.startswith("standalone:") else 1.0
        return sum(timings.get(c, default) for c in members) * factor

    # A bundle bigger than an even share would make its shard the slowest no matter where it went
    # -- and the default-configuration bundle is about two thirds of all classes (measured
    # 2026-10-22: 270s of an 826s total, against a 275s even share), so it is the one new tests
    # mostly join. Such a bundle is cut, in class-name order, into the fewest pieces that each fit
    # an even share. Each extra piece costs one more boot of that context (~4s), far less than an
    # unbalanced shard.
    share = sum(cost(k, m) for k, m in bundles.items()) / shards
    for key in [k for k, m in bundles.items() if cost(k, m) > share]:
        members = bundles.pop(key)
        pieces = -(-cost(key, members) // share)
        piece_target = cost(key, members) / pieces
        piece, n = [], 0
        for c in members:
            piece.append(c)
            if cost(key, piece) >= piece_target and n < pieces - 1:
                bundles[f"{key}#{n}"] = piece
                piece, n = [], n + 1
        if piece:
            bundles[f"{key}#{n}"] = piece
    weight = {key: cost(key, members) for key, members in bundles.items()}
    loads = [0.0] * shards
    assignment = {}
    for key in sorted(bundles, key=lambda k: (-weight[k], k)):
        target = min(range(shards), key=lambda s: (loads[s], s))
        loads[target] += weight[key]
        for c in bundles[key]:
            assignment[c] = target
    return assignment, bundles, loads


def shard(classes, shards, index):
    assignment, _, _ = plan(classes, shards)
    return [c for c in classes if assignment[c] == index - 1]


def reported_it_classes(report_dir):
    # By file name (surefire/failsafe write TEST-<fully.qualified.Class>.xml), not by parsing the
    # XML. The first CI run of this check (2026-10-04) crashed on a report that is not
    # well-formed XML -- a UNIT test's, TEST-com.finora.architecture.LazyBinaryColumnTest.xml,
    # which this check has no business reading at all. A report that exists means its class ran;
    # whether it passed is the shard job's exit code, not this check's question.
    counts = {}
    for xml in sorted(Path(report_dir).glob("TEST-*.xml")):
        name = xml.name[len("TEST-"):-len(".xml")]
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
    assignment, bundles, loads = plan(classes, shards)
    parts = [shard(classes, shards, i) for i in range(1, shards + 1)]
    flat = [c for p in parts for c in p]
    problems = []
    if sorted(flat) != classes:
        problems.append("the shards' union is not exactly the full class list")
    if len(flat) != len(set(flat)):
        problems.append("a class is in more than one shard")
    if any(not p for p in parts):
        problems.append("a shard is empty")
    split = [k for k, members in bundles.items() if len({assignment[c] for c in members}) > 1]
    if split:
        problems.append(f"{len(split)} bundle(s) split across shards")
    if problems:
        print("self-test FAILED: " + "; ".join(problems))
        return 1
    shared = sum(1 for k in bundles if k.startswith("shared:"))
    print(f"self-test ok: {len(classes)} classes, {len(bundles)} bundles ({shared} on the shared "
          f"base class), {shards} shards of {', '.join(str(len(p)) for p in parts)} classes, "
          f"estimated {', '.join(f'{l:.0f}s' for l in loads)}.")
    return 0


def update_timings(report_dir):
    # Read the name and time attributes from the head of each report rather than parsing the XML:
    # reported_it_classes() above explains why a report cannot be trusted to be well-formed.
    head = re.compile(r'<testsuite\b[^>]*\bname="([^"]+)"[^>]*\btime="([0-9.]+)"')
    timings = {}
    for xml in sorted(Path(report_dir).rglob("TEST-*IT.xml")):
        with open(xml, encoding="utf-8", errors="replace") as f:
            m = head.search(f.read(4096))
        if m:
            timings[m.group(1)] = round(float(m.group(2)), 1)
    if not timings:
        print(f"BLOCKED: no *IT reports with a time under {report_dir}.")
        return 1
    TIMINGS.write_text(json.dumps(dict(sorted(timings.items())), indent=1) + "\n")
    print(f"wrote {len(timings)} class timings to {TIMINGS.relative_to(REPO_ROOT)}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--shards", type=int, default=3)
    ap.add_argument("--index", type=int)
    ap.add_argument("--check-reports", metavar="DIR")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--update-timings", metavar="DIR")
    args = ap.parse_args()

    if args.shards < 1:
        ap.error("--shards must be at least 1")
    if args.update_timings:
        return update_timings(args.update_timings)
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
