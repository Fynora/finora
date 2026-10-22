# AI Insights foundation — design

Date: 2026-10-22. Status: written for Sid's review.
Project 2 of the order agreed on 2026-10-18 (1 shared payments → **2 AI foundation** → 3 spotting trouble →
4 Ask Insights → 6 habits → 5 explain a debit → 7 language).

## 1. Why

Insights today is all rules. The words are fixed templates, and every row on the mobile "To look at" list
arrives in a fixed order. AI is good at choosing what matters to one person and saying why in plain words,
but it is bad at arithmetic and exact facts. So the rule for this whole programme is: **the server works
out every number, and AI only chooses and words them.**

This project builds the shared pieces every later AI idea stands on, and ships the first three uses on top
of them:

1. **The month story** (idea 1). A few sentences at the top of mobile Insights about this month. The
   website's existing Fyn narration slot gets the same story.
2. **"Why?" on a "To look at" row** (idea 2). For example: "Food is up because you paid 14 times this
   month against 6 in a usual month, mostly at weekends."
3. **AI ranking** (idea 14). The "To look at" list is ordered by what matters most to this user.

## 2. Decisions already made (Sid)

- **2026-10-18, approach B:** the server builds facts, AI picks and explains them, a check rejects any
  number not in the facts, and the rules text shows when the check fails.
- **2026-10-22, the SEBI line.** The AI explains the user's own past money. It never names a security,
  fund, SIP amount, asset split, insurance or loan product, and never tells the user how to reach a goal
  ("you should invest/save in…"). The server checks every reply for advice words as well as for numbers.
  Budget suggestions, the salary-day plan and what-if are not in this project; they wait for a lawyer's
  answer on whether goal-based planning counts as advice. Source: SEBI (Investment Advisers) Regulations
  2013, consolidated to 25 Nov 2025, reg 2(1)(h), 2(1)(l) and 15(14).
- **2026-10-22, plan tier.** Free gets a taster: the AI month story for the current month, plus 5 AI
  "Why?" answers a calendar month. Plus and Premium get everything with no count limit. The non-AI
  insights stay free for everyone. Free usage has its own slice of the AI budget, so it can never stop
  paying users' AI.
- **Measured 2026-10-22 (prod, read-only):** 10 AI calls ever, from 4 users, on Haiku 4.5, at
  $0.0016–0.0020 per call. The org-wide budget is $50 a month. Cost does not limit this project at
  today's size.

## 3. Goals, non-goals, anti-goals

**Goals**
- One server-side home for Insights facts, AI text, the checks, the cache, the limits and the feedback.
  Projects 3–7 add new kinds of facts and texts to it rather than building their own.
- Mobile Insights shows the AI month story, a "Why?" on running-high rows, and an AI-ranked list.
- Every AI text is labelled "Written by AI", with a thumbs up and down.
- Anything that fails, whether unavailable, over a limit, or failing a check, falls back quietly to the
  rules text. The screen never shows an error because of AI.

**Non-goals (later projects)**
- Spotting trouble, Ask Insights on mobile, what-if, salary-day plan, weekly push, year in review, budget
  suggestions, explaining a debit, regional languages.
- The number check on Fyn chat. Chat answers from tools, not from a fact list, so it needs its own
  design.
- "Why?" on the website. The website has no "To look at" list yet.

**Anti-goals (a failure even if it works)**
- A number in an AI sentence that the server did not compute.
- Any sentence that reads as investment advice or a plan to reach a goal.
- A payee name, a person's name, a UPI id, an account number or a narration sent to the AI.
- A Free user's AI use stopping a Plus user's AI.
- The Insights screen loading slower, or breaking, because the AI is slow or down.
- The 1.0.0 app breaking. It keeps calling today's endpoints, which stay unchanged.

## 4. How it works

### 4.1 Facts

The server builds a **fact sheet**: a list of facts, each with a stable id, a plain label, and its
numbers. Every number carries the exact display form the app would show (₹6,200, 15%, 14 payments,
3 Oct). Only Tier 1 data goes in: category names, amounts, counts, percentages, month and day. No payee
names, people's names, UPI ids, account names or numbers, or narrations. Categories a user named after a
person still go through `FynNameShield`, as the narration does today.

Two fact sheets in this project:

- **Month sheet** for one reporting month. Built from what the server already computes:
  - from `InsightsService.build`: the month total, the biggest category, every category mover (current,
    usual, % change), the coverage caveat, and unlinked card bills (count and total);
  - from `DashboardService.summarize`, for the newest month only: money in, money out, and the change
    against last month;
  - from `RecurringService`: how many repeating payments need an answer, and how many changed amount
    with the old and new amounts (no payee names);
  - from `PeopleService`: the total owed to the user and the total the user owes (no names).
  The builder confirms each source returns the same figures the mobile screen shows today, and lists
  any that don't.
- **Row sheet** for one running-high category in one month, built from the same `InsightsService`
  pipeline rows (so shares, loans and refund netting already apply, and card bills stay excluded):
  - the number of payments this month against the usual month;
  - the average payment against the usual average;
  - the 3 largest payments (amount and day only);
  - payments on Saturday or Sunday this month against usual;
  - how many payees are new this month, and their total.

### 4.2 The "To look at" list moves to the server

Today `mobile/src/lib/insightsFeed.ts` `buildToLookAt` builds the list on the phone. AI ranking and
"Why?" both need the server to know exactly which rows the user sees, so this project ports
`buildToLookAt` to the server unchanged:
- the same 5 kinds, the same thresholds (15% and ₹500, at most 3 running-high rows);
- the same order;
- the same "Got it" keys (V277/V281).

The server returns the rows with their keys. The existing `insightsFeed.test.ts` cases become server
tests, and pass with the same results.

Mobile 1.1.0 renders the server's rows. Mobile 1.0.0 keeps building its own, since the endpoints it calls
don't change.

### 4.3 The AI call

- **Month call:** one call per user per month sheet. It returns JSON with two parts:
  - `story`: 2–4 sentences about what is worth knowing this month;
  - `order`: the "To look at" row keys, most important first.
  The order may only contain keys the server sent. If it is missing a key, invalid, or not a reordering of
  the server's keys, the rules order is used.
- **Why call:** one call per row sheet. It returns 1–2 sentences on why this category is running high.
- **Model:** the configured Fyn model (`FYN_MODEL`, Haiku 4.5 today), with the same prompt rules as
  today's narration: plain prose, no markdown, no merchant names, history not advice. Each prompt is
  versioned.
- **Timing:** never on the page-load path of the numbers. The story and order come from their own
  endpoint. "Why?" runs only when the user taps it.

### 4.4 The checks

Every AI text passes two checks before anyone sees it. A failure means the rules text is shown, and the
failure is recorded with the reason (`CHECK_FAILED:number` or `CHECK_FAILED:advice`) in `ai_audit_log`.

- **Number check.** Every number in the reply must equal a number in the fact sheet that call was given.
  That covers digits, Indian grouping (1,23,456), decimals, percentages, day of month, and number words
  ("six", "twice", "half", "double", "lakh", "thousand"). Rupee amounts must match to the rupee; a
  percentage must match the fact's percentage rounded to a whole number. Years and month names are
  allowed only when they belong to a month in the sheet. The tests list examples that must pass and
  examples that must fail. Of those that must fail: a figure off by ₹1, a percentage the sheet doesn't
  hold, and a word-number for a count the sheet doesn't hold.
- **Advice check.** The reply is rejected if it uses:
  - advice phrasing: "you should", "we recommend", "consider", "try to", "it's a good idea", "advise";
  - an investment or product word: invest, investment, mutual fund, SIP, stock, equity, share price,
    portfolio, NAV, returns, insurance, policy, loan offer, credit card offer, FD, fixed deposit, gold,
    crypto.

  A product word is allowed only where it is part of a category name in that call's fact sheet. The
  categories "Insurance", "Investments" and "Loan EMI" exist, so "Insurance was ₹2,000" passes, but
  "consider insurance" fails on the advice phrasing. Words the product uses for itself are always
  allowed: share (a shared bill), lent, owe, budget. The list sits in one place, with tests, so later
  projects extend it.

### 4.5 Rules text (the fallback)

- **Month story:** the server sends no story, and the app keeps today's month line exactly as it is.
- **Order:** today's rules order.
- **"Why?":** a server-built sentence from the row sheet, for example: "14 payments this month against 6
  in a usual month; the largest was ₹2,400." It is shown without the "Written by AI" label.

### 4.6 Cache

There is a new table of insight texts, one row per user, kind, subject and month. Each row holds:
- the fact sheet's hash;
- the text and the order;
- `source` (AI or RULES), the prompt version, and the creation time;
- the thumbs answer.

A request whose fact sheet hash matches a stored row returns that row and makes no AI call. A new import
changes the facts, so the next visit writes a new row. "Got it" doesn't change the hash: acknowledged keys
are taken out of the stored order when it is served.

### 4.7 Limits (plan tier)

- `FYN_INSIGHTS` is granted to Free, Plus and Premium in a new migration. The narration endpoint that
  refuses everyone today starts working. The limits are enforced in the service by plan code, the same
  way Fyn chat's Free cap is.
- **Free:**
  - The AI month story is written for the newest reporting month only, and is rewritten after imports
    that change its facts. Older months picked in the month picker get the rules line.
  - 5 AI "Why?" answers per calendar month, in the user's timezone. Re-opening one already written
    doesn't count. After the fifth, "Why?" still answers with the rules text, plus one line saying Plus
    explains every row, linking to the paywall.
  - No AI ranking: Free users get the rules order.
- **Plus and Premium:** everything, every month, held back only by the existing per-user daily dollar
  cap.
- **Free budget slice:** a new setting `FYN_FREE_MONTHLY_BUDGET_USD` (default 10, out of the 50).
  `ai_audit_log` gains a nullable `plan_code` column, which insights calls fill in. When Free spend this
  month reaches the slice, Free users get rules text, and Plus and Premium carry on. Fyn chat doesn't
  fill the column in this project, so its spend doesn't count against the slice; chat's Free use is
  already capped at 3 questions a day.
- A user with no plan is treated as Free, the same as chat.

### 4.8 Endpoints

All of these are new, and all are under `/api/v1/insights`:

- `GET /story?month=` returns `{ id, story | null, source, writtenByAi, orderChanged }`. This is the only
  endpoint that makes the month call. It stores the story and the order together (§4.6).
- `GET /look-at?month=` returns the "To look at" rows (§4.2), with an `orderedByAi` flag. It never calls
  the AI, so it is as fast as the rules. When a stored order exists for the current fact hash, it uses
  that order; otherwise it uses the rules order. The app loads both endpoints together and refetches
  `/look-at` once if `/story` says `orderChanged`. On a first visit the list may reorder once; a cached
  visit shows the AI order straight away.
- `POST /look-at/why` with `{ kind, itemKey, month }` returns `{ id, text, source, writtenByAi,
  freeLeftThisMonth | null }`.
- `PATCH /ai-texts/{id}/feedback` with `HELPFUL`, `NOT_HELPFUL` or null, the same as chat's feedback.

`GET /narration` stays for the website and is served from the same month story.

Everything is owner-scoped: another user's id or row is a 404. Types go through the OpenAPI contract
like every other endpoint.

### 4.9 Mobile (ships in the 1.1.0 binary, never as an update to 1.0.0)

- At the top of Insights: the story when there is one, with a small "Written by AI" label and thumbs,
  otherwise today's month line. While the story is loading, today's month line shows. No skeleton,
  because the screen shouldn't jump.
- "To look at" comes from `GET /look-at`. Rows look the same as today.
- A running-high row gains a "Why?" link. Tapping it opens the answer under the row, with the label and
  thumbs when the AI wrote it. For Free users it shows "4 of 5 left this month" once they've used one.
- Haptics and skeletons follow the existing app rules.

### 4.10 Website

- The Insights narration slot calls the same story, and adds the "Written by AI" label and thumbs.
- `frontend/src/pages/landing/plans.ts`: Free gains "AI month story and 5 AI explanations a month", and
  Plus gains "AI insights with no monthly limit". `landing-claims.test.tsx` is updated to match.
- `Privacy.tsx` gains an "AI insights" paragraph covering:
  - what is sent to Anthropic: category names, amounts, counts and dates, never payee or people's names,
    account details or narrations;
  - that the AI explains history and doesn't give advice.

  It goes out with the server change, before any user's data is sent.

## 5. Constraints

- Repo rules apply: worktrees only, no AI attribution in commits, Node 22, synthetic test data only
  (forms like `vpa:flatmate.a`, `100000000001`), and no real values in comments.
- The new migration takes the next free version number, checked against `origin/main` at build time
  (V281 is the latest today).
- Prod is read-only. Any prod check is a query Sid runs.

## 6. How we'll know it worked

- **Check pass rate:** for each prompt version, the share of AI texts that pass both checks, read from
  `ai_audit_log`. A version below 90% gets its prompt fixed before more users see it. (90% is my call.)
- **Thumbs:** helpful against not helpful, for each kind of text.
- **Cost:** monthly AI spend split by plan, from the new `plan_code` column.
- **Free to Plus:** upgrades that start from the "Plus explains every row" line. The paywall already
  tracks its entry point; the builder confirms this, or adds it.

## 7. Delivery

Three PRs:

1. **Server:**
   - fact sheets, the "To look at" port, the AI calls, the checks;
   - the cache table, `plan_code`, the Free slice, the `FYN_INSIGHTS` grant;
   - the endpoints, and OpenAPI with the 3 clients' generated types.
2. **Mobile:** the story, the server rows, "Why?", the label and thumbs. Ships with 1.1.0.
3. **Website:** the story label and thumbs, plus the pricing and privacy copy. The privacy copy merges
   no later than PR 1 is deployed.

## 8. Calls I made (Sid skipped the questions; check these)

1. **"To look at" moves to the server (§4.2)**, because ranking and "Why?" need it there. It's the largest
   piece of this project.
2. **"Why?" only on running-high rows.** Of the other kinds, two are questions for the user (new
   repeating payment, people), one is a statement to upload (card statement), and the amount-changed row
   already says its whole story (old amount to new).
3. **Story and ranking come from one AI call**, to halve the calls.
4. **Free gets no AI ranking.** Your taster named the story and "Why?" only.
5. **Free's story covers the newest month only.** Older months get the rules line.
6. **The Free budget slice is $10 of the $50.**
7. **No payee names go to the AI, even for companies.** The row on screen already shows the name, so the
   AI doesn't need it. Sending names would be a new data-tier decision, separate from this project.
8. **The website gets the story** (with label and thumbs), not "Why?" or ranking.
9. **A 90% check pass rate** is the bar for a prompt version.

## 9. Left to the builder

- Prompt wording, within §4.3–4.4 and the tests.
- The fact sheet's exact JSON shape and hash.
- The table's name and indexes.
- Exactly how number words and Indian grouping are parsed, provided the listed pass/fail examples hold.
