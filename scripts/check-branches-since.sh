#!/bin/sh
# Pre-flip check before the repository goes from private back to public: scans what the remote's
# branches hold for secrets (gitleaks) and customer PII (check-fixture-hygiene.sh).
#
# Why this exists: in the private CI profile (docs/architecture/infrastructure/ci-visibility-
# profiles.md) no CI job scans pull-request branches -- only main's pushes and main's nightly
# history. Every branch pushed while private, merged or not, becomes public the moment the
# repository flips, so this is the scan those branches would otherwise never get.
#
# Usage: sh scripts/check-branches-since.sh YYYY-MM-DD    (the day the repository went private)
#
#   Secrets: gitleaks over every commit reachable from any origin branch -- no date filter. It is
#            cheap (measured 2026-10-04: 3,972 commits in ~40s), so nothing is left to a date.
#   PII:     check-fixture-hygiene.sh on each non-merge commit that is on some origin branch but
#            not on origin/main, committed on or after the date (main is scanned by its own push
#            runs). Per commit, not per branch: branches share commits, and dev's sync merges
#            would otherwise re-scan all of main. The date is the committer date, so a commit
#            made BEFORE the repository went private but first pushed while it was private is
#            missed -- pass an earlier date when unsure; an earlier date only costs time.
#
# Exits non-zero, naming each finding, if either scan finds anything -- and also if gitleaks is not
# installed, because a clean result without the secret scan would mean nothing. Read-only apart
# from `git fetch --prune`.

set -u

since="${1:-}"
case "$since" in
  [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
  *) echo "usage: sh scripts/check-branches-since.sh YYYY-MM-DD" >&2; exit 2 ;;
esac

command -v gitleaks >/dev/null 2>&1 || {
  echo "check-branches-since: gitleaks is not installed -- the secret scan cannot run, so this check cannot pass. See https://github.com/gitleaks/gitleaks#installing" >&2
  exit 2
}

git fetch --quiet --prune origin || { echo "check-branches-since: git fetch failed" >&2; exit 2; }
git rev-parse --verify --quiet origin/main >/dev/null || {
  echo "check-branches-since: origin/main not found" >&2; exit 2; }

rc=0

# --redact keeps any finding's value out of the terminal.
echo "== Secrets: every commit on every origin branch"
if ! gitleaks git --no-banner --redact --log-opts="--remotes=origin" .; then
  echo "SECRETS: gitleaks reported findings above (commit and file listed for each)."
  rc=1
fi

# "00:00" is required: git reads a bare date as that date at the CURRENT time of day, which
# silently drops everything committed earlier that day (measured: `--since=2026-10-04` selected
# no commits at 12:00 IST on a day with 20 of them).
commits=$(git rev-list --no-merges --since="$since 00:00" --remotes=origin --not origin/main)
count=$(printf '%s' "$commits" | grep -c . || true)
echo "== Customer PII: $count non-merge commit(s) on origin branches, not on main, since $since"

for c in $commits; do
  if ! out=$(sh scripts/check-fixture-hygiene.sh --each "$c^" "$c" 2>&1); then
    printf '%s\n' "$out"
    echo "PII: commit $c, on: $(git branch -r --contains "$c" | head -5 | tr -s ' \n' ' ')"
    rc=1
  fi
done

if [ "$rc" -eq 0 ]; then
  echo "check-branches-since: clean -- no secrets on any origin branch, no customer PII in $count commit(s) since $since."
else
  echo "check-branches-since: FINDINGS -- fix, delete or rewrite those branches before making the repository public." >&2
fi
exit "$rc"
