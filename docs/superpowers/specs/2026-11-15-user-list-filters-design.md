# Admin Users list: filters by plan, dates, bank and imports — design

Date: 2026-11-15. Size: small change (short design).

## Intent

Sid: "Better user filters: filter by plan, sign-up date, last active, bank and number of imports.
Today it's only search and status." The admin Users page (`admin-portal/src/pages/Users.tsx`,
`GET /admin/users`) needs to find groups of users, e.g. paying users who have not been seen since a
date, or users of one bank with no imports.

Goals: the five new filters, combined with search, status and paging, all done in the database.
Non-goals: exports, bulk actions, new tracking of activity. Anti-goal: loading every user into the
browser, or counts that drift from the real data.

## What each filter means (decided by Sid, 2026-11-15)

- **Plan**: the plan the user gets now, as `EntitlementService.planCodeFor` works it out: the higher
  of their ACTIVE/TRIAL subscription's plan and any ACTIVE, unexpired referral reward. Options Free,
  Plus, Premium. *My call:* a user with neither (the app treats that as the most limited) matches
  Free and shows "No plan" in the column.
- **Joined**: from / to date, on `users.created_at`, as calendar days in the platform reporting zone
  (Asia/Kolkata, `ReportingDay`), both ends included.
- **Last active**: from / to date, on the latest `user_activity_days.activity_date` (recorded only
  since 3 Oct 2026). A user with no recorded day matches a "to" date (they are the most inactive) and
  does not match a "from" date. Column shows "Not seen" for them.
- **Bank**: the user has a live (not deleted) account whose `bank_id` is the chosen bank. Every
  import lands in an account, so this covers "imports from that bank" too. Picker = the existing bank
  list (`GET /banks`), plus "Unknown bank" (`OTHER`).
- **Imports**: number of statements imported (`statement_imports`, not deleted, into a live account —
  an account delete leaves its statements' rows undeleted, the same leak `getUser` already scopes
  out for transactions). Min and max, both optional, both included.

## Approaches (Sid picked 1)

1. **Build SQL from only the filters set** (picked). A small query class adds one clause per filter
   used; bound parameters only. Postgres can reuse one cached plan for a fixed query with
   "`:x IS NULL OR ...`" for every combination, which picked a bad plan before (audit log flake,
   2026-11-15).
2. One fixed query with "or not set" per filter: least code, risk of slow plans.
3. Store plan / last active / import count on `users`: fastest reads, but imports, deletes, billing
   webhooks and referral expiry all write them; counts drift.

## Design

- **API**: `GET /admin/users` gains optional `plan`, `bankId`, `joinedFrom`, `joinedTo`,
  `lastActiveFrom`, `lastActiveTo`, `importsMin`, `importsMax` (dates `yyyy-MM-dd`). Bad values
  (unknown plan, from after to, negative or min > max) are 400 with a plain message. Same
  `USER_VIEW` permission; the route is already in `AdminReadAuditPolicy`.
- **Query**: native SQL over `users`, built from the filters set, same order (`created_at DESC, id`)
  and paging, with a matching count query. Each filter is an `EXISTS`, or a correlated subquery
  that can use an existing index: `accounts(user_id) WHERE deleted_at IS NULL`,
  `statement_imports(user_id, account_id) WHERE deleted_at IS NULL`, the `user_activity_days
  (user_id, activity_date)` unique index, `subscriptions(user_id)`, `referral_grants(user_id)`.
  No migration unless the large-table test shows one is needed.
- **Rows**: `UserSummaryDto` gains `planCode`, `lastActiveDate`, `importCount`, worked out for the
  page's rows only with one query each (not per row).
- **Page**: new filters in the existing `FilterBar` (Plan select, Bank select, Joined from/to,
  Last active from/to, Imports min/max — a small `number` field type added to `FilterBar`); any
  change resets to page 1; all saved in saved views (old saved views still load). New columns Plan,
  Last active, Imports.
- **Tests**: unit tests for the filter parsing and SQL building; an integration test against real
  Postgres for every filter alone and combined, the edge cases above (no activity record, deleted
  account, deleted statement, referral reward expired, trial), and query plans on a large table
  (tens of thousands of users) that show index use and a time bound; portal tests for the filters,
  saved views and columns.

## Left to the builder

Exact component layout of the filter row; how the large-table test seeds data; the SQL text.
