# Quick sort: show the payoff of each answer

Status: draft for Sid's approval, 2026-11-15. Idea 3 of the categorization roadmap
("make sorting rewarding").

## Why

Quick sort asks one question per payee. Today a tap on a category just brings up the next
question: nothing says what the answer did. Production counts (2026-11-15, read-only, kept
out of this repo) showed two things:

- Most visits end after the first answer, even though the full Quick sort already has a
  progress line ("You've sorted X% of your waiting money").
- About half of all answers file a single payment, so a payoff that only counts payments
  would read as nothing half the time.

What every answer for a known payee does have is a future payoff: the answer is remembered
for that payee. Nobody is told this.

Goal: after each answer, one short line says what it filed and, when true, that Fynora will
remember it, so customers see the value and keep answering.

## Approaches considered

- **A. Count only** ("Food · 3 payments filed"). Weak when one payment is filed.
- **B. Count plus the future** (chosen by Sid). True for every answer, worth something even
  for one payment.
- **C. Change in the Other share of spending** ("Other: 34% → 31%"). Ties to the goal, but
  small payments barely move it.

Future line wording: Sid chose the soft "Fynora will remember this for <payee>" over
"Future <payee> payments will go to <category> automatically". The strong form held for
every later payment checked in production, but only a couple of payees have had a later
import, and the import order (rules, learned history and keyword rules run before the
remembered payee) can file a later payment differently. The soft line is guaranteed by the
code: the answer is saved for the payee every time.

How it is built (my call): the server reports what the answer actually did, instead of the
client working it out from the question it showed. The rows may have changed since the
question was loaded, and the server is the only one that knows whether the payee was
remembered.

## What it does

`POST /transactions/quick-sort/answer` returns more in `AnswerResult`:

- `filed` (exists): how many payments the answer filed.
- `amount` (new): the money those payments add up to, as a positive number.
- `remembered` (new): true when the answer was saved for the payee. That is exactly when the
  question's key names one payee (`CounterpartyIdentity.identifiesOnePayee`), which is when
  `QuickSortService.answer` calls `updateCategory` with the default scope, so it learns and
  pins the payee. A "row:" question remembers nothing.

Both apps, in both the full Quick sort and the Dashboard card, show one line above the next
question after an answer:

- Remembered: "✓ Food · 3 payments filed (₹2,400). Fynora will remember this for Swiggy."
- One payment: "✓ Food · 1 payment filed (₹450). Fynora will remember this for Swiggy."
- Not remembered: "✓ Filed as Food."

Details:

- The category is the one the customer picked; the payee is the question's readable name.
  Money uses the apps' usual rupee format.
- The line stays until the next answer or skip, so there's no timer to miss. It also shows
  above "All sorted" on the last answer, and above the Dashboard card's "still waiting"
  ending.
- Screen readers announce it once (web: a polite live region; phone: a polite live region,
  or an announcement where a live region is not supported).
- If the server omits the new fields (an older backend during a deploy), the app shows
  "✓ Filed as Food", never a wrong count.

## Non-goals

- No progress line on the Dashboard card. Its design left that out on purpose.
- No end-of-visit summary, no Other-share line, no undo from the line.
- No change to how imports file payments, so the soft wording stays true.

## Measuring

There are no new counters. Two to four weeks after release, rerun the read-only visit query
(answers per visit, share of visits that end after one answer) and compare with the
2026-11-15 baseline. The phone part ships with the next store build.

## Left to the builder

- Exact component structure and test layout.
- Choosing the screen reader mechanism on each platform.
