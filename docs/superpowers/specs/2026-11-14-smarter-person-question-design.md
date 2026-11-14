# Smarter person question in Quick sort — design (short)

Date: 2026-11-14. Status: awaiting Sid's approval.

## Why

"Personal Transfer" is the largest unresolved bucket. A read-only production count on 2026-11-14 (aggregate
only; figures stay out of this public repository) showed that steady monthly payments to a person — the
"is this rent?" case — are rare. Most money paid to people goes to people paid many times, with amounts that
vary: a few dozen payees hold most of these rows. Many are probably small vendors paid by UPI to a personal
account, or family; that is not established.

Quick sort (and the Dashboard card, PR #2552) already asks one question per person, and one answer files
every payment to them and remembers the person. But the question gives little to go on — payee name, waiting
count, total, latest date — and offers only "Personal Transfer" and "Friend Repayment" after the customer's
own past picks.

## Goals

- Help the customer recognise who the person is and what the payments are.
- Let them file a person's payments to a real spending category in one tap.

## Non-goals / anti-goals

- No new categories. No change to which rows wait, to the order of questions, or to how an answer files and
  remembers.
- No guessing on the customer's behalf: the neutral answers stay ahead of any spending answer, and nothing
  is filed without a tap.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | **Fixed wider list + pattern line** | No invented thresholds; the customer's own picks already rise to the top over time. |
| B | Pattern-based answers (small and frequent leads with Groceries/Transport; large and monthly leads with Rent) | Feels smarter, but its "small" and "monthly" cut-offs would be guesses until measured. |

Sid picked A on 2026-11-14, after choosing "smarter person question" over widening recurring detection.

## Design

1. **Answers for a person question (PERSON_PAID).** The current order stays: the customer's own past picks for
   people first (most used first), then the defaults, at most 5, each only if the customer still has that
   category. Defaults become, in this order: **Personal Transfer, Friend Repayment, Groceries, Transport,
   Rent**. "More…" still opens the full list. Shop, money-in and guess questions are unchanged.
2. **A pattern line on every question whose payee is one payee** (shop and person alike; not on a single-row
   question whose key names no one payee):
   `14 payments since Aug · usually ₹150–₹400`
   - Counted over all live payments to that payee in that direction, not only the waiting ones.
   - "since" = the month of the first payment, with the year when it differs from the latest payment's.
   - "usually": 4+ payments → 25th–75th percentile; 2–3 → lowest–highest; whole rupees; one value when both
     ends round the same; left out for a single payment.
3. **Where it shows:** the full Quick sort and the Dashboard card, on web and mobile, in place of today's
   "N payments · ₹total · latest date" line. The waiting count stays visible where it is today.
4. **Seeing the payments behind the line** (Sid, 2026-11-14: the customer may want to look before choosing).
   Tapping the pattern line opens the list right inside the card, without leaving the question: the
   payee's latest payments, newest first, up to 10, each as date · amount · the bank's narration. When there
   are more than 10, a "See all 14 in Transactions" link opens the Transactions page searched for that
   payee. Tapping the line again closes the list. Answering works the same with the list open or closed.
   This replaces the web's current "Show payments" (3 waiting rows) and is new on mobile and the Dashboard
   card.

```
RAVI K                                   2 of 3
14 payments since Aug · usually ₹150–₹400
What was this person paid for?
[Personal Transfer] [Friend Repayment] [Groceries] [Transport] [Rent] [More…]
```

## Calls I made (correct any)

- The pattern line appears on shop questions too: same data, same cost, same help.
- Range rule (percentiles for 4+, min–max for 2–3) — a presentation choice, not a threshold that files
  anything.
- Server computes the line's numbers (new nullable fields on the question); clients only format them, so web
  and mobile cannot disagree.
- The list holds all live payments to the payee in that direction (not only waiting ones), capped at 10 and
  sent with the question, so opening it needs no extra request. Amounts in the list are rounded to whole
  rupees, like other lists in the app.
- "See all" uses the Transactions page's existing search (`?q=` on web); the mobile equivalent is for the
  builder to confirm, and if mobile has no such search the link is left out there rather than built here.
- Branch stacks on PR #2552's branch (same card and panel files); its PR opens against `main` once #2552 has
  merged, after checking its base.

## Left to the builder

Exact field names, the batch query for payee stats (one query per batch, not per question), date formatting
within the apps' existing rules, and tests: answer list and order (including a deleted category), each range
rule and its boundaries (1, 2, 3, 4 payments; equal ends), year shown only when it differs, no line for a
non-payee key, both clients rendering it.
