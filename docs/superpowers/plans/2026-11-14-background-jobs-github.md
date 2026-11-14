# Background Jobs Health Part 2 (GitHub checks) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ten GitHub workflows (4 private-repo, 6 public nightlies) appear on the admin Background jobs page with the same states, dashboard row and emails as the backend's own jobs.

**Architecture:** A catalogue lists the workflows; a new scheduled poller reads each one's latest `main` run every 15 minutes with the Claude fix job's existing GitHub settings and writes it into part 1's `background_job_status` under `github:<repo>/<file>`. `JobScheduleRegistry` merges the catalogue into its list, so the page service and the email checker see GitHub rows with no other change. `JobSchedule` gains a source so `OverdueRule` applies GitHub's rules.

**Tech Stack:** Java 25, Spring Boot 3.5, `java.net.http.HttpClient`, JDBC; JDK `HttpServer` for test stubs (as `GitHubFixChannelTest`); admin portal React + vitest.

**Spec:** `docs/superpowers/specs/2026-11-14-background-jobs-github-design.md` (part 1: `docs/superpowers/specs/2026-11-13-background-jobs-health-design.md`)

## Global Constraints

- Keys: `github:<owner>/<repo>/<file>`, e.g. `github:Fynora/finora/e2e-nightly.yml`.
- Only runs on branch `main` count; `skipped` and `cancelled` runs are ignored; the latest other run counts.
- Conclusions: `success` → OK; `failure`, `timed_out`, `startup_failure` → CRASHED (page label "Failed" for GitHub rows); anything else → OK.
- Claude fix (`fix.yml`): a failure whose failed steps are only `Read the copy again, and run every test` and/or `The guard refused the public PR` → OK.
- Failure summary: `Failed: <step>, <step>` (step names only).
- A run is recorded once: only when its run id differs from `last_external_run_id`, or it was running and has now completed.
- Overdue (GitHub, scheduled): 3 hours after the most recent UTC cron fire with no run started at or after it; a run in progress for more than 6 hours; no "before the app started" exception. Push-triggered (no cron): never overdue.
- Off: GitHub traffic metrics, reason "Needs a GitHub token from Sid"; Off rows never email.
- Poll every 15 minutes (`app.background-jobs.github.poll-interval-ms`, default 900000; initial delay 60000). A failed read reports a failed item for the poller and leaves that workflow's row unchanged.
- Migration **V321** (next free on 14 Nov; re-check on `origin/main` before adding).
- Repo rules: no AI attribution in commits; regenerate `backend/openapi/openapi.json` and all three clients' generated types; never print a token.

## Review Focus

1. **Token missing or expired** (401/403, blank token) → the poller is Partly failed with the error type; GitHub rows keep their last result and go Overdue when stale; no crash. (Task 3 test.)
2. **A failed run seen on every 15-minute poll** → recorded once, so one failure row and one email, not one every 15 minutes. (Task 3 test.)
3. **Claude fix outcomes** → a refused public PR or a fix that broke tests is OK; a failed token step is Failed. (Task 3 test.)
4. **GitHub delays a nightly run 2 hours** → still OK; 3 h 1 min after the fire with no run → Overdue. (Task 2 test.)
5. **Traffic metrics** → shows Off from the catalogue even if a stored row says CRASHED, and sends no email. (Task 2 and Task 4 tests.)

---

### Task 1: Workflow catalogue and run storage

**Files:**
- Create: `backend/src/main/java/com/finora/observability/jobs/github/GitHubWorkflow.java`, `GitHubWorkflowCatalog.java`
- Create: `backend/src/main/resources/db/migration/V321__background_job_external_runs.sql`
- Modify: `backend/src/main/java/com/finora/observability/jobs/BackgroundJobStatus.java` (append `Long lastExternalRunId, String lastExternalUrl`), `BackgroundJobStore.java` (read them; add `recordExternalRun`), every test constructing `BackgroundJobStatus` (append `null, null`)
- Test: `backend/src/test/java/com/finora/observability/jobs/github/GitHubWorkflowCatalogTest.java`, `backend/src/test/java/com/finora/observability/jobs/BackgroundJobStoreExternalRunIT.java`

**Interfaces:**
- Produces:
  - `record GitHubWorkflow(String key, String name, String purpose, String repo, String file, String cronUtc /*nullable*/, String offReason /*nullable*/, Set<String> outcomeSteps)` with `static String keyFor(String repo, String file)`.
  - `GitHubWorkflowCatalog.all(): List<GitHubWorkflow>` (10 entries, crons from the spec: traces `30 4 * * *`, retention `0 5 * * *`, privacy scan none, fix none, e2e `0 3 * * *`, maestro `15 3 * * *`, gitleaks `30 3 * * *`, image scan `0 4 * * *`, CodeQL `0 4 * * 1`, traffic metrics `0 */6 * * *` + offReason) and `find(String key): Optional<GitHubWorkflow>`. Crons are 5-field GitHub crons; convert to Spring's 6-field form with a leading `0` where used.
  - `BackgroundJobStore.recordExternalRun(String key, long runId, String url, Instant startedAt, Instant finishedAt /*null while running*/, JobOutcome outcome /*null while running*/, String failureSummary): boolean` — returns true when it wrote; writes only if `last_external_run_id IS DISTINCT FROM runId` or the stored run is unfinished and this one is finished. On a finished write it applies part 1's success/failure counters and adds a failure row for CRASHED.
  - V321: `ALTER TABLE background_job_status ADD COLUMN last_external_run_id BIGINT, ADD COLUMN last_external_url TEXT;`

- [ ] **Step 1: Failing tests.** `GitHubWorkflowCatalogTest`: `tenWorkflows_uniqueKeys_keysMatchRepoAndFile`, `everyPublicRepoFileExists` (reads `../.github/workflows/<file>` from the backend module), `onlyTrafficMetricsIsOff`, `fixYmlListsItsTwoOutcomeSteps`. `BackgroundJobStoreExternalRunIT`: `aRunIsWrittenOnce` (same id twice → second returns false, one failure row), `aRunningRunThenItsFinishIsWritten`, `aNewRunReplacesTheOld_andSuccessClearsTheStreak`.
- [ ] **Step 2: Run** `./mvnw -q -o test -Dtest='GitHubWorkflowCatalogTest,BackgroundJobStoreExternalRunIT'` — FAIL (missing classes).
- [ ] **Step 3: Implement** the records, catalogue, migration and store method (single upsert with the run-id guard in its `WHERE`; failure row in the same transaction).
- [ ] **Step 4: Run** the two tests plus `BackgroundJobRecordingIT,OverdueRuleTest,BackgroundJobAlertDecisionTest` — PASS.
- [ ] **Step 5: Commit** `feat(backend): catalogue the GitHub workflows the jobs page watches, and store their runs`.

### Task 2: GitHub rows join the schedule list, with GitHub's overdue rules

**Files:**
- Modify: `JobSchedule.java` (append `Source source, String offReason`; `enum Source { BACKEND, GITHUB }`), `JobScheduleRegistry.java` (append one `JobSchedule` per catalogue entry, `description` like "daily at 04:30 UTC", "on each push to main"), `OverdueRule.java`, all `new JobSchedule(...)` call sites (append `JobSchedule.Source.BACKEND, null`)
- Test: `OverdueRuleTest.java` (new cases), `JobScheduleRegistryIT.java` (new case)

**Interfaces:**
- Consumes: `GitHubWorkflowCatalog.all()` (Task 1).
- Produces: `OverdueRule.state(...)` returns `OFF` when `schedule.offReason() != null`; GitHub rules per Global Constraints.

- [ ] **Step 1: Failing tests** in `OverdueRuleTest`: `github_nightlyDelayed2h_isNotOverdue`, `github_3h1mAfterTheFireWithNoRun_isOverdue` (UTC fire regardless of JVM zone), `github_runningOver6h_isOverdue`, `github_pushTriggered_isNeverOverdue`, `github_fireBeforeTheAppStarted_stillCounts`, `anOffEntryIsOff_whateverItsRowSays`. In `JobScheduleRegistryIT`: `listsTheTenGitHubWorkflowsAfterTheBackendJobs` (keys present, source GITHUB, traffic metrics offReason set).
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** those plus `AdminBackgroundJobsControllerIT,BackgroundJobAlertCheckerIT` — PASS.
- [ ] **Step 5: Commit** `feat(backend): GitHub workflows join the background jobs list with their own overdue rules`.

### Task 3: The poller

**Files:**
- Create: `observability/jobs/github/GitHubRunsClient.java`, `GitHubWorkflowPoller.java`
- Modify: `BackgroundJobCatalog.java` (entry `GitHubWorkflowPoller.poll`: "GitHub checks reader", "Reads the latest run of each GitHub check the jobs page watches."), `application.yml` (`app.background-jobs.github.poll-interval-ms`, `initial-delay-ms`)
- Test: `GitHubRunsClientTest.java`, `GitHubWorkflowPollerTest.java`

**Interfaces:**
- Consumes: `ClaudeFixProperties` (`getGithubToken`, `getPublicReadToken`, `getApiBase`, `getPrivateRepo`), `BackgroundJobStore.recordExternalRun` (Task 1), `BackgroundJobReport.itemFailed`.
- Produces:
  - `record Run(long id, String status, String conclusion, Instant runStartedAt, Instant updatedAt, String htmlUrl)`;
  - `GitHubRunsClient.mainRuns(String repo, String file): List<Run>` (`GET /repos/{repo}/actions/workflows/{file}/runs?branch=main&per_page=10`), `failedSteps(String repo, long runId): List<String>` (`GET /repos/{repo}/actions/runs/{id}/jobs`, names of steps with conclusion `failure`); same headers and timeouts as `GitHubFixChannel`; private repo uses the main token, other repos the public-read token when set; throws `GitHubReadException(String what, int status)` on non-2xx or I/O; never includes the token in a message.
  - `GitHubWorkflowPoller.poll()` `@Scheduled(fixedDelayString="${app.background-jobs.github.poll-interval-ms:900000}", initialDelayString="${app.background-jobs.github.initial-delay-ms:60000}")`; `static JobOutcome outcomeOf(GitHubWorkflow w, Run run, List<String> failedSteps)`.

- [ ] **Step 1: Failing tests.** `GitHubRunsClientTest` (JDK `HttpServer`): `readsMainRunsWithTheRightTokenPerRepo`, `failedStepsAreTheStepsThatFailed`, `a401IsAGitHubReadException_withoutTheToken`. `GitHubWorkflowPollerTest` (client and store mocked): `aNewFailedRunIsRecordedOnceWithItsStepNames`, `skippedAndCancelledRunsAreIgnored`, `aRunInProgressIsRecordedAsRunning`, `fixRefusedOrTestsFailedIsOk_tokenStepIsFailed`, `aReadFailureIsAFailedItem_andOtherWorkflowsStillRead`, `offWorkflowsAreNotRead`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** **Step 4: Run** plus `BackgroundJobCatalogTest` — PASS.
- [ ] **Step 5: Commit** `feat(backend): read the GitHub checks every 15 minutes`.

### Task 4: API, email and page

**Files:**
- Modify: `BackgroundJobDto.java` (append `String source, String runUrl, String offReason`), `BackgroundJobsService.java`, `BackgroundJobAlertEmails.java` (GitHub CRASHED reads "its last run failed"; GitHub rows link to the page as now), `backend/openapi/openapi.json` + 3 clients' `generated-types.ts` (regenerated), `admin-portal/src/types/index.ts`, `admin-portal/src/pages/BackgroundJobs.tsx`
- Test: `AdminBackgroundJobsControllerIT.java`, `BackgroundJobAlertCheckerIT.java`, `admin-portal/src/pages/BackgroundJobs.test.tsx`

- [ ] **Step 1: Failing tests.** IT: `githubRowsCarrySourceRunUrlAndOffReason`; checker IT: `aFailedGitHubRunEmailsOnce_saysFailed`, `anOffGitHubRowNeverEmails`. Portal: `githubRowsShowTagFailedLabelAndRunLink`, `offGitHubRowShowsItsReason`.
- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** Page: a small "GitHub" tag next to the name; label "Failed" instead of "Crashed" for GitHub rows; in details "Open this run on GitHub" (`target="_blank" rel="noopener noreferrer"`) when `runUrl`; Off rows show the reason instead of "Switched off on purpose".
- [ ] **Step 4: Run** the tests; regenerate the OpenAPI spec and types; run portal `vitest`, `tsc -b`, lint on Node 22 — PASS.
- [ ] **Step 5: Commit** `feat: GitHub checks on the background jobs page and in its emails`.

### Task 5: Verify and open the PR

- [ ] `./mvnw clean verify` (all phases, 0 failures); portal suite, `tsc -b`, lint.
- [ ] Private trace tests against this branch (it touches nothing under `imports/`; run anyway only if Task 1–4 touched it).
- [ ] Local stack with a stubbed GitHub API base (`-Dapp.claude-fix.api-base=<local stub>` serving fixture runs; the env-var form of that property does not bind): page at 375 px and desktop shows GitHub rows, tag, "Failed", link, Off reason.
- [ ] Confirm V321 free; push; open the PR with what was checked.
