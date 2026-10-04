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
| `ci.yml` — Repository hygiene | every PR event and push | push to main (plus the merge alarm, below), `full-ci` PRs, by hand, weekly |
| `ci.yml` — every other job | as before, plus the weekly run | `full-ci` PRs, by hand, and the weekly run of main (Monday 04:30 UTC) |
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

### Private runners are smaller and slower

GitHub's standard Linux runner for a private repository is **2 CPUs / 8 GB**. For a public one it
is **4 CPUs / 16 GB** (GitHub's "Standard GitHub-hosted runners" tables). This changes two things:

- **Time.** Measured 2026-10-04 by comparing the same jobs: the last public full run against the
  first private `full-ci` run.
  - Most jobs took 1.4–2.6× as long: User frontend 196s → 519s, Backend (unit) 285s → 511s, Admin
    portal 98s → 214s.
  - The billed minutes for the run rose from **43 to 65**.
- **Memory.** The mobile Jest suite ran out of heap on the private runner. It had never done so on
  the public runner. See `ci.yml`'s mobile Test step for the cause and the fix.

### Estimated private usage at the measured week's pace

Rescaled with the private runner timings above. These are still projections from one busy public
week. Check real usage early; see "How to check real usage" below.

| What | Per month (approx.) |
|---|---|
| Repository hygiene, every push to main (~94 a week, 1 billed minute each) | ~400 |
| Migration order, PR events that touch migrations (42 of 193 PRs that week) | ~290 |
| Migration re-check, main pushes that change migrations (22 that week) | ~100 |
| Container image scan, nightly (~2.4× slower on private runners) | ~180 |
| Secret scan, nightly | ~30 |
| Weekly full run of main (~65 billed minutes, 4–5 a month) | ~280 |
| **Baseline** | **~1,280** |
| **Left for full runs** | **~720**: about 15–25 labelled PR runs at ~28–50 minutes each |

Repository hygiene stays at one billed minute only because its whole-tree ratchet is skipped on a
private push to main (46s of the job's 65s on the private runner). See that step's comment in
`ci.yml`.

### How to check real usage

```bash
gh api organizations/Fynora/settings/billing/usage/summary
```

`actions_linux` → `grossQuantity` is minutes used this month. `netAmount` above 0 means the
included minutes have run out.

A labelled PR run is an ordinary pull_request run, so it is path-filtered. Its ~19 minutes is the
measured week's average, 5,737 job-minutes over 302 PR runs.

There is one difference from a public PR run. When the PR touches the backend, the labelled run
also runs the backend's integration tests (`./mvnw verify`, not `./mvnw test`). In the private
profile a push to main runs only repo-hygiene, so otherwise the integration tests would never run.

The integration tests run in three parallel shards (`backend-integration`) plus a summary job.
Measured on 2026-10-04, on the first full run of the split:
- **Shards:** 209s, 266s and 296s.
- **Summary job:** under 10s.
- **Extra cost:** about 13 job-minutes on top of the unit-only backend job, on public runners.
  Budget ~33 minutes for a labelled backend PR there.

The split runs faster but costs more minutes in total. Each shard compiles the backend and starts
its own database. Before the split, one job ran everything in 781s.

On the private runners, the first `full-ci` run (a change to `ci.yml`, so every area ran) billed
65 minutes. A by-hand (`workflow_dispatch`) or weekly run always runs everything, so budget ~65
minutes for one. Every push to a PR that still carries `full-ci` costs another run.

Dependabot's own update jobs don't count toward the included minutes. GitHub's docs state this for
standard hosted runners. The CI runs that Dependabot PRs trigger do count, like any other PR's
runs.

These are estimates projected from one busy week, not a measured private month. Check real usage
in the org's billing page early in any private spell.

## Working while private

- **A PR's checks don't prove anything ran.** GitHub reports a skipped job as "Success", even
  for a required check. Before merging, give the PR a full run: label it, then push.

  ```bash
  gh pr edit <number> --add-label full-ci
  git commit --allow-empty -m "ci: full run" && git push
  ```

  - **The label alone starts nothing.** It's a condition the jobs check, not a trigger, so the
    push is what starts the run. A PR opened with `gh pr create --label full-ci` runs in full from
    its first push.
  - **Every push to a labelled PR runs in full,** so remove the label if the PR will take many
    more pushes.
  - **The run covers the whole PR**, not just the empty commit: `changes` diffs the PR's
    base..head. The empty commit disappears in the squash merge.
  - **It includes the backend's integration tests,** which a public PR run skips because main
    runs them after merge. In the private profile, main doesn't run them, so the labelled run is
    the only place they run.
  - **Draft PRs still skip the heavy jobs**, the same as in public. Mark the PR ready for review.
  - **Read the result on the PR.** A green check is real only if its job actually ran. In GitHub's
    UI a skipped job shows a grey "skipped" icon, not a green tick.

  Why the label isn't also a trigger (`on: pull_request: types: labeled`), measured on #1974:
  - A run that skips every job still creates a check run per job.
  - The PR's check list shows the *newest* run for each check name.
  - So labelling a PR after its real run replaced the real results with "skipped", which counts
    as passing. A red PR would have turned green.

  Why not a draft/ready toggle to re-trigger: `gh pr ready --undo` documents draft PRs as
  plan-dependent, and this has to work on a private repository on the Free plan.

  Why not `gh workflow run ci.yml --ref <branch>` (measured on #1974, 2026-10-04):
  - **The results wouldn't show on the PR.** A `workflow_dispatch` run's checks attach to the
    commit, but the PR's check list keeps showing the `pull_request` run's results. In this
    profile those are the skipped ones.
  - **It tests the branch alone,** not GitHub's merge of the PR into main.
  - **It narrows the customer-PII scan.** It gives repo-hygiene no base commit, so the scan covers
    only the branch's last commit.

  A labelled PR's run has none of these problems, because it is an ordinary `pull_request` run.
- **Production deploys don't wait for CI, in either profile.** Railway starts deploying each main
  commit as soon as it lands. Measured on 2026-10-03: commit `62f0ec5be` showed Railway "success"
  at 20:43 while its CI push run failed at 20:54. So the private profile doesn't weaken deploy
  gating, because there wasn't any. The labelled run before merging is the only full check a
  change gets before it reaches production.
- **Main's ruleset isn't enforced.** On GitHub Free, rulesets (required checks, no force-push, no
  deletion) only apply to public repositories. Nothing blocks a merge with failing or missing
  checks.
- **A merge that skipped the full run turns main red.** On every private push to main,
  repo-hygiene's last step (`scripts/check-merge-was-tested.py`) looks up the merged PR. It
  requires the latest `ci.yml` run on the PR's final commit to have finished `success` with
  `Detect changed areas` actually run, which is the marker of a full run.
  - It fails when:
    - the PR had no full run (label forgotten, or a push after the full run);
    - the last run failed, or was still going at merge time;
    - no PR produced the commit at all (a direct push).
  - The failure shows on main's CI run, and GitHub notifies whoever merged.
  - The fix is to test main by hand (`gh workflow run ci.yml --ref main`) and repair anything it
    finds.
  - Checked on 2026-10-04 against real PRs before shipping: a fully tested merge (#2003) passed; a
    lean-only PR (#2017), a failed-CI PR (#1880), a non-merge commit and an unknown PR all failed,
    each with its own reason. The "still running at merge time" branch had no live example and has
    not been exercised.
- **Main gets a full run every week.** `ci.yml`'s schedule runs every job against main on Monday
  at 04:30 UTC (10:00 IST). That catches two changes that each pass alone but break together, which
  per-PR runs cannot see. A failure notifies the account that last changed the cron line.
- **Keeping dev current takes two steps:** `gh workflow run ci.yml --ref main`, then once it
  passes, `gh workflow run sync-dev-branch.yml`.
- **Secret and customer-PII scanning, while private:**
  - On every local commit: `.husky/pre-commit` (gitleaks only where installed).
  - On every push to main: repo-hygiene's PII scan.
  - Every night: the full-history gitleaks run on main.
  - On labelled PRs: both scans.
  - Before flipping back to public: `scripts/check-branches-since.sh`, over every branch.

  GitHub's own secret-scanning push protection is free only on public repositories.

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

The cost is speed, in both profiles. When several merges land close together, their main runs now
run side by side instead of one after another. GitHub Free allows 20 concurrent jobs per account
on standard hosted runners (GitHub's Actions limits reference), so during a burst of merges, PR
runs can wait longer in the queue. Nothing is cancelled or skipped.
