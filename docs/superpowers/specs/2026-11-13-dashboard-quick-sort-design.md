# Quick sort on the Dashboard ("a few at a time") — design

Date: 2026-11-13. Status: awaiting Sid's approval.

## Why

Payments the categorization engine finds nothing for land in "Other". Read-only production counts taken on
2026-11-13 (aggregate only; the exact figures stay out of this public repository) showed a large share of
customer transactions there, every one still waiting for review. The automatic layers do not reach them
today: the AI fallback never runs on imported rows (the import path is read-only), and the shared corpus
needs three different users to agree on a payee, which had not happened.

Quick sort already solves the asking: one question per payee, biggest money first, and one answer files
every payment to that payee. The same counts show most customers do use it, but in one sitting, around
their import, and then not again. Most rows still in "Other" belong to a payee the same customer paid more
than once, so they are answerable; they are simply never put in front of the customer again. Quick sort lives on the Ledger (web) and the Category review and Import screens (mobile),
none of which is where people land.

So the job is: put Quick sort's questions where people land, a few per visit, until nothing is waiting.

## Goals

- Every Dashboard visit shows up to 3 Quick sort questions while anything is waiting.
- One tap files the whole payee, exactly as Quick sort does today.
- The "Other" count per customer goes down between imports, not only on import day.

## Non-goals

- No notifications, email or push (the "Reminder" option was considered and not chosen).
- No new categorization logic, no AI, no change to which rows wait for review or in what order.
- No change to the existing Quick sort on the Ledger, the review lists, or the Import screen.

## Anti-goals (a failure even if it works)

- A card that nags: it must be easy to put away, and it stays away for the rest of the day.
- A card that disagrees with Quick sort: same questions, same order, same answers, same server.
- A card that clears rows without the customer answering them. "Stop asking about these" (keep-rest) is
  never offered on the Dashboard.

## Approaches considered

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | **A few questions per visit on the Dashboard** | Adds a card to the top of the Dashboard; meets people every visit with no message. |
| B | A reminder a few days after an import | Outward-facing, needs notification consent and wiring, brings people back once rather than every time. |
| C | A bigger first sitting (more questions, louder "Sort 10 more") | Cheapest, but leans harder on the one sitting where people already stop. |

Sid picked A on 2026-11-13: the evidence is that people answer when the question is in front of them.

## Decided with Sid

- Placement: the Dashboard, web and mobile.
- Up to 3 questions per visit, biggest money first (Quick sort's own order).
- "Not now" hides the card until the next calendar day (Sid, 2026-11-13: "Tomorrow").

## The design

```
Home
┌──────────────────────────────────┐
│ Sort a few payments        1 of 3│
│ Swiggy · 14 payments · ₹6,200    │
│ [Dining] [Groceries] [More…]     │
│                  Skip · Not now  │
└──────────────────────────────────┘
(existing Dashboard content below)
```

Behaviour:

1. On Dashboard load the client fetches one Quick sort batch (`GET /api/v1/transactions/quick-sort`) and
   shows its first 3 questions, one at a time.
2. A question shows the payee, the payment count and total, and Quick sort's suggested answers plus
   "More…" (or "Change" for a GUESS question), which opens the existing category picker.
3. Answering posts to the existing `POST /quick-sort/answer`; the next question slides in. Views that
   show categories refresh the way Quick sort already refreshes them.
4. "Skip" moves to the next question for this visit only (Quick sort's own `skip`).
5. After the third answer or skip, the card shows how many payees are still waiting and a link to the full
   Quick sort (Ledger on web, Category review on mobile), then stays until the next visit.
6. When the batch is empty, no card renders. While loading, or if the fetch fails, no card renders either
   (the Dashboard must not gain an error state for an optional card).
7. "Not now" hides the card until the next local calendar day, remembered per device.

Placement:

- Mobile: at the top of `DashboardScreen`, in place of today's "N transactions need a quick look" line.
  When the card has nothing to show (empty, hidden for today, failed), that line comes back as today.
- Web: at the top of `Dashboard.tsx`, above the first summary row. The existing "Spending needs category
  review" warning stays as is; it measures this month's spend, a different signal.

## Calls I made (correct any of them)

- **Reuse, don't fork.** The web `QuickSortCard` and mobile `QuickSortPanel` gain a compact Dashboard mode
  (question limit 3, "Not now", no "Stop asking about these", no "Sort 10 more") rather than new components,
  so the interaction exists once per platform.
- **"Not now" is per device**, stored as the local date (web `localStorage`, mobile `safeStorage`), wrapped
  so a storage failure simply shows the card. The stored value names the signed-in account (its
  email, the way the web referral prompt already does it, because the web profile carries no user id),
  so a second account signed in on the same device is not hidden by the first one's "Not now". Not synced across devices; a phone and a laptop may each show
  it once a day.
- **Same questions as Quick sort, including payments to people** ("Personal Transfer" guesses), so the two
  never disagree. The Dashboard does not filter to "Other" only.
- **One small server change:** an optional `source` (`LEDGER` default, `DASHBOARD`) on the batch request
  and the answer request, used only as a tag on the existing Quick sort counters, so Dashboard use can be
  told apart from Ledger use. No behaviour depends on it.
- **No new endpoint and no migration.**

## Constraints

- Mobile reaches customers only with the next store build, never OTA (repo rule for screens).
- Mobile: dark mode and the largest text sizes must not push the Dashboard's first real content off-screen;
  earlier pinned-bar work broke at accessibility text sizes. Never stack a native modal on another
  (the category picker opens from the Dashboard itself).
- Web: one accent per view; the card uses the secondary style, not a primary call to action.
- Privacy: the card shows only the customer's own payee label, count and total — what Quick sort already
  shows them.

## How we will know it worked

Re-run the read-only per-customer query (`other_left`, `answered_by_hand`, last answer date) two and four
weeks after the web deploy and again after the mobile store build. Success: answers dated after the import
day for customers who return, and "Other" falling between imports. The `source=DASHBOARD` counter gives the
same signal from the server where Prometheus is scraped.

## Left to the builder

- Exact copy, spacing and the transition between questions, within each app's existing card styles.
- Test shape: unit tests for the compact mode (limit, skip, Not now date logic incl. the day boundary,
  empty and failed fetch render nothing, keep-rest absent), the mobile nudge fallback, and the server tag.
