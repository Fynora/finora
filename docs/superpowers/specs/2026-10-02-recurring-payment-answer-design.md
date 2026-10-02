# Ask once about a repeating payment — design

Date: 2026-10-02. Status: approved by Sid 2026-10-02, including the review changes (money-out-only payee rules, audit
logging, telemetry notes).

## Problem (measured, not assumed)

After the categorisation work in #1876 and #1885, 689 of the 1,940 rows in the real statement corpus
are still "Other" on a fresh account. Some of them are payments the app already recognises as
repeating (RecurringService: three or more payments to the same payee label, amounts within ±20%
(+₹1) of the group average, a regular gap of 5–95 days). The recurring card on web and mobile lets
the user confirm (✓) or dismiss (✕) such a group, but it never asks what the payment is for.

Measured on origin/main (b03a3c02) with a local probe that staged every corpus statement for a fresh
user with the default categories seeded, then replayed RecurringService's grouping per statement:

- 29 distinct statements produce 3 recurring groups, 13 rows: one already categorised correctly, one
  "Other" (5 rows), one structural "Personal Transfer" (3 rows).
- 25 of 31 statements cover a month or less, so a monthly pattern cannot be seen within one of them.
  Three more payees appear twice, a month apart, and would qualify with a third month.

The measured gain on the corpus is therefore small (8 rows). The feature's value grows with months of
history per user, which the corpus cannot show. Sid chose to build it small on that basis.

## Decisions (Sid, 2026-10-02)

1. Ask only about groups whose payments are all still "Other" or a structural "Personal Transfer"
   guess. Groups already categorised are not asked about.
2. The question offers a short list plus "Something else": Rent, Loan EMI, Subscriptions, Education,
   Insurance, Utilities, Investments. "Something else" opens the normal category picker.
3. The answer applies to future payments to that payee only when the amount is similar: within the
   same ±20% (+₹1) of the group average that recurring detection uses.
4. Store the answer as a user category rule (approach 1 of 3; a separate answers table and reusing
   merchant learning were rejected — see "Rejected approaches").
5. Review fixes 1–7 below are in scope; limits 9–11 are accepted and documented.
6. Payee rules apply only to money going out (review, 2026-10-02): the question is only ever asked
   about outgoing payments, and a landlord's deposit return, an employer's reimbursement or a
   broker's redemption must not be filed under the answer. This removes what was limit 8.
7. The category list stays as above for now; which category each answer picks (including picks
   through "Something else") is logged so the chips can be revised from real answers.

## Design

### 1. Rules gain a payee match and an amount range

- New `CategoryRule.Field.PAYEE`. Its value is `CategoryRules.extractMerchantLabel(description)`, the
  same payee label stored on `Transaction.merchant` at confirm and used by RecurringService to group.
  Matched with `EQUALS`, ignoring case, and only on money going out: a PAYEE rule needs the
  transaction direction to be EXPENSE, and a caller that gives no direction gets no PAYEE match
  (fails closed). The direction is passed into the shared matching function alongside the other
  fields; rules of every other field ignore it, so existing rules behave exactly as before.
- Two new nullable columns on `category_rules`: `amount_min`, `amount_max` (numeric). When set, a rule
  matches only if the transaction amount is within them, inclusive, in addition to its own condition.
  Existing rules have both null and behave exactly as before.
- The check lives in `RuleEngineService.matches`, the one function every rule path calls (fix 7):
  import preview and confirm (`evaluateCategoryRule` over the hoisted rule set), manual add
  (`suggest`), statement refresh, side-effect rules and the admin rule tester (`testMatch`). The payee
  label is computed once per evaluation and only when a PAYEE rule is present.
- One migration, at the next free Flyway version (checked against origin/main and open PRs at build
  time; V247 is the latest on main as of this spec): the two columns, plus a partial unique index on
  `(user_id, lower(comparison_value))` where `field = 'PAYEE'` and `scope = 'USER'` (fix 4).

### 2. Answering

`POST /api/v1/recurring/categorize` with `{merchant, category}`.

1. Returns 404 if `RECURRING_DETECTION_ENABLED` is off, or if this user has neither a detected group
   with that payee label nor a saved answer for it. The server re-detects the group itself and never
   trusts an amount sent by the client.
2. Resolves the category by name for this user (`resolveOrCreateCategory`; a chosen name is an
   explicit decision, so creating it is correct).
3. Creates the user's PAYEE rule for that label, or updates the existing one (fix 4). Action
   `ASSIGN_CATEGORY`, priority 100. The amount range, with tolerance t(a) = 20% of a + ₹1:
   - from a detected group: average ± t(average), widened to cover every payment in the group;
   - when a saved answer exists, the new range also keeps the old one and covers the payee's latest
     payment ± t(latest). This is how "still Rent?" widens the range for a payee the detector no
     longer groups (fix 5).
4. Re-files past rows: the user's live-account EXPENSE rows whose `merchant` equals the label and
   whose amount is in range, except rows with `categoryManuallySet` (fix 2). Each gets the category,
   `decisionSource = USER_RULE`, `decisionRuleId`, `needsCategoryReview = false`, and is saved through
   the entity, so its version bumps and the mobile change stamp moves. No merchant learning is queued
   and nothing is written to the shared corpus.
5. Runs the same investment-exclusion recount as a manual recategorisation when the category is
   Investments or any re-filed row is currently an investment transfer (fix 3). The private
   `reconcileIfInvestmentExclusionMayChange` logic is shared, not duplicated.
6. Records an audit entry `RECURRING_ANSWERED` (payee label, category, and kind: first answer,
   change, or "still X? Yes") and counts as confirming the group. Dismissing a group also records
   `RECURRING_DISMISSED`, which it does not today.

### 3. What the recurring list returns

`GET /api/v1/recurring` keeps its shape; `RecurringDto` gains:
- `category` — the most common category among the group's rows.
- `answer` — the saved answer's category name, or null.
- `state` — one of:
  - `NEEDS_ANSWER`: no saved answer, and every row is "Other" or a structural "Personal Transfer"
    guess, none set by hand;
  - `ANSWERED`: a saved answer exists and covers the latest payment;
  - `AMOUNT_CHANGED`: a saved answer exists but the latest payment is outside its range (fix 5);
  - `NONE`: anything else (no question shown).

A saved answer stops the question whatever its category, including "Other" or "Personal Transfer"
(fix 1).

Fix 5 also needs payees that RecurringService no longer detects: a rent rise above 20% breaks its
amount-consistency test, so the group disappears from that list. These come from a new endpoint,
`GET /api/v1/recurring/changed-amounts`: for each saved PAYEE answer whose payee is not in the
detected list, the payee's latest EXPENSE payment when it is outside the saved range — payee label,
category, latest amount and date, current range. A separate endpoint rather than extra entries in the
existing list, because older app versions read every `RecurringDto` field (label, next estimate) as
always present; only new app versions call it.

### 4. Apps

Shown where the recurring card already has its ✓/✕ actions: Dashboard and Insights, on web and mobile.
Financial Memory stays read-only. One shared component per app, placed in the existing rows.

- `NEEDS_ANSWER`: "What is this ₹X monthly payment?" with chips for the short list and "Something
  else" (opens the existing category picker). A chip appears only if the user has that category, so a
  deleted category is not brought back by a chip.
- `ANSWERED`: "<category> · Change". Change shows the chips again.
- `AMOUNT_CHANGED` (a detected group in that state, or an entry from `/recurring/changed-amounts`):
  "₹Y to <payee> — still <category>?" with Yes (re-sends the same category, which widens the range)
  and Change.
- After an answer: refetch recurring, transactions and summary queries; on mobile through the existing
  gated change-sync client so the stamp baseline stays correct.
- Older app versions ignore the new fields and behave as today.
- API description and the three apps' generated types are regenerated (fix 6); the admin portal shows
  the PAYEE field and the amount range in its rule lists and forms.

## Accepted limits

9. Two different repeating payments to one payee (rent and maintenance) are not detected: the
   existing detector requires consistent amounts. Unchanged by this work.
10. The rule matches the payee label exactly. If the bank's narration or our label extraction changes
    the label, the rule stops matching and the payments return to "Other". The `AMOUNT_CHANGED`
    re-ask cannot catch this (the label no longer matches); the detector asks again only once the new
    label repeats three times.
11. The payee label (possibly a person's name) is stored on the rule and visible to admins in the
    admin portal's rule list, as other user rules already are.

## Telemetry

The corpus shows 8 rows of benefit today, so whether this works depends on behaviour over months of
real use. The app has no web or mobile analytics tool, so measurement uses what the backend has:

- Answered, and which category: the `RECURRING_ANSWERED` audit entries (kind distinguishes first
  answer, change and "still X? Yes"; a high change rate means the chips or ranges are wrong).
- Dismissed: the new `RECURRING_DISMISSED` audit entries.
- Rule later matched: `category_rules.match_count` and `last_matched_at`, already maintained for every
  rule (`recordRuleMatch` at confirm and manual add).
- Rule stopped matching (the payee-label drift of limit 10): a PAYEE rule whose `last_matched_at` is
  older than about 60 days while its user keeps importing statements. A query, no new code.
- Range too narrow: frequency of the "still X? Yes" kind among answers.

Not measured until an analytics tool exists: how often the question is shown, and shown-but-ignored.

## Rejected approaches

- A separate "remembered answers" table: new loading code inside the import pipeline, which is
  guarded by a query-count test, for no advantage over a rule.
- Reusing merchant learning: cannot hold the amount condition (decision 3), and merchants are grouped
  by the payee's first word until the pooling fix lands.
- Reusing `TransactionService.bulkRecategorize` for re-filing: it marks rows as set by hand (blocking a
  later Change) and queues merchant learning (fix 2).

## Testing

- Rule engine: PAYEE match (case, missing label), amount range at the exact boundaries and one paisa
  past them, both null, one bound null; a PAYEE rule never matches a credit or a call with no
  direction, while other rules are unaffected by direction; every evaluation path in fix 7.
- Recurring service: each `state`; a saved answer stops the question for every category, including
  Other; `/recurring/changed-amounts` lists a payee the detector no longer groups and never one already in
  the detected list; categorize on such a payee widens the range to cover its latest payment.
- Categorize: creates then updates one rule; double submit leaves one rule (unique index, IT);
  re-files only the payee's in-range, non-manual EXPENSE rows; investment recount runs for
  Investments; refused when the feature flag is off or the group is unknown; the audit entries are
  written with the right kind.
- End to end (IT): answer, stage the next statement, the new in-range payment is filed by the rule and
  an out-of-range one is not.
- Migration IT for the new columns and index.
- Real corpus, before/after on every row: with no answers saved, zero rows change; then a simulated
  answer on the corpus's one "Other" group changes only that payee's in-range rows, each read.
- Web (Vitest) and mobile (Jest) component tests for the three states, under Node 22; OpenAPI drift
  check; full backend `./mvnw -o verify`; fixture hygiene and both corpus leakage scans.
