# Reading quality by bank Implementation Plan

> Executed inline (native) in the session that wrote it: Sid's standing rule is no subagents.

**Goal:** An admin page that ranks banks by hold rate, with statements read, rows read, holds and
fixes per week, so an admin can see which bank's statements most need reader work next.

**Architecture:** One read-only endpoint runs four grouped SQL queries over existing tables
(`statement_analysis_sessions`, `held_statements`, `import_jobs`, `claude_fix_jobs`,
`held_statement_events`) and folds them into per-bank, per-week counts. One admin portal page shows
the table and a weekly hold-rate trend per bank.

**Tech Stack:** Spring Boot + JdbcTemplate, Postgres; React + TanStack Query + recharts (admin portal).

**Spec:** `docs/superpowers/specs/2026-11-15-reading-quality-by-bank-design.md`

## Global Constraints

- Bank names and counts only on the wire: no file names, user ids, job ids, references or content.
- Bank for every number comes from `statement_analysis_sessions.bank_name`, never `held_statements.bank_name`.
- Weeks start Monday, `Asia/Kolkata` (`UserZone.DEFAULT`). Period = this week plus the previous `weeks - 1`.
- `weeks` accepts 4 or 12 only; anything else is `VALIDATION_ERROR` (400).
- Hold rate shown only when a bank has at least 10 statements read in the period.
- Fix = Claude fix job `FIXED` (dated `updated_at`) or non-blank `fix_reference` (dated by latest
  `FINDINGS_UPDATED` event); one per import job, dated by the earlier.
- Endpoint `GET /api/v1/admin/imports/reading-quality`, `@PreAuthorize("hasAuthority('PLATFORM_DIAGNOSTICS_VIEW')")`,
  classified `Rule.none()` in `AdminReadAuditPolicy`.
- Commit messages carry no AI attribution trailer.

## Review Focus

- A bank with holds or fixes but no statements read in the period still gets a row (too few to judge).
- Several evidence rows for one staging session (a re-read): the hold is counted once, under the latest row's bank.
- A trust hold whose evidence row has no bank is counted in "Bank not known", not dropped.
- A hold that has both a Claude fix and an engineer fix reference is one fix, dated by the earlier.
- A week with no statements has a null rate (gap in the trend), not 0%.

---

### Task 1: Backend endpoint

**Files:**
- Create: `backend/src/main/java/com/finora/dto/ReadingQualityDto.java`
- Create: `backend/src/main/java/com/finora/service/ReadingQualityService.java`
- Create: `backend/src/main/java/com/finora/controller/AdminReadingQualityController.java`
- Modify: `backend/src/main/java/com/finora/security/AdminReadAuditPolicy.java` (one `Rule.none()` entry)
- Test: `backend/src/test/java/com/finora/controller/AdminReadingQualityControllerIT.java`

**Interfaces (produces):**
- `ReadingQualityDto.Report(int weeks, int minStatements, List<LocalDate> weekStarts, List<BankRow> banks, Unknown bankNotKnown)`
- `ReadingQualityDto.BankRow(String bank, long statementsRead, long rowsRead, long holds, Double holdRate, long fixes, List<Week> weeks)` — `holdRate` null when too few to judge.
- `ReadingQualityDto.Week(LocalDate weekStart, long statementsRead, long rowsRead, long holds, long fixes)`
- `ReadingQualityDto.Unknown(long importHolds, long trustHolds, long fixes)`
- `ReadingQualityService.report(int weeks, Instant now)`

Steps: IT first (unique made-up bank names per test, since the IT tables are shared), run red,
implement, run green. Tests: bank link through `import_session_id`; latest evidence row wins;
10-statement cutoff (9 vs 10); sorting (rate desc, too-few last); fix de-duplication and dating;
bank-not-known counts as deltas; `weeks=5` gives 400; no permission gives 403; body carries no
user id or file name.

### Task 2: Admin portal page

**Files:**
- Create: `admin-portal/src/pages/ReadingQualityByBank.tsx`, `admin-portal/src/pages/ReadingQualityByBank.test.tsx`
- Modify: `admin-portal/src/types/index.ts`, `admin-portal/src/api/endpoints.ts` (`adminReadingQualityApi.report(weeks)`),
  `admin-portal/src/App.tsx` (route `/reading-quality`), `admin-portal/src/components/Sidebar.tsx` (entry next to Import Health)

Page: 4/12-week switch, table sorted as served, rate or "too few to judge", tiny recharts line of
weekly hold rate, a "Bank not known" line, one line on known limits. Tests: rows render in served
order, too-few label, switch refetches with `weeks=4`, empty state, error state.

### Task 3: Contracts and verification

- Regenerate `backend/openapi/openapi.json` (`backend/scripts/generate-openapi-spec.sh`) and the three
  `generated-types.ts` (`npm run generate:types` per client).
- `./mvnw clean verify` (background), portal `vitest`, `tsc -b`, `eslint` on Node 22.
- Browser check locally with made-up data; screenshot.
- Commit, push, open PR.
