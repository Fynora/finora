# AI sorting after an import — design

Date: 2026-11-14. Status: approved by Sid on 2026-11-14, with the calls below as written. The
question wording is a draft for counsel.

Idea 2 of the categorization roadmap (see the "Other" bucket work and Quick sort, #2552, #2558).

## Why

After an import, shop and business payments that no rule, saved answer or shared list could place
land in "Other". Fynora already has an AI that can suggest a category for such a payment, but
**imports never use it**: imports read saved answers only (`suggestReadOnly`), and only a payment
typed in by hand reaches the model (`CategorizationService.suggest`). So even a customer who said yes
to AI gets nothing from it on their statements.

A read-only production count on 2026-11-14 (aggregate only; figures stay out of this public
repository) showed:

- Very few customers have said yes to AI. Most have never been asked, because they have not opened
  Fynora since the question step shipped on 2026-10-31; they will be asked on their next visit.
- Those never asked hold most of the unsorted shop payments, across a few hundred payees.
- Payments to people are the larger unsorted group, and the AI never sees them (a privacy rule in
  `CategorizationService.narrationForModel`). This feature does not touch them.

## Goals

- Right after an import, offer to sort that statement's unsorted shop payments with AI, and show
  what it did.
- Ask a customer who said no to AI **one more time**, at that moment, with the real number in front
  of them (Sid, 2026-11-14).

## Non-goals / anti-goals

- No AI on payments to people; no change to what may be sent to the model.
- Nothing filed for good without the customer: each AI answer is a suggestion to confirm.
- No AI work the customer did not ask for: nothing runs without a tap.
- Never ask a customer who said no more than once again. A second no is final, short of the
  Settings switch.
- No change to sign-up, the question step for those never asked, or the Settings switch.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | **A card on the import's Done step; the customer taps to sort** | The question and the payoff come at the same moment; nothing spent unless asked; needs a tap. |
| B | Automatic in the background for customers who said yes; Done-step question for the rest | No tap, but the result arrives unseen later, and two separate flows. |
| C | Both | Most work, for very few yes customers today. |

Sid picked A on 2026-11-14.

## Design

### Who sees the card, and what it says

On the Done step of a confirmed import (single or several statements, the way `ImportMoneyInCard`
takes `statementImportIds`), the card shows when **all** of these hold:

1. AI sorting is available at all (`FynAvailabilityGuard`: kill switch, key, monthly budget,
   categorization enabled), not counting the customer's own answer.
2. The import has at least one **sortable payee**: a payee (a counterparty key that names one payee)
   with a payment out (EXPENSE) from these statements that is still waiting for a category, whose
   narration may go to the model (`narrationForModel` is present), and for which the customer has no
   saved answer yet. Money in is left out: an unexplained credit already has its own "what was it?"
   question.
3. The customer either said yes to AI, or said no and has **not** been asked again yet.

A customer never asked (null) does not see the card: the question step already asks them before
they can reach the Done step.

What it shows:

- **Said yes:** "AI can sort 12 shop payments (₹8,400)" · **[Sort with AI]**.
- **Said no, not asked again:** the same line, then the one-time question with **[Turn on AI and
  sort]** and **[No thanks]**. Draft wording for counsel: "Fynora can use AI to suggest categories for
  these payments. It sends the payment text, with names and account numbers removed, to Anthropic.
  You can turn this off any time in Settings." Either button records that the question was asked;
  "No thanks" leaves AI off and the card goes away for good.
  `[Turn on AI and sort]` records yes exactly as the Settings switch does (same columns, same time
  stamp), then sorts.

### Sorting

- Runs in the background after the tap (each payee takes one or two model calls, seconds each). The
  card shows progress ("Sorting… 4 of 12") and then the outcome: "Sorted 9 — check them in Quick
  sort", with a link to Quick sort. Payees the AI could not place are counted as "3 left for you".
- Order: payees with the most money first, so the per-customer daily AI cap
  (`dailyUserCostCapUsd`, today $1) or the monthly budget stops at the least useful ones. A stop
  says so on the card ("Sorted 9; AI has reached today's limit — the rest stay in Quick sort").
- Per payee: the existing `FynCategorizationFallbackService.suggest` (the model call, its caching,
  its audit log, and the saved answer it writes). Every row of that payee still waiting for a category
  — in this import or earlier — gets the AI's category, marked as the AI's
  (`DecisionSource.AI_FALLBACK`), and stays flagged for review. Quick sort then asks it as a
  "Fynora thinks: Food" question, one tap to confirm or change, as for a hand-typed payment today.
- The AI may pick a category the customer does not have yet; it is created, as it is for a
  hand-typed payment today (`resolveOrCreateCategory`).
- A payee answered by the customer while sorting runs is left as they answered it.
- Availability is checked before each payee: turning AI off in Settings, or the global switch, stops
  the run there.
- Leaving the Done step does not stop the sorting; it finishes in the background.

### Data

- `users.ai_import_question_at TIMESTAMPTZ NULL`: when the one-time question was shown and answered
  (either way). Not null = never ask again.
- A sorting run is recorded (who, which import, started, finished, payees sorted, left, stopped by
  limit) so the card can show progress, a second tap cannot start a second run for the same import,
  and the effect can be measured later.

### Measuring

- Counters: card shown (yes / asked again), tapped, question answered yes / no, payees sorted / left /
  stopped by limit, and later how many AI suggestions customers confirmed versus changed in Quick
  sort.

## Calls I made (correct any)

- Only payees found in the confirmed statements are offered, not every unsorted payee on the
  account; their earlier waiting rows are included because one answer is per payee.
- A customer who turned AI off in Settings after saying yes counts as "said no" and may be asked the
  one more time. (Alternative: never re-ask someone who turned it off themselves. Needs a new column
  to tell the two apart, since the Settings switch writes the same columns.)
- The card does not show when the import has no sortable payee, so it never promises zero.
- The card does not show to a customer who said yes and has already reached today's AI limit; it
  would start a run that sorts nothing.
- Payments out only; money in keeps its own question.
- AI answers stay suggestions to confirm in Quick sort, not final filing, matching how a hand-typed
  payment's AI answer works today.
- Same card on web and mobile; mobile ships with the next store build.

## Left to the builder

The endpoint shapes (count, start, progress), the background runner (existing worker patterns:
`MerchantLearningEventWorker`, `ImportJobWorker`), how progress is read (polling is fine), the
migration number (check `origin/main` first), placement among the Done step's other cards (money-in
question, invite card, store rating), and tests: who sees the card (each rule above, including a
never-asked customer, a no after the one more time, AI switched off globally), the question recorded
both ways, order by money, the cap stopping a run, a second tap, a payee answered mid-run, rows to
people never sent, and both clients.
