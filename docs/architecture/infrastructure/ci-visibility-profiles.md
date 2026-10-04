# CI visibility profiles (public / private)

The repository can be switched between public and private without editing any workflow file. Each
workflow reads `github.event.repository.private` at run time and picks one of two profiles:

- **Full profile (public).** Everything runs as it always has. GitHub-hosted standard runners cost
  nothing in a public repository.
- **Lean profile (private).** Only a small set of cheap guards runs on its own. The rest runs only
  when started by hand (`workflow_dispatch`).

GitHub has included `repository` in the event payload for every trigger these workflows use (push,
pull_request, workflow_dispatch, and, since 2022-09-27, schedule). If it were ever missing, the
expressions read `null`, and every one of them is written so that `null` selects the full profile.
That means more minutes used, never less checking.

## Why a lean profile exists

The Fynora org is on **GitHub Free**. Private repositories get **2,000 Actions minutes a month**,
and each job is billed rounded up to the whole minute. Measured over 2026-09-27 to 2026-10-04
(1,000 workflow runs, the API's listing cap), every workflow together used about **12,700 minutes
in one week**, or roughly 56,000 a month:

| Workflow | Minutes in the week |
|---|---|
| CI (`ci.yml`) | 9,153 |
| CodeQL | 1,383 |
| Sync dev branch | 1,076 |
| Mobile E2E (Maestro) | 831 |
| Everything else | ~235 |

That week had ~300 pull_request CI runs and ~94 pushes to main. At that rate, even a single
1-minute job on every PR event uses ~1,300 minutes a month. So no automatic per-PR subset of CI
fits the private allowance. A full run started on demand does fit.

The last time the repository was private on hosted runners, the allowance ran out and GitHub
stopped starting jobs at all ("recent account payments have failed or your spending limit needs to
be increased", 2026-08-07). See [self-hosted-runner.md](self-hosted-runner.md).

## What each workflow does in each profile

| Workflow / job | Public (full) | Private (lean) |
|---|---|---|
| `ci.yml` — Repository hygiene | every PR event and push | push to main and on demand only |
| `ci.yml` — every other job | as before | on demand only |
| `migration-order.yml` — both jobs | only when migrations (or the checker) change | same |
| `codeql.yml` | as before | skipped (code scanning needs GitHub Code Security on private repos) |
| `maestro-nightly.yml` | mobile merges, nightly | on demand only |
| `e2e-nightly.yml` | nightly | on demand only |
| `corpus-coverage-nightly.yml` | path-filtered PRs, nightly | on demand only |
| `sync-dev-branch.yml` | every push to main | on demand only, and needs a passing on-demand CI run on main |
| `github-traffic-metrics.yml` | every 6 hours | skipped (no outside traffic; auto-merge is public-only on Free) |
| `gitleaks-nightly.yml` | nightly | nightly (unchanged) |
| `image-scan-nightly.yml` | nightly | nightly (unchanged) |

`migration-order.yml` is the same in both profiles. Its jobs moved out of `ci.yml` so a `paths:`
filter could keep them from running on PRs that add no migration. Before the move, the check ran on
every PR event even though it exits in seconds when there is nothing to check. The reasoning is in
that file's header.

### Estimated private usage at the measured week's pace

| What | Per month (approx.) |
|---|---|
| Repository hygiene, every push to main (~94 a week) | ~420 |
| Migration order, PR events that touch migrations (42 of 193 PRs that week) | ~290 |
| Migration re-check, main pushes that change migrations (22 that week) | ~100 |
| Container image scan, nightly | ~80 |
| Secret scan, nightly | ~30 |
| **Baseline** | **~920** |
| **Left for on-demand runs** | **~1,080**, about 30 full `ci.yml` runs at ~35 minutes each |

Dependabot's own update jobs don't count toward the included minutes. GitHub's docs state this for
standard hosted runners. The CI runs that Dependabot PRs trigger do count, like any other PR's
runs.

These are estimates projected from one busy week, not a measured private month. Check real usage
in the org's billing page early in any private spell.

## Working while private

- **A PR's checks don't prove anything ran.** GitHub reports a skipped job as "Success", even
  for a required check. Before merging, run the full suite on the PR's branch:

  ```bash
  gh workflow run ci.yml --ref <branch>
  ```

  The run attaches to the branch's head commit, so its results show on the PR. Pushing again
  afterwards needs another run.
- **Main's ruleset isn't enforced.** On GitHub Free, rulesets (required checks, no force-push, no
  deletion) only apply to public repositories. Nothing blocks a merge with failing or missing
  checks.
- **Keeping dev current takes two steps:** `gh workflow run ci.yml --ref main`, then once it
  passes, `gh workflow run sync-dev-branch.yml`.
- **Secret scanning moves to two places:** the nightly full-history gitleaks run and the local
  `.husky/pre-commit` hook (when gitleaks is installed). GitHub's own secret-scanning push
  protection is free only on public repositories.

## Checklist: before making the repository private

1. Nothing in the workflows needs to change.
2. Expect `codeql.yml` and `github-traffic-metrics.yml` to show skipped jobs. That's by design.

## Checklist: before making the repository public again

Everything committed while private becomes public history the moment the repository flips.

1. Confirm the latest `gitleaks-nightly.yml` run on main passed. It scans main's full history.
   Run it now if the last one is stale: `gh workflow run gitleaks-nightly.yml`.
2. Run full CI on main and confirm it passes: `gh workflow run ci.yml --ref main`.
3. After flipping: open PRs keep the skipped results from their last private-profile run. Those
   count as passing required checks. For each open PR, push a commit or close and reopen it, so
   the full profile actually checks it. **Re-running the old run is not enough.** A re-run replays
   the original event: same commit, same ref, and the same payload. `migration-order-recheck`
   relies on that to keep a PR's `head.sha`. That payload still says `private: true`, so the re-run
   runs the lean profile again.
4. Main's ruleset is enforced again automatically. CodeQL and traffic metrics resume on their next
   trigger.
