# Background Jobs Health Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every run of every backend `@Scheduled` job, show each job's state on a new admin
page and the dashboard, and email admins when a job breaks, stays broken 24 h, or recovers.

**Architecture:** Spring Boot 3.5 already wraps every scheduled method in a Micrometer observation
(`ScheduledTasksObservabilityAutoConfiguration`). One `ObservationHandler<ScheduledTaskObservationContext>`
records start, finish and crash per job into one status row; a thread-local `BackgroundJobReport`
lets job code report failed items and "switched off". Schedules come from
`ScheduledAnnotationBeanPostProcessor.getScheduledTasks()`. A 5-minute checker computes overdue and
sends emails. The admin portal reads one list endpoint.

**Tech Stack:** Java 25, Spring Boot 3.5.16 (Spring 6.2.19), Micrometer Observation, JPA + Flyway
(Postgres), JUnit 5 + Testcontainers (`AbstractIntegrationTest`); admin portal React + TS +
react-query + vitest.

**Spec:** `docs/superpowers/specs/2026-11-13-background-jobs-health-design.md`

## Global Constraints

- States, exactly: **OK**, **Partly failed**, **Crashed**, **Overdue**, **Off** (API enum `OK`,
  `PARTLY_FAILED`, `CRASHED`, `OVERDUE`, `OFF`).
- Outcome order: escaped exception → `CRASHED`; else failed items > 0 → `PARTLY_FAILED`; else
  switched off → `OFF`; else `OK`.
- Overdue: fixed delay/rate — no start within `2 × interval + 5 min` of the last finish; cron — last
  start more than 30 min older than the most recent expected fire; started-never-finished on the same
  clock; after startup, `initialDelay + interval` grace; latest run across instances counts.
- Store an error as **type and code location only** (`SimpleName at Class:line`, first frame in
  `com.finora`), never its message.
- `background_job_failures` kept **30 days**; successful runs write no new row.
- Emails: **Broken** (first transition), **Still broken** (once, 24 h after Broken), **Working again**
  (first clean run after a Broken email). No others. Subject `Background job needs attention: <name>`.
  Times in IST. Recipients: admins with `PLATFORM_DIAGNOSTICS_VIEW`, via
  `UserRepository.findByPermissionNameAndAccountScope(..., User.SCOPE_ADMIN)`.
- Emails behind `app.background-job-alerts.enabled` / `APP_BACKGROUND_JOB_ALERTS_ENABLED`, default **false**.
- Recording must never throw into, or change, the job.
- Page and endpoints: permission `PLATFORM_DIAGNOSTICS_VIEW`; page in the sidebar's System group;
  works at phone width (375 px).
- Repo rules: no AI attribution in commits; migration version checked against `origin/main` before
  adding (latest today V316 → plan uses **V317**); regenerate `backend/openapi/openapi.json` with
  `backend/scripts/generate-openapi-spec.sh` and `admin-portal` types with `npm run generate:types`;
  never put real customer values in tests or comments.

## Review Focus

1. **Expected, customer-caused failures** — `ImportJobWorker` dead-letters a corrupt or locked PDF
   with `AlertSeverity.NONE`. That must not make the import worker "Partly failed". (Task 6 test.)
2. **Two backend instances** — both run every job and both run the checker. One state change must
   send one email, not two. (Task 5 test: conditional update claims the email.)
3. **Restart and removed jobs** — a fresh deploy must not show Overdue, and a status row for a job
   that no longer exists in code must not appear on the page or alert. (Task 3 and Task 4 tests.)
4. **Customer data in errors** — an exception whose message holds an email address stores and shows
   only type and location. (Task 2 test.)
5. **Flapping jobs** — a job that fails, recovers and fails again repeatedly must not send more than
   one Broken email per 24 h. *My call, beyond the spec's wording: a second Broken email for the same
   job is suppressed within 24 h of the previous one.* (Task 5 test.)

---

### Task 1: Job catalogue and the guard test

**Files:**
- Create: `backend/src/main/java/com/finora/observability/jobs/BackgroundJobCatalog.java`
- Test: `backend/src/test/java/com/finora/observability/jobs/BackgroundJobCatalogTest.java`

**Interfaces:**
- Produces: `record JobInfo(String name, String purpose)`;
  `static Optional<JobInfo> BackgroundJobCatalog.lookup(String key)`;
  `static Set<String> BackgroundJobCatalog.keys()`; key format `SimpleClassName.methodName`.

- [ ] **Step 1: Write the failing test** `everyScheduledMethodIsCatalogued_andNothingElse`: scan
  `com.finora` classes on the main classpath (Spring `ClassPathScanningCandidateComponentProvider`
  with a filter accepting any class, then reflect over declared methods) for `@Scheduled`; assert the
  set of keys equals `BackgroundJobCatalog.keys()`, listing missing and stale keys in the message.
  Second test `everyEntryHasAPlainNameAndPurpose`: name and purpose non-blank, purpose ≤ 120 chars.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=BackgroundJobCatalogTest` — expect FAIL
  (class missing).
- [ ] **Step 3: Implement** the catalogue as an immutable `Map<String, JobInfo>`: one entry per
  scheduled method (41 today, plus the checker added in Task 5), each with a plain name ("Account
  deletion sweep") and one-line purpose written from the method's own doc comment.
- [ ] **Step 4: Run the test** — expect PASS.
- [ ] **Step 5: Commit** `feat(backend): name every scheduled job in one catalogue`.

### Task 2: Storage and the run recorder

**Files:**
- Create: `backend/src/main/resources/db/migration/V317__background_job_runs.sql`
- Create: `backend/src/main/java/com/finora/observability/jobs/BackgroundJobStatus.java` (entity),
  `BackgroundJobFailure.java` (entity), `BackgroundJobStatusRepository.java`,
  `BackgroundJobFailureRepository.java`, `JobOutcome.java`, `BackgroundJobReport.java`,
  `BackgroundJobRecorder.java`, `BackgroundJobObservationHandler.java`
- Test: `.../observability/jobs/BackgroundJobReportTest.java`, `ErrorSummaryTest.java`,
  `BackgroundJobRecordingIT.java`

**Interfaces:**
- Consumes: catalogue keys (Task 1).
- Produces:
  - `enum JobOutcome { OK, PARTLY_FAILED, CRASHED, OFF }`
  - `BackgroundJobReport`: `static void itemFailed(Throwable cause)`, `static void handled(int count)`,
    `static void switchedOff()` — no-ops outside a recorded run.
  - `BackgroundJobRecorder`: `void started(String key, String instance, Instant at)`,
    `void finished(String key, String instance, Instant at, JobOutcome outcome, int failedItems, int handled, Throwable firstError)`
    — both catch and log every exception.
  - `static String ErrorSummary.of(Throwable t)` → `"DataIntegrityViolationException at AccountPurgeSweepService:212"`.
  - Table `background_job_status`: `job_key text PK, last_started_at, last_finished_at, last_outcome text,
    last_failed_items int, last_handled int, last_success_at, consecutive_failures int, broken_since,
    last_email_kind text, last_email_at, last_broken_email_at, last_instance text, updated_at`.
  - Table `background_job_failures`: `id uuid PK, job_key, occurred_at, outcome, failed_items, error_summary text`,
    index on `(job_key, occurred_at desc)` and on `occurred_at`.

- [ ] **Step 1: Write failing unit tests.** `BackgroundJobReportTest`:
  `outcomeOrder_crashBeatsFailedItemsBeatsOffBeatsOk`, `callsOutsideARun_doNothing`,
  `stateDoesNotLeakIntoTheNextRunOnTheSameThread`. `ErrorSummaryTest`:
  `keepsTypeAndFirstFinoraFrame_neverTheMessage` — throw `new IllegalStateException("bad row for someone@example.com")`
  from a `com.finora` class and assert the summary contains no `@` and no `someone`.
- [ ] **Step 2: Run them** — expect FAIL.
- [ ] **Step 3: Implement** `BackgroundJobReport` (thread-local per-run accumulator: failed count, first
  error, handled count, off flag; `begin()`/`end()` package-private for the handler), `ErrorSummary`,
  the migration, entities and repositories. `finished` updates the status row in place (upsert), sets
  `last_success_at` on `OK`/`OFF`, increments or resets `consecutive_failures`, sets/clears
  `broken_since`, and inserts a `background_job_failures` row only for `CRASHED`/`PARTLY_FAILED`.
  Each recorder call runs in its own `REQUIRES_NEW` transaction so a job's rollback can't erase it.
- [ ] **Step 4: Write the failing IT** `BackgroundJobRecordingIT extends AbstractIntegrationTest`
  with a `@TestConfiguration` bean holding four `@Scheduled(fixedDelay = 200)` methods (ok, one that
  calls `itemFailed` twice, one that throws, one that calls `switchedOff`). Await (≤ 10 s) status rows:
  outcomes `OK`, `PARTLY_FAILED` with `last_failed_items = 2`, `CRASHED`, `OFF`; failure rows exist
  only for the two failures. Plus `aRecorderFailureNeverReachesTheJob`: with the recorder's repository
  stubbed to throw, the ok job still runs repeatedly (a counter keeps rising).
  The IT's test methods are excluded from the catalogue guard by living under `src/test`.
- [ ] **Step 5: Implement** `BackgroundJobObservationHandler implements ObservationHandler<ScheduledTaskObservationContext>`
  as a `@Component`: `onStart` → `report.begin()` + `recorder.started(key(ctx), instance, now)`;
  `onError` → remember the error; `onStop` → compute outcome, `recorder.finished(...)`, `report.end()`.
  `key(ctx)` = `ctx.getTargetClass().getSimpleName() + "." + ctx.getMethod().getName()` (unwrap CGLIB
  names). Instance id = `HOSTNAME` env or `InetAddress.getLocalHost().getHostName()`, read once.
- [ ] **Step 6: Run** `./mvnw -q test -Dtest=BackgroundJobRecordingIT,BackgroundJobReportTest,ErrorSummaryTest` (ITs run under surefire here; `-Dtest` selects them)
  — expect PASS.
- [ ] **Step 7: Commit** `feat(backend): record every scheduled run's outcome`.

### Task 3: Live schedules and the overdue rule

**Files:**
- Create: `.../observability/jobs/JobSchedule.java`, `JobScheduleRegistry.java`, `OverdueRule.java`,
  `JobState.java`
- Test: `.../observability/jobs/OverdueRuleTest.java`, `JobScheduleRegistryIT.java`

**Interfaces:**
- Consumes: `BackgroundJobStatus` (Task 2).
- Produces:
  - `record JobSchedule(String key, Duration interval, Duration initialDelay, String cron, String description)`
    — exactly one of `interval`/`cron` set; `description` like "every 30 seconds", "daily at 03:45".
  - `JobScheduleRegistry.all(): List<JobSchedule>` from `ScheduledAnnotationBeanPostProcessor.getScheduledTasks()`
    (`IntervalTask` → interval + initial delay; `CronTask` → expression), keyed via the task's
    `ScheduledMethodRunnable`. `JobScheduleRegistry.startedAt(): Instant` (application ready time).
  - `enum JobState { OK, PARTLY_FAILED, CRASHED, OVERDUE, OFF }`
  - `static JobState OverdueRule.state(JobSchedule s, BackgroundJobStatus status /* nullable */, Instant appStartedAt, Instant now)`
  - `static Instant OverdueRule.overdueAt(JobSchedule s, BackgroundJobStatus status, Instant appStartedAt)`

- [ ] **Step 1: Write failing tests** in `OverdueRuleTest` (interval 30 s; grace = 2 × 30 s + 5 min):
  `fixedDelay_exactlyAtTheLimit_isNotOverdue`, `fixedDelay_oneSecondPast_isOverdue`,
  `cron_daily0345_30MinutesAfterAMissedFire_isOverdue_29MinutesIsNot`,
  `startedNeverFinished_isOverdueOnTheSameClock`,
  `freshStart_noRowYet_isNotOverdueBeforeInitialDelayPlusInterval`,
  `lastOutcomeWins_whenNotOverdue` (CRASHED/PARTLY_FAILED/OFF/OK pass through),
  `overdueBeatsAStaleOk`.
- [ ] **Step 2: Run** `-Dtest=OverdueRuleTest` — expect FAIL.
- [ ] **Step 3: Implement** `OverdueRule` per Global Constraints; cron's "most recent expected fire"
  via `CronExpression.parse(cron)` stepping forward from `now - 25 h` (zone: the scheduler's, i.e.
  JVM default as Spring uses it) and keeping the last fire ≤ `now`. Never-run jobs count from
  `appStartedAt`.
- [ ] **Step 4: Run** — expect PASS.
- [ ] **Step 5: Write and pass** `JobScheduleRegistryIT`: `listsEveryCataloguedJobThatIsScheduled`
  (registry keys ⊆ catalogue keys; a known interval job reports its configured interval) and
  `aCronDisabledWithDashIsNotListed` if any job uses `"-"` in tests' config (skip otherwise).
- [ ] **Step 6: Commit** `feat(backend): work out each job's schedule and when it is overdue`.

### Task 4: Admin endpoints and the dashboard count

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminBackgroundJobsController.java`,
  `.../observability/jobs/BackgroundJobsService.java`
- Modify: `backend/src/main/java/com/finora/dto/AdminDtos.java` (append to `NeedsAttentionDto`),
  `backend/src/main/java/com/finora/service/AdminOperationalDashboardService.java:148-163`,
  `backend/openapi/openapi.json` (regenerated)
- Test: `backend/src/test/java/com/finora/controller/AdminBackgroundJobsControllerIT.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `GET /api/v1/admin/background-jobs` → `List<BackgroundJobDto>` where
    `record BackgroundJobDto(String key, String name, String purpose, String schedule, JobState state,
    Instant lastStartedAt, Instant lastFinishedAt, Instant lastSuccessAt, int lastFailedItems,
    int consecutiveFailures, Instant brokenSince, boolean running)`; ordered broken
    (CRASHED, PARTLY_FAILED, OVERDUE) first, then OFF, then OK; ties by name.
  - `GET /api/v1/admin/background-jobs/{key}/failures` → `List<BackgroundJobFailureDto(Instant occurredAt,
    JobOutcome outcome, int failedItems, String errorSummary)>`, last 30 days, newest first; 404 for an
    unknown key.
  - `BackgroundJobsService.countNeedingAttention(): long`
  - `NeedsAttentionDto` gains a final component `long backgroundJobsNeedingAttention`.

- [ ] **Step 1: Write the failing IT**: `listRequiresDiagnosticsPermission` (403 without
  `PLATFORM_DIAGNOSTICS_VIEW`), `listsOnlyLiveJobs_brokenFirst` (insert a status row for a key not in
  the registry → absent; a CRASHED row sorts first), `failuresAreTypeAndLocationOnly_newestFirst`,
  `unknownKeyIs404`, `dashboardCountsBrokenJobs`.
- [ ] **Step 2: Run** it — expect FAIL.
- [ ] **Step 3: Implement** the service (joins `JobScheduleRegistry.all()` with status rows; state via
  `OverdueRule.state` at request time) and controller, class-level
  `@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")`; append the count to `NeedsAttentionDto`
  and fill it in `AdminOperationalDashboardService`.
- [ ] **Step 4: Run** — expect PASS; also run `AdminOperationalDashboard*` tests.
- [ ] **Step 5: Regenerate** the OpenAPI spec (`backend/scripts/generate-openapi-spec.sh`, local
  Postgres as in CI) and commit it with the code: `feat(backend): background jobs endpoints and dashboard count`.

### Task 5: The checker and the emails

**Files:**
- Create: `.../observability/jobs/BackgroundJobAlertChecker.java`, `BackgroundJobAlertEmails.java`
- Modify: `BackgroundJobCatalog.java` (add the checker), `backend/src/main/resources/application.yml`
  (`app.background-job-alerts.enabled: ${APP_BACKGROUND_JOB_ALERTS_ENABLED:false}`,
  `app.background-job-alerts.interval-ms: 300000`), `BackgroundJobStatusRepository` (claim query)
- Test: `.../observability/jobs/BackgroundJobAlertDecisionTest.java`, `BackgroundJobAlertCheckerIT.java`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces:
  - `enum AlertKind { BROKEN, STILL_BROKEN, WORKING_AGAIN }`
  - `static Optional<AlertKind> BackgroundJobAlertDecision.decide(JobState state, BackgroundJobStatus status, Instant now)`
  - `int BackgroundJobStatusRepository.claimEmail(String key, String expectedKind /* nullable */, String newKind, Instant at)`
    — `UPDATE ... SET last_email_kind = :newKind, last_email_at = :at[, last_broken_email_at = :at]
    WHERE job_key = :key AND last_email_kind IS NOT DISTINCT FROM :expectedKind`; returns rows updated.

- [ ] **Step 1: Write failing tests** `BackgroundJobAlertDecisionTest`:
  `firstBreak_sendsBroken`, `stillBrokenAfter24h_sendsStillBrokenOnce`,
  `cleanRunAfterBrokenEmail_sendsWorkingAgain`, `cleanWithNoBrokenEmail_sendsNothing`,
  `secondBreakWithin24hOfLastBroken_sendsNothing`, `offIsNeverBroken`.
- [ ] **Step 2: Run** — expect FAIL. **Step 3: Implement** the decision.
- [ ] **Step 4: Write failing IT** `BackgroundJobAlertCheckerIT` (email provider replaced by a
  recording fake): `brokenJob_emailsEachDiagnosticsAdminOnce`,
  `twoCheckersAtOnce_sendOneEmail` (call `check()` from two threads; the claim lets one win),
  `flagOff_sendsNothingButStillPrunes`, `prunesFailuresOlderThan30Days`,
  `emailBody_namesTheJob_whatHappened_sinceWhenInIst_andLinksToThePage` (link
  `EmailProperties.getAdminAppBaseUrl() + "/background-jobs?job=" + key`; assert no error message text appears).
- [ ] **Step 5: Implement** `BackgroundJobAlertChecker.check()` — `@Scheduled(fixedDelayString =
  "${app.background-job-alerts.interval-ms:300000}")`; for each live job: decide, claim, then send via
  `EmailProvider.send(EmailMessage.html(...))` to each recipient (catch per recipient, mask in logs
  with `EmailMasking.mask`); delete failures older than 30 days. Body HTML escaped with `HtmlUtils`,
  times formatted in `Asia/Kolkata`. Link base: `EmailProperties.getAdminAppBaseUrl()`, as
  `HeldItemAdminAlertService.heldImportLink` uses.
- [ ] **Step 6: Run** both — expect PASS; run `BackgroundJobCatalogTest` (checker catalogued).
- [ ] **Step 7: Commit** `feat(backend): email admins when a background job breaks or recovers`.

### Task 6: Reports from the jobs themselves

**Files:**
- Modify: `backend/src/main/java/com/finora/observability/WorkerExecution.java` and each of the 40
  classes with a `@Scheduled` method, where needed.
- Test: `backend/src/test/java/com/finora/observability/WorkerExecutionReportTest.java`, plus one
  focused test per changed pattern listed below.

**Interfaces:**
- Consumes: `BackgroundJobReport` (Task 2).

- [ ] **Step 1: Write failing tests** `WorkerExecutionReportTest`:
  `deadLetteredWithErrorOrWarning_reportsAFailedItem`, `deadLetteredWithNone_reportsNothing`
  (Review Focus 1), `retryScheduled_reportsNothing`, `failureNotRecorded_reportsAFailedItem`,
  `heldWithoutReviewRecord_reportsAFailedItem`.
- [ ] **Step 2: Run** — expect FAIL. **Step 3: Implement** the forwarding inside `WorkerExecution`.
  **Step 4: Run** — PASS.
- [ ] **Step 5: Inventory the 40 classes** (`grep -rl -E "^\s*@Scheduled\(" backend/src/main/java`).
  For each scheduled method, record in the PR description one line: class, and which of these applied
  — (a) a catch that swallows one item's failure → add `BackgroundJobReport.itemFailed(e)` in it;
  (b) an early `return` on a disabled flag (`if (!enabled) return;`) → call
  `BackgroundJobReport.switchedOff()` before it; (c) a catch around the whole run that logs and
  returns → `itemFailed(e)`; (d) nothing to add (rethrows, or no catch). Catches that are a normal
  control-flow path (an expected "not found", an optimistic-lock retry that then succeeds) stay as
  they are, with the reason in the inventory line.
- [ ] **Step 6: Add tests** for representative cases: `AccountPurgeSweepServiceTest` —
  one account's purge throwing reports one failed item while the others are purged;
  `GmailDiscoveryWorkerTest` — disabled flag reports switched off.
- [ ] **Step 7: Run** the touched classes' existing tests and the new ones — PASS.
- [ ] **Step 8: Commit** `feat(backend): background jobs report failed items and switched-off runs`.

### Task 7: The admin portal page

**Files:**
- Create: `admin-portal/src/pages/BackgroundJobs.tsx`, `admin-portal/src/pages/BackgroundJobs.test.tsx`
- Modify: `admin-portal/src/api/generated-types.ts` (regenerated), `admin-portal/src/types/index.ts`,
  `admin-portal/src/api/endpoints.ts` (add `adminBackgroundJobsApi`), `admin-portal/src/App.tsx`
  (route `/background-jobs`), `admin-portal/src/components/Sidebar.tsx` (System group, after
  "System Health", permission `PLATFORM_DIAGNOSTICS_VIEW`), `admin-portal/src/lib/needsAttentionItems.ts`
  (+ its test)

**Interfaces:**
- Consumes: Task 4 endpoints.
- Produces: `adminBackgroundJobsApi.list(): Promise<BackgroundJobDto[]>`,
  `adminBackgroundJobsApi.failures(key: string): Promise<BackgroundJobFailureDto[]>`.

- [ ] **Step 1: Write failing tests** (mock `../api/endpoints` as `SystemHealth.test.tsx` does):
  `showsBrokenJobsFirstWithTheirState`, `headerCountsJobsNeedingAttention`,
  `offJobsReadSwitchedOffNotBroken`, `tappingAJobShowsItsFailures`, `aFailedLoadShowsLoadError`
  (not an empty list), `jobQueryParamOpensThatJob` (`?job=<key>` from the email link);
  `needsAttentionItems` test `backgroundJobsRowLinksToThePage_onlyWhenNonZero`.
- [ ] **Step 2: Run** `cd admin-portal && npx vitest run src/pages/BackgroundJobs.test.tsx src/lib/needsAttentionItems.test.ts`
  — expect FAIL.
- [ ] **Step 3: Implement** the page per the spec's sketch: state chip, name, purpose, schedule in
  words, last run (IST, relative), last success, runs in a row failed, "Running since …" when
  `running`; expandable failures list. Phone width: one column, no horizontal scroll, 44 px tap
  targets (as on the held-statement screens). Add the route, sidebar entry and needs-attention row
  ("background job needs attention" / "background jobs need attention", link "Open Background jobs").
- [ ] **Step 4: Run** tests, `npm run lint`, `npx tsc --noEmit` — expect PASS.
- [ ] **Step 5: Commit** `feat(admin-portal): background jobs page and dashboard row`.

### Task 8: Verify end to end and open the PR

- [ ] **Step 1:** `cd backend && ./mvnw -q verify` (full suite, ITs included; nothing else may commit while it runs) — PASS. Note any
  unrelated flake by name and rerun it alone.
- [ ] **Step 2:** `cd admin-portal && npx vitest run && npm run lint && npx tsc --noEmit` — PASS.
- [ ] **Step 3: Local stack check** (Postgres + Redis containers, jar on port 18080, portal via vite):
  open `/background-jobs` at 375 px and desktop; every catalogued job listed; switched-off jobs show
  Off; temporarily point one job's interval very low and make it throw (local only, not committed) —
  it shows Crashed, the dashboard row appears, and with `APP_BACKGROUND_JOB_ALERTS_ENABLED=true` one
  email is logged by the dev email provider. Screenshot both widths.
- [ ] **Step 4:** Confirm `V317` is still free on `origin/main`; rebase if not.
- [ ] **Step 5:** Push and open the PR (repo is public: PRs open without asking). Body: what it does,
  the Task 6 inventory, how emails are switched on (set `APP_BACKGROUND_JOB_ALERTS_ENABLED=true`
  after a day of watching the page), screenshots, and test commands run.
