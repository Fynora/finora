# Background jobs health, part 2: GitHub checks

**Status:** design, awaiting Sid's approval (2026-11-14). Part 1:
`docs/superpowers/specs/2026-11-13-background-jobs-health-design.md` (merged as #2550).

## Why

Part 1 shows the backend's own scheduled jobs. The checks that run on GitHub on their own are still
invisible unless someone opens GitHub: the private trace tests were red every night for four weeks
(17 Oct to 13 Nov) and nobody knew. On 14 Nov a look showed more of the same: the container image
scan had failed three nights running, the phone app tests had failed once, and the traffic metrics
job fails almost every run (it needs a token from Sid).

## Goals

1. The Background jobs page also lists these GitHub workflows, with the same states, the same
   dashboard row and the same emails (Sid chose option B):
   - private repo Fynora/finora-statement-traces: **Traces against public code** (daily 04:30 UTC),
     **Trace retention** (daily 05:00 UTC), **Privacy scan** (on push to main), **Claude fix**
     (on each held statement sent);
   - public repo Fynora/finora, nightly: **E2E full suite** (03:00), **Mobile E2E (Maestro)** (03:15),
     **Secret scan, full history** (03:30), **Container image scan** (04:00), **CodeQL** (Mondays
     04:00), **GitHub traffic metrics** (every 6 hours).
2. Each row says when the workflow last ran, whether it passed, and links to that run on GitHub.

## Non-goals

- CI on pull requests and on every merge to main (Sid: option B, not C; GitHub already shows those).
- Run logs, or why a step failed beyond its name: the link opens the run on GitHub.
- Re-running a workflow from the page.

## Anti-goals

- A row that is red for a designed outcome (a Claude fix whose public PR the guard refused, or whose
  fix broke tests) rather than for something being broken.
- A new key, endpoint or way into the backend from the internet (Sid: approach 1, not a webhook).
- Any customer data on the page or in emails: only workflow names, times, conclusions, step names.

## Approaches (Sid chose 1)

1. **The backend checks GitHub every 15 minutes** with the token it already holds
   (`APP_CLAUDE_FIX_GITHUB_TOKEN`: Actions read on the private repo; it already reads public PRs and
   private runs for the Claude fix job). Results go into the same status table, so page, dashboard
   and emails work unchanged. Up to 15 minutes late; while the token is expired the rows go stale.
2. A GitHub webhook: instant, but a new public endpoint and secret, and still blind to a run that
   never started. Rejected.
3. Each workflow reports to the backend: edits ten workflows and puts a backend key in public CI.
   Rejected.

## Design

### The list of workflows

A hand-written `GitHubWorkflowCatalog`, like part 1's job catalogue: per workflow its key
(`github:<repo>/<file>`, for example `github:Fynora/finora/e2e-nightly.yml`), a plain name and
purpose, the repo, the file, which runs count, its schedule (the cron from the workflow file, in UTC,
or none for push-triggered ones), and optionally "off, with a reason".

- **Which runs count:** runs on `main` only (branch runs are work in progress: that is how the
  privacy scan went red on 9 and 11 Nov). Skipped and cancelled runs are ignored; the latest
  completed run counts. A run still in progress shows "Running since".
- **Off:** GitHub traffic metrics starts Off ("needs a token from Sid"), so it is not red from day one.
  Turning it on is a one-line change once the token exists.

### Reading GitHub

A new scheduled job, `GitHubWorkflowPoller.poll()`, every 15 minutes (itself a catalogued backend job,
so a broken poller shows on the page too). Per workflow, one call:
`GET /repos/{repo}/actions/workflows/{file}/runs?branch=main&per_page=10`. Reuses the Claude fix job's
GitHub settings (`app.claude-fix.github-token`, `public-read-token`, `api-base`) and its request
headers; private repo with the main token, public repo with the public-read token when set, else the
main token (what the Claude fix job does today). About 40 calls an hour against a limit of 5,000.

When a call fails (no token, 401 expired, 403, 5xx, timeout), the poller reports a failed item for
that workflow (part 1's `BackgroundJobReport`), so the poller itself turns "Partly failed" with the
error's type, and the workflow's own row keeps its last known result and goes Overdue if it stays
stale. An expired token is therefore visible on the page and by email.

### Recording a run

For each workflow, the latest counted run is written to `background_job_status` under its key, only
when its run id differs from the one recorded last (new column `last_external_run_id`, migration
**V321**, the next free number on 14 Nov), so a failure is recorded once, not every 15 minutes:

- started = the run's `run_started_at`; finished = `updated_at` once completed;
- `success` → OK; `failure`, `timed_out`, `startup_failure` → **Failed** (stored as CRASHED; the page
  says "Failed" for GitHub rows); `action_required`, `neutral`, `stale` → OK with no change;
- a failed run adds a `background_job_failures` row whose summary is the names of the failed steps
  (`Failed: Trace privacy checks`), read with one more call
  (`GET /repos/{repo}/actions/runs/{id}/jobs`) for failed runs only. Step names are written by us, not
  by customers.

**Claude fix runs:** a failure whose only failed steps are designed outcomes counts as OK: "Read the
copy again, and run every test" (Claude's fix broke a test, so no PR) and "The guard refused the
public PR" (a person finishes it from the private PR). Any other failed step, such as yesterday's
"Token to open the PRs", is Failed. The catalogue entry lists those outcome steps by name.

### Overdue

Scheduled workflows use part 1's cron rule with a GitHub allowance of **3 hours** after the expected
UTC fire time (GitHub delays scheduled runs under load and silently disables them on idle repos).
Push-triggered workflows (privacy scan, Claude fix) are never Overdue. The cron is read in UTC, not the
JVM's zone. The "fire before the app started" exception does not apply: GitHub runs whether or not
the backend is up.

### Joining part 1's list

Part 1 lists jobs from Spring's scheduler (`JobScheduleRegistry`); the page service and the email
checker must also list the GitHub catalogue's entries, so both see every row. An entry marked Off is
Off from the catalogue alone, whatever its stored row says, and never emails.

**Running too long:** a GitHub run in progress for more than 6 hours (GitHub's own limit for a job) is
Overdue.

**Guard test:** each public-repo entry's file must exist under `.github/workflows/` (a renamed
workflow would otherwise go silently unread); private-repo files cannot be checked from here and are
listed in the catalogue's comment with the date they were confirmed.

### The page

GitHub rows join the same list, sorted by state as now, with a small "GitHub" tag; details add
"Open this run on GitHub" (the run's `html_url`; the private repo's links work only for Sid). Failure
history shows the failed step names. The duration line works as for backend jobs.

### Emails

Unchanged: part 1's checker treats GitHub rows like any job (broken, still broken after 24 hours,
working again), already switched on.

## Left to the builder

- Whether the poller extends `GitHubFixChannel` or uses a small client beside it with the same
  settings; how the page tells GitHub rows apart (key prefix or a field in the DTO).
- The plain names and purposes of the ten workflows.
- Confirm V321 is still free before adding it.
