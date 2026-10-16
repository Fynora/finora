# Gate 2a: Learn Why Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **On this repository, Sid has ruled out subagents: execute natively with superpowers:executing-plans.**

**Goal:** Record where every user stops on the way to a first statement, ask them what stopped them, keep an unlinked reason when someone deletes their account, and show it all on one admin page. Also ship the observed-session kit.

**Architecture:**
- **Backend:** a new `com.finora.activation` package holds three tables (`import_funnel_days`,
  `activation_answers`, `account_deletion_reasons`), one signed-in controller for clients and one admin
  controller for the report. The per-user tables follow `user_activity_days` (V249): IST date only,
  covered by the export and the purge.
- **Clients:** fire-and-forget calls, plus one shared question component per app.

**Tech Stack:** Spring Boot 3 / JPA / Flyway / Postgres; React + Vite + Vitest (web, admin-portal);
React Native + Expo + Jest (mobile).

**Spec:** `docs/superpowers/specs/2026-10-16-gate2a-learn-why-design.md` (approved 16 Oct, including
all copy).

## Global Constraints

**Git and PRs**
- Work in a fresh worktree from `origin/main` per PR. Never write in `/Users/sid/Downloads/finora`.
- **Commit messages never carry a `Co-Authored-By` or any AI-attribution trailer.** PR descriptions
  end with "🤖 Generated with [Claude Code](https://claude.com/claude-code)".
- **Commit format:** type(scope): lowercase subject under 100 characters. Scopes come from the
  commitlint list. This plan uses `analytics`, `users`, `imports`, `frontend`, `mobile`,
  `admin-portal` and `docs`.
- **The repository is public:** open PRs automatically. Never commit tester names, emails, user-id
  fragments or real statement values.
- **Squash merges:** before every push, check by file content what is already on `origin/main`.
  Never reuse a squash-merged branch.
- **Never commit while `./mvnw verify` is running.**

**Database**
- **Migrations:** fetch `origin/main` and take the next free `V` number at build time (V274 is the
  latest at writing). Never edit an existing migration.

**Commands**
- **Node 22 for every app command:** put `/Users/sid/.npm/_npx/52027bd8fc0022aa/node_modules/node/bin`
  first on PATH, inside a script written with the Write tool and run with `bash`. The worktree Bash
  guard refuses computed values in git/npx commands.
- **Backend unit test:**
  `backend/mvnw -o -f backend/pom.xml test -Dtest=<Class>`
- **Backend IT:**
  `backend/mvnw -o -f backend/pom.xml verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Class> -Dfailsafe.failIfNoSpecifiedTests=false`
- **Full backend:** `backend/mvnw -o -f backend/pom.xml verify`
- **OpenAPI regeneration** after any DTO or endpoint change:
  1. `backend/mvnw -q -o -f backend/pom.xml -DskipTests package`
  2. Start a throwaway `postgres:16-alpine` on port 5432.
  3. Run `backend/scripts/generate-openapi-spec.sh`.
  4. Run `npm run generate:types` in `frontend`, `mobile` and `admin-portal`.
  5. Commit the regenerated files.

**Behaviour rules**
- **Dates:** every date is the calendar day in `app.platform.reporting-zone` (default Asia/Kolkata).
  No time of day is stored anywhere in this plan.
- **No free text:** no question accepts typed text.
- **Fixed answer lists:** the server holds them. Clients send only enum names. An unknown name is a
  400.
- **Fire and forget:** funnel calls are never retried, never shown, and never delay a screen. Errors
  are swallowed.
- **Mobile releases:** JS ships by OTA, never to 1.0.0 builds. The OTA itself is Sid's action, not a
  task here.
- **Copy is fixed by the spec.** Use the exact strings in this plan.

**Verification**
- **Before every PR:** build, tests, lint and typecheck for every app touched, the full backend
  `verify` when backend changed, then a self-review of the diff for bugs and gaps (CLAUDE.md
  "Mandatory post-implementation verification").

## Review Focus

1. **The same answer twice in a day** (double tap, or two devices). The second is refused with 409,
   and the component hides without an error. Test: `secondAnswerSameDayIsRefused` (Task 6) and the
   409 case in each client test (Tasks 7, 8).
2. **IST midnight.** A request at 18:29 UTC and one at 18:31 UTC fall on different dates. Test:
   `todayUsesReportingZoneNotUtc` (Task 2).
3. **A deletion refused after a reason was chosen** (expired OTP session). No reason row is written.
   Test: `reasonNotStoredWhenDeletionRefused` (Task 9).
4. **The report counting the wrong people.** ADMIN-scope, internal, pending-deletion and deleted
   accounts are excluded. Test: `reportExcludesAdminInternalAndDeletedAccounts` (Task 12).
5. **An answer from another moment's list,** e.g. `NO_STATEMENT` for `REFUSED`. 400, nothing stored.
   Test: `answerFromAnotherMomentIsRefused` (Task 6).

---

## PR 1: Observed-session kit (documents only)

### Task 1: The kit

**Files:**
- Create: `docs/research/gate2/README.md`, `session-guide.md`, `notes-template.md`,
  `recruitment-message.md`, `consent.md`

**Content:**
- **`README.md`:** what the kit is, that Sid runs or attends each session, and that filled notes and
  recordings never enter the repository.
- **`session-guide.md`:** spec §7 word for word:
  - who: up to 10 real users, at least 5, split 4 / 3 / 3 across the groups;
  - the three parts and their exact prompts;
  - the five questions;
  - the rule "record each problem before helping", and that the unaided and assisted outcomes are
    recorded separately;
  - the privacy rules.
- **`notes-template.md`:**
  - a header with session number, group and date, and no name;
  - a two-column table: "What they did" | "What they said";
  - fields for the unaided outcome and the assisted outcome;
  - "Barriers, in the order hit";
  - a closing line: "Never write amounts, names, account numbers or merchants."
- **`recruitment-message.md`:** one message per group (never uploaded / tried and failed / got data
  in), each stating about 30 minutes, their own statement, and nothing recorded without asking.
- **`consent.md`:**
  - wording for the start of the session;
  - wording before any recording;
  - that a recording is deleted once the notes are written and never shared.

- [ ] **Step 1: Write the five files.**
- [ ] **Step 2: Privacy check.** Run
  `grep -rnE "@|[0-9a-f]{8}|HLD-" docs/research/gate2/`. Expected: no output.
- [ ] **Step 3: Commit, push, open the PR, and send Sid the five files with SendUserFile.**

```bash
git add docs/research/gate2
git commit -m "docs(analytics): the gate 2 observed-session kit"
```

---

## PR 2: The import-funnel record (server)

### Task 2: Table, reporting day and endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__import_funnel_days.sql`
- Create (`backend/src/main/java/com/finora/activation/`):
  - `FunnelEvent.java`
  - `ImportFunnelDay.java`
  - `ImportFunnelDayRepository.java`
  - `ReportingDay.java`
  - `ImportFunnelService.java`
  - `ActivationController.java`
  - `ActivationDto.java`
- Test:
  - `backend/src/test/java/com/finora/activation/ReportingDayTest.java`
  - `backend/src/test/java/com/finora/activation/ImportFunnelIT.java`

**Interfaces:**
- **Produces:**
  - `enum FunnelEvent { IMPORT_SCREEN_OPENED, FILE_CHOSEN, REVIEW_OPENED }`
  - `ReportingDay` (`@Component`):
    - `LocalDate today()`
    - public ctor `@Value("${app.platform.reporting-zone:Asia/Kolkata}") String zone`, with an invalid
      zone falling back to Asia/Kolkata as `UserActivityInterceptor` does;
    - package-private ctor `ReportingDay(Clock clock)`.
  - `ImportFunnelDayRepository extends JpaRepository<ImportFunnelDay, UUID>`:
    - `void record(UUID userId, String event, LocalDate date)`: native
      `INSERT … ON CONFLICT (user_id, event, funnel_date) DO NOTHING`, `@Transactional @Modifying`;
    - `List<ImportFunnelDay> findByUserIdOrderByFunnelDateAscEventAsc(UUID userId)`;
    - `boolean existsByUserIdAndEvent(UUID userId, FunnelEvent event)`;
    - `boolean existsByUserIdAndEventAndFunnelDateBefore(UUID userId, FunnelEvent event, LocalDate date)`;
    - `@Modifying(clearAutomatically = true, flushAutomatically = true) int deleteByUserId(UUID userId)`,
      as JPQL `DELETE`, like `UserActivityDayRepository`.
  - `ImportFunnelService.record(UUID userId, FunnelEvent event)`.
  - `ActivationDto.FunnelEventRequest(@NotNull FunnelEvent event)`.
  - `POST /api/v1/activation/funnel-events` → `ApiResponse<Void>`. The user is always
    `currentUser.id()`.

**Migration:**
- Columns: `id UUID PK DEFAULT gen_random_uuid()`, `user_id UUID NOT NULL REFERENCES users(id) ON
  DELETE CASCADE`, `event VARCHAR(32) NOT NULL CHECK (event IN ('IMPORT_SCREEN_OPENED','FILE_CHOSEN','REVIEW_OPENED'))`,
  `funnel_date DATE NOT NULL`.
- `UNIQUE (user_id, event, funnel_date)`, plus an index on `funnel_date`.
- No `created_at`. Say why in the header comment: the date only, per the privacy promise, as in V249.

- [ ] **Step 1: Write the failing tests.**

  `ReportingDayTest`:
  ```java
  @Test void todayUsesReportingZoneNotUtc() {
      assertThat(new ReportingDay(Clock.fixed(Instant.parse("2026-10-16T18:29:00Z"), ZoneId.of("Asia/Kolkata"))).today())
          .isEqualTo(LocalDate.of(2026, 10, 16));
      assertThat(new ReportingDay(Clock.fixed(Instant.parse("2026-10-16T18:31:00Z"), ZoneId.of("Asia/Kolkata"))).today())
          .isEqualTo(LocalDate.of(2026, 10, 17));
  }
  ```

  `ImportFunnelIT` (extends `AbstractIntegrationTest`, signed-in user):
  - `recordsEachEventOncePerDay`: POST `FILE_CHOSEN` twice and `IMPORT_SCREEN_OPENED` once. Assert
    two rows, dated `reportingDay.today()`.
  - `unknownEventIsRefused`: POST `{"event":"FILE_UPLOADED"}`. Assert 400 and no row.
  - `missingEventIsRefused`: POST `{}`. Assert 400.
  - `userIsAlwaysTheCaller`: POST with an extra `"userId":"<other>"` field. Assert the row belongs to
    the caller.
  - `unauthenticatedIsRefused`: Assert 401.

- [ ] **Step 2: Run both tests and confirm they fail** (the classes don't exist yet).
- [ ] **Step 3: Implement** the migration, entity, repository, `ReportingDay`, service and controller.
  `ReportingDay` replaces nothing: `UserActivityInterceptor` keeps its own clock.
- [ ] **Step 4: Run both tests and confirm they pass.**
- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "feat(analytics): record the import screen, file chosen and review opened, one row per day"
  ```

### Task 3: Export, purge, privacy line, OpenAPI

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DataExportService.java`.
  - New entry `import_funnel_days.json`: a list of `{event, date}`.
  - Manifest text: "The dates you opened the import screen, chose a file to upload and opened an import's review -- the date only, never the time, the file or anything in it."
- Modify: `backend/src/main/java/com/finora/dto/DataExportDto.java`. Add record
  `ImportFunnelDayExportDto(String event, String date)` and a bundle field.
- Modify: `backend/src/main/java/com/finora/service/AccountPurgeSweepService.java`. Call
  `importFunnelDayRepository.deleteByUserId(userId)` beside `userActivityDayRepository.deleteByUserId`
  (around line 808).
- Modify these tests, which construct the bundle or the service by hand:
  - `DataExportServiceTest.java`: add `Map.entry("importFunnelDayRepository.deleteByUserId", "import_funnel_days.json")` to the purge-vs-export map.
  - `DataExportServiceIT.java`
  - `AccountPurgeSweepServiceIT.java`
  - `AccountPurgeSweepServiceTest.java`
- Modify: `frontend/src/pages/Privacy.tsx`, the paragraph at about line 170. Append the interim
  sentence below. Task 6 inserts the answers clause.
- Test: `frontend/src/pages/Privacy.test.tsx`.

**Interim privacy sentence** (the approved spec §8 text, minus the answers clause):

> We also record the dates on which you opened the import screen, chose a file to upload and opened
> an import's review — the date only, never the time, the file or anything in it — so we can see
> where adding a statement gets difficult. These records are linked to your account and are deleted
> if you delete your account.

- [ ] **Step 1: Write the failing tests.**
  - `AccountPurgeSweepServiceIT.purgeDeletesImportFunnelDays`: record a row, purge, assert none.
  - `DataExportServiceIT.exportIncludesImportFunnelDays`: record two rows, export, assert
    `import_funnel_days.json` has two entries with `event` and `date`, and no other keys.
  - `Privacy.test.tsx`: the page contains "opened the import screen, chose a file to upload".
- [ ] **Step 2: Run them and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass.** Then run the full `DataExportServiceTest`,
  `AccountPurgeSweepServiceTest` and `AccountPurgeSweepServiceIT`.
- [ ] **Step 5: Regenerate OpenAPI and the types in all three apps.**
- [ ] **Step 6: Full backend `verify`, web tests/lint/typecheck. Commit, push, open the PR.**

---

## PR 3: Funnel events in the apps

### Task 4: Web

**Files:**
- Create: `frontend/src/lib/trackFunnel.ts`
- Modify: `frontend/src/api/endpoints.ts`. Add `activationApi` with
  `funnelEvent(event: FunnelEvent): Promise<void>`, which POSTs `/activation/funnel-events`.
- Modify: `frontend/src/pages/Import.tsx`
- Test:
  - `frontend/src/lib/trackFunnel.test.ts`
  - `frontend/src/pages/Import.test.tsx`

**Interfaces:**
- **Produces:**
  - `export type FunnelEvent = 'IMPORT_SCREEN_OPENED' | 'FILE_CHOSEN' | 'REVIEW_OPENED'`
  - `export function trackFunnel(event: FunnelEvent): void`, which calls `activationApi.funnelEvent`
    and swallows any rejection.

**Placement in `Import.tsx`:**
- **`IMPORT_SCREEN_OPENED`:** once per mount, in a `useEffect`.
- **`FILE_CHOSEN`:** first statement of `handleFile` (line 446), before the type check, once per
  mount. Guard it with a ref. Both the drop zone (line 1382) and the input (line 1418) go through
  `handleFile`.
- **`REVIEW_OPENED`:** once per mount, the first time `step` becomes `'review'`.

- [ ] **Step 1: Write the failing tests.**
  - `trackFunnel.test.ts`: `swallowsFailure` (the API rejects, nothing throws, no unhandled rejection)
    and `postsTheEventName`.
  - `Import.test.tsx`, with the funnel API mocked:
    - `sendsScreenOpenedOnce`: mount and re-render. Assert one call.
    - `sendsFileChosenForARefusedFileType`: choose `notes.txt`. Assert `FILE_CHOSEN` was sent and the
      type error is shown.
    - `sendsFileChosenOncePerVisit`: choose two files. Assert one call.
    - `sendsReviewOpenedWhenReviewShows`: drive a staged upload to review. Assert one call.
    - `funnelFailureNeverShows`: the API rejects. Assert no error text is rendered.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full web suite, lint and typecheck.
- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "feat(frontend): send the three import funnel moments"
  ```

### Task 5: Mobile

**Files:**
- Create: `mobile/src/lib/trackFunnel.ts`, with the same interface as web.
- Modify: `mobile/src/api/endpoints.ts` (`activationApi.funnelEvent`)
- Modify: `mobile/src/screens/import/ImportScreen.tsx`
- Test:
  - `mobile/src/lib/trackFunnel.test.ts`
  - `mobile/src/screens/import/ImportScreen.test.tsx`

**Placement in `ImportScreen.tsx`:** one visit is one focus of the Import tab. Reset the per-visit
refs on each focus.
- **`IMPORT_SCREEN_OPENED`:** in `useFocusEffect`, on each focus.
- **`FILE_CHOSEN`:** first statement of `applyPicked` (line 426), once per visit. Both the picker
  and the share sheet reach `applyPicked`.
- **`REVIEW_OPENED`:** once per visit, the first time `step` becomes `'review'`.

- [ ] **Step 1: Write the failing tests.** Mirror Task 4, plus `sendsFileChosenForASharedFile`
  (drive `sharedFileParam`).
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full mobile suite, `tsc --noEmit` and
  `eslint src --max-warnings 0`.
- [ ] **Step 5: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(mobile): send the three import funnel moments"
  ```

---

## PR 4: "What stopped you?"

### Task 6: Answers, limits and the next-question endpoint (server)

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__activation_answers.sql`
- Create (`backend/src/main/java/com/finora/activation/`):
  - `ActivationMoment.java`
  - `ActivationAnswer.java`
  - `ActivationAnswerEntry.java`
  - `ActivationAnswerRepository.java`
  - `ActivationFacts.java`
  - `ActivationQuestionService.java`
- Modify:
  - `ActivationController.java`, `ActivationDto.java`
  - `DataExportService.java`, `DataExportDto.java`, `AccountPurgeSweepService.java`, and the four
    tests named in Task 3
  - `frontend/src/pages/Privacy.tsx` (+ test)
- Test:
  - `backend/src/test/java/com/finora/activation/ActivationQuestionIT.java`
  - `backend/src/test/java/com/finora/activation/ActivationAnswerTest.java`

**Interfaces:**
- **Consumes:** `ReportingDay.today()`, `ImportFunnelDayRepository.exists…` (Task 2).
- **Produces:**
  - `enum ActivationMoment { NO_FILE, REFUSED, NOT_CONFIRMED }`
  - `enum ActivationAnswer`:
    - `NO_STATEMENT, NOT_COMFORTABLE, DIDNT_KNOW_HOW, NO_POINT_YET, NOT_NOW` (NO_FILE);
    - `TRY_ANOTHER_FILE, NO_OTHER_STATEMENT, DONT_UNDERSTAND_FAILURE, LEAVE_FOR_NOW` (REFUSED);
    - `ROWS_LOOKED_WRONG, WRONG_FILE, NOT_READY_TO_SAVE, COME_BACK_LATER` (NOT_CONFIRMED);
    - `SKIPPED` (any moment);
    - method `boolean allowedFor(ActivationMoment m)`.
  - `ActivationAnswerRepository`:
    - `existsByUserIdAndAnswerDate(UUID, LocalDate)`
    - `existsByUserIdAndMomentAndAnswerDateAfter(UUID, ActivationMoment, LocalDate)`
    - `findByUserIdOrderByAnswerDateAsc(UUID)`
    - `deleteByUserId(UUID)` (JPQL, as in Task 2)
  - `ActivationFacts` (`@Component`, `JdbcTemplate`):
    - `boolean hasEverImported(UUID userId)`: any `statement_imports` row, deleted ones included, via
      a native `EXISTS`, which bypasses `@SQLRestriction`;
    - `boolean hasUnconfirmedExpirySince(UUID userId, Instant since)`: a
      `statement_analysis_sessions` row with `expired_unconfirmed_at >= since`.
  - `ActivationQuestionService`:
    - `Optional<ActivationMoment> nextDashboardQuestion(UUID userId)`
    - `void answer(UUID userId, ActivationMoment moment, ActivationAnswer answer)`
  - `GET /api/v1/activation/question` → `ApiResponse<ActivationDto.QuestionResponse(ActivationMoment moment)>`,
    where `moment` is null for none.
  - `POST /api/v1/activation/answers` with body
    `ActivationDto.AnswerRequest(@NotNull ActivationMoment moment, @NotNull ActivationAnswer answer)`.
    Returns 400 if `!answer.allowedFor(moment)`, and 409 (`ApiException(HttpStatus.CONFLICT, …)`) if
    the limits refuse it.

**Migration:**
- Columns: `id`, `user_id … REFERENCES users(id) ON DELETE CASCADE`, `moment VARCHAR(16) NOT NULL`
  with a CHECK on the three moments, `answer VARCHAR(32) NOT NULL` with a CHECK on the enum names,
  and `answer_date DATE NOT NULL`.
- Index `(user_id, answer_date)`. No uniqueness: one user answers many times over months.

**Rules** (spec §4.3 as clarified 16 Oct; `today = reportingDay.today()`):
- **Nothing today** if `existsByUserIdAndAnswerDate(user, today)`.
- **A moment is blocked** if `existsByUserIdAndMomentAndAnswerDateAfter(user, m, today.minusDays(30))`.
- **`NOT_CONFIRMED` is eligible** if `hasUnconfirmedExpirySince(user, now − 7 days)`.
- **`NO_FILE` is eligible** if all of these hold:
  - `existsByUserIdAndEventAndFunnelDateBefore(user, IMPORT_SCREEN_OPENED, today)`;
  - `!existsByUserIdAndEvent(user, FILE_CHOSEN)`;
  - `!hasEverImported(user)`.
- **`nextDashboardQuestion` checks `NOT_CONFIRMED` first**, then `NO_FILE`. `REFUSED` is never a
  dashboard question.
- **`answer()`** enforces the day and moment blocks for every moment, then stores the answer.

**Privacy paragraph:** replace Task 3's interim sentence with spec §8's first paragraph, verbatim:

> We also record the dates on which you opened the import screen, chose a file to upload and opened
> an import's review, and any answer you tap when Fynora asks what stopped you. We record the date
> only, never the time, the file or anything in it, so we can see where adding a statement gets
> difficult. These records are linked to your account and are deleted if you delete your account.

**Export:**
- New entry `activation_answers.json`: a list of `{moment, answer, date}`.
- Manifest text: "Your answers when Fynora asked what stopped you, with the date only."
- Purge, export and test-map entries as in Task 3.

- [ ] **Step 1: Write the failing tests.**

  `ActivationAnswerTest`: every non-SKIPPED value is allowed for exactly one moment, and `SKIPPED` is
  allowed for all three.

  `ActivationQuestionIT` (fixed `ReportingDay` via `@MockitoSpyBean` or a test `Clock` bean, the
  same approach other ITs use for time):
  - `noFileAskedTheDayAfterOpeningImport`: funnel `IMPORT_SCREEN_OPENED` yesterday. Assert
    `NO_FILE`.
  - `noFileNotAskedOnTheSameDay`: opened today. Assert none.
  - `noFileNotAskedAfterAFileWasChosen`
  - `noFileNotAskedOnceDataIsIn`: a `statement_imports` row, including a soft-deleted one.
  - `notConfirmedAskedForAnExpiryWithinSevenDays`, and `notConfirmedNotAskedForAnExpiryEightDaysOld`.
  - `notConfirmedAskedEvenWithDataIn`
  - `notConfirmedComesBeforeNoFile`
  - `answeredMomentNotAskedForThirtyDays`: answer on day D; asked again on D+31 but not D+29.
  - `secondAnswerSameDayIsRefused`: Assert 409 and one row.
  - `answerFromAnotherMomentIsRefused`: Assert 400 and no row.
  - `skipCountsAsAnAnswer`
  - `unknownAnswerNameIsRefused`: Assert 400.
  - `purgeDeletesAnswers` (in `AccountPurgeSweepServiceIT`)
  - `exportIncludesAnswers` (in `DataExportServiceIT`)
  - **Queued-path check:** `expiryStampedForAQueuedImport`. Stage a queued import (as
    `ImportJobReviewStateIT` does), expire its session via the sweep, and assert
    `hasUnconfirmedExpirySince` is true.
    - **If it fails,** the sweep does not stamp queued sessions. Then extend `ActivationFacts` to
      also return true for a COMPLETED `import_jobs` row whose review state is EXPIRED
      (`ImportJobReviewStates.of(job, now)`) and whose `finished_at` is within 9 days (48 h TTL + 7
      days). Keep the test, and write the outcome in the PR description.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the tests named in Task 3 and `Privacy.test.tsx`.
- [ ] **Step 5: Regenerate OpenAPI and the types.**
- [ ] **Step 6: Commit.**
  ```bash
  git commit -m "feat(analytics): one-tap answers to what stopped you, with daily and 30-day limits"
  ```

### Task 7: Web questions and the onboarding copy

**Files:**
- Create:
  - `frontend/src/lib/activationQuestions.ts`
  - `frontend/src/components/activation/WhatStoppedYou.tsx`
  - `frontend/src/components/activation/ActivationQuestionCard.tsx`
- Modify:
  - `frontend/src/api/endpoints.ts`: `activationApi.question()` and `activationApi.answer(moment, answer)`
  - `frontend/src/pages/Import.tsx`, `frontend/src/components/ImportTimeline.tsx`
  - `frontend/src/pages/Dashboard.tsx`
  - `frontend/src/onboarding/FinancialFocusScreen.tsx`
- Test: a sibling `*.test.tsx` for each created or modified component.

**Interfaces:**
- **Consumes:** the Task 6 endpoints. The types come from `generated-types.ts`.
- **Produces:**
  - `ACTIVATION_QUESTIONS: Record<ActivationMoment, { question: string; answers: { key: ActivationAnswer; label: string }[] }>`
  - `SKIP_LABEL = 'Skip'`
  - `<WhatStoppedYou moment onDone? />`: renders the question, one button per answer and a Skip
    button. A tap posts the answer, then shows "Thanks." and calls `onDone`. A 409 or any failure
    calls `onDone` silently.
  - `<ActivationQuestionCard />`: queries `['activation','question']`. Renders nothing when `moment`
    is null. Otherwise renders a card with `WhatStoppedYou` and a close button (aria-label "Skip"),
    which posts `SKIPPED`.

**Copy** (spec §4.1, verbatim):

| Moment | Question | Answers, in order |
|---|---|---|
| NO_FILE | "Last time you looked at adding a statement. What stopped you?" | "I don't have my statement" · "I'm not comfortable uploading it" · "I didn't know how" · "I didn't see the point yet" · "Not now" |
| REFUSED | "What will you do now?" | "Try another file" · "I don't have another statement" · "I don't understand why it failed" · "Leave it for now" |
| NOT_CONFIRMED | "What made you stop?" | "The rows looked wrong" · "I uploaded the wrong file" · "I'm not ready to save it" · "I'll come back to it" |

**Placement:**
- **`REFUSED`:**
  - In the `Import.tsx` error banner, only when the banner came from a server response carrying an
    import `errorCode`. That is the `catch` around line 751 when `e.response` exists. Never for
    network errors or the client-side type check.
  - In `ImportTimeline.tsx`'s failed state, below the reason.
- **`NOT_CONFIRMED`:** on the upload step, after `discardReviewSessionAndStartOver` succeeds. Hide it
  when a new file is chosen or it is answered.
- **Never during an action:** the question hides whenever an upload, read or confirm is in progress.
- **Dashboard:** `ActivationQuestionCard` directly above `ChecklistWidget` in `Dashboard.tsx`.
- **Onboarding copy:** in `FinancialFocusScreen.tsx`, "Select all that apply. We'll personalize your
  experience." becomes "Select all that apply. It helps us understand what you want from Fynora."

- [ ] **Step 1: Write the failing tests.**
  - `WhatStoppedYou.test.tsx`:
    - `rendersEachMomentsExactCopy`
    - `postsTheTappedAnswer`
    - `skipPostsSkipped`
    - `conflictHidesWithoutError` (the API returns 409)
    - `noTextInput` (no `input` or `textarea` is rendered)
  - `ActivationQuestionCard.test.tsx`: `rendersNothingWhenNoQuestion`, `closeButtonPostsSkipped`.
  - `Import.test.tsx`:
    - `refusalAsksWhatNow` (a server 4xx with an import errorCode);
    - `networkErrorDoesNotAsk`;
    - `wrongFileTypeDoesNotAsk`;
    - `cancelImportAsksWhatMadeYouStop`;
    - `questionHiddenWhileUploading`.
  - `ImportTimeline.test.tsx`: `failedTimelineAsksWhatNow`.
  - `FinancialFocusScreen.test.tsx`: the new subtitle is shown and "personalize" is absent.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full web suite, lint and typecheck.
- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "feat(frontend): ask what stopped you at a refusal, a cancelled review and on the dashboard"
  ```

### Task 8: Mobile questions and the onboarding copy

**Files:**
- Create: `mobile/src/lib/activationQuestions.ts`,
  `mobile/src/components/activation/WhatStoppedYou.tsx`,
  `mobile/src/components/activation/ActivationQuestionCard.tsx`. Same interfaces and copy as Task 7.
- Modify:
  - `mobile/src/api/endpoints.ts`
  - `mobile/src/screens/import/ImportScreen.tsx`, `mobile/src/components/ImportProgressCard.tsx`
  - `mobile/src/screens/DashboardScreen.tsx`
  - `mobile/src/onboarding/FinancialFocusScreen.tsx`
- Test: sibling tests.

**Placement:**
- **`REFUSED`:**
  - Where `ImportScreen` sets an error from `importFailureMessage(apiErrorCode(e))` (lines 651 and
    740), only when `apiErrorCode(e)` is non-null.
  - In `ImportProgressCard`'s failed state.
- **`NOT_CONFIRMED`:** after the review's "Cancel" button (line 1599, `resetToUpload`), on the upload
  step.
- **Dashboard:** the card directly above `ChecklistWidget`.
- **Copy:** the same onboarding subtitle change as web (line 43).
- **Testing note:** use `not.toBeOnTheScreen()` rather than `waitFor(() => toBeNull())` (repository
  gotcha).

- [ ] **Step 1: Write the failing tests,** mirroring Task 7's names.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full mobile suite, `tsc --noEmit` and eslint.
- [ ] **Step 5: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(mobile): ask what stopped you at a refusal, a cancelled review and on the dashboard"
  ```

---

## PR 5: A reason when someone deletes their account

### Task 9: Server

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__account_deletion_reasons.sql`
- Create: `backend/src/main/java/com/finora/activation/DeletionReason.java`,
  `AccountDeletionReason.java`, `AccountDeletionReasonRepository.java`
- Modify: `backend/src/main/java/com/finora/dto/AccountLifecycleDtos.java`:
  `DeleteAccountRequest(@NotBlank String sessionId, DeletionReason reason)`. `reason` is optional.
- Modify: `backend/src/main/java/com/finora/service/UserAccountLifecycleService.java`.
  - `requestDeletion(UUID userId, String sessionId, DeletionReason reason)`.
  - Keep `requestDeletion(UUID, String)` delegating with `null`.
- Modify: `backend/src/main/java/com/finora/controller/UserController.java:190` to pass the reason.
- Test: `backend/src/test/java/com/finora/activation/AccountDeletionReasonIT.java`

**Interfaces:**
- **Produces:**
  - `enum DeletionReason { COULDNT_GET_STATEMENTS_IN, NOT_COMFORTABLE_WITH_DATA, NOTHING_USEFUL, USE_ANOTHER_APP, DONT_NEED_IT, SOMETHING_ELSE }`
  - Table `account_deletion_reasons`: `id`, `reason VARCHAR(32) NOT NULL` with a CHECK on the enum
    names, `deletion_month DATE NOT NULL` (the first day of the month), `had_data BOOLEAN NOT NULL`.
    **No `user_id`, no foreign key, no other column.**

**Write point:**
- Inside the existing transaction in `requestDeletion`, after `consumeForAccountDeletion` succeeds
  and before the purge.
- `deletion_month = reportingDay.today().withDayOfMonth(1)`.
- `had_data = activationFacts.hasEverImported(userId)` (Task 6).
- A null `reason` writes nothing.

- [ ] **Step 1: Write the failing tests** (`AccountDeletionReasonIT`, driving the real OTP-backed
  delete flow as the existing deletion ITs do):
  - `reasonStoredWithMonthAndNoLink`: Assert one row with the month and `had_data=false`. Assert
    the table has exactly the columns `id, reason, deletion_month, had_data`, checked via
    `information_schema.columns`.
  - `hadDataTrueWhenAStatementWasImported`
  - `noReasonWritesNothing`
  - `reasonNotStoredWhenDeletionRefused`: an invalid or expired session. Assert a 400 and no row.
  - `unknownReasonIsRefused`: Assert a 400, the account is not deleted, and no row.
  - `reasonSurvivesPurge`: after the purge completes, the row is still present.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the existing account-deletion ITs.
- [ ] **Step 5: Regenerate OpenAPI and the types. Commit.**
  ```bash
  git commit -m "feat(users): keep an optional reason for deleting an account, with no link to the account"
  ```

### Task 10: The question in both apps, and the privacy sentence

**Files:**
- Modify: `frontend/src/components/DeleteAccountModal.tsx` (the `'confirm'` step) and
  `frontend/src/api/endpoints.ts` (`deleteAccount(sessionId, reason?)`).
- Modify: `mobile/src/screens/settings/DeleteAccountSheet.tsx` and `mobile/src/api/endpoints.ts`.
- Modify: `frontend/src/pages/Privacy.tsx` (+ test), the "Data Retention" section.
- Test: `DeleteAccountModal.test.tsx`, `DeleteAccountSheet.test.tsx`.

**Copy** (spec §5, verbatim):
- Question: "Before you go: what's the main reason?"
- Answers, as single-choice radios in this order, mapped to the enum in order:
  1. "I couldn't get my statements in"
  2. "I'm not comfortable with my data here"
  3. "It didn't show me anything useful"
  4. "I use another app"
  5. "I don't need it anymore"
  6. "Something else"
- Nothing selected sends no reason. The delete button never depends on a selection.

**Privacy sentence** (spec §8, verbatim):

> If you tell us why you are deleting your account, we keep that answer with the month only and no
> link to you or your account, so it is not deleted with your account.

- [ ] **Step 1: Write the failing tests.**
  - `DeleteAccountModal.test.tsx` (and the sheet's):
    - `sendsTheChosenReason`
    - `sendsNoReasonWhenNoneChosen`
    - `deleteEnabledWithoutAReason`
  - `Privacy.test.tsx`: the sentence is present.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full web and mobile suites, lint and
  typecheck.
- [ ] **Step 5: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(users): ask why someone is leaving, on web and mobile"
  ```

---

## PR 6: The activation page

### Task 11: The internal-account flag

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__users_internal_account.sql`. It holds
  `ALTER TABLE users ADD COLUMN internal_account BOOLEAN NOT NULL DEFAULT FALSE`.
- Modify: `backend/src/main/java/com/finora/entity/User.java` (field `internalAccount`, getter and
  setter).
- Modify: `AdminUserController.java`:
  - add `PUT /{id}/internal`, `@PreAuthorize("hasAuthority('USER_UPDATE')")`;
  - body `AdminInternalAccountRequest(@NotNull Boolean internal)`;
  - returns `ApiResponse<UserSummaryDto>`.
- Modify: `AdminUserService` (`setInternal(UUID actorId, UUID userId, boolean internal)`) and
  `UserDetailDto` (`internalAccount`).
- Modify: `admin-portal/src/pages/UserDetail.tsx`. Add a toggle, "Internal or test account (left out
  of activation counts)", shown when the account is USER-scope and the viewer holds `USER_UPDATE`.
- Test: `backend/src/test/java/com/finora/controller/AdminUserInternalAccountIT.java`,
  `admin-portal/src/pages/UserDetail.test.tsx`.

**Rules:**
- An ADMIN-scope target gets a 400.
- Each change is audited with `auditService.record(actorId, "USER_INTERNAL_ACCOUNT_SET", "User", userId, Map.of("internal", internal))`.

- [ ] **Step 1: Write the failing tests.**
  - `AdminUserInternalAccountIT`: `setsAndClearsTheFlag`, `auditsEachChange`,
    `refusesAdminScopeAccount`, `needsUserUpdate`.
  - `UserDetail.test.tsx`: `togglesInternalFlag`, `hiddenWithoutUserUpdate`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "feat(admin-portal): mark an account as internal so activation counts leave it out"
  ```

### Task 12: The report (server)

**Files:**
- Create (`backend/src/main/java/com/finora/activation/`):
  - `FunnelStep.java`
  - `FunnelSteps.java`
  - `ActivationFunnelService.java`
  - `ActivationReportDto.java`
  - `AdminActivationController.java`
- Test:
  - `backend/src/test/java/com/finora/activation/FunnelStepsTest.java`
  - `backend/src/test/java/com/finora/activation/ActivationReportIT.java`

**Interfaces:**
- **Produces:**
  - `enum FunnelStep { SIGNED_UP, OPENED_IMPORT, CHOSE_FILE, REFUSED, READ_FAILED, HELD, READ_NOT_REVIEWED, REVIEWED_NOT_CONFIRMED, GOT_DATA_IN }`.
    The declaration order is the rank.
  - `record FunnelSteps.UserFacts(boolean openedImport, boolean choseFile, boolean refused, boolean readFailed, boolean held, boolean read, boolean reviewOpened, boolean imported)`
  - `static FunnelStep FunnelSteps.furthest(UserFacts f)`: the highest step whose fact is true, or
    `SIGNED_UP`.
  - `ActivationReportDto`:
    - `LocalDate funnelGoLive`: the earliest `funnel_date`, or null;
    - `List<WeekRow> weeks`, where `WeekRow(LocalDate weekStart, Map<FunnelStep, Long> counts)`;
    - `List<AnswerCount> answers`, where `AnswerCount(ActivationMoment moment, ActivationAnswer answer, long count)`;
    - `List<StepAnswerCount> answersByStep`;
    - `List<StepLabelCount> focusByStep`;
    - `List<StepLabelCount> trackingByStep`, where `StepLabelCount(FunnelStep step, String label, long count)`
      and `label` is `"NONE"` when unanswered;
    - `List<DeletionReasonCount> deletionReasons`, where
      `DeletionReasonCount(LocalDate month, DeletionReason reason, long count)`.
  - `GET /api/v1/admin/analytics/activation?from=YYYY-MM-DD&to=YYYY-MM-DD`:
    - `@PreAuthorize("hasAuthority('PLATFORM_ANALYTICS_VIEW')")`;
    - defaults: `to` = today, `from` = `to` − 90 days;
    - the range filters `answers` and `answersByStep` only.

**Facts per user:** one native query, one row per eligible user.
- **Eligible:** `account_scope = 'USER' AND internal_account = false AND status NOT IN ('PENDING_DELETION','DELETED')`.
- **Week:** `date_trunc('week', created_at AT TIME ZONE :zone)::date`.
- **Facts,** each an `EXISTS`:

| Fact | True when |
|---|---|
| openedImport | an `import_funnel_days` row with `IMPORT_SCREEN_OPENED` |
| choseFile | an `import_funnel_days` row with `FILE_CHOSEN` |
| refused | `statement_analysis_sessions.outcome = 'REJECTED'` |
| readFailed | `outcome = 'FAILED'`, or `import_jobs.status = 'FAILED'` |
| held | `import_jobs.status IN ('HELD_FOR_REVIEW','HELD_FOR_TRUST_REVIEW')`, or any `held_statements` row |
| read | `outcome = 'PARSED'`, or `import_jobs.status = 'COMPLETED'` |
| reviewOpened | an `import_funnel_days` row with `REVIEW_OPENED` |
| imported | any `statement_imports` row |

- **Classify** in Java with `FunnelSteps.furthest`, then count. Answers and onboarding answers join on
  user id inside the service. **No user id, email or name leaves the service.**

- [ ] **Step 1: Write the failing tests.**
  - `FunnelStepsTest`:
    - `noFactsIsSignedUp`
    - `importedWinsOverEverything`
    - `heldRanksAboveReadFailed`
    - `reviewOpenedRanksAboveRead`
  - `ActivationReportIT`, which builds one user per step:
    - `countsEachUserAtTheirFurthestStep`
    - `groupsBySignupWeekInIst`: a user created at 19:00 UTC on a Sunday lands in the next
      (Monday) week.
    - `reportExcludesAdminInternalAndDeletedAccounts`
    - `answersFilteredByRange`
    - `focusAndTrackingIncludeNone`
    - `deletionReasonsByMonth`
    - `responseHasNoUserIdentifiers`: the serialized JSON has no UUID pattern and no email pattern.
    - `needsPlatformAnalyticsView`
    - `funnelGoLiveIsEarliestDate`
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Regenerate OpenAPI and the types. Commit.**
  ```bash
  git commit -m "feat(analytics): an activation report of where each signup stopped and what they said"
  ```

### Task 13: The admin page

**Files:**
- Create: `admin-portal/src/pages/Activation.tsx` (+ `Activation.test.tsx`)
- Modify:
  - `admin-portal/src/App.tsx`: route `/analytics/activation`, lazy-loaded.
  - `admin-portal/src/components/Sidebar.tsx` (+ test):
    - new entry `{ to: '/analytics/activation', label: 'Activation', permission: 'PLATFORM_ANALYTICS_VIEW' }`;
    - set the Platform Analytics entry to `end: true`, so both entries are not highlighted at once.
  - `admin-portal/src/api/endpoints.ts`: `activationReport(from, to)`.

**Page:** spec §6, four sections, counts only.
1. **Funnel by signup week.** Columns in `FunnelStep` order. Column labels:
   "Signed up", "Opened import", "Chose a file", "Refused", "Read failed", "Held", "Read, not
   reviewed", "Reviewed, not confirmed", "Got data in".
2. **Answers per moment,** with a date-range control.
3. **Answers, focus and tracking by step.**
4. **Deletion reasons by month.**

Under the title, two lines:
- "Funnel steps that use the import-funnel record count from {funnelGoLive}." (or "The import-funnel
  record has no rows yet.")
- "Counts are small; one person changes them."

- [ ] **Step 1: Write the failing tests.**
  - `Activation.test.tsx`:
    - `rendersStepColumnsInOrder`
    - `showsGoLiveLine`
    - `showsEmptyGoLiveLine`
    - `rangeChangeRefetches`
    - `noLinksInFunnelCells`
  - `Sidebar.test.tsx`: `showsActivationWithPlatformAnalyticsView`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full admin-portal suite, lint and typecheck.
- [ ] **Step 5: Write a read-only check query for Sid.**
  - **Where:** outside the repository, in the session scratchpad.
  - **What:** `BEGIN READ ONLY; … ROLLBACK;`, computing the same per-week step counts straight from
    the tables.
  - **How it's used:** Sid runs it with `railway connect Postgres < file`. The page must match it
    (spec §10.2).
- [ ] **Step 6: Commit, push, open the PR,** and send Sid the check query as a file.
  ```bash
  git commit -m "feat(admin-portal): the activation page"
  ```
