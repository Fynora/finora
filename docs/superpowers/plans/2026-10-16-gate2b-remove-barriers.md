# Gate 2b: Remove Barriers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **On this repository, Sid has ruled out subagents: execute natively with superpowers:executing-plans.**

**Goal:** Make a first statement easier to get in, for every user. The work covers:
- clear recovery copy for every refusal;
- a smaller first ask, and a card-statement route;
- telling people they can share a file in;
- a truthful "what happens to my statement" panel;
- a monthly push reminder with its own switch;
- an upload code for statements that live on a computer.

**Architecture:** Mostly client copy in web and mobile, with three backend pieces:
- a REMINDERS notification category, with a scheduled month-start sender on the existing outbox;
- a one-time send code, with a public upload endpoint that feeds the existing queued import;
- a refactor that lets the existing and the new upload endpoints share one set of upload checks.

**Tech Stack:** Spring Boot 3 / JPA / Flyway / Postgres / Redis rate limiter; React + Vite + Vitest
(web); React Native + Expo + Jest (mobile); Cloudflare Pages (`_redirects`).

**Spec:** `docs/superpowers/specs/2026-10-16-gate2b-remove-barriers-design.md`. Approved 16 Oct with
all copy, and with these decisions:
- §5.1 (a): the Privacy page sentence;
- §6 (b): a REMINDERS category;
- §7: never suggest an unprotected copy.

## Global Constraints

**Shared with 2a**
- Every Global Constraint in `docs/superpowers/plans/2026-10-16-gate2a-learn-why.md` applies:
  - worktrees;
  - no AI trailer in commits;
  - commit format and scopes;
  - public repository, so no real names, ids or statement values;
  - squash-merge checks;
  - migrations numbered at build time;
  - Node 22 via script;
  - the backend, IT and OpenAPI commands;
  - mobile by OTA, never to 1.0.0 builds;
  - the verification loop before every PR.

**Copy**
- **Exact copy:** use the strings in this plan. They come from the approved spec. The few lines the
  spec did not fix are marked *(new)*. Show those to Sid in the PR description.
- **Files and storage:** every sentence about them must be true in production on the day it ships.
  Its PR lists, for each sentence, the code that makes it true.

**Order and dependencies**
- **2a goes first:**
  - 2a's PR 2 (the funnel record) must be merged before this plan's PRs 2, 3 and 4, so their effect
    shows.
  - 2a's PR 4 must be merged before this plan's PR 5, which uses `ActivationFacts.hasEverImported`
    and the funnel rows.
- **The trust panel** (Task 7) ships only after Sid confirms R2 is configured in production. Ask
  before starting it.

## Review Focus

1. **Two send-code uploads racing with the same code.** One import job, and the second upload is
   refused. Test: `concurrentUploadsWithOneCodeMakeOneJob` (Task 10).
2. **A wrong code, an expired code and a used code all look the same to the computer.** One
   message and one status, so the page leaks nothing. Test: `failureKindsAreIndistinguishable`
   (Task 10).
3. **A user who turned off Reminders still gets "your statement is ready", and the reverse.** Test:
   `remindersSwitchOnlyAffectsReminders` (Task 8).
4. **The month-start run twice in one month,** e.g. a restart or two instances. One push per user
   per month. Test: `secondRunSameMonthSendsNothing` (Task 9).
5. **A refused upload by send code releases the code** for another file within its 10 minutes. A
   successful one burns it. Tests: `refusedFileReleasesTheCode`, `acceptedFileBurnsTheCode`
   (Task 10).

---

## PR 1: Recovery copy (spec §8)

### Task 1: Refusal audit

**Files:**
- Create: `docs/research/gate2/refusal-audit.md`. It holds codes and observed screens only, no user
  data.

**What to list, one row each:**
- every `ErrorCode` an upload or import can return: the `IMPORT_*` codes, plus scan, size, type and
  busy refusals;
- every client-side refusal in `Import.tsx` and `ImportScreen.tsx`, such as the type check in
  `handleFile`.

**Columns:**
- the code or trigger;
- web: the title and message the user sees;
- mobile: the title and message the user sees;
- the next action offered, or "none".

**Method:** drive each case in the running apps (web dev server and simulator), with synthetic files
only, per the repository's synthetic-fixture policy. Reading code alone is not enough. A case that
cannot be driven gets a row that says so, and says why.

- [ ] **Step 1: Build the code list** from `ErrorCode.java` and the two `importFailureMessages.ts` maps.
- [ ] **Step 2: Drive each case and fill the table.**
- [ ] **Step 3: Commit.**
  ```bash
  git commit -m "docs(imports): what a user sees today for every import refusal"
  ```

### Task 2: Password hint and the missing reasons

**Files:**
- Modify:
  - `frontend/src/pages/Import.tsx:1221`
  - `mobile/src/screens/import/ImportScreen.tsx:1034`
  - `frontend/src/api/importFailureMessages.ts`, `mobile/src/api/importFailureMessages.ts`
  - wherever Task 1 found a refusal that does not go through these maps
- Test:
  - `frontend/src/api/importFailureMessages.test.ts`, `mobile/src/api/importFailureMessages.test.ts`
  - `Import.test.tsx`, `ImportScreen.test.tsx`

**Password hint** (both apps, spec §8.1, verbatim):

> This is the password your bank set for the statement file, usually a mix of your name, PAN, date
> of birth or account number. It's not your UPI PIN, card PIN or netbanking password. Check the email
> the statement came in.

**Every row of the Task 1 audit marked "none":**
- gets a title and a message, in the same words in both apps;
- each message ends with one next action.
- These strings are *(new)*. List them in the PR for Sid.

**Contract test:** the web and mobile maps hold identical keys and identical strings.

- [ ] **Step 1: Write the failing tests.**
  - `passwordHintSaysWhatItIsNot` (both apps): assert the hint contains "not your UPI PIN, card PIN
    or netbanking password".
  - `everyAuditedCodeHasTitleAndMessage`: iterate a code list copied from the audit.
  - `webAndMobileMapsAgree`: read both files' exported maps and assert equality. Web reads the mobile
    file by path.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full web and mobile suites, lint and
  typecheck.
- [ ] **Step 5: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(imports): a reason and a next step for every refusal, and what the pdf password is not"
  ```

---

## PR 2: The first ask, and the review promise (spec §4, §5.3)

### Task 3: "Start with one statement" and "Nothing is saved until you confirm"

**Files:**
- Modify:
  - `frontend/src/pages/Import.tsx`: the upload step, above the drop zone (about line 1360)
  - `mobile/src/screens/import/ImportScreen.tsx`: under `SectionHeading "Import a statement"`
    (line 962)
  - `frontend/src/onboarding/checklistItems.ts`, `mobile/src/onboarding/checklistItems.ts`
  - the review step in both apps: web `step === 'review'` (line 1606); mobile, above
    `SectionHeading "Detected"` (line 1288)
  - `frontend/src/components/ImportTimeline.tsx`, `mobile/src/components/ImportProgressCard.tsx`
- Test: the siblings of each modified file.

**Copy:**
- **Import screen** (shown only while nothing is importing):
  - bold: "Start with one statement:"
  - then: "last month, from the account you use most."
  - The card line is added in Task 4.
- **Checklist:** `IMPORT_STATEMENT` label "Import first statement" becomes "Start with one statement".
  *(The spec says to use the same first line; "Start with one statement" is that line without its
  colon.)*
- **Review screen,** above the rows: "Nothing is saved until you confirm."
- **The resolved-hold notice:**
  - where it says "An update on your statement", append "Approved. Nothing is saved until you
    confirm.";
  - only when `isReviewable(job)` (`lib/importJob.ts`) is true.

- [ ] **Step 1: Write the failing tests.**
  - `showsStartWithOneStatement`
  - `hidesItWhileImporting`
  - `reviewSaysNothingSavedUntilConfirm`
  - `approvedHoldSaysNothingSaved`
  - `rejectedHoldDoesNotSayApproved`
  - `checklistUsesNewLabel`
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full suites, lint and typecheck.
- [ ] **Step 5: Commit.**
  ```bash
  git commit -m "feat(imports): ask for one statement first and say nothing is saved until you confirm"
  ```

### Task 4: The card line, after a corpus check

**Files:**
- Read only: `backend/src/test/java/com/finora/imports/RealCorpusImportEndToEndIT.java` and
  `scripts/run-corpus-ground-truth.py`.
- Modify: the same import-screen spots as Task 3.

**The check:**
1. Run the IT with `FINORA_CORPUS_DIR` set to the local corpus.
2. Run the ground-truth runner over the same corpus.
3. Take the credit card statements. For each issuer, count:
   - statements;
   - statements that import;
   - statements whose rows match ground truth.

**Rules:**
- Record the counts in the PR description: counts and issuer names only, never values or file
  names.
- **If every issuer in the corpus reads correctly:** add the second line, "No bank statement to
  hand? A credit card statement works too."
- **Otherwise:** name only the issuers that read correctly, as "No bank statement to hand? A credit
  card statement from {issuers} works too." *(new)*
- **If none read correctly:** add no line, and say so in the PR.

- [ ] **Step 1: Run the check and write down the counts.**
- [ ] **Step 2: Write the failing test** for the chosen line (`showsCardStatementLine`).
- [ ] **Step 3: Implement, and run the tests.**
- [ ] **Step 4: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(imports): say a credit card statement works too, for the issuers we read correctly"
  ```

---

## PR 3: Share to Fynora (spec §3)

### Task 5: Prove sharing works, and tell people

**Files:**
- Modify: `mobile/src/screens/import/ImportScreen.tsx`, under the "Choose a file" button (line 982).
- Modify: `frontend/src/pages/helpArticles.ts`, the article on importing a statement.
- Possibly modify: `mobile/app.config.ts` (`iosActivationRules`), if iOS sharing fails.
- Test: `ImportScreen.test.tsx`, plus a help-articles test if one exists.

**Copy** (spec §3, verbatim): "Statement in your email? Open the email, tap the PDF, then Share →
Fynora." It goes on the mobile import screen and in the help article.

- [ ] **Step 1: Android.** On the Pixel emulator (debug build), share a synthetic PDF from Files into
  Fynora. Confirm the import screen opens with the file chosen.
- [ ] **Step 2: iOS.** Do the same in the iOS Simulator from Files.
  - Then ask Sid to share from Mail on a real iPhone. The live panel only drives simulators.
  - If iOS fails, find the cause with evidence (logs, a prebuild manifest) before changing the
    predicate. Re-verify after any change.
- [ ] **Step 3: Write the failing test** `showsShareHint` (mobile), and the help-article assertion.
- [ ] **Step 4: Implement, and run the tests.**
- [ ] **Step 5: Commit, push, open the PR.** The description says which platforms were proven, and
  how.
  ```bash
  git commit -m "feat(mobile): tell people they can share a statement from their email into fynora"
  ```

---

## PR 4: What happens to my statement (spec §5.1, §5.2)

**Before starting:** ask Sid to confirm R2 is configured in production (Global Constraints).

### Task 6: The retention paragraph

**Files:**
- Modify: `frontend/src/pages/Privacy.tsx`. Add a new paragraph in "Data Retention" (about line 277).
- Test: `Privacy.test.tsx`.

**Copy** (spec §5.1, verbatim):

> When you delete a statement, its original file stays in our storage for 90 days and is then
> deleted. The file from an import that could not be completed is kept, so it can be retried without
> uploading it again, until you delete your account. When you delete your Fynora account, your
> statement files are deleted with it.

- [ ] **Step 1: Write the failing test.**
- [ ] **Step 2: Implement.** In the PR description, trace each clause to the code named in spec §5.1.
- [ ] **Step 3: Run the tests. Commit.**
  ```bash
  git commit -m "docs(security): say how long a deleted statement's file stays in storage"
  ```

### Task 7: The panel

**Files:**
- Create: `frontend/src/components/StatementHandlingPanel.tsx`,
  `mobile/src/components/StatementHandlingPanel.tsx`
- Modify: both import screens, beside the file picker.
- Test: sibling tests.

**Interfaces:**
- **Produces:** `<StatementHandlingPanel />`, with no props.
  - A disclosure titled "What happens to my statement?", closed by default.
  - Web: a `<button aria-expanded>`. Mobile: a `Pressable` with
    `accessibilityState={{ expanded }}`.

**Copy** (spec §5.2, verbatim, in this order):
1. **Fynora reads it with its own rules.** It is not sent to AI services.
2. **The original file is kept in your account,** so you can download it or have it read again later.
3. **You check every row before anything is saved.** If you cancel, nothing is added.
4. **If it can't be read automatically,** our team may open it to fix the problem. Every opening needs a written reason, is logged, and you are told.
5. **You can delete a statement any time** from Statement History. Its file is deleted from our storage 90 days later.
6. **A PDF password is saved only if you choose,** and is stored encrypted.
7. A link to the Privacy page, "Read the full privacy policy". On web this goes to `/privacy`; on
   mobile it opens the web privacy page, as other mobile privacy links do.

**Before merging:** verify line 4's "you are told" in code. Both held paths
(`AdminOriginalViewController` → `OriginalStatementViewService`, for held imports and held
statements) must write the customer notice.
- Prove it with an IT if none exists:
  `customerNotifiedWhenStaffOpenHeldImport` and `…HeldStatement`.
- If either path does not notify, stop and ask Sid. Do not soften the copy.

- [ ] **Step 1: Write the failing tests.**
  - `closedByDefault`
  - `opensAndShowsSevenItems`
  - `linksToPrivacy`
  - the two ITs above
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full suites, lint and typecheck.
- [ ] **Step 5: Commit, push, open the PR** (Tasks 6 and 7 together).
  ```bash
  git commit -m "feat(imports): what happens to my statement, next to the file picker"
  ```

---

## PR 5: The month-start nudge (spec §6)

### Task 8: The REMINDERS category and its switch

**Files:**
- Modify (backend):
  - `backend/src/main/java/com/finora/notification/domain/NotificationCategory.java`: add
    `REMINDERS`.
  - `NotificationPreferenceService.java`:
    - `list` also returns `(REMINDERS, PUSH)`, enabled by default;
    - `set` also accepts `(REMINDERS, PUSH)`;
    - every other REMINDERS channel is still refused.
  - `DatabaseNotificationPreferenceResolver.java`: confirm REMINDERS defaults to enabled. Its
    `category != MARKETING` rule already does this; the test proves it.
- Modify (clients):
  - `frontend/src/pages/settings/NotificationsPane.tsx` and
    `mobile/src/screens/settings/NotificationPreferencesSection.tsx`: a new switch, "Statement
    reminders". Its description: "A push at the start of the month when a new statement has
    probably arrived." *(new)*
- Test:
  - `backend/src/test/java/com/finora/notification/api/NotificationPreferenceServiceTest.java`
  - `NotificationsPane.test.tsx`, `NotificationPreferencesSection.test.tsx`

**First, check for database constraints:** grep the migrations for a CHECK on `notifications.category`
or `notification_preferences.category`. None was found while planning. If one exists, add a migration
that widens it.

- [ ] **Step 1: Write the failing tests.**
  - `listsRemindersPushEnabledByDefault`
  - `setsRemindersPush`
  - `refusesRemindersEmail`
  - `remindersSwitchOnlyAffectsReminders`: turn REMINDERS off; FINANCIAL push stays enabled; and the
    reverse.
  - The client test `togglesStatementReminders`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Regenerate OpenAPI and the types. Commit.**
  ```bash
  git commit -m "feat(settings): a statement reminders switch, separate from import notices"
  ```

### Task 9: The sender

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__statement_month_start_template.sql`.
  It adds the `notification_templates` PUSH row for `STATEMENT_MONTH_START`.
- Create: `backend/src/main/java/com/finora/imports/StatementMonthStartNudge.java`
- Modify:
  - `NotificationType.java`: add `STATEMENT_MONTH_START`.
  - `StatementStatusNotifier.java`: add `notifyMonthStart(UUID userId, YearMonth month, boolean hasData)`.
  - `backend/src/main/resources/application.yml`, `application-test.yml`:
    `app.reminders.month-start.enabled` (true; false under test) and `…interval-ms` (3600000).
  - `mobile/src/navigation/usePushNotificationNavigation.ts`: `TYPE_ROUTE` gains
    `STATEMENT_MONTH_START: 'Import'`.
- Test:
  - `backend/src/test/java/com/finora/imports/StatementMonthStartNudgeIT.java`
  - `usePushNotificationNavigation.test.ts`

**Interfaces:**
- **Produces:**
  - `int StatementMonthStartNudge.runDue(Instant now)`: returns the number of users notified. It does
    not read the enabled flag; `scheduledRun()` does, as `UnconfirmedImportReminder` does.
  - Scheduling: `@Scheduled(fixedDelayString = "${app.reminders.month-start.interval-ms:3600000}")`.

**Who gets it** (spec §6; `today` = the IST date of `now`):

| Case | Who | Template params |
|---|---|---|
| No data | Eligible on or after the 3rd, from 10:00 IST: no `statement_imports` row at all (`ActivationFacts.hasEverImported`, from 2a Task 6). | `{{month}}` = the previous month's full English name |
| Has data | Some live account whose latest `statement_period_end` is more than 33 days before `today`, with no newer statement for that account. | none |

**Rules, for both cases:**
- **One per user per month:** the dedupe key is `"STATEMENT_MONTH_START_" + userId + "_" + YearMonth`.
- **Eligible accounts only:** USER-scope, `ACTIVE` accounts, with a device token.
- **The REMINDERS PUSH preference is respected** through the normal outbox path.
- **No-data stop rule:** skip a user who was sent the no-data nudge in each of the previous two
  months and has no `IMPORT_SCREEN_OPENED` funnel row since the first of those sends.

**Templates** (spec §6, verbatim):

| Case | Title | Body |
|---|---|---|
| No data | "Your {{month}} statement is out" *(new title)* | "Your {{month}} statement is out. It's a good one to start with, and takes a couple of minutes." |
| Has data | "A new statement" *(new title)* | "A new statement has probably arrived for one of your accounts. Add it to keep your months complete." |

Templates are keyed by type and channel, and the two bodies differ. Use the template engine only if
it supports two variants per type. Otherwise add `STATEMENT_MONTH_START_FIRST` and
`STATEMENT_MONTH_START_NEXT` as two types, and map both in `TYPE_ROUTE`. Decide by reading
`NotificationTemplate` lookup, and write the choice in the PR.

**Never** put a bank name, an account or an amount in the params.

- [ ] **Step 1: Write the failing tests** (`StatementMonthStartNudgeIT`, driving `runDue` at fixed
  instants):
  - `noDataUserNudgedOnTheThird`
  - `notBeforeTenIstOnTheThird`
  - `hasDataUserNudgedWhenAnAccountIs34DaysStale`
  - `notWhenTheAccountHasANewerStatement`
  - `secondRunSameMonthSendsNothing`
  - `oneNudgePerUserAcrossCasesAndAccounts`
  - `stopsAfterTwoUnansweredMonths`
  - `restartsAfterImportScreenOpened`
  - `skipsRemindersOptOut`
  - `skipsAdminScopeAndInactive`
  - `noBankNameInParams`
  - Mobile: `monthStartOpensImport`.
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.**
- [ ] **Step 5: Dry run, read-only, against a local database seeded with synthetic users.** It must
  print who would get it this month, and show that a second run sends nothing (spec §10.4). Paste
  the counts in the PR.
- [ ] **Step 6: Full backend `verify`, then the mobile suite. Commit, push, open the PR.**
  ```bash
  git commit -m "feat(imports): a month-start push when a new statement has probably arrived"
  ```

The activation page counts for nudges (spec §6, last paragraph) are added here if 2a PR 6 has
merged, as `nudgesSent` and `importedWithin3Days` per month on `ActivationReportDto`. If it has not
merged, they are added when it does.

---

## PR 6: Send a statement from your computer (spec §7)

### Task 10: Codes and the public upload (backend)

**Files:**
- Create: `backend/src/main/resources/db/migration/V<next>__statement_send_codes.sql`
- Create (`backend/src/main/java/com/finora/imports/send/`):
  - `StatementSendCode.java`
  - `StatementSendCodeRepository.java`
  - `StatementSendService.java`
  - `SendCodeController.java` (signed in)
  - `PublicSendController.java`
  - `SendDto.java`
- Create: `backend/src/main/java/com/finora/imports/jobs/QueuedUploadChecks.java`
- Modify:
  - `ImportJobController.java`: move its checks (lines 101–145) into `QueuedUploadChecks`, with
    unchanged behaviour.
  - `SecurityConfig.java`: `permitAll` for `POST /api/v1/send`.
  - `RateLimitFilter.java`: a new `send-statement` limiter, 20 per 10 minutes per IP, on
    `POST /api/v1/send`.
  - `AccountPurgeSweepService.java`, `DataExportServiceTest.java`: purge the user's codes; the map
    entry is `"statementSendCodeRepository.deleteByUserId" → "statement_send_codes"`, not exported,
    like `password_reset_tokens`.
- Test:
  - `backend/src/test/java/com/finora/imports/send/StatementSendIT.java`
  - `StatementSendCodeTest.java`
  - existing `ImportJobController` ITs must stay green, unchanged.

**Interfaces:**
- **Produces:**
  - `String QueuedUploadChecks.check(UUID userId, MultipartFile file, StatementUpload.Format format, Boolean savePassword, String password)`:
    returns the password to save, or null; logs refusals with `uploadRefusalLog`; throws as before.
  - Table `statement_send_codes`:
    - `id`
    - `user_id … REFERENCES users(id) ON DELETE CASCADE`
    - `code_hash VARCHAR(64) NOT NULL`: hex SHA-256
    - `created_at TIMESTAMPTZ NOT NULL`
    - `expires_at TIMESTAMPTZ NOT NULL`
    - `used_at TIMESTAMPTZ`
    - `cancelled_at TIMESTAMPTZ`
    - `import_job_id UUID`
    - a partial unique index on `code_hash WHERE used_at IS NULL AND cancelled_at IS NULL`
  - **Signed in:**
    - `POST /api/v1/imports/send-codes` → `SendDto.CodeIssued(String code, Instant expiresAt)`.
      Cancels the user's live codes, then issues a new one.
    - `GET /api/v1/imports/send-codes/current` → `SendDto.CodeStatus(String state, UUID importJobId)`,
      where `state ∈ {NONE, WAITING, RECEIVED, EXPIRED}`.
  - **Public:** `POST /api/v1/send`, multipart `code` and `file`.
    - Success: 202 with `{ received: true }`.
    - Failure: 400 with error code `SEND_CODE_INVALID` for a wrong, expired, used or cancelled code.
      **The same message for all four:** "That code didn't work. Check it on your phone, or ask for a
      new one." *(new)*
    - A locked PDF: 400 with `IMPORT_PDF_PASSWORD_REQUIRED`.
    - Five failed codes from one IP within an hour: 429. Use a `RateLimiter` named
      `send-code-failure`, consumed only on a failed code.

**Code:**
- 6 characters, drawn with `SecureRandom` from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. There is no I, L,
  O, 0 or 1.
- Valid for 10 minutes.
- Only the hash is stored.
- Regenerate on a collision with a live code.

**Upload sequence:**
1. Claim the code atomically:
   `UPDATE … SET used_at = now() WHERE code_hash = ? AND used_at IS NULL AND cancelled_at IS NULL AND expires_at > now() RETURNING user_id, id`.
2. Run `QueuedUploadChecks.check(userId, file, format, false, null)`.
3. **If it refuses:** set `used_at = NULL` again (the code stays usable until it expires) and return
   the refusal.
4. **If it passes:** `importJobService.accept(userId, file, format, null)`, store `import_job_id`, and
   audit `STATEMENT_SEND_CODE_USED`.

**Auditing:**
- Issuing a code is audited as `STATEMENT_SEND_CODE_ISSUED`.
- Audit metadata goes through `requestMetadata.addTo`, as `requestDeletion` does.

**Response:** never includes the user's name, email or any account data.

- [ ] **Step 1: Write the failing tests.**
  - `StatementSendCodeTest`: `codeUsesOnlyTheSafeAlphabet` (10,000 draws), `codeIsSixCharacters`.
  - `StatementSendIT`:
    - `issueThenUploadCreatesAJobForTheIssuer`
    - `statusGoesWaitingThenReceived`
    - `expiredCodeRefused` (10 min + 1 s)
    - `usedCodeRefused`
    - `newCodeCancelsTheOld`
    - `failureKindsAreIndistinguishable`: the wrong, expired, used and cancelled codes give the same
      status, code and message.
    - `fiveFailuresThenTooManyRequests`
    - `lockedPdfRefusedWithPasswordRequired`
    - `refusedFileReleasesTheCode`
    - `acceptedFileBurnsTheCode`
    - `concurrentUploadsWithOneCodeMakeOneJob`: two threads, one code.
    - `responseCarriesNoAccountData`
    - `onlyHashStored`: the raw code does not appear in the row.
    - `issueAndUseAreAudited`
    - `publicEndpointNeedsNoAuth`; `issueNeedsAuth`.
    - `purgeDeletesCodes` (in `AccountPurgeSweepServiceIT`)
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.** Refactor `ImportJobController` first, and run its ITs before adding
  anything new.
- [ ] **Step 4: Run and confirm they pass.** Then run the full backend `verify`.
- [ ] **Step 5: Regenerate OpenAPI and the types. Commit.**
  ```bash
  git commit -m "feat(imports): a one-time code to send a statement from a computer, upload only"
  ```

### Task 11: The computer page and the phone screen

**Files:**
- Create:
  - `frontend/src/pages/SendStatement.tsx` (+ test)
  - `mobile/src/components/SendFromComputerSheet.tsx` (+ test)
- Modify:
  - `frontend/src/App.tsx`: public route `/send`.
  - `frontend/public/_redirects`: add `/send /spa-shell 200` and `/send/ /spa-shell 200` above the
    `/app` lines. The redirects test fails without them. **Do not add a `/send` block to `_headers`;**
    a second `/*`-matching block drops the CSP.
  - The prerender and sitemap config, if any lists routes: `/send` is not prerendered and not in the
    sitemap. The page sets `noindex`.
  - `mobile/src/screens/import/ImportScreen.tsx`: a link-style button under "Choose a file", "My
    statement is on my computer", which opens the sheet.
  - `frontend/src/api/endpoints.ts`: `sendStatement(code, file)` on `rawApi`, with no auth.
  - `mobile/src/api/endpoints.ts`: `sendCodesApi.issue()` and `sendCodesApi.current()`.

**Computer page copy:**
- Title: "Send a statement to Fynora" *(new)*
- Code field label: "Code from your phone" *(new)*
- Button: "Send" *(new)*
- Success: "Sent. Open Fynora on your phone to review it."
- Locked PDF: "This file has a password. Send it to your phone and share it into Fynora instead."
- Failed code: the server message from Task 10.
- No account information is ever shown.

**Phone sheet copy:**
- The code in large text.
- "Open app.fynora.net/send on your computer and type this code. It works once, for 10 minutes."
  *(new)*
- Status:
  - "Waiting for your file…" (WAITING)
  - "Received. It's in Recent Imports." (RECEIVED, then close the sheet and refresh the list)
  - "This code has expired." with a "Get a new code" button (EXPIRED)
- Poll `current()` every 3 s while the sheet is open. Stop when it closes or the status is RECEIVED
  or EXPIRED.

- [ ] **Step 1: Write the failing tests.**
  - Web:
    - `sendsCodeAndFile`
    - `showsSentOnSuccess`
    - `showsPasswordMessageForLockedPdf`
    - `showsServerMessageForBadCode`
    - `showsNoAccountData`
    - `routeIsPublic`
    - `redirectsListSend` (the existing `_redirects` test covers new routes: run it)
  - Mobile:
    - `showsCodeAndInstructions`
    - `pollsUntilReceived`
    - `stopsPollingWhenClosed`
    - `expiredOffersNewCode`
- [ ] **Step 2: Run and confirm they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run and confirm they pass.** Then run the full web and mobile suites, lint and
  typecheck.
- [ ] **Step 5: End-to-end proof** (spec §10.5), with a local backend and web dev server and the
  simulator:
  - issue a code on the phone;
  - upload from the browser;
  - see "Received" and the import in Recent Imports.
  - Then repeat with an expired code, a reused code, five wrong codes, and a second code cancelling
    the first.
  - Record each outcome in the PR.
- [ ] **Step 6: Security self-review** in the PR description, covering:
  - CSRF on the public multipart endpoint (cookie-less; it reads no session);
  - upload size limits;
  - rate limits;
  - edge rules;
  - what a stolen code allows.
- [ ] **Step 7: Commit, push, open the PR.**
  ```bash
  git commit -m "feat(web): send a statement from a computer with a code from your phone"
  ```
