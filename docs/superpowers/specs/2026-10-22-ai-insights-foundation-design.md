# AI Insights foundation — design

Date: 2026-10-22. Status: revision 2, after an outside review. Written for Sid's approval.
This is project 2 of the order agreed on 2026-10-18: 1 shared payments → **2 AI foundation** → 3 spotting
trouble → 4 Ask Insights → 6 habits → 5 explain a debit → 7 language.

## 1. Why

Insights today is all rules: fixed sentence templates, and a "To look at" list in a fixed order. AI is good
at choosing what matters to one person and saying why in plain words, but bad at arithmetic and exact facts.
So the rule for the whole programme is: **the server works out and writes every number, and AI only chooses
the facts and words the sentence around them.**

This project builds the shared pieces every later AI idea stands on: facts, output checks, cache, limits,
feedback and rollout. It ships three first uses on top of them:

1. **The month story** (idea 1). Two to four sentences at the top of mobile Insights about this month. The
   website's existing Fyn narration slot gets the same story.
2. **"Why?" on a running-high row** (idea 2). For example: "Food is up: you paid 14 times this month,
   against 6 in a usual month, mostly at weekends."
3. **AI ranking of "To look at"** (idea 14). It is built, but stays switched off until it beats the rules
   order in an evaluation (§7.3).

## 2. Decisions already made (Sid)

- **2026-10-18, approach B:** the server builds facts, and AI picks and explains them. A check rejects any
  number not grounded in the facts, and the rules text shows instead.
- **2026-10-22, the SEBI line:**
  - The AI explains the user's own past money.
  - It never names a security, fund, SIP amount, asset split, insurance or loan product.
  - It never tells the user how to reach a goal.
  - Budget suggestions, the salary-day plan and what-if are not in this project. They wait for a lawyer's
    answer on whether goal-based planning counts as advice.

  Source: SEBI (Investment Advisers) Regulations 2013, consolidated to 25 Nov 2025, regulations 2(1)(h),
  2(1)(l) and 15(14).
- **2026-10-22, plan tier:**
  - Free gets a taster: the AI month story for the current month, plus 5 AI "Why?" answers a month.
  - Plus and Premium have no count limit.
  - The non-AI insights stay free for everyone.
  - Free usage has its own slice of the AI budget, so it can never stop paying users' AI.
- **Measured 2026-10-22 (prod, read-only):**
  - AI calls ever: 10, from 4 users, all on Haiku 4.5.
  - Cost: $0.0016–0.0020 per call.
  - Org-wide budget: $50 a month. It is already a hard ceiling: `FynCostGovernanceService` stops every AI
    call, chat included, once the month's spend reaches it.

## 3. Goals, non-goals, anti-goals

**Goals**
- One server-side home for insight facts, AI text, checks, cache, limits, feedback and rollout. Projects
  3–7 add new kinds of facts and texts to it rather than building their own.
- Mobile Insights shows the AI month story and "Why?" on running-high rows. AI ranking is ready to switch
  on.
- Every AI text is labelled "Written by AI", shows what it compares against, and has thumbs up and down.
- Any failure (unavailable, over a limit, failing a check, slow) falls back quietly to rules text. The
  screen never shows an error because of AI.

**Non-goals (later projects)**
- Spotting trouble, Ask Insights on mobile, what-if, salary-day plan, weekly push, year in review, budget
  suggestions, explaining a debit, regional languages.
- Any statement about the future. That includes forecasts, which belong to project 3.
- The checks on Fyn chat. Chat answers from tools, not from a fact sheet, so it needs its own design.
- "Why?" and ranking on the website. The website has no "To look at" list yet.

**Anti-goals (a failure even if it works)**
- A number shown in an AI sentence that is wrong, or that is attached to the wrong category, metric,
  period or unit.
- Any sentence that reads as investment advice, a recommendation, an instruction or a prediction.
- A payee name, a person's name, a UPI id, an account name or number, a narration, or a user-written
  category name sent to the AI.
- A Free user's AI use stopping a Plus user's AI, or going over the Free allowance.
- Two model calls for the same text, from double taps, retries or parallel requests.
- The Insights screen loading slower, or breaking, because the AI is slow or down.
- The 1.0.0 app breaking. It keeps calling today's endpoints, which stay unchanged.

## 4. Facts

### 4.1 The fact sheet

The server builds a fact sheet from typed records with a fixed allowlist of fields. No entity or DTO is
serialised into a sheet and then pruned. Each fact has:

- a stable **id** (for example `food.this_month`);
- a **subject**: the month itself, or one category;
- a **metric**: total, payment count, average payment, largest payments, weekend payments, new payees,
  change in percent, and so on;
- a **period**: this month, or the usual month;
- a **unit**: rupees, count, percent or day;
- a **value**;
- a **display form**, rendered by the server.

A fact whose period is the usual month carries its period in its display form, for example "₹5,000 in a
usual month" or "6 payments in a usual month". That way the AI can't present a usual figure as this
month's.

**Subjects.** Built-in categories (`is_system`) are sent by name. A category the user created is sent as an
opaque token (`[category-1]`) and is never named to the AI; the server puts the user's own name back when it
renders. This covers anything a user might type into a category name. The `FynNameShield` people-name tokens
stay as they are today.

**Never in a sheet:** payee names (companies included), people's names, UPI ids, account names or numbers,
card last four, narrations, notes, or transaction ids.

### 4.2 Two sheets

**Month sheet** (one reporting month). Every value is copied from an existing service's output, never
re-added:
- From `InsightsService.build`:
  - the month total and the biggest category;
  - each category mover: this month, the usual month, % change, and how many usual months the baseline
    holds;
  - the coverage caveat;
  - unlinked card bills, as a count and a total.
- From `DashboardService.summarize`: money in, money out, and the change against last month. These are
  included only when the dashboard's reporting month equals the sheet's month.
- From `RecurringService`:
  - how many repeating payments need an answer;
  - how many changed amount, with each one's old and new amounts (no payee names).
- From `PeopleService`: the total owed to the user and the total the user owes.

**Row sheet** (one running-high category in one month). Built from the same `InsightsService` pipeline rows
the mover came from, so shares, loans, refund netting, the card-bill exclusion and gap-month exclusion
already apply:
- payments this month against the usual month;
- the average payment against the usual average;
- the 3 largest payments, as amount and day only;
- payments on Saturday or Sunday, this month against usual;
- how many payees are new this month, and their total.

### 4.3 Correctness contract

- **Month and timezone:** months are the user's calendar months in their own timezone (`UserZone`). That is
  the same as every screen today.
- **Usual month:** `InsightsService`'s own baseline. That is up to 3 prior months, starting from the
  category's first month with spend, and leaving out months with a statement gap.
- **Weak baseline:** a comparison fact goes into a sheet only when its baseline holds at least 2 months.
  With fewer, the sheet describes this month alone, and "Why?" isn't offered on that row (§6).
- **Parity tests:** for fixtures covering refunds, transfers, shared bills, loans, card bills, a statement
  gap, a partial month, a first month and the user's timezone, every month-sheet value equals the source
  service's value. The row sheet's payment total equals its mover's "this month" figure exactly.
- **Audit:** the sheet that produced a text is stored with it (§5.4), so any text can be traced to its
  facts.

## 5. Generating text

### 5.1 What the model returns

The model never writes a number or a category name. It returns JSON: a list of sentences, each with a
`type` and a template.

```json
{ "sentences": [
    { "type": "COMPARISON", "template": "{food} came to {food.this_month}, above {food.usual}." },
    { "type": "EXPLANATION", "template": "Most of the rise was {food.weekend_payments}." } ],
  "order": ["CATEGORY_HIGH|Food|2026-10", "AMOUNT_CHANGED|..."] }
```

- `type` is one of OBSERVATION, COMPARISON or EXPLANATION.
- `{subject}` placeholders render the category's name.
- `{fact}` placeholders render the fact's display form.
- `order` appears only in the month call, and only when ranking is switched on and the sheet has rows to
  rank. Asked for an order with no rows, the model copied the example row ids from the prompt (6 of 30
  evaluation histories on 2026-10-24).
- Each change fact (`change_pct`, `money_out_change_pct`) is sent with a `direction` of UP, DOWN or SAME.
  Its display is unsigned, so without it the model could not tell a rise from a fall (rule 5).

### 5.2 Checks (all deterministic, all in one place, all versioned as the *policy version*)

The text is shown only if every check passes. On any failure, rules text is shown and the reason code is
recorded (§5.5).

**Grounding**
1. Valid JSON matching the schema, with 1–4 sentences.
2. Every placeholder exists in that call's sheet.
3. The template contains no digits, no number words (one, twice, half, double, dozen, lakh, crore,
   thousand and so on), no currency signs, no month or day names, and no category or product names.
   Every figure and name comes from a placeholder.
3b. No figure is followed by words its display already holds: a count's noun ("{food.payments_this_month}
   payments" renders "6 payments payments") or a usual figure's "in a usual month". Added in policy-v2
   (2026-10-24): under the first prompt these texts passed every other check and reached the reader.

**Fact binding**
4. In each sentence, every category-scoped fact must belong to the nearest category placeholder before it.
   "{food} came to {shopping.this_month}" fails.
5. Direction words must agree with the sign of the change fact in the same sentence. Words like
   up/more/higher/rose need a rise; down/less/lower/fell need a fall.
6. Usual-period words (usual, usually, normally, typically, average) must not sit beside a this-month fact.
   This-month words (this month, so far) must not sit beside a usual-period fact.

**Policy**
7. No prescriptive or advice phrasing: should, recommend, consider, try, avoid, cut, reduce, save, set a,
   need to, make sure, it's a good idea, advise. Also no sentence that opens with a bare verb (an
   instruction).
8. No product words: invest, mutual fund, SIP, stock, equity, share price, portfolio, NAV, returns,
   insurance, policy, loan, credit card offer, FD, fixed deposit, gold, crypto, ELSS, 80C, tax saving. The
   last three come from SEBI's own investment adviser FAQ (Q16). It treats advising a client to invest in
   ELSS during tax planning as investment advice. These are safe to ban
   because category names are never written by the model (rule 3).
9. No future or predictive phrasing (will, going to, next month, expect, likely, forecast). Forecasts belong
   to project 3.

**Ranking (when on)**
10. `order` holds each sent row key at most once and no unknown key. Rows it leaves out are appended in rules
    order.

**What passing means.** These checks are a filter, not a compliance proof. §7.2's evaluation measures how
often they wrongly pass or wrongly reject.

### 5.3 Rules text (the fallback)

- **Month story:** none is shown, and the app keeps today's month line exactly as it is.
- **Order:** today's rules order.
- **"Why?":** a server-built sentence from the row sheet, for example: "14 payments this month against 6 in
  a usual month; the largest was ₹2,400." It carries no "Written by AI" label.

### 5.4 Store, single-flight and idempotency

There is one table of insight texts. Each row's key is user, kind (STORY or WHY), subject, month, fact-sheet
hash, prompt version, model and policy version, and the key is unique. Each row also holds:
- a status: PENDING, READY or FAILED;
- the stored sheet, the rendered text and the order;
- the reason code and the plan at generation;
- the feedback (§8);
- its timestamps.

Rows are deleted with the account, through `ON DELETE CASCADE` to `users` and a line in
`AccountPurgeSweepService`, as `ai_audit_log` already is.

- **Single-flight:** a generate request first inserts a PENDING row for its key (`INSERT … ON CONFLICT DO
  NOTHING`). Only the request that inserted the row calls the model. A parallel request finds PENDING and
  gets `status: generating`, and the app asks again shortly after. A PENDING row older than 60 seconds is
  stale (the model's read timeout is 30 seconds), and the next request may take it over.
- **Failed attempts:** a FAILED row is never served. The same key isn't retried for 10 minutes, so a retry
  storm can't repeat the spend.
- **Serving:** only READY rows whose prompt, model and policy versions are current are served. A version
  bump means old texts are regenerated on the next visit. That regeneration never uses up a Free
  allowance.
- **New facts:** imports, edits, deletions, refunds and re-imports change the sheet, so its hash changes.
  The next visit generates afresh, within the regeneration limit (§6). "Got it" doesn't change the hash:
  acknowledged rows are taken out of a stored order when it is served.
- **Bounds:**
  - every call has the client's 5-second connect and 30-second read timeouts, and no automatic retry;
  - at most 4 insight model calls run at once on an instance (prod runs 1 replica);
  - beyond that, the request gets rules text with reason CONCURRENCY_LIMIT.

### 5.5 Reason codes (for `ai_audit_log` and the text row)

- `OK`
- `PROVIDER_TIMEOUT`
- `PROVIDER_ERROR`
- `RATE_LIMITED`
- `INVALID_JSON`
- `SCHEMA_MISMATCH`
- `UNKNOWN_FACT`
- `LITERAL_NUMBER`
- `REPEATED_WORDS`
- `FACT_BINDING`
- `DIRECTION_MISMATCH`
- `PERIOD_MISMATCH`
- `ADVICE`
- `PRODUCT_WORD`
- `PREDICTION`
- `BAD_ORDER`
- `EMPTY`
- `PERSIST_FAILED`
- `FREE_ALLOWANCE_USED`
- `FREE_BUDGET_SPENT`
- `ORG_BUDGET_SPENT`
- `USER_DAILY_CAP`
- `CONCURRENCY_LIMIT`
- `NOT_IN_AUDIENCE`

The app sees only "AI text", "rules text" or "generating". It never sees the reason.

`ai_audit_log` gains a nullable `plan_code` and a `reason_code`. As now, it stores tokens, cost, model,
prompt version and timing. It stores no prompt, reply or sheet. Only admins read it, and it is deleted with
the account.

## 6. Limits and plans

- `FYN_INSIGHTS` is granted to Free, Plus and Premium in a new migration. The narration endpoint that
  refuses everyone today starts working. The limits below are enforced in the service by plan code, as Fyn
  chat's Free cap is. A user with no plan counts as Free.
- **Free:**
  - the AI story for the newest reporting month. Older months get the rules line;
  - 5 AI "Why?" answers per calendar month, in the user's timezone at the moment of use.
- **Plus and Premium:** stories for any month, and unlimited "Why?". The existing per-user daily dollar cap
  still applies.
- **Allowance accounting:**
  - there is one usage row per user per month;
  - a "Why?" reserves one use with an atomic `UPDATE … SET why_used = why_used + 1 WHERE why_used < 5
    RETURNING`;
  - the use is given back if the attempt ends without an AI text shown, whether it failed a check, errored,
    or hit a limit;
  - reopening a stored answer is free, so parallel taps can never exceed 5.
- **Regeneration limit:**
  - a story is rewritten at most once a day for the same user and month, whatever the plan;
  - Free stories are generated at most 4 times a calendar month.
- **Free budget slice:** `FYN_FREE_MONTHLY_BUDGET_USD` (default 10, out of the 50). The slice is the sum of
  `cost` where `plan_code = 'FREE'` this month. Once it's spent, Free users get rules text and Plus carries
  on. Fyn chat doesn't fill `plan_code` in this project, so it doesn't count here (it already has a cap of 3
  questions a day). The org-wide $50 ceiling still covers everything.
- **Plan changes:**
  - texts already written stay readable after a downgrade;
  - new generations follow the plan at the moment of the request;
  - an upgrade takes effect on the next request.
- **Messages:** only an exhausted Free allowance shows a message: "You've used this month's 5 AI
  explanations. Plus explains every row.", linking to the paywall. A spent budget, an outage or a failed
  check shows rules text with no message.

### 6.1 Cost model (estimates; the internal rollout measures the real figures)

Haiku 4.5 costs $1 per million tokens in and $5 per million out (`FynPricing`). Assumed token counts: a story
call is about 2,500 in and 300 out, roughly $0.004. A "Why?" call is about 800 in and 120 out, roughly
$0.0014.

| User | Calls a month | Cost a month |
|---|---|---|
| Free, light | 1 story + 2 "Why?" | ~$0.007 |
| Free, heavy | 4 stories + 5 "Why?" | ~$0.023 |
| Plus, heavy | 30 stories (the daily limit) + 60 "Why?" | ~$0.20 |

So the $10 Free slice covers about 430 heavy or about 1,400 light Free users a month, and the $50 ceiling
must rise with the user count. Both are environment settings. The internal rollout records the real tokens
per call before the wider release.

## 7. Rollout and evaluation

### 7.1 Audience

There is a new setting, `FYN_INSIGHTS_AUDIENCE`: `internal` (users with `internal_account`, V279) or `all`.
It starts as `internal`. Users outside the audience get rules text with reason NOT_IN_AUDIENCE. AI ranking
has its own flag, `FYN_INSIGHTS_AI_RANKING`, which defaults to off.

### 7.2 Launch gate (before `all`)

1. **Checker test suite.** These are fixed examples, run in CI:
   - Must fail: each anti-goal written out. That includes a right number on the wrong category or period, a
     wrong direction, a literal number, a number word, indirect advice ("many people in your situation
     move this to…"), negated advice ("you might not want to keep…"), a prediction, a product word, and an
     instruction.
   - Must pass: plain history sentences, including sentences about the Insurance, Investments and Loan EMI
     categories.
2. **Offline evaluation.** About 30 synthetic histories (refunds, shares, gaps, first month, weak
   baselines, large one-off payments) run through the real model. Every output is reviewed by hand, and the
   suite records:
   - wrongly passed outputs. The bar is **0** incorrect claims and **0** advice, predictions or
     instructions;
   - wrongly rejected outputs, as a rate.

   The check pass rate must be at least 90%.
3. **Internal cohort for at least 2 weeks.** It is watched for check pass rate per prompt version, fallback
   rate, p95 latency, cost per active user, and thumbs.

### 7.3 Ranking evaluation (before the ranking flag is turned on)

The ranking criteria given to the model, in order:
1. something the user has to do: a statement to upload, or a question to answer;
2. a repeating payment whose amount changed;
3. the size of the rise against the usual month, in rupees and in percent;
4. how unusual it is against the user's own history.

For the same synthetic histories, the AI's top 3 and the rules' top 3 are shown side by side with the
source hidden. Sid picks the more useful one. The flag turns on only if the AI order wins a majority. Stored
orders are reused, so the same facts always give the same order.

## 8. API, apps and feedback

### 8.1 Endpoints (new, under `/api/v1/insights`, owner-scoped; another user's id is a 404)

- `GET /story?month=` only reads. It returns `{ status: ready | none | generating, id, text, writtenByAi,
  basis, canGenerate, rating, ratingReason }`. It never calls the model. `rating` and `ratingReason` are the
  thumb this user saved on the text, so the app shows it again; both are null on rules text and when none
  is saved.
- `POST /story` with `{ month }` generates if no current text exists. It is idempotent: it returns the
  stored or generating row for the same key.
- `GET /look-at?month=` returns the "To look at" rows (§8.2), with `orderedByAi`. It never calls the model.
- `POST /look-at/why` with `{ kind, itemKey, month }` returns `{ status, id, text, writtenByAi, basis,
  freeLeftThisMonth | null, rating, ratingReason }`. It is idempotent per key.
- `PATCH /ai-texts/{id}/feedback` takes `{ rating: HELPFUL | NOT_HELPFUL | null, reason: WRONG_NUMBER |
  NOT_USEFUL | CONFUSING | null }`. The reasons are a fixed list with no free text, so nothing a user types
  is stored or sent anywhere.

`basis` is a short server-written line about what the text compares against, for example "Compared with
your usual month (Jul–Sep)". `GET /narration` stays for the website and serves the same story. Types go
through the OpenAPI contract.

### 8.2 "To look at" moves to the server

Today the phone builds this list itself, in `mobile/src/lib/insightsFeed.ts` `buildToLookAt`. Ranking and
"Why?" need the server to know exactly which rows the user sees. So the port to the server keeps:
- the same 5 kinds;
- the same thresholds (15% and ₹500, at most 3 running-high rows);
- the same order;
- the same "Got it" keys (V277/V281).

The existing `insightsFeed.test.ts` cases become server tests and must give the same results. Mobile 1.1.0
renders the server's rows. If `/look-at` fails, it falls back to its own `buildToLookAt`, which stays in the
app. Mobile 1.0.0 is unaffected.

### 8.3 Mobile (in the 1.1.0 binary, never as an update to 1.0.0)

- **Story:**
  - The screen calls `GET /story`, then `POST /story` when `canGenerate`. It polls once or twice while the
    story is generating.
  - Until a story is ready, today's month line shows, with no skeleton and no layout jump.
  - The story shows with a "Written by AI" label, its `basis` line, and thumbs. A thumbs-down offers the
    3 reasons.
- **"Why?":**
  - The link appears only on running-high rows whose baseline holds at least 2 months.
  - Tapping it opens the answer under the row: AI text with its label and basis, or the rules sentence.
  - Free users see "4 of 5 left this month" once they have used one.
- **Accessibility:** the label, basis, thumbs and expanded answers all have accessibility labels, and an
  opened answer is announced.
- Haptics and skeletons follow the existing app rules.
- The release build is checked on a device before 1.1.0 ships.

### 8.4 Website

- The narration slot uses the same story, with the label, basis and thumbs.
- `plans.ts`:
  - Free gains "AI month story and 5 AI explanations a month";
  - Plus gains "AI insights with no monthly limit";
  - `landing-claims.test.tsx` is updated to match.
- `Privacy.tsx` gains an "AI insights" paragraph. It covers:
  - what is sent to Anthropic: built-in category names, amounts, counts and dates. Never payee or people's
    names, the names of categories the user created, account details or narrations;
  - that the AI describes history and doesn't advise;
  - what is stored, and that it is deleted with the account;
  - under Anthropic's standard commercial terms: Anthropic doesn't train on it, and deletes it within
    30 days, or keeps it up to 2 years if it is flagged for misuse (§9).

  The existing sentence about the Insights summary changes from "your category names" to "built-in
  category names".

  The paragraph merges before the server's audience is set to `all`.

## 9. Constraints

- Repo rules apply:
  - worktrees only;
  - no AI attribution in commits;
  - Node 22;
  - synthetic test data only (forms like `vpa:flatmate.a`, `100000000001`);
  - no real values in comments.
- The migration takes the next free version number, checked against `origin/main` at build time (V281 is
  the latest today).
- Prod is read-only. Any prod check is a query Sid runs.
- **Anthropic's terms, checked 2026-10-22:**
  - The Commercial Terms (effective 17 Jun 2025) say: "Anthropic may not train models on Customer Content
    from Services". The customer owns the outputs, and the Data Processing Addendum applies.
  - The Privacy Center article (updated 1 Jul 2026) says API inputs and outputs are deleted within 30 days.
    Content flagged under the Usage Policy may be kept for up to 2 years.
  - These are the standard terms. The Privacy paragraph (§8.4) says "under Anthropic's standard commercial
    terms" and states the 30 days, the 2 years and no training. If Fynora's Anthropic account has any
    separate agreement, Sid says so and the wording follows it.
  - The current Privacy page states none of these terms. It also says the Insights summary is sent "your
    category names", which becomes "built-in category names only".
- **SEBI reading, checked 2026-10-22, for the month story and "Why?":**
  - Reg 2(1)(l) covers advice on dealing in securities, plus financial planning.
  - Reg 2(1)(h) defines financial planning as three things together: analysing the situation, identifying
    goals, and recommending strategies to reach them.
  - The story and "Why?" describe past spending only. They name no security, identify no goal and
    recommend no strategy, and §5.2's checks reject any reply that does.
  - No SEBI regulation, FAQ or circular found addresses spending-history apps either way, so this is a
    reading of the text, not a ruling. Sid's 2026-10-22 decision stands: a lawyer reviews only the deferred
    planning-like features (budget suggestions, salary-day plan, what-if).

## 10. Delivery

1. **Server PR.** It contains:
   - the sheets, with the allowlist, category tokens and parity tests;
   - the "To look at" port;
   - the model calls, with the template output and all the checks, plus the checker test suite;
   - the text table, with single-flight, the allowance row and regeneration limits;
   - the reason codes, `plan_code`, the Free slice, the concurrency bound and the audience flag;
   - the `FYN_INSIGHTS` grant and the endpoints;
   - OpenAPI, and the 3 clients' generated types.

   An outbound-request test captures the real `LlmRequest` for a fixture full of payee names, UPI ids,
   account numbers, narrations, people's names and a user-created category, and asserts that none of them
   appear. A log test asserts the same for the log lines and the `ai_audit_log` row. A concurrency test sends
   parallel generate requests and parallel "Why?" taps, and asserts one model call per key and at most 5
   uses. The PR deploys with the audience set to `internal`.
2. **Mobile PR.** The story, server rows with the local fallback, "Why?", label, basis, thumbs with
   reasons, accessibility, and the release-build check. Ships with 1.1.0.
3. **Website PR.** The story with label, basis and thumbs; the pricing and privacy copy.
4. **Before `all`.** The launch gate (§7.2), and the Privacy change merged. The provider terms were checked
   on 2026-10-22 (§9).

## 11. Calls I made (Sid skipped the questions; check these)

1. "To look at" moves to the server (§8.2), because ranking and "Why?" need it there.
2. "Why?" appears only on running-high rows, and only when the baseline holds at least 2 months.
3. The story and the ranking come from one model call.
4. Free gets no AI ranking, and ranking stays off for everyone until it wins the §7.3 comparison.
5. Free's story covers the newest month only, at most 4 generations a month.
6. The Free slice is $10 of the $50.
7. No payee names go to the AI, companies included. The names of user-created categories don't go either.
8. The website gets the story only.
9. The model writes templates, and the server writes every number and name (§5.1). This is stronger than the
   original "every number must appear in the facts" check, which could pass a right number on the wrong
   category.
10. Rollout goes internal accounts first, then everyone after the §7.2 gate.

## 12. Left to the builder

- Prompt wording, within §5 and the test suites.
- The sheet's JSON shape and hash.
- Table and column names, and indexes.
- The exact word lists. They start from §5.2 and can be extended, with tests.
