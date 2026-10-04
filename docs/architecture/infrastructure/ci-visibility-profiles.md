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
fits the private allowance. A full run when someone asks for one (the `full-ci` label) does fit.

The last time the repository was private on hosted runners, the allowance ran out and GitHub
stopped starting jobs at all ("recent account payments have failed or your spending limit needs to
be increased", 2026-08-07). See [self-hosted-runner.md](self-hosted-runner.md).

## What each workflow does in each profile

| Workflow / job | Public (full) | Private (lean) |
|---|---|---|
| `ci.yml` — Repository hygiene | every PR event and push | push to main, `full-ci` PRs, by hand |
| `ci.yml` — every other job | as before | `full-ci` PRs and by hand only |
| `migration-order.yml` — both jobs | only when migrations (or the checker) change | same |
| `codeql.yml` | as before | skipped (code scanning needs GitHub Code Security on private repos) |
| `maestro-nightly.yml` | mobile merges, nightly | by hand only |
| `e2e-nightly.yml` | nightly | by hand only |
| `corpus-coverage-nightly.yml` | path-filtered PRs, nightly | path-filtered pushes to `full-ci` PRs, by hand |
| `sync-dev-branch.yml` | every push to main | by hand only, and needs a passing by-hand CI run on main |
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
| **Left for full runs** | **~1,080**: about 55 labelled PR runs at ~19 minutes each, or ~30 by-hand runs at ~35 |

A labelled PR run is an ordinary pull_request run, so it is path-filtered. Its ~19 minutes is the
measured week's average, 5,737 job-minutes over 302 PR runs. A by-hand (`workflow_dispatch`) run
always runs everything; its ~35 minutes is the average main push run. Every push to a PR that
still carries `full-ci` costs another run.

Dependabot's own update jobs don't count toward the included minutes. GitHub's docs state this for
standard hosted runners. The CI runs that Dependabot PRs trigger do count, like any other PR's
runs.

These are estimates projected from one busy week, not a measured private month. Check real usage
in the org's billing page early in any private spell.

## Working while private

- **A PR's checks don't prove anything ran.** GitHub reports a skipped job as "Success", even
  for a required check. Before merging, give the PR a full run by labelling it:

  ```bash
  gh pr edit <number> --add-label full-ci
  ```

  - Adding the label fires a `pull_request` run. While the label stays, every later push to the
    PR runs in full too, so remove it if the PR will take many more pushes.
  - Draft PRs still skip the heavy jobs, the same as in public. Mark the PR ready for review.
  - Read the result on the PR. A green check is real when its job actually ran; in GitHub's UI a
    skipped job shows a grey "skipped" icon, not a green tick.

  Why a label and not `gh workflow run ci.yml --ref <branch>` (measured on #1974, 2026-10-04):
  - **The results wouldn't show on the PR.** A `workflow_dispatch` run's checks attach to the
    commit, but the PR's check list keeps showing the `pull_request` run's results. In this
    profile those are the skipped ones.
  - **It tests the branch alone,** not GitHub's merge of the PR into main.
  - **It narrows the customer-PII scan.** It gives repo-hygiene no base commit, so the scan covers
    only the branch's last commit.

  A labelled run has none of these problems, because it is an ordinary `pull_request` run.
- **Production deploys don't wait for CI, in either profile.** Railway starts deploying each main
  commit as soon as it lands. Measured on 2026-10-03: commit `62f0ec5be` showed Railway "success"
  at 20:43 while its CI push run failed at 20:54. So the private profile doesn't weaken deploy
  gating, because there wasn't any. The labelled run before merging is the only full check a
  change gets before it reaches production.
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
3. Note the date. The checklist below scans everything pushed after it.
4. The first night after the flip, check `gitleaks-nightly.yml`. Its last step prints
   `github.event.repository.private=true (schedule event)`. If that step fails instead, scheduled
   payloads don't carry the field, and every scheduled job is running in the full profile and
   spending minutes.

## Checklist: before making the repository public again

Everything pushed while private becomes public the moment the repository flips. That includes
branches that never merged, which no private-profile CI job scans.

1. Confirm the latest `gitleaks-nightly.yml` run on main passed. It scans main's full history.
   Run it now if the last one is stale: `gh workflow run gitleaks-nightly.yml`.
2. Scan the branches for secrets and customer PII, giving the day the repository went private:

   ```bash
   sh scripts/check-branches-since.sh YYYY-MM-DD
   ```

   What it scans:
   - **Secrets:** gitleaks over every commit on every origin branch, with no date filter.
   - **Customer PII:** every non-merge commit on an origin branch that is not on main, committed
     since the date.

   It exits non-zero and names each finding. Delete, or rewrite and force-push, any branch it
   names before flipping.

   PII selection goes by committer date. A commit made *before* the repository went private but
   pushed *while* it was private is missed, so pass an earlier date when unsure.

   Verified 2026-10-04 in a throwaway clone with three planted findings: a secret, a customer email,
   and a customer email in a file a later commit deleted. All three were reported and the script
   exited 1. Against the real repository it reported clean in 51 seconds.
3. Run full CI on main and confirm it passes: `gh workflow run ci.yml --ref main`.
4. After flipping: open PRs keep the skipped results from their last private-profile run. Those
   count as passing required checks. For each open PR, push a commit or close and reopen it, so
   the full profile actually checks it. **Re-running the old run is not enough.** A re-run replays
   the original event: same commit, same ref, and the same payload. `migration-order-recheck`
   relies on that to keep a PR's `head.sha`. That payload still says `private: true`, so the re-run
   runs the lean profile again.
5. Main's ruleset is enforced again automatically. CodeQL and traffic metrics resume on their next
   trigger.

## Why runs on main never share a concurrency group

`ci.yml` and `migration-order.yml` give every run on main its own concurrency group (the run id).
With one shared group, `cancel-in-progress: false` only protects the run that is already running.
GitHub still cancels a *pending* run in the group as soon as a newer one queues. Measured on
2026-10-04: 8 of the last 300 main push runs had been cancelled, and 5 of those never started a
job.

That matters because three checks read only their own push's `before..sha` range:
- the customer-PII scan,
- the secret scan,
- the migration re-check.

A cancelled run skips those checks for its commit for good. In the private profile, that PII scan
on main is the only automatic one.
