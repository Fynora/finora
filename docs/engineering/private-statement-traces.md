# Private statement traces

**What this covers:** where statement traces, their golden outputs and the tests that read them
live, how to run those tests against a change here, and when a parser pull request is ready to
merge.

## Where traces live, and why

A trace is a statement's text layer with every run's position kept and every word that is not
statement furniture masked (see [`import/trace-lifecycle.md`](import/trace-lifecycle.md)). It is
not anonymous: the length and capitalisation of every hidden word, and the sequence of amounts and
dates, survive redaction on purpose, because the reading code and its bugs depend on them. So a
trace is derived from a customer's statement even when it passes every check.

Since 2026-10-15 nothing derived from a statement is kept in this public repository (project 3 of
[the 2026-10-14 design](../superpowers/specs/2026-10-14-fixing-statements-without-the-real-statement-design.md)).
Traces live in the private repository **`Fynora/finora-statement-traces`**:

| Folder | Holds |
|---|---|
| `traces/` | one `.trace` per statement |
| `golden/` | the reader's frozen output per trace, for `GoldenOutputSnapshotTest` |
| `tests/java/` | every test class that reads a trace, in the backend's own packages |

A test class that read traces *and* tested hand-built input was split: the hand-built half kept
its name here, and the trace half is `<Name>TracesTest` in the private repository.

Only the owner has access. No key to the private repository exists anywhere in this repository's
code, workflows or secrets, and none may be added: the private repository's own workflows check
this public one out and run the tests there, with the "Fynora Fix Bot" App's read-only token once
the owner has made the App (anonymously until then), so they work whether this repository is
public or private. The automatic Claude fix job's token for the private repository lives in
Railway only ([`claude-fix-job.md`](claude-fix-job.md)).

This repository refuses traces:
- the pre-commit hook (`scripts/check-fixture-hygiene.sh`) blocks a staged `.trace` or
  `.golden.txt` file by its path;
- CI's `No statement traces` step (`scripts/check-no-statement-traces.sh`) fails if any tracked
  path is one, so a commit made with `--no-verify` still fails.

Public history before 2026-10-15 still holds the 32 traces that were here. It is not rewritten:
the owner's decision on 2026-10-15.

## Running the trace tests against a change here

The backend's `private-traces` Maven profile compiles the private repository's `tests/java` with
the backend's own tests and points the trace loader (`PdfTrace.tracesDir()`, property
`finora.traces.dir`) and the golden snapshots (`finora.golden.dir`) at its folders.

With a checkout of the private repository beside this one:

```bash
cd backend
./mvnw test -Pprivate-traces -Dfinora.private.repo=/absolute/path/to/finora-statement-traces
```

That runs the whole backend suite plus the private tests. To run only the private tests against a
pushed branch of this repository, from the private checkout:

```bash
./scripts/run-against.sh <branch>
```

Or dispatch the private repository's `Traces against public code` workflow with that branch name.
It accepts only a branch of `Fynora/finora` whose head commit the owner authored, never a fork.

Capturing a new trace writes it into the private checkout:

```bash
FINORA_PRIVATE_TRACES=/absolute/path/to/finora-statement-traces \
  ./scripts/trace-capture.sh <trace-name> <path-to.pdf> ...
```

## When a parser pull request is ready to merge

This repository's CI no longer runs any trace-backed test. So a pull request that changes anything
under `backend/src/main/java/com/finora/imports/`, or a dependency the statement reader uses (a
PDFBox bump in `backend/pom.xml` among them: `PdfTextExtractorSnapshotTest`, which pins what PDFBox
extracts, is a private test now), is not merge-ready until one of these passes for its branch:
- the local `-Pprivate-traces` command above, or
- the private repository's on-demand run for that branch.

Say which one in the pull request. A green public CI run is not evidence either way: it cannot see
the traces.

If a public change that breaks a trace test merges anyway, the private repository's nightly run
against `main` goes red and opens an issue there.

## Cost

Private repositories' Actions minutes count against the owner's 2,000-a-month allowance; public
ones are free. The private workflows are kept small:

| Workflow | When | Measured on 2026-10-15 |
|---|---|---|
| `traces.yml` | push to the private `main`, pull requests there, once a day at 04:30 UTC, on demand | 5 min 19 s (26 classes, 86 tests), billed as 6 minutes |
| `privacy-scan.yml` | every push (except to `incoming`) and pull request in the private repository, on demand | 2 min 48 s, billed as 3 minutes |
| `retention.yml` | once a day at 05:00 UTC, on demand | not yet measured; with no trace to delete it makes one HTTP call |
| `fix.yml` | each Claude fix attempt production sends to `incoming` (at most 50 jobs a month, 2 attempts each) | not yet measured; at most 90 minutes (see the cost warning in `claude-fix-job.md`) |

So a quiet month costs about 180 minutes for the nightly `traces.yml` runs plus the daily
retention runs, and each push to the private `main` adds about 9 (both push workflows). No private
workflow is scheduled more than once a day. A pre-merge commit there that cannot pass against
public `main` can carry `[skip ci]` to save the minutes.
