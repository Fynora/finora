# Quick sort: fewer, easier category questions after an import

Status: proposed, 2026-10-03. Tester report item 4.

## The problem

A tester imported a 6-month savings statement and a credit-card statement (376 transactions) and
spent about 30 minutes categorising. The time did not go on tapping. It went on **studying**: each
waiting payment had to be read, understood, and given a category picked from the full list, one
row at a time, 10 rows a page.

### What was measured (production, the tester's own account, 2026-10-03)

| How a row got its category | Rows | Waiting for the user |
|---|---|---|
| Structural person-to-person transfer (`STRUCTURAL_P2P`, "Personal Transfer") | 114 | 114 |
| Nothing matched, "Other" (`MERCHANT_DEFAULT`) | 96 | 96 |
| Set by the user (`MANUAL`) | 91 | 0 |
| Keyword, global rule, learned pattern | 75 | 0 |

- 210 rows were waiting, from **145 payees**, worth Rs 4.74 lakh in total.
- **75% of the waiting rows were under Rs 1,000**, and 46% were under Rs 200.
- Money is concentrated in a few payees:

  | Biggest payees | Share of the waiting money |
  |---|---|
  | 5 | 63% |
  | 10 | 78% |
  | 20 | 89% |
  | 40 | 95% |

- Of the 96 "Other" rows: 39 were QR-code shop payments, whose narration prints only the shop
  owner's personal name and a QR id; 22 carried another UPI id, 18 an id the bank cut short,
  15 a name, and 2 no key at all.
- The user's own small (< Rs 1,000) choices used only three categories (Friend Repayment,
  Transport, Credit Card bill). Not once was a small payment to a person given a spending
  category, so nothing in the user's history says what those payments were for.

### What the code does today (read on `main` at 77c35c18)

1. **Rules added after an import never reach the rows still waiting.** `BankActivityCategory`
   (bank interest, a card bill received) and `ShopTradeCategory` landed in #1876/#1885 on
   2026-10-02, the day after the tester's import. Interest rows and a card-bill row from that
   import are still "Other" and waiting. `CounterpartyBackfillSweepService` re-types payees but
   never re-suggests a category.
2. **Imports never call the AI model.** Staging uses `CategorizationService.suggestReadOnly`,
   which only reads saved AI answers (`UserMerchantCategoryResolutionService.resolveReadOnly`);
   confirm saves the category the preview showed. The one path that reaches the model
   (`resolve`, through `suggest` with a direction) is `TransactionService.create`, a hand-added
   transaction. The tester's 376 rows got 0 AI answers for that reason.
3. **Shop words are missing.** A shop whose name is the Hindi word for tea was not recognised:
   `ShopTradeCategory` has "tea stall" but not that word.
4. **The review screens ask per row.** The Ledger shows a merchant-group card, a payee-group card
   (groups of 2+ only) and `AskOnceCard` (one row at a time, full category list, newest first).
   Mobile mirrors this in `CategoryReviewScreen`.

## Goal

After importing a statement, a user answers **at most 10 short questions**, biggest money first,
and every payment still ends up in a real category. The rest is optional.

Success, measured on the tester's account after Parts 1-4: the first Quick sort asks 10 or fewer
questions and covers at least 78% of the waiting money (today: 210 rows, 0% covered until the
user works through them).

## Non-goals

- No guessing a category for a payment to a person. Nothing in the narration says what a payment
  to a person was for, and a wrong category saved silently is worse than a question.
- No change to what may be sent to an AI model (#1894: no person transfers, names masked, ids
  redacted).
- No daily-review reminders or notifications.
- No change to money-in "counts as income" (inflow kinds, Money review). Quick sort sets a
  credit's **category** only, through the same endpoint as today.

## Design

Four parts, built and merged in this order. Each is useful alone.

### Part 1: re-check waiting rows when the rules change (server)

- `CategorizationService` gets a `SUGGESTION_VERSION` constant, raised whenever a rule, word list
  or waterfall step changes, the same way `CounterpartyClassifier.VERSION` drives the
  counterparty backfill.
- New column `transactions.suggestion_version` (integer, default 0). The Flyway version number is
  taken when the migration is written, after checking `origin/main`.
- A sweep (same shape as `CounterpartyBackfillSweepService`: batches, fixed delay, per-batch
  transaction) re-runs the read-only suggestion for rows that are **all** of:
  - `needs_category_review = true`
  - `category_manually_set = false`
  - `suggestion_version < SUGGESTION_VERSION`
- If the new suggestion is not the default "Other" or the structural person guess, the row takes
  the new category, decision source and confidence, and its review flag is recomputed with
  `needsCategoryReview`. Otherwise only `suggestion_version` is raised.
- A row the user chose is never touched. A row not waiting is never touched.
- What else must re-run after a category change (flow class, reconciliation) is listed and
  checked in the implementation plan against `TransactionService.updateCategory`'s own
  follow-ups. Not established yet.

### Part 2: more shop words (server)

- Add the words found missing in the tester's rows to `ShopTradeCategory` /
  `CategoryRules`: the Hindi word for tea (Dining), the parking/FASTag app (Transport), a
  payments app's bill-payment handle (Utilities). Each word is checked against the whole real
  corpus before and after, at row level: no row that is right today may change.
- Raising `SUGGESTION_VERSION` lets Part 1 apply these to rows already waiting.

### Part 3: AI suggestions after an import (server)

- After an import commits, a background job takes that user's waiting rows that are **all** of:
  - decision source `MERCHANT_DEFAULT` (never `STRUCTURAL_P2P`)
  - a counterparty key that `CounterpartyIdentity.identifiesOnePayee` accepts
  - a narration `CategorizationService.narrationForModel` allows
- It calls the existing `UserMerchantCategoryResolutionService.resolve` once per payee (it
  already caches per user, key and direction) under the existing guard
  (`FynAvailabilityGuard.categorizationAvailableFor`, daily cost cap).
- An answer sets the row's category with decision source `AI_FALLBACK` and **keeps the row
  waiting**, so Quick sort shows it as a one-tap "Fynora thinks: X" question. The category shows
  in reports straight away; it is better than "Other" and the user can still change it.
- Plus plan only. This part waits for the AI-gating plan's step 3 (AI on Plus only), which has
  not merged. Until then it is not built.

### Part 4: Quick sort (server, web, mobile)

**Server: `GET /api/v1/transactions/quick-sort?skip=N`**

1. Candidates: waiting rows on the user's live accounts, not marked duplicate (the same set
   `TransactionGroupingService.needsReviewCandidates` uses).
2. Group by payee and direction when the key names one payee (`identifiesOnePayee`); otherwise
   each row is its own group, so different payees are never filed together (#1930, #1947).
3. Rank groups by total amount, biggest first.
4. Take groups, skipping the first `skip`, until they cover **80% of the waiting money or 10
   questions**, whichever comes first. Both numbers are application properties
   (`app.quick-sort.coverage-target`, `app.quick-sort.max-questions`), not constants: 80% rests
   on one account's measurement (its top 10 payees held 78%), and the usage measurement below
   may move it.
5. Return:
   - `questions`: for each group, a stable id, the anchor transaction id, the kind (below), a
     readable payee label, the number of payments, the total, the latest date, up to 3 sample
     rows, the current guess (category name) when there is one, and up to 5 suggested answers.
   - `coveredPct`: share of the waiting money covered by groups already answered plus these.
   - `rest`: the number of groups, rows and money left after this batch.

Question kinds and their first answers. Only categories the user still has are offered; the
user's own most-used categories for the same payee type and direction come first; "More…" opens
the full list. A payment to a person offers no spending category of its own accord: nothing in
its narration says what it was for (see the non-goals), and a ready-made "Groceries" or "Rent"
button there would nudge a guess. The user's own history, and "More…", still reach every
category.

| Kind | When | Default answers after the user's own |
|---|---|---|
| Person paid | expense, structural person transfer | Personal Transfer, Friend Repayment |
| Shop | expense, "Other", no guess | Groceries, Dining, Shopping, Transport, Health |
| Guess | a rule or AI guess is present | the guess as "Correct", then "Change" |
| Money in | income | Friend Repayment, Salary, Gifts & Donations, Transfer |
| One-off | a single payment of at least Rs 5,000 (proposed threshold, to confirm against the corpus) | the kind's own list |

**Answering** uses what already exists: `PATCH /transactions/{anchorId}/category` with
`applyTo=SIMILAR` (#1902). It files every waiting payment of that payee, learns once, and pins
the payee (only when the key names one payee), so the next import does not ask again. A group on
a key that names no one payee is a single row and is answered with `ONLY_THIS`.

**The rest, in one step: `POST /api/v1/transactions/quick-sort/keep-rest`** with the
transaction ids shown in the "rest" summary. It keeps each row's current category and clears the
waiting flag. No learning, no pin, no change to decision source. Ids not owned by the user, or no
longer waiting, are skipped.

Its wording must say what it does, not that the rows are right. The button reads **"Stop asking
about these"**, with the line under it: "N payments (Rs X) stay as Personal Transfer or Other.
You can change any of them later from the Ledger." It never says "correct", "done" or "keep as
they are".

**Web and mobile**
- After an import is confirmed, the success screen offers "Sort N questions" (N from the server).
- The Ledger's review area (web) and `CategoryReviewScreen` (mobile) open with Quick sort: one
  question at a time, the progress line ("You've sorted 78% of your waiting money"), and after
  the batch: "Sort 10 more", or "Stop asking about these" (wording above), or leave.
- The existing per-row list stays available behind "See all waiting payments", so nothing
  becomes unreachable.

## Errors and edge cases

- Nothing waiting: the endpoint returns no questions and Quick sort is not offered.
- A payment answered elsewhere while Quick sort is open: the answer endpoint already ignores rows
  the user set by hand; the next fetch reflects the new state.
- A deleted account: its rows are excluded, as in the existing grouping.
- The AI job fails or is unavailable: rows stay as they are; no placeholder answer is saved
  (existing failure behaviour of `resolve`).
- `skip` past the end: an empty batch with `rest` all zero.

## Testing and measurement

- Unit: grouping, ranking, the 80%/10 cut (right at 80%, one rupee short, exactly 10 groups,
  ties), answer lists, keep-rest ownership checks.
- Integration: import, Quick sort, answer with SIMILAR, re-import of the same payee asks nothing;
  the Part 1 sweep never changes a user-chosen row.
- Corpus: for every statement in the real corpus, the number of questions in the first batch and
  the money they cover, before and after Parts 1-2. Row-level diff of categories: only waiting
  rows may change.
- Production, the tester's account (SQL run by the owner): waiting rows and payees before and
  after each part.
- Web and mobile: tests for the question card, progress, "Sort 10 more" and "Stop asking about
  these".

### Usage measurement (ships with Part 4)

Whether Quick sort helps is measured, not assumed. Counters (Micrometer, no payee data, no
amounts per user):

| Counter | Tells us |
|---|---|
| batches shown, questions shown | exposure |
| questions answered, by kind | engagement |
| "Sort 10 more" taken | whether one batch is enough |
| "Stop asking about these" taken, and rows it cleared | abandonment |
| an answered row's category changed again later | answer quality |

The last one needs to know a row was answered in Quick sort: the answer sets a new nullable
`transactions.quick_sorted_at`, and `TransactionService.updateCategory` counts a change on a row
that has it. Same migration as Part 1's column if both land together; otherwise its own.

## Order of work

1. Part 1 and Part 2 (one PR each). Then re-measure the tester's account and the corpus.
2. Part 4, with its usage measurement.
3. Read the usage numbers.
4. Part 3, only if the rows still waiting after 1-3 justify it, and once AI-on-Plus has merged.
   Expect it to clear little: most waiting "Other" rows carry a QR owner's personal name or a
   bare id, which `narrationForModel` masks or which give a model nothing to read. Part 3 only
   ever sees worded shop rows.
