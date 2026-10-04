#!/bin/sh
# Self-test for check-fixture-hygiene.sh. Run: sh scripts/test-fixture-hygiene.sh
#
# WHY THIS EXISTS
# ---------------
# The guard was intact and still missed a real account number: a full number went into a design note
# in one commit and was redacted in a later one, so the net `BASE..HEAD` diff CI scanned contained no
# trace of it. Nobody was told. A human noticed during review.
#
# A guard that can pass while the thing it guards against is in the branch is worse than no guard,
# because the green check reads as coverage. So the behaviour is now pinned by tests, and the
# add-then-remove case -- the exact shape of the failure -- is one of them.
#
# Every value in this file is invented. None is derived from a real statement, which is the same rule
# the script enforces.
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT="$REPO/scripts/check-fixture-hygiene.sh"
PASS=0
FAIL=0

# A digit run that looks like an Indian bank account number and defeats the script's own
# looks_synthetic() heuristic: no character repeats 4+ times, and it contains none of the words
# (example/sample/test/dummy/fake/placeholder/redacted) that mark a value as obviously invented.
# A weaker fixture would pass for the wrong reason.
# These two lines carry the synthetic-ok marker, and having to add it is the point: the guard blocked
# this very commit until they were annotated. The exception is now visible in review, which is what
# the marker is for.
FAKE_ACCOUNT="90183746251908"   # synthetic-ok
FAKE_IFSC="ZZZZ0918374"         # synthetic-ok

ok()   { PASS=$((PASS + 1)); printf '  ok    %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$1"; }

# A throwaway repo per case, so no test can see another's commits.
#
# Sets the global D and cds; deliberately NOT `new_repo`. Command substitution runs the function
# in a subshell, so its `cd` would never reach the caller and every case would run in this repository
# instead -- which is not theoretical: the first version of this file did exactly that and committed
# its fixtures onto the branch under test.
new_repo() {
  D=$(mktemp -d)
  cd "$D" || exit 1
  git init -q .
  git config user.email t@example.com
  git config user.name t
  git config commit.gpgsign false
  echo seed > seed.md
  git add seed.md
  git commit -q -m seed
}

commit_file() {  # commit_file <path> <content> <message>
  # Belt and braces after the subshell bug above: never commit into a repo that has a remote.
  if git remote | grep -q .; then
    echo "REFUSING to commit fixtures into a repo with a remote ($(pwd))" >&2
    exit 1
  fi
  printf '%s\n' "$2" > "$1"
  git add "$1"
  git commit -q -m "$3"
}

# --------------------------------------------------------------- 1. it catches a bare account number

new_repo
commit_file note.md "The RD narration carries account $FAKE_ACCOUNT in full." "add pii"
if sh "$SCRIPT" --range HEAD^ HEAD >/dev/null 2>&1; then
  bad "a synthetic account number in a .md file is caught"
else
  ok "a synthetic account number in a .md file is caught"
fi

# --------------------------------------------------- 2. THE regression: add in one, remove in the next

# This is how the real leak survived. The net diff base..HEAD shows note.md added with the number
# already gone, so a net scan sees a clean branch. --each sees the commit that added it.
commit_file note.md "The RD narration carries an account number in full." "redact pii"

if sh "$SCRIPT" --range HEAD~2 HEAD >/dev/null 2>&1; then
  ok "net-range scanning does NOT see add-then-remove (this is the gap --each exists to close)"
else
  bad "net-range scanning does NOT see add-then-remove -- fixture no longer reproduces the gap"
fi

if sh "$SCRIPT" --each HEAD~2 HEAD >/dev/null 2>&1; then
  bad "--each catches a value added in one commit and removed in the next"
else
  ok "--each catches a value added in one commit and removed in the next"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# --------------------------------------- 2b. add in one commit, delete the whole FILE in the next

# The same leak shape when the cleanup deletes the file instead of editing it. Each commit was
# scanned against its parent, but a file was only read if it existed in the WORKING TREE -- and a
# checkout of the branch tip (what CI scans from) no longer has it. Found 2026-10-04.
new_repo
commit_file leak.md "account $FAKE_ACCOUNT" "add pii file"
git rm -q leak.md
git commit -q -m "delete pii file"
if sh "$SCRIPT" --each HEAD~2 HEAD >/dev/null 2>&1; then
  bad "--each catches a value whose file a later commit deletes"
else
  ok "--each catches a value whose file a later commit deletes"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# ------------------------------------------- 2c. a commit on a branch that is not checked out

# scripts/check-branches-since.sh scans other branches' commits from whatever is checked out.
new_repo
git checkout -q -b side
commit_file leak.md "account $FAKE_ACCOUNT" "pii on a side branch"
side=$(git rev-parse HEAD)
git checkout -q -
if sh "$SCRIPT" --each "$side^" "$side" >/dev/null 2>&1; then
  bad "--each catches a value in a commit that is not checked out"
else
  ok "--each catches a value in a commit that is not checked out"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# ------------------------------------------------- 2d. a rename that also adds a value is scanned

# git diff detects renames by default, and the file list used to keep only Added/Copied/Modified,
# so a commit that renamed a file and added a value to it was never read. Found 2026-10-04.
new_repo
for i in 1 2 3 4 5 6 7 8 9 10; do echo "Line $i of an ordinary design note." >> old.md; done
git add old.md
git commit -q -m "ordinary note"
git mv old.md new.md
echo "account $FAKE_ACCOUNT" >> new.md
git add new.md
git commit -q -m "rename and add"
if sh "$SCRIPT" --each HEAD^ HEAD >/dev/null 2>&1; then
  bad "--each catches a value added in the same commit as a rename"
else
  ok "--each catches a value added in the same commit as a rename"
fi
# The other side: a rename alone adds nothing, so a value that was already there (here marked, as
# an old allowed line would be, by being committed before the rename) must not be re-flagged as new.
git mv new.md newer.md
git commit -q -m "plain rename"
if sh "$SCRIPT" --each HEAD^ HEAD >/dev/null 2>&1; then
  ok "a plain rename is not re-scanned as all-new content"
else
  bad "a plain rename is not re-scanned as all-new content"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# ------------------------------------------------------------------- 3. a clean branch still passes

new_repo
commit_file a.md "Sections are compared positionally while the count is unchanged." "clean one"
commit_file b.md "Ground truth lives outside the repository." "clean two"
if sh "$SCRIPT" --each HEAD~2 HEAD >/dev/null 2>&1; then
  ok "a clean two-commit branch passes --each"
else
  bad "a clean two-commit branch passes --each"
fi

# Non-vacuity: the same range must fail once a number is added, or the case above proves nothing.
commit_file c.md "account $FAKE_ACCOUNT" "add pii late"
if sh "$SCRIPT" --each HEAD~3 HEAD >/dev/null 2>&1; then
  bad "--each is not vacuous: the same clean range fails once a number is added"
else
  ok "--each is not vacuous: the same clean range fails once a number is added"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# --------------------------------------------------------------------------- 4. IFSC codes are caught

new_repo
commit_file note.md "branch code $FAKE_IFSC" "add ifsc"
if sh "$SCRIPT" --each HEAD^ HEAD >/dev/null 2>&1; then
  bad "an IFSC-shaped code is caught"
else
  ok "an IFSC-shaped code is caught"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# ------------------------------------------------ 5. the synthetic-ok escape hatch still works

new_repo
commit_file fixture.md "account $FAKE_ACCOUNT synthetic-ok" "add marked synthetic"
if sh "$SCRIPT" --each HEAD^ HEAD >/dev/null 2>&1; then
  ok "a line marked synthetic-ok is allowed"
else
  bad "a line marked synthetic-ok is allowed"
fi
cd "$REPO" || exit 1; rm -rf "$D"

# ------------------------------------------------------- 6. an empty range is not a silent pass

new_repo
out=$(sh "$SCRIPT" --each HEAD HEAD 2>&1)
case "$out" in
  *"no commits"*) ok "an empty range says so rather than passing silently" ;;
  *)              bad "an empty range says so rather than passing silently (got: $out)" ;;
esac
cd "$REPO" || exit 1; rm -rf "$D"

printf '\n  %d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
