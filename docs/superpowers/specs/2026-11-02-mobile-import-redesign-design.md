# Mobile Import redesign — design

Date: 2026-11-02. Status: draft for Sid's approval. Platform: mobile app only (`mobile/`). The web
Import page is not changed.

Figma: file `t3Bapfm0kCVpfbSIfMr5U1`, page "Import redesign — ideas", bottom row "Idea B"
(frames B1, B2, B3). Sid chose Idea B for all three steps on 2026-11-02. The figures in those
frames are made up; this document, not the frames, is the source for behaviour and copy.

## Why

The Import screen (`mobile/src/screens/import/ImportScreen.tsx`, about 2,000 lines) grew one
notice at a time. Today:

- **Add a statement** is one long card: three ways in, a file-type note, a scanned-PDF caveat, an
  email hint, a computer link and "What happens to my statement?", all at once. It never says which
  statement to add next.
- **Check** puts the account form ("File into") and every staged row in one long list, with the
  Import button at the very bottom. On an 80-row statement the two or three rows that need an
  answer are lost among rows that need nothing.
- **Done** is a block of numbers (Imported, Skipped, Duplicates, Credits, Debits) with no sense of
  what changed.

## Goals

1. Add: show, per account, which months are already in Fynora, which month is missing, and which
   statement comes next.
2. Check: open only the rows that need the person; fold the rest into one line; keep Import in
   reach at all times and say what stands in its way.
3. Done: after the count, show what the statement's month now shows (from Insights' "To look at"),
   each line leading somewhere.

## Non-goals

- No change to parsing, staging, duplicate detection, verification or confirm. Every rule that
  decides what is imported stays exactly as it is.
- No server changes. Everything below uses endpoints that exist on `main` today (checked
  2026-11-02, see "Data sources").
- No web changes.
- No new AI. The "To look at" rows used on Done are rule-built and work on every plan.

## Anti-goals (a failure even if it works)

- A month shown as covered or missing that the server's statement data does not support.
- A question removed from Check that today blocks Import (unresolved possible duplicates).
- Losing any existing state of the screen: unfinished "Ready to review" sessions, the import
  progress card, waiting for a file from a computer, the PDF password panel, failed imports with
  Try again, free-plan and previous-import notices, unparseable rows, the statement checks panel.
- Shipping this JS to the 1.0 or 1.1.x binaries over the air. It ships in the next store build only.

## Data sources (all verified on `main` at c467795b9)

| Need | Source |
|---|---|
| Months per account | `GET /api/v1/accounts/{accountId}/coverage` → `CoverageDto` (`segments[]` with `periodStart`/`periodEnd`, `gaps[]` with `gapStart`/`gapEnd`). Exists in `mobile/src/api/generated-types.ts`; no mobile caller yet. |
| Which accounts get a month row | `Account.primarySource === 'MANUAL'` and `statementsCount > 0` (both on the mobile `Account` type). |
| Rows that need an answer | `StagedRow.likelyDuplicate` + the review decision (`unresolvedCount` in `lib/importReview.ts`); rows marked by the statement checks (`balanceFlagged`, `descriptionFlagged`, from `verificationFlags.ts`). |
| Category guesses | `isUnconfirmedGuess(row.categorySource)` in `lib/importReview.ts`. |
| Done: what changed | `GET /api/v1/insights/look-at?month=YYYY-MM` (`aiInsightsApi.lookAt`). Built on each request from `InsightsService.buildDetailed`, no cache, no model call. Rendered with `lookAtRowsFromServer` and filtered with `isAcked` from `lib/insightsFeed.ts`, as `InsightsScreen` does. |
| Done: sort | `QuickSortCta` (already counts Quick sort questions itself). |

## Step 1 — Add a statement (frame B1)

Top to bottom:

1. Title "Import".
2. Every existing top-of-screen state, unchanged in behaviour, in today's order: "Ready to review"
   sessions, waiting for a computer file, the import progress card.
3. **"Your statements"**: one card per qualifying account (see Data sources), newest-imported
   first. Each card: account name and masked number, a row of month chips, and one line of text.
4. Primary button **"Choose a file"** (opens the same picker as today). Secondary text line:
   "Other ways: from your email, or from your computer with a one-time code." — "from your
   computer" opens `SendFromComputerSheet`; "from your email" shows today's Share → Fynora hint.
5. Two folded lines, closed by default: **"Which files work?"** (today's CSV/PDF line and the
   scanned-PDF caveat) and **"What happens to my statement?"** (today's `StatementHandlingPanel`).
6. The PDF password panel and "Recent failed imports" stay where they appear today.

When no account qualifies (a new user), section 3 is replaced by today's lead: "Start with one
statement" / "Last month, from the account you use most." / "No bank statement to hand? A credit
card statement works too."

### Month chips — exact rule

Months are calendar months. For one account, from its coverage response:

- A month M is **covered** if a segment's `periodEnd` falls in M, or a segment's period contains
  every day of M.
- A month M is **missing** if it is not covered, a gap overlaps at least one day of M, and M lies
  between the earliest and the latest segment-end months.
- **Next** is the month after the latest segment-end month.
  - If the latest `periodEnd` plus one month is on or before today, show a "next" chip and the line
    "Next: {Month}'s statement".
  - Otherwise show no "next" chip and the line "Up to date".
- Show at most the last five covered-or-missing months, oldest on the left, then the "next" chip.
- If any month is missing, the line instead reads "{Month}'s statement is missing" (the most recent
  missing month; "{n} statements are missing" when more than one).

Worked cases, each to be a test:

- Monthly savings statements, 1 Jun to 30 Sep, today 2 Nov → Jun–Sep covered, next October,
  "Next: October's statement".
- Same, but August never imported (gap 1–31 Aug) → August missing, line says it.
- Card cycles 15th to 14th, cycles ending 14 Jul, 14 Aug and 14 Oct imported (gap 15 Aug–14 Sep),
  today 2 Nov → Jul, Aug, Oct covered, September missing, one missing chip (not two for the one
  absent cycle); line "September's statement is missing".
- One statement 1 Jun to 30 Sep → Jun, Jul, Aug, Sep all covered.
- Latest card cycle ends 14 Oct, today 2 Nov → 14 Nov is after today → "Up to date", no next chip.
- Coverage request fails for one account → that card shows the account name and "Couldn't load
  months" and nothing else; the rest of the screen is unaffected.

## Step 2 — Check the transactions (frame B2)

Top to bottom:

1. Title "Check your statement". Today's line "Nothing is saved until you tap Import." stays,
   under the summary.
2. **Summary card**: detected account name and masked number, statement period, count of rows,
   In (green ink) and Out (red ink) totals of the included rows.
3. Statement checks panel (`VerificationPanel`), unchanged.
4. Free-plan limit notice and previous-import notice, unchanged.
5. **"File into"**: collapsed to one line, "File into: {account name} · Change", when an existing
   account is selected and none of the form's own blockers in `canConfirmImport`
   (`lib/importGate.ts`) is set: `accountChoice` is `'existing'` with a `selectedAccountId`,
   `amountOwedInvalid` is false and `newAccountBankMissing` is false. Otherwise it is open, as
   today's full form. "Change" opens the full form in place. On a re-import (`reimport !== null`)
   the account is fixed, so the line shows without "Change".
6. **"{n} need you"** (hidden when n = 0): one card per row that needs an answer, in statement order:
   - **Unresolved possible duplicate**: the row, the existing reason line, and today's two answers
     ("Skip this row", "Import anyway"). Answering removes the card from this list.
   - **Marked by the statement checks** ("Balance didn't match" / "Check description"): the row
     with its mark, the full description, and one button "Looks right", which removes the card
     from this list. Nothing is saved; the mark stays on the row in the full list.
7. **"{m} look fine"** card with "See all". "See all" opens the full list as today
   (`StagedRowCard` for every row, include toggles, category picker, apply-to-similar). Answered and
   dismissed rows appear there with their badges.
8. **Category guesses line**, only when k > 0: "{k} categories are guesses — sort them after
   import", where k is the number of included rows for which `isUnconfirmedGuess` is true. These
   rows are counted in "look fine", never in "need you".
9. Unparseable rows notice, unchanged.

**Pinned Import bar**, above the tab bar, always visible on this step:

- The button is enabled exactly when `canConfirmImport` is true today; that function and its tests
  are not changed.
- The text line names the first blocking reason, using today's notice wording shortened to one
  line, in this order: unresolved duplicates ("Answer {n} question(s) to import"), new account's
  bank missing, amount owed invalid, free-plan limit, no rows included. Each reason's existing
  full notice stays where it is today in the page.
- When nothing blocks but marked rows are not yet dismissed: "{n} row(s) to check · Show me"
  (Show me scrolls to the first).
- Button: "Import {included count} transactions", with today's confirming state. Today's "Cancel"
  link stays, at the end of the page.

## Step 3 — Done (frame B3)

1. Title "Imported {n} transactions"; under it "Into {account name}", and today's skipped and
   duplicates wording when non-zero.
2. In (green) and Out (red) from `totalCredits` / `totalDebits`.
3. Today's warnings, unchanged.
4. **"{Month} in Fynora"**: Month is the calendar month of `statementPeriodEnd`. Fetch look-at for
   that month, drop acknowledged rows the way `InsightsScreen` does, and show up to three rows in
   server order. Each row opens Insights on that month. The heading says the month, not "this
   statement", because the rows cover all of the person's accounts. Hidden when there are no rows,
   when the request fails, or when `statementPeriodEnd` is null.
5. Buttons: `QuickSortCta` ("Sort N questions") as the primary button when it renders; then "See
   Insights" (primary when Quick sort renders nothing), "Import another", "See them in
   Transactions".

## Constraints

- Theme tokens from `mobile/src/theme` only; Manrope headings, Inter body. Expense amounts red,
  income green.
- Light and dark mode, Dynamic Type at the jest default font scale of 2, VoiceOver labels on every
  new control.
- `mobile/AGENTS.md`: read the versioned Expo docs before writing code.
- Tests: jest + RNTL for every rule above, including the six month-chip cases; the existing
  ImportScreen tests must still pass.
- Ships in the next store binary. No OTA.

## Left to the builder

- Splitting `ImportScreen.tsx` into per-step components, and where the month-chip rule lives
  (a pure function in `lib/` is expected so it can be tested on its own).
- One coverage request per qualifying account through React Query; no batch endpoint.
- Exact spacing within the Figma frames' layout.

## Calls I made (Sid to check)

1. The "Add" button says "Choose a file", not "Add October for {account}": picking an account first
   would promise a filing the Check step's detection may overrule.
2. "Looks right" on a checks-marked row only hides it from "need you"; it is not saved.
3. The missing-month line drops the mockup's "so its card bills can't be matched": that reason is
   true only for some card accounts.
4. "Up to date" uses the latest statement end plus one month as the estimate of when the next
   statement exists.
