#!/usr/bin/env python3
"""Lists candidate bank sentences from the owner's local statements into a private file.

WHY THIS EXISTS
---------------
A blanked copy keeps a printed line verbatim only when the whole line is a listed bank sentence
(BankSentences; see docs/superpowers/specs/2026-10-15-bank-sentences-design.md). The list is
hand-made: the owner picks every entry. This finds the lines worth picking from, once, on his own
machine.

A candidate is a printed line, normalised, with no digit, at least four words, no '@', no IFSC
shape, ASCII only, that appears in at least --min-statements different statement files.

WHAT LEAVES THE MACHINE: NOTHING
--------------------------------
The candidate lines are text from real statements. They go to the output file only, which must be
outside every repository, and this script prints the counts line and nothing else: not a line, not
a file name, not a parser's message. The probe's own output is captured and only its counts line is
passed on. The owner reads the file himself.

    python3 scripts/bank-sentence-candidates.py <corpus-dir> -o <out-file>
"""

import argparse
import importlib.util
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
PROBE_CLASS = "com.finora.imports.analysis.BankSentenceCandidates"
SUMMARY_PREFIX = "statements read: "

_spec = importlib.util.spec_from_file_location("corpus_run", REPO_ROOT / "scripts" / "corpus-run.py")
corpus_run = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(corpus_run)


def repository_roots(cwd: Path = REPO_ROOT) -> list[Path]:
    """This checkout's root and, for a worktree, the main checkout's root as well: a path inside
    either is one `git add -A` away from a commit."""
    roots = []
    top = subprocess.run(["git", "rev-parse", "--show-toplevel"], cwd=cwd, capture_output=True, text=True)
    if top.returncode == 0 and top.stdout.strip():
        roots.append(Path(top.stdout.strip()).resolve())
    common = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
                            cwd=cwd, capture_output=True, text=True)
    if common.returncode == 0 and common.stdout.strip():
        roots.append(Path(common.stdout.strip()).resolve().parent)
    # Fallback when git cannot answer: the directory this script lives in is a checkout.
    roots.append(REPO_ROOT)
    return roots


def refuse_if_inside_repo(path: Path, what: str, roots: list[Path]) -> None:
    resolved = path.resolve()
    for root in roots:
        if resolved == root or resolved.is_relative_to(root):
            print(f"REFUSED: the {what} is inside a repository ({root}). "
                  "Statements and their lines must stay outside every working tree.", file=sys.stderr)
            sys.exit(2)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("corpus", type=Path, help="directory of the owner's statements, OUTSIDE every repository")
    ap.add_argument("-o", "--out", type=Path, required=True, help="output file, OUTSIDE every repository")
    ap.add_argument("--min-statements", type=int, default=3,
                    help="how many different statements a line must appear in (default 3)")
    ap.add_argument("--timeout", type=int, default=1800, help="whole-run timeout in seconds")
    args = ap.parse_args()

    roots = repository_roots()
    refuse_if_inside_repo(args.corpus, "corpus", roots)
    refuse_if_inside_repo(args.out, "output file", roots)
    corpus = args.corpus.resolve()
    if not corpus.is_dir():
        print("not a directory: the corpus", file=sys.stderr)
        return 2

    classpath = corpus_run.build_classpath(quiet=True)
    out = args.out.resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    # Created owner-only before the probe writes into it, so the lines are never readable by
    # anyone else, not even for the length of the run.
    os.close(os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600))
    os.chmod(out, 0o600)
    try:
        result = subprocess.run(["java", "-cp", classpath, PROBE_CLASS, str(corpus), str(out),
                                 str(args.min_statements)],
                                capture_output=True, text=True, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        print(f"probe timed out after {args.timeout}s", file=sys.stderr)
        return 1
    # Only the counts line is passed on: the probe's other output (library warnings) is not shown,
    # because a warning can quote a statement.
    summary = [line for line in result.stdout.splitlines() if line.startswith(SUMMARY_PREFIX)]
    if result.returncode != 0 or len(summary) != 1:
        print(f"probe failed (exit {result.returncode}); its output is not shown", file=sys.stderr)
        return 1
    print(summary[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
