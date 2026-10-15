#!/usr/bin/env bash
# Statement traces and golden outputs live in the private Fynora/finora-statement-traces
# repository (project 3 of the 2026-10-14 design). Nothing derived from a statement may be committed here.
#
# CI's form of the pre-commit refusal in check-fixture-hygiene.sh: that one reads what is staged and
# is skipped by --no-verify, this one reads every tracked path, so a trace that got past the hook
# still fails the build.
set -euo pipefail
found=$(git ls-files -- '*.trace' '*.golden.txt')
if [ -n "$found" ]; then
  echo "statement traces belong in Fynora/finora-statement-traces, not here:" >&2
  echo "$found" >&2
  exit 1
fi
