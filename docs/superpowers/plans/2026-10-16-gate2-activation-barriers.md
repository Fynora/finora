# Gate 2: Activation Barriers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native execution: Sid's standing rule is no subagents for implementation or its verification). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** give Sid what he needs to learn why testers don't get a statement in: a kit for observed sessions, a per-user record of where each import attempt stops, and the missing recovery copy.

**Architecture:** three small PRs on fresh branches from `origin/main`:
1. Session kit: documents only.
2. Import-funnel record: one table (`import_funnel_days`), one signed-in endpoint, export and deletion, privacy line, then fire-and-forget events from web and mobile.
3. Phase 1a: an audit of how each refusal reads in both apps, then the password help and whatever the audit finds missing.

**Tech Stack:** Spring Boot / JPA / Flyway (backend), React + Vitest (web), React Native + Jest (mobile), openapi-typescript for generated client types.

**Spec:** `docs/superpowers/specs/2026-10-16-gate2-activation-barriers-design.md`

## Global Constraints
- Events are exactly `IMPORT_SCREEN_OPENED`, `FILE_CHOSEN`, `REVIEW_OPENED`. Nothing else is accepted.
- Stored per user, per event, per calendar day in `app.platform.reporting-zone` (default `Asia/Kolkata`), at most once. No time, no file name, size or type, no device, no screen detail.
- Measurement never degrades the import: a failed event call is never retried, never shown and never delays a screen.
- The privacy line (spec §4.3) and the password help copy (spec §5) are drafts. **Sid confirms the exact wording before the PR carrying it merges.**
- No funnel event reaches production before the privacy line is live: PR 2's backend, privacy line and clients merge together.
- No tester names, emails or user-id fragments in anything committed (the repository is public). Filled-in session notes never enter the repository.
- Migration number: `V275` is next on `main` (16 Oct, `V274` latest, no open PR adds one). **Re-check against `origin/main` and every open PR immediately before PR 2.**
- Commits: `type(scope): lowercase subject`, scope from `commitlint.config` (`analytics`, `imports`, `backend`, `frontend`, `mobile`, `web`, `docs`, …), **no Co-Authored-By or any AI-attribution trailer.**
- App commands run on Node 22 (`npx node@22`, or Node 22's `bin` first on `PATH`); mobile Jest needs `NODE_OPTIONS=--experimental-vm-modules`.
- Full backend verification is `./mvnw verify`. Never commit while it runs (the pre-commit hook recompiles `backend/target`).
- After a DTO or endpoint change: `backend/scripts/generate-openapi-spec.sh` (needs Postgres on 5432), then `npm run generate:types` in `frontend`, `mobile` and `admin-portal`.
- Mobile reaches users by OTA update; per the glass UI notes, Phase 2 JavaScript never goes to 1.0.0 builds. Confirm the runtime before publishing.

## Review Focus
1. **An offline phone or a server error on the event call** must leave the import screen working and silent. Pinned in Task 4 and Task 5 (event call rejects; no error shown, upload still proceeds).
2. **The same moment reported many times** (a re-render, React StrictMode's double effect in dev, leaving and returning to the tab, two devices) must give one row a day. Pinned in Task 2 (two posts, one row) and Tasks 4–5 (re-render fires once per visit).
3. **Just after midnight in India** (18:30–23:59 UTC) the event belongs to the next IST date. Pinned in Task 2 (recorder with a fixed clock at `2026-10-16T19:00:00Z` records `2026-10-17`).
4. **A request naming someone else, or an unknown event,** must not write a row for anyone else or any row at all. Pinned in Task 2 (body with a `userId` field is recorded for the caller only; unknown event is 400 with no row).
5. **A deleted account** must leave no funnel rows; an export must include them. Pinned in Task 3.

---

## PR 1: Session kit (branch `docs/gate2-session-kit` from `origin/main`)

### Task 1: The session kit

**Files:**
- Create: `docs/research/gate2/README.md` (what the kit is, who runs it, where filled notes go: never in the repository)
- Create: `docs/research/gate2/session-guide.md`
- Create: `docs/research/gate2/notes-template.md`
- Create: `docs/research/gate2/recruitment-message.md`
- Create: `docs/research/gate2/consent.md`

- [ ] **Step 1: Write `session-guide.md`** from spec §3.1–3.2: who (4 never uploaded, 3 failed, 3 got data in; at least 5, up to 10), the three parts, the task wording "Find out where your money went last month.", the five questions word for word, and the rule: record each problem before helping, then help to find the next barrier, unaided and assisted outcomes kept separate.
- [ ] **Step 2: Write `notes-template.md`**: header (session number, group, date — no name), a two-column table "What they did" / "What they said", unaided outcome, assisted outcome, barriers hit in order, and a closing line reminding the note-taker: no amounts, names, account numbers or merchants.
- [ ] **Step 3: Write `recruitment-message.md`**: one short message per group (never uploaded, failed, got data in), each saying about 30 minutes, their own statement, nothing recorded without asking.
- [ ] **Step 4: Write `consent.md`**: the two consent moments from spec §3.3 (start of session; before any recording), and what happens to a recording (deleted once notes are written, never shared).
- [ ] **Step 5: Check** that no file contains a real name, email or id: `grep -rniE "@|[0-9a-f]{8}" docs/research/gate2/` returns only intended matches (none expected).
- [ ] **Step 6: Commit and PR.** `git commit -m "docs(analytics): the gate 2 observed-session kit"`. Open the PR; send Sid the kit as files.

---

## PR 2: Import-funnel record (branch `feature/import-funnel-record` from `origin/main`)

### Task 2: Table, recorder and endpoint

**Files:**
- Create: `backend/src/main/resources/db/migration/V275__import_funnel_days.sql`
- Create: `backend/src/main/java/com/finora/imports/funnel/ImportFunnelEvent.java`
- Create: `backend/src/main/java/com/finora/entity/ImportFunnelDay.java`
- Create: `backend/src/main/java/com/finora/repository/ImportFunnelDayRepository.java`
- Create: `backend/src/main/java/com/finora/imports/funnel/ImportFunnelRecorder.java`
- Create: `backend/src/main/java/com/finora/controller/ImportFunnelController.java`
- Test: `backend/src/test/java/com/finora/imports/funnel/ImportFunnelRecorderTest.java`
- Test: `backend/src/test/java/com/finora/imports/funnel/ImportFunnelIT.java`

**Interfaces:**
- Produces: `enum ImportFunnelEvent { IMPORT_SCREEN_OPENED, FILE_CHOSEN, REVIEW_OPENED }`
- Produces: `ImportFunnelDayRepository.recordDay(UUID userId, String event, LocalDate eventDate)`, `findByUserIdOrderByEventDateAscEventAsc(UUID userId): List<ImportFunnelDay>`, `deleteByUserId(UUID userId): int`
- Produces: `ImportFunnelRecorder.record(UUID userId, ImportFunnelEvent event)`
- Produces: `POST /api/v1/import/funnel-events`, body `{"event": "<ImportFunnelEvent>"}`, answers 204; signed-in only.

- [ ] **Step 1: Write the migration.** Table `import_funnel_days`: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE`, `event VARCHAR(32) NOT NULL CHECK (event IN ('IMPORT_SCREEN_OPENED','FILE_CHOSEN','REVIEW_OPENED'))`, `event_date DATE NOT NULL`, `UNIQUE (user_id, event, event_date)`, index on `event_date`. Header comment in the style of `V249__user_activity_days.sql`: why a date and not a time, the reporting zone, that the purge deletes rows explicitly.
- [ ] **Step 2: Write the failing tests.**
  - `ImportFunnelRecorderTest.recordsTheIndiaDateNotTheUtcOne`: clock fixed at `2026-10-16T19:00:00Z`, zone `Asia/Kolkata` → `recordDay(user, "FILE_CHOSEN", 2026-10-17)`.
  - `ImportFunnelIT.anEventIsRecordedOncePerDay`: two posts of `FILE_CHOSEN` → 204 twice, one row.
  - `ImportFunnelIT.eachEventIsItsOwnRow`: the three events → three rows for the caller, dated today (IST).
  - `ImportFunnelIT.anUnknownEventIsRefusedAndWritesNothing`: `{"event":"PAGE_VIEWED"}` → 400, no row.
  - `ImportFunnelIT.theCallerIsTheOnlyUserRecorded`: body `{"event":"FILE_CHOSEN","userId":"<other user>"}` → 204, a row for the caller, none for the other user.
  - `ImportFunnelIT.signedOutIsRefused`: no token → 401, no row.
- [ ] **Step 3: Run them and see them fail** (`-Dit.test=ImportFunnelIT`, `-Dtest=ImportFunnelRecorderTest`).
- [ ] **Step 4: Implement.** Entity and repository after `UserActivityDay` / `UserActivityDayRepository` (native `INSERT … ON CONFLICT (user_id, event, event_date) DO NOTHING`, its own `@Transactional`; JPQL `deleteByUserId` with `clearAutomatically`/`flushAutomatically`). Recorder takes `Clock` in the reporting zone the way `UserActivityInterceptor` does. Controller takes `record ImportFunnelEventRequest(@NotNull ImportFunnelEvent event)` with `@Valid`, reads the user from `CurrentUser`, returns `ResponseEntity.noContent()`.
- [ ] **Step 5: Run the tests and see them pass.**
- [ ] **Step 6: Commit.** `feat(analytics): a per-user, per-day record of three import-funnel moments`

### Task 3: Export, deletion, privacy line, API types

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DataExportService.java` (bundle gains `importFunnelDays` as its **last** component; written to `import_funnel_days.json`)
- Modify: `backend/src/main/java/com/finora/service/AccountPurgeSweepService.java:808` (beside `userActivityDayRepository.deleteByUserId`)
- Modify: `frontend/src/pages/Privacy.tsx:170-174` (the activity-dates paragraph)
- Test: `backend/src/test/java/com/finora/service/DataExportServiceTest.java`, `backend/src/test/java/com/finora/service/AccountPurgeSweepServiceIT.java`, `frontend/src/pages/Privacy.test.tsx`
- Regenerate: `backend/openapi/openapi.json`, `frontend|mobile|admin-portal/src/api/generated-types.ts`

**Interfaces:**
- Consumes: `ImportFunnelDayRepository.findByUserIdOrderByEventDateAscEventAsc`, `deleteByUserId` (Task 2).
- Produces: export entry `import_funnel_days.json`, an array of `{"date": "YYYY-MM-DD", "event": "<ImportFunnelEvent>"}`; manifest text "The days you opened the import screen, chose a file, or opened an import's review -- the date only, never the time or the file."

- [ ] **Step 1: Write the failing tests.**
  - `DataExportServiceTest.exportsTheImportFunnelDays`: the bundle carries the user's rows as ISO dates with their event.
  - `AccountPurgeSweepServiceIT.sweep_removesImportFunnelDays`: rows for the purged user are gone; another user's rows stay.
  - `Privacy.test.tsx`: the page says it records the dates the user opened the import screen, chose a file and opened a review, "never the time, the file or anything in it", and that they are deleted with the account.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** Export and purge as the `user_activity_days` code beside each does. Privacy paragraph: append the spec §4.3 sentence to the existing activity-dates paragraph. **Confirm the wording with Sid before this PR merges.**
- [ ] **Step 4: Regenerate OpenAPI and the three apps' types.** Expected spec diff: the new path and its request schema only.
- [ ] **Step 5: Run the tests and see them pass.**
- [ ] **Step 6: Commit.** `feat(analytics): import-funnel days are exported, deleted with the account, and on the privacy page`

### Task 4: Web events

**Files:**
- Modify: `frontend/src/api/endpoints.ts` (add `importFunnelApi.record(event: ImportFunnelEvent): Promise<void>`)
- Create: `frontend/src/lib/importFunnel.ts`
- Modify: `frontend/src/pages/Import.tsx` (mount effect; `handleFile` at line 446; an effect on `step`)
- Test: `frontend/src/lib/importFunnel.test.ts`, `frontend/src/pages/Import.test.tsx`

**Interfaces:**
- Produces: `type ImportFunnelEvent = 'IMPORT_SCREEN_OPENED' | 'FILE_CHOSEN' | 'REVIEW_OPENED'` and `recordImportFunnelEvent(event: ImportFunnelEvent): void` — fire and forget, swallows every failure, never retries.

- [ ] **Step 1: Write the failing tests.**
  - `importFunnel.test.ts`: a rejecting API call resolves silently (no throw, no console error spy hit beyond what the project's test setup allows).
  - `Import.test.tsx`, with `importFunnelApi.record` mocked:
    - opening the page records `IMPORT_SCREEN_OPENED` once, and a re-render does not record it again;
    - choosing a file records `FILE_CHOSEN` once, including a `.txt` file the page refuses itself;
    - reaching the review records `REVIEW_OPENED` once, from a fresh upload and from "Continue previous import";
    - when the record call rejects, nothing is shown and the upload still proceeds.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.** `IMPORT_SCREEN_OPENED` from a mount effect guarded by a ref so StrictMode's second run does not resend; `FILE_CHOSEN` as the first line of `handleFile`; `REVIEW_OPENED` from an effect that fires when `step` becomes `'review'` from another step.
- [ ] **Step 4: Run the tests, the full web suite, lint and build.** Expected: all pass, eslint 0 warnings.
- [ ] **Step 5: Commit.** `feat(frontend): the import screen reports where an attempt stops`

### Task 5: Mobile events

**Files:**
- Modify: `mobile/src/api/endpoints.ts` (same `importFunnelApi.record`)
- Create: `mobile/src/lib/importFunnel.ts` (port of web's, same exports)
- Modify: `mobile/src/screens/import/ImportScreen.tsx` (navigation `focus` listener, as line 911 already uses; the `pickStatement()` result; an effect on `step`)
- Test: `mobile/src/lib/importFunnel.test.ts`, the ImportScreen tests

**Interfaces:**
- Consumes: the endpoint from Task 2; mirrors Task 4's `recordImportFunnelEvent`.

- [ ] **Step 1: Write the failing tests**, the mobile equivalents of Task 4's: focusing the Import tab records `IMPORT_SCREEN_OPENED` once per focus; a non-null `pickStatement()` result records `FILE_CHOSEN`, a cancelled pick (null) records nothing; reaching review records `REVIEW_OPENED` once; a rejecting call shows nothing.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** as in Task 4.
- [ ] **Step 4: Run the tests, the full mobile suite (`--runInBand --forceExit`), `tsc --noEmit` and eslint.**
- [ ] **Step 5: Commit.** `feat(mobile): the import screen reports where an attempt stops`

### Task 6: Verify PR 2, open it, and hand Sid the reading query

- [ ] **Step 1:** re-check the migration number against `origin/main` and open PRs; rename if taken.
- [ ] **Step 2:** full backend `./mvnw verify`; full web, admin and mobile suites, lint and typecheck. Record the numbers in the PR description.
- [ ] **Step 3:** self-review the whole diff (no names, no time of day stored, no retry anywhere, privacy line present).
- [ ] **Step 4:** open the PR. In the description, state that the privacy line and the funnel ship together and that mobile needs an OTA update.
- [ ] **Step 5:** write the reading query (spec §4.4) as a read-only scratchpad file, not in the repository: per user id prefix, first and latest attempt's furthest stage, joining `import_funnel_days`, `statement_analysis_sessions` (REJECTED / FAILED / PARSED, `expired_unconfirmed_at`), `import_jobs` and `statement_imports`. Dry-run it against a local database at the new schema; send it to Sid after deploy.

---

## PR 3: Phase 1a recovery (branch `feature/import-recovery-copy` from `origin/main`)

### Task 7: How every refusal reads today (the item 3 audit)

**Files:**
- Create: `docs/research/gate2/phase1a-refusal-audit.md`
- Test: `frontend/src/pages/Import.refusals.test.tsx`, `mobile/src/screens/import/ImportScreen.refusals.test.tsx` (kept: they become the regression tests in Task 8)

- [ ] **Step 1: Write one rendering test per refusal, per app,** driving the upload with the API rejecting as the server does, and asserting what the screen shows. The cases:
  - empty file (400), wrong file type (415), too large (`UPLOAD_TOO_LARGE`), virus found (`UPLOAD_MALWARE_DETECTED`), scanner unavailable (`UPLOAD_SCANNER_UNAVAILABLE`), server busy (`IMPORT_SYSTEM_BUSY`);
  - wrong password (`IMPORT_PDF_PASSWORD_INVALID`);
  - the import codes outside `importFailureMessages.ts`: `IMPORT_SESSION_ALREADY_CONFIRMED`, `IMPORT_SESSION_HELD_FOR_REVIEW`, `IMPORT_ACCOUNT_REQUIRED`, `IMPORT_ACCOUNT_NAME_REQUIRED`, `IMPORT_ACCOUNT_FORBIDDEN`, `IMPORT_ACCOUNT_NOT_FOUND`.

  Take each error body's exact shape (`errorCode`, status, message) from the backend: `GlobalExceptionHandler`, `StatementUpload.requireReadable`, `UploadScanGate`, `ImportConcurrencyLimiter`. Write the first version of each test to assert what the screen shows today.
- [ ] **Step 2: Run them; fill `phase1a-refusal-audit.md`** with one row per case: app, what the user sees, whether it names a reason, whether it names one next action, and the evidence (test name).
- [ ] **Step 3: Send the audit to Sid with draft copy** for every row missing a reason or an action. Wait for the approved words before Task 8.
- [ ] **Step 4: Commit.** `docs(imports): how every upload refusal reads today, in both apps`

### Task 8: Password help and the missing next actions

**Files:**
- Modify: `frontend/src/pages/Import.tsx:1221` and `mobile/src/screens/import/ImportScreen.tsx:1034` (the password hint)
- Modify: `frontend/src/api/importFailureMessages.ts`, `mobile/src/api/importFailureMessages.ts`, and wherever Task 7 found a refusal handled
- Test: the two refusal test files from Task 7; the existing password panel tests

- [ ] **Step 1: Change the Task 7 tests to assert the approved copy** (one reason and one next action per case, identical in both apps), and add a password-hint test per app asserting the approved help text, which includes "not your UPI PIN, card PIN or netbanking password".
- [ ] **Step 2: Run them and see the new assertions fail.**
- [ ] **Step 3: Implement** the approved copy in both apps, the same words in each.
- [ ] **Step 4: Run the full web and mobile suites, lint and typecheck.**
- [ ] **Step 5: Commit.** `fix(web): every upload refusal says why and what to do, and password help says what the password is not` (or `fix(frontend)` / `fix(mobile)` split if the diff is clearer that way)

### Task 9: Verify PR 3 and open it
- [ ] Full web, mobile and admin suites, lint, typecheck (backend untouched; skip its run and say so). Self-review the diff. Open the PR, noting the OTA update mobile needs.

---

## After the three PRs
- Sid runs the sessions with the kit; the barrier list is written from the filled notes (spec §3.5), outside the repository until it holds no personal data.
- The Gate 2 report checks each exit criterion (spec §7) with the query or command that proves it.
