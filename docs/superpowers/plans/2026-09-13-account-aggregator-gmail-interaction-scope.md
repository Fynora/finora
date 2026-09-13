# Account Aggregator sync — Plan 3 scope (AA↔Gmail interaction)

Status: scoped, not yet an implementation plan. Boundary decisions below are settled; this is not
a task-by-task TDD plan (that's a separate future step, same as Plans 1 and 2's).

Builds on Plan 1 (link lifecycle, merged #1396/#1400) and Plan 2 (transaction sync, merged #1406).
Plan 2 already added one piece of AA-vs-Gmail handling as a defensive fix, not a feature: it
excluded `ACCOUNT_AGGREGATOR` from the *existing* Gmail cross-source pass's candidate pool (a bug
that pass would otherwise have had, once the enum value existed). Plan 3 is where the *real*,
dedicated AA-vs-Gmail behavior the design spec calls for actually gets built.

## A spec assumption that no longer holds — verified, not guessed

The design spec's "Gmail confirm-time UX for an AA-linked account" section assumes a user can still
*attempt* to confirm a staged Gmail receipt against an AA-linked account, just with a warning
("This account already syncs directly with your bank...", Skip/Review-later/Continue-anyway).

That assumption is false today. Checked directly against `origin/main` (not the stale primary
checkout, which was 26 commits behind and would have missed this entirely):
`ImportService.resolveTargetAccount` calls `AccountAggregatorGuard.checkNotActivelySynced(userId,
request.existingAccountId())` unconditionally whenever an existing account is chosen — for *every*
import source, Gmail included, since `GmailStagingBridge` stages a receipt through the exact same
`ImportSession`/confirm path a CSV or PDF upload uses (that reuse is its own explicit design goal —
see that class's own doc comment). Confirming a Gmail receipt into an actively AA-linked account
therefore already fails with a hard `409 CONFLICT`, before any warning dialog could ever render.

**Decision (confirmed with the user): accept Plan 1's guard as authoritative.** An AA-linked
account is manual-import-blocked, full stop — no Gmail-specific exception to a guard that was
deliberately built universal. The confirm-time warning dialog is dropped from Plan 3 entirely, not
deferred. What Plan 3 keeps and builds instead:

- The **reconciliation-side rule** stays fully necessary and is unaffected by this: it protects
  Gmail-sourced transactions that were confirmed *before* an account became AA-linked. The guard
  only blocks *future* confirms into an *already*-`ACTIVE` link; it does nothing retroactively, so
  pre-existing Gmail rows still collide with AA's incoming feed once Plan 2's sync starts landing
  data for that account.
- A **proactive, informational** (not interactive) UI addition, per the user's own framing: no
  dialog, no buttons, no override. Just telling the user *why* an option isn't available, at the
  point they'd otherwise be confused by a raw 409.

## In scope

- **Dedicated AA-vs-Gmail reconciliation pass**, `ReconciliationService`, new and separate from
  Plan 2's AA-vs-manual fuzzy pass (different policy, so not a parameterized variant of it):
  - Trigger: same account, same amount, same direction, tight date window, **high**-confidence
    description similarity — a stricter threshold than the AA-vs-manual pass's bootstrap 0.6 (spec:
    "a stricter threshold than the review-only Gmail pass uses"). Bootstrap value only, same
    discipline as Plan 2's own thresholds: a literal with a comment, not a named constant, until
    real data validates one.
  - Action: **auto-exclude**, unlike every fuzzy pass built so far. Marks the `GMAIL_IMPORT` row
    `reconciliationStatus = DUPLICATE`, `isDuplicateOf` = the AA row — reusing the exact-match
    pass's existing legacy-column mechanism, not a new status or a new totals-exclusion path. This
    is the one deliberate policy difference from Plan 2's AA-vs-manual pass and from the existing
    Gmail-vs-bank-statement pass, both of which only ever produce a candidate graph edge. The
    spec's own justification: AA is a live bank feed, not a parsed/OCR'd document, which is what
    licenses trusting a high-confidence match here without a human in the loop.
  - Scope: `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)` only — does not touch or loosen the existing
    Gmail-vs-CSV/PDF pass's conservatism, which stays as-is (OCR uncertainty on that side is a
    different problem this plan isn't reopening).
  - **Idempotent by construction, not by accident**: the Gmail-side candidate selection must exclude
    any Gmail transaction **already resolved by any reconciliation mechanism**, not merely one this
    pass itself wrote on a prior run — the implementation is the same one check the exact-match pass
    already uses (`if (t.getIsDuplicateOf() != null) continue`), but the intent is broader than "this
    pass doesn't reprocess its own work." The two mechanisms this actually has to protect against are
    distinct: (a) the exact-match pass resolving a Gmail row first, in the same or an earlier run,
    which this pass must not then re-touch; and (b) this pass resolving one itself on a prior run,
    which a later run must not re-write. Skipping any already-`isDuplicateOf`-set row handles both
    with one check, which is exactly why the exact-match pass already applies it at the very top of
    its own loop rather than per-pass. Without it, a Gmail row resolved by either mechanism would be
    re-matched and re-written on every subsequent `reconcileForUser` call — not incorrect (same
    values every time), but it would mark `dirty`/`changedSomething` true on every run for any user
    with one active AA-Gmail duplicate pair, producing a no-op audit record forever. Needs its own
    test: run the pass twice over the same fixture, assert the second run touches nothing.
  - **Confirmed unnecessary, not assumed**: no defensive check for a Gmail transaction with a null
    `accountId` is needed. `transactions.account_id` has been `NOT NULL REFERENCES accounts(id)`
    since `V1__init_schema.sql` — the very first migration, not a later addition — so no
    `Transaction` row of any source has ever been able to exist without a resolved account. The
    "same account" trigger condition is always meaningful by construction.
- **Expose `Account.primarySource` on the API**, since it currently isn't surfaced anywhere —
  verified: absent from `AccountDto`, absent from the generated OpenAPI spec. Needed for the UI
  piece below to know which accounts are AA-linked at all. Minimal addition: one field on the
  existing `AccountDto` record (whatever accessor shape matches its established pattern), no new
  endpoint.
- **Informational UI in the shared account picker** (`frontend/src/pages/Import.tsx`'s existing
  `<select>` of `existingAccounts`, the same picker every import source — Gmail, CSV, PDF — already
  shares, per `GmailStagingBridge`'s own "no code path of their own" design). For an
  `ACCOUNT_AGGREGATOR`-sourced account: `disabled` on that `<option>`, with a label suffix like
  `" — Bank Sync active"` (exact copy TBD, matches the user's own framing: informational, not a
  confirmation flow). This is not Gmail-specific despite Plan 3's name — it's the same shared
  component, so a CSV/PDF import attempt gets the identical proactive signal instead of only a
  reactive 409 after clicking confirm. Worth being explicit about: this is a genuine improvement to
  the CSV/PDF path too, arrived at because Plan 3 needed it for Gmail, not scope creep — there was
  no proactive signal for *any* source before this.

## Out of scope (explicitly, not silently dropped)

- **The confirm-time warning dialog** (Skip these receipts / Review duplicates later / Continue
  anyway) from the design spec's "Gmail confirm-time UX" section — dropped per the decision above,
  not deferred to a later plan. If this is ever revisited, it needs its own fresh justification for
  overriding Plan 1's guard, not a resurrection of the original spec text.
- **The outage escape hatch's interaction with this guard** (Plan 4): once that lands, an
  `ACTIVE` link whose sync has gone stale past 3× cadence unblocks manual upload again — at that
  point `AccountAggregatorGuard` needs to also check for that outage-hatch state, not just
  `ACTIVE`/not-`ACTIVE`. Plan 3 does not touch the guard at all; this is Plan 4's own change to make
  when it exists, not something to speculatively half-build now.
- **Any other reconciliation UI** beyond the one account-picker indicator — a full
  consent-management surface (linked-accounts list, revoke/relink, last-synced display) is Plan 5.

## Testing

- Unit: the new AA-vs-Gmail pass, both directions (fires at high confidence, does not fire at the
  AA-vs-manual pass's lower 0.6-class threshold — the two thresholds must be independently
  verified, not assumed consistent just because both live in the same file now); confirms
  `isDuplicateOf`/`reconciliationStatus` are actually set (unlike every other fuzzy pass, which is
  exactly the point being tested); confirms it does not touch the existing Gmail-vs-CSV/PDF pass's
  own candidates; **confirms idempotency directly** — run `reconcileForUser` twice over the same
  fixture (a Gmail row already matched from the first run) and assert the second run produces no
  new edge, no re-write of `isDuplicateOf`, and does not mark `changedSomething`/audit as if
  something new happened.
- Unit/component: the account-picker's disabled state and label for an `ACCOUNT_AGGREGATOR`-sourced
  account, across at least one Gmail-confirm and one CSV-confirm scenario (same shared component,
  both need coverage since both are now affected).
- No end-to-end reconciliation-quality testing beyond unit level — same limitation Plan 2 noted:
  real threshold tuning needs real data, not available yet.

## Open items still unresolved (carried forward, not blocking Plan 3 start)

- Exact high-confidence similarity threshold for the new pass — bootstrap value ships with Plan 3,
  tuning happens later against real data.
- Exact account-picker copy/label text — a placeholder ships with Plan 3; the precise wording is a
  product/copy decision, not an engineering one.
