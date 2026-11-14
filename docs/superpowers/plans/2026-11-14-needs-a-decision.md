# Needs a decision: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One admin page listing every open hold of both kinds, ordered by who is on it and by time left on the 48-hour promise, with a next step and the Claude fix status per row.

**Architecture:** A read-only backend service builds the rows from `import_jobs` (held statuses), `held_statements`, `claude_fix_jobs` and admin names, and works out "who's on it" and "next step" as codes. A new admin page renders them and links each row to the hold's existing page.

**Tech Stack:** Spring Boot 3.5 / JPA, React 19 + TanStack Query + Vitest (admin-portal).

**Spec:** `docs/superpowers/specs/2026-11-14-needs-a-decision-design.md`

Execution: native (Sid: no agents).

## Global Constraints

- No statement text, file name, error text or customer name in the list or its DTO (browsing is not audited).
- Clock = `import_jobs.finished_at`; promise = `ImportJob.HOLD_PROMISE` (48 h).
- `IMPORT` rows need `IMPORT_TRIAGE_MANAGE`; `STATEMENT` and `NO_RECORD` rows need `TRUST_REVIEW_MANAGE`; the endpoint needs either.
- Commit messages: lowercase subject, at most 100 characters, no AI attribution.
- Portal tests run on Node 22: `npx --yes node@22 node_modules/.bin/vitest run`.

## Review Focus

1. A re-upload riding on an open review must not appear as its own row (covered `NO_RECORD`): Task 1 test `coveredReuploadIsNotListed`.
2. An admin with only one of the two permissions must not see the other kind: Task 2 IT `importTriageOnlySeesImports`.
3. A hold exactly at 48 h is not overdue; one minute past is (same rule as `ImportJob.isHoldOverdue`): Task 3 test `exactlyAtPromiseIsNotPast`.
4. Claude switched off with no row reads OFF, not "Claude is on it": Task 1 test `claudeOffIsNobody`.
5. An assigned admin who has since been deleted or anonymised: show "an admin", never crash: Task 1 test `assigneeWithoutNameReadsAnAdmin`.

---

### Task 1: Rows, who's on it, next step (backend service)

**Files:**
- Create: `backend/src/main/java/com/finora/dto/DecisionQueueRowDto.java`
- Create: `backend/src/main/java/com/finora/service/DecisionQueueService.java`
- Modify: `backend/src/main/java/com/finora/repository/ImportJobRepository.java` (add `List<ImportJob> findByStatusInOrderByFinishedAtAscIdAsc(Collection<ImportJob.Status> statuses, Pageable limit)`)
- Test: `backend/src/test/java/com/finora/service/DecisionQueueServiceTest.java` (Mockito)

**Interfaces:**
- Produces:
  - `record DecisionQueueRowDto(String kind, UUID jobId, String heldId, String bank, String sourceFormat, Instant heldAt, Instant promiseAt, String who, String whoName, String nextStep, Integer prNumber, ClaudeFixStatus claudeFix)`
    - `kind`: `IMPORT | STATEMENT | NO_RECORD`; `who`: `ADMIN | CLAUDE | NOBODY`
    - `nextStep`: `OPEN_REVIEW | APPROVE_RERUN_PASSED | MERGE_PR | CLAUDE_WORKING | CLAUDE_REREADING | CLAUDE_NOT_STARTED | ADMIN_LOOKING | FIX_AND_REPROCESS | CHECK_AND_DECIDE`
  - `DecisionQueueService.list(boolean includeImports, boolean includeStatements) -> List<DecisionQueueRowDto>`, already sorted.
- Consumes: `ClaudeFixStatusLookup.forJob(UUID)`; `HeldStatementService.isCoveredByOpenReview(ImportJob)`; `HeldStatementRepository.findAllById`; `UserRepository.findAllById`.

Rules, from the spec's tables: `CLAUDE` when the Claude status is `SENT`, `REREADING`, or null (on, no decision yet); `ADMIN` when the hold has `assignedEngineerId`; otherwise `NOBODY`. For `who`, `ADMIN` beats `CLAUDE`. `nextStep` is the first row of the spec's table that matches. Sort: `NOBODY` first, then `promiseAt` ascending, then `jobId`. Load at most 500 jobs (`PageRequest.of(0, 500)`). `promiseAt = heldAt + HOLD_PROMISE`; a held job with null `finishedAt` uses `createdAt`.

- [ ] **Step 1: Write the failing tests** in `DecisionQueueServiceTest`:
  - `importHoldWithClaudeWorkingIsClaude`: who `CLAUDE`, nextStep `CLAUDE_WORKING`.
  - `prOpenNeedsAPerson`: who `NOBODY`, nextStep `MERGE_PR`, prNumber 123.
  - `statementReadyForImportSaysApprove`: `APPROVE_RERUN_PASSED`.
  - `assignedStatementIsAdminWithName`: who `ADMIN`, whoName "Asha Admin", nextStep `ADMIN_LOOKING` (Claude not working).
  - `assigneeWithoutNameReadsAnAdmin`: assigned id with no user, so whoName is null and who is `ADMIN`.
  - `noRecordHoldSaysOpenReview`: kind `NO_RECORD`, heldId null, `OPEN_REVIEW`.
  - `coveredReuploadIsNotListed`: `isCoveredByOpenReview` true, so the row is absent.
  - `claudeOffIsNobody`: status `OFF`, so `NOBODY` and `FIX_AND_REPROCESS` (import) or `CHECK_AND_DECIDE` (statement).
  - `nobodyFirstThenLeastTimeLeft`: four rows in expected order.
  - `permissionsFilterKinds`: `list(true,false)` returns only `IMPORT`; `list(false,true)` returns no `IMPORT`.
- [ ] **Step 2: Run them, expect a compile failure.** `cd backend && ./mvnw -q test -Dtest=DecisionQueueServiceTest`
- [ ] **Step 3: Implement** the DTO, the repository method and the service (`@Transactional(readOnly = true)`, batch reads, no per-row query except the covered check for record-less trust holds).
- [ ] **Step 4: Run them, expect PASS.**
- [ ] **Step 5: Commit** `feat: decision queue service lists every open hold with who and next step`

### Task 2: Endpoint, permissions, audit policy, OpenAPI

**Files:**
- Create: `backend/src/main/java/com/finora/controller/AdminDecisionQueueController.java`: `GET /api/v1/admin/decision-queue`, `@PreAuthorize("hasAnyAuthority('IMPORT_TRIAGE_MANAGE','TRUST_REVIEW_MANAGE')")`, kinds from `CurrentUser.hasAuthority`.
- Modify: `backend/src/main/java/com/finora/.../AdminReadAuditPolicy.java`: the route gets `Rule.none()` (no content).
- Test: `backend/src/test/java/com/finora/controller/AdminDecisionQueueControllerIT.java`
- Regenerate: `backend/scripts/generate-openapi-spec.sh`; then `npx openapi-typescript@7.13.0` for the admin-portal, frontend and mobile `generated-types.ts`.

- [ ] **Step 1: Write the ITs:**
  - `superAdminSeesBothKinds`: a seeded held import and a held statement, both returned, overdue first.
  - `importTriageOnlySeesImports`
  - `noPermissionIs403`
  - `rowCarriesNoFileNameOrError`: the JSON has no `fileName` or `lastError` keys.
- [ ] **Step 2: Run them, expect FAIL (404).** `./mvnw -q verify -Dit.test=AdminDecisionQueueControllerIT -DskipUTs`
- [ ] **Step 3: Implement** the controller and the audit-policy entry; regenerate the spec and types.
- [ ] **Step 4: Run them plus `AdminReadAuditPolicyCoverageTest`, expect PASS.**
- [ ] **Step 5: Commit** `feat: admin decision queue endpoint`

### Task 3: "Needs a decision" page, sidebar, dashboard and bell links

**Files:**
- Create: `admin-portal/src/pages/NeedsADecision.tsx` and `NeedsADecision.test.tsx`
- Create: `admin-portal/src/lib/timeLeft.ts` (`timeLeftLabel(promiseAt: string, now: Date) -> { text: string; past: boolean }`)
- Modify: `admin-portal/src/api/endpoints.ts` (`adminDecisionQueueApi.list()`), `types/index.ts`, `App.tsx` (route `/needs-a-decision`), `components/Sidebar.tsx` (first link of the first group, permission `['IMPORT_TRIAGE_MANAGE','TRUST_REVIEW_MANAGE']`), `lib/needsAttentionItems.ts` (the four hold items link to `/needs-a-decision`).
- Reuse: `ClaudeFixLine` for the Claude status.

Words (the page owns them):
- `OPEN_REVIEW` "Open a review for it"
- `APPROVE_RERUN_PASSED` "The re-run passed: approve it"
- `MERGE_PR` "Review and merge PR #n"
- `CLAUDE_WORKING` "Claude is working on a fix"
- `CLAUDE_REREADING` "Fix is live: Claude is re-reading it"
- `CLAUDE_NOT_STARTED` "Claude has not started yet"
- `ADMIN_LOOKING` "<name or 'An admin'> is looking into it"
- `FIX_AND_REPROCESS` "Fix the reader and reprocess, or resolve with a message"
- `CHECK_AND_DECIDE` "Check the evidence, then approve or reject"

Time left: "6 h left", "45 min left", "3 h past the promise" (red). Who: "Claude", the name, or "Nobody yet". Open links: `IMPORT` goes to `/held-imports?job=<id>`, `STATEMENT` to `/held-statements/<heldId>`, `NO_RECORD` to `/held-statements`. Empty: "Nothing is waiting on a decision."

- [ ] **Step 1: Write the tests.**
  - `timeLeft`: `exactlyAtPromiseIsNotPast` (0 min left, so "0 min left", past false), `oneMinutePastIsPast`, `hoursRoundDown`.
  - Page: renders rows in server order; "Nobody yet"; next-step words; open links per kind; the empty state; the error state via `LoadError`; the sidebar link shown for a TRUST_REVIEW-only admin.
- [ ] **Step 2: Run them, expect FAIL.**
- [ ] **Step 3: Implement.** The table on wide screens, stacked cards under `sm`, no sideways scroll at 375 px.
- [ ] **Step 4: Run the full portal suite, `tsc -b` and `npm run lint` on Node 22, expect PASS.**
- [ ] **Step 5: Commit** `feat: needs a decision page lists every open hold in promise order`

### Task 4: Verify end to end

- [ ] Run `./mvnw clean verify` in full (all unit tests and ITs).
- [ ] Run the local stack (throwaway Postgres/Redis, the jar, Vite on 5181). Seed one hold of each kind, plus a covered re-upload and an overdue one. Check in Chrome at desktop width and 375 px: order, words, links, the sidebar, and the dashboard and bell links.
- [ ] Fix anything found, with a test, then re-run.
- [ ] Open the PR.
