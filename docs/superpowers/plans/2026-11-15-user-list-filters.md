# Admin Users list filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (Sid's standing rule: no subagents). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Filter the admin Users list by plan, joined dates, last active dates, bank and number of imports, on the server, combined with search, status and paging; show Plan, Last active and Imports columns.

**Architecture:** A new `AdminUserListQuery` builds native SQL over `users` from only the filters set (one clause each, bound parameters), returns a page of user ids plus a total, and a second method loads plan / last active / import count for a page's ids in three set queries. `AdminUserService.list` validates the filter, runs it, and maps rows into the widened `UserSummaryDto`. The portal adds the filters to `FilterBar` and saved views.

**Tech Stack:** Spring Boot 3 / JPA `EntityManager` native queries / Postgres; JUnit 5 + AssertJ, `AbstractIntegrationTest` (Testcontainers Postgres); React + TanStack Query + Vitest in `admin-portal/`.

**Spec:** `docs/superpowers/specs/2026-11-15-user-list-filters-design.md`

## Global Constraints

- Plan = the plan the user gets: max rank of (ACTIVE/TRIAL subscription's plan code, ACTIVE referral grant's tier with `expires_at` null or in the future). Rank PREMIUM 2, PLUS 1, anything else 0 (`ReferralGrant.tierRank`). Filter values `FREE`, `PLUS`, `PREMIUM`; `FREE` = rank 0, including no plan. Column: plan code, or null for none (portal shows "No plan").
- Joined dates are calendar days in `ReportingDay.zone()`; both ends included.
- Last active = latest `user_activity_days.activity_date`. `lastActiveTo` matches users with no row; `lastActiveFrom` does not.
- Bank = live account (`accounts.deleted_at IS NULL`) with that `bank_id`. `OTHER` allowed.
- Imports = `statement_imports` with `deleted_at IS NULL` whose account is live.
- Order stays `created_at DESC, id DESC`; page size clamp stays `MAX_PAGE_SIZE`.
- Bad input = HTTP 400 `ApiException` with a plain message: unknown plan, from after to, negative imports, min > max, unparseable date.
- No migration unless the large-table IT shows a seq scan on a child table; if needed, take the next free `V` number on fresh `origin/main`.
- Commit messages: no AI attribution trailer. Never commit while `./mvnw clean verify` runs.
- Portal tests: `npx --yes node@22 node_modules/.bin/vitest run`.

## Review Focus

- Old saved views (only `q`, `status`) must still apply, with the new filters cleared, not `undefined` sent to the API.
- Search text with `%` or `_` must still match literally (existing `LikePatterns.escape`, `ESCAPE '\'` in native SQL).
- A user whose only Plus is an expired referral grant (`expires_at` past, status still ACTIVE) is Free.
- Imports into a deleted account are not counted, both in the filter and in the column.
- Joined "to" date includes users who signed up late that evening India time (after 18:30 UTC).

---

### Task 1: Filter value and SQL builder

**Files:**
- Create: `backend/src/main/java/com/finora/service/adminusers/UserListFilter.java`
- Create: `backend/src/main/java/com/finora/service/adminusers/AdminUserListQuery.java`
- Test: `backend/src/test/java/com/finora/service/adminusers/UserListFilterTest.java`

**Interfaces:**
- Produces: `record UserListFilter(String q, String status, String plan, String bankId, LocalDate joinedFrom, LocalDate joinedTo, LocalDate lastActiveFrom, LocalDate lastActiveTo, Integer importsMin, Integer importsMax)` with `static UserListFilter of(...raw strings/ints...)` that normalises blanks to null, escapes `q` with `LikePatterns.escape`, upper-cases `plan`, and throws `ApiException(BAD_REQUEST, msg)` for the bad inputs above.
- Produces: `@Component AdminUserListQuery` with `Page<UUID> findIds(UserListFilter f, Pageable p)` and `Map<UUID, UserListFacts> factsFor(Collection<UUID> ids)`; `record UserListFacts(String planCode, LocalDate lastActiveDate, long importCount)`.

- [ ] **Step 1: Write failing tests** `UserListFilterTest`: blanks become null; `plan("plus")` → `PLUS`; `plan("GOLD")` throws 400 "Unknown plan"; `joinedFrom` after `joinedTo` throws; same for last active; `importsMin=-1` throws; `importsMin=5, importsMax=2` throws; `importsMin=0, importsMax=0` allowed; `q="50%"` stored escaped.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=UserListFilterTest` — expect compile failure.
- [ ] **Step 3: Implement `UserListFilter`** and `AdminUserListQuery`: SQL text appended clause by clause into a `StringBuilder` with a `Map<String,Object>` of params, applied to both the page query (`SELECT u.id ... ORDER BY u.created_at DESC, u.id DESC LIMIT/OFFSET`) and the count query. Clauses as in the spec: `EXISTS`/`NOT EXISTS` on `user_activity_days`, `EXISTS` on `accounts`, correlated count subquery for imports, `GREATEST(COALESCE(sub rank,0), COALESCE(grant rank,0)) = :planRank`, joined bounds as `Instant`s from `ReportingDay.zone()`. Subscriptions: respect the entity's soft-delete column if it has one (check `Subscription` annotations). `factsFor`: three `IN (:ids)` queries.
- [ ] **Step 4: Run** `UserListFilterTest` — PASS.
- [ ] **Step 5: Commit** `feat(backend): user list filter value and query builder`.

### Task 2: Service, controller, DTO + integration tests

**Files:**
- Modify: `backend/src/main/java/com/finora/dto/AdminDtos.java` (`UserSummaryDto` + `String planCode, LocalDate lastActiveDate, long importCount`)
- Modify: `backend/src/main/java/com/finora/service/AdminUserService.java` (`list(UserListFilter, page, size)`; `toSummary` takes facts; single-user returns load facts for one id)
- Modify: `backend/src/main/java/com/finora/controller/AdminUserController.java` (new `@RequestParam(required=false)` params, dates `@DateTimeFormat(iso=DATE)`)
- Modify: `backend/src/main/java/com/finora/repository/UserRepository.java` (remove `search` if no caller remains)
- Modify: `backend/src/test/java/com/finora/service/AdminUserServiceTest.java` (mocks follow the new collaborator)
- Create: `backend/src/test/java/com/finora/controller/AdminUserListFiltersIT.java`
- Create: `backend/src/test/java/com/finora/controller/AdminUserListQueryPlanIT.java`

**Interfaces:**
- Consumes: Task 1.
- Produces: `GET /api/v1/admin/users?plan&bankId&joinedFrom&joinedTo&lastActiveFrom&lastActiveTo&importsMin&importsMax` (dates `yyyy-MM-dd`), rows with `planCode`, `lastActiveDate`, `importCount`.

- [ ] **Step 1: Write failing IT** `AdminUserListFiltersIT` (each test scopes with a unique `q` email prefix so other ITs' rows never interfere):
  - `plan_PLUS_matchesPaidAndReferralPlus_notExpiredGrant` — paid Plus, trial Plus, referral Plus, expired grant (status ACTIVE, `expires_at` past) → only first three; expired one matches `FREE`.
  - `plan_FREE_includesUserWithNoSubscription`, row `planCode` null.
  - `joined_rangeIsInclusiveInIndiaTime` — user created `2026-11-10T19:00Z` (11 Nov IST) matches `joinedFrom=joinedTo=2026-11-11`, not `2026-11-10`.
  - `lastActiveTo_includesNeverSeen_lastActiveFrom_excludesThem`; `lastActive_rangeUsesLatestDay` (days 1 Nov and 12 Nov: `to=2026-11-05` does not match).
  - `bank_matchesLiveAccountOnly` (deleted HDFC account does not match).
  - `imports_countSkipsDeletedStatementsAndDeletedAccounts`; `importsMax=0` matches user with none; column `importCount` agrees.
  - `filtersCombineWithSearchStatusAndPaging` — 25 matching users, size 20: page 0 has 20, page 1 has 5, `totalElements` 25, order newest first.
  - `badFilter_is400` for `plan=GOLD`, `importsMin=5&importsMax=2`, `joinedFrom=2026-11-12&joinedTo=2026-11-01`.
  - `searchWithPercent_matchesLiterally`.
- [ ] **Step 2: Write** `AdminUserListQueryPlanIT`: seed 50,000 users and spread accounts, statement imports, activity days, subscriptions, grants with `generate_series` SQL, `ANALYZE`; for each filter alone and all together, run `EXPLAIN (ANALYZE, FORMAT JSON)` of the built SQL (expose a package-visible `explainForTest`), assert no `Seq Scan` node on `accounts`, `statement_imports`, `user_activity_days`, `subscriptions`, `referral_grants`, and execution time under 2000 ms; print the measured times. Clean up seeded rows after.
- [ ] **Step 3: Run** both ITs (`./mvnw -q verify -Dit.test=AdminUserListFiltersIT,AdminUserListQueryPlanIT -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false`) — expect FAIL.
- [ ] **Step 4: Implement** service, controller, DTO changes.
- [ ] **Step 5: Run** both ITs plus `AdminUserControllerIT`, `AdminUserServiceTest`, `AdminEndpointAuthorizationTest`, `PagedListSortEndsOnIdIT` — PASS. If the plan IT shows a child-table seq scan, add the index migration and re-run.
- [ ] **Step 6: Commit** `feat(backend): admin users list filters by plan, dates, bank and imports`.

### Task 3: OpenAPI spec and generated client types

**Files:**
- Modify: `backend/openapi/openapi.json`, `admin-portal/src/api/generated-types.ts`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts` (whichever exist)
- Modify: `admin-portal/src/types/*.ts` (`UserSummaryDto` + `planCode: string | null; lastActiveDate: string | null; importCount: number`)

- [ ] **Step 1:** Run `backend/scripts/generate-openapi-spec.sh` per its header, then each client's `generate:types`.
- [ ] **Step 2:** `git diff --stat` shows only the users list params and the three DTO fields.
- [ ] **Step 3: Commit** `chore: regenerate OpenAPI spec and client types for user list filters`.

### Task 4: Portal filters, columns, saved views

**Files:**
- Modify: `admin-portal/src/components/FilterBar.tsx` (add `{ type: 'number'; key; value; onChange; label; min?: number }`, labelled like `date`)
- Modify: `admin-portal/src/api/endpoints.ts` (`UserListFilters` + `plan, bankId, joinedFrom, joinedTo, lastActiveFrom, lastActiveTo, importsMin, importsMax`)
- Modify: `admin-portal/src/pages/Users.tsx`
- Test: `admin-portal/src/pages/Users.test.tsx`, `admin-portal/src/components/FilterBar.test.tsx` (if present)

**Interfaces:**
- Consumes: Task 2 API, `banksApi.search()` for the bank picker (+ "Unknown bank" `OTHER`).

- [ ] **Step 1: Write failing tests** in `Users.test.tsx`: choosing Plan "Plus" calls `list` with `plan: 'PLUS', page: 0`; bank, dates and imports min/max reach `list` with those keys; empty fields are not sent (`undefined`); a saved view with only `{q, status}` applies and clears the others; columns show "Plus", "No plan", "Not seen", a formatted date, and the import count; a 400 message from the API is shown.
- [ ] **Step 2: Run** `npx --yes node@22 node_modules/.bin/vitest run src/pages/Users.test.tsx` — FAIL.
- [ ] **Step 3: Implement** filter state as one `UserFilterValues` object (all string keys), query key includes it, any change resets page to 0, saved views store all keys and `applyView` fills missing keys with `''`; add the number field to `FilterBar`; columns Plan / Last active / Imports.
- [ ] **Step 4: Run** the portal suite, `npx tsc -b` (or the repo's type-check script) and `npm run lint` — all clean.
- [ ] **Step 5: Commit** `feat(admin-portal): users list filters for plan, dates, bank and imports`.

### Task 5: Full verification, browser check, PR

- [ ] **Step 1:** `./mvnw clean verify` in `backend/` (background; nothing committed while it runs) — BUILD SUCCESS.
- [ ] **Step 2:** Run backend + portal locally against a local DB with made-up users (no production data); exercise every filter and paging in Chrome; screenshot.
- [ ] **Step 3:** Check merge state against `origin/main` by content, push, open PR (body: what each filter means, measured plan-IT times, browser evidence).
