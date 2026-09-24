# Fynora v2 brainstorm

**Status:** directional thinking, not a roadmap. Same posture as `insights-evolution.md`: nothing
here is scoped, estimated, or sequenced against launch work. It exists so the v2 conversation
starts from what the repo actually contains instead of re-proposing features that already ship.

**Baseline:** `main` @ 49a2b971, 2026-09-24. Every "exists" claim names the class or page that
proves it; every "missing" claim was grep-negative on that commit. Re-verify before acting.

---

## Part 1. What v1 ships and what it verifiably lacks

### What v1 actually ships

- Import: CSV + PDF + scanned/OCR, held-statement flow, share-to-Fynora on iOS/Android,
  Account Aggregator (Setu) code path, Gmail sync built but paused behind one switch.
- Intelligence: 5-tier categorization with `TransactionExplanationService`, merchant
  normalization + shared corpus learning, duplicate/transfer/refund netting, recurring detection
  (`RecurringService`), health score with factor cards, insights (rule-based), Financial Memory,
  Journey timeline, Wrapped, Advanced Reports (8 cards incl. Multi-Year and Lifestyle Inflation).
- Fyn (AI): chat on web + mobile over `AnthropicClient`, 4 read tools (balance, recent
  transactions summary, spend by category, budget status), screenshot OCR, import diagnosis,
  insights narration, categorization fallback, cost governance.
- Money objects: accounts, budgets, goals (+ momentum), net worth (cards only as liabilities),
  investments (manual holdings + SIP list), referrals, support tickets.
- Platform: Razorpay + RevenueCat billing, entitlements, OTP login, notification outbox with
  push tokens (7 types, all operational/referral), navigation counters, Prometheus + Grafana.

### Verified gaps (grep-negative today)

- No forecasting, safe-to-spend, or month-end projection anywhere.
- No financial notifications: budget pace, renewal due, card due, low balance, weekly digest.
- No user-facing rules UI; rules API is `/api/v1/admin/...` only.
- No Subscriptions page; recurring data only appears as cards on Dashboard/Insights/Memory.
- Goal `targetDate` used in zero calculations; no pace or "to hit target" math.
- Loans, insurance, forex cards are `NOT_MODELLED_YET`; net worth ignores EMIs.
- No XIRR/CAGR/NAV; investments are a manual tracker.
- No attribution join in `InsightsService` (category delta and merchant totals never meet).
- No multi-currency on `Account`; no household/family; no tax view; no credit score.
- Fyn has read tools only; no confirm-gated actions; no goal/recurring/net-worth/health tools.

---

## Part 2. Ideas in depth

Effort labels are S (days), M (weeks), L (a quarter-scale track). Tier suggestions use the
entitlement keys that already exist in V99: `BASIC_DASHBOARD`, `ADVANCED_REPORTS`,
`EXTENDED_HISTORY`, `FINO_AI`, `INVESTMENT_INSIGHTS`, `PRIORITY_SUPPORT`.

### Theme 1. Proactive layer: Fynora talks first

**The thesis.** Today every feature waits for a visit. The only pushes a user ever receives are
password changed, statement ready/held/resolved, and three referral events. The notification
platform already has a `FINANCIAL` category with zero types behind it, an outbox
(`Notification`, `NotificationLog`), a dispatcher, EMAIL and SMS channels, FCM push with
per-token outcome handling, and per-category per-channel preferences. The plumbing is done. What
is missing is the producers.

**Producers to add, each a scheduled sweep like the existing `*SweepService` classes:**

| Type | Trigger | Source of truth already computed |
|---|---|---|
| `BUDGET_PACE` | spend crosses 50/80/100 percent of `Budget.monthlyLimit`, or projected overrun | `BudgetService` totals; projection needs Theme 2 |
| `RENEWAL_DUE` | `RecurringDto.nextEstimate` within 3 days | `RecurringService` |
| `CARD_DUE` / `LOW_BALANCE` | already in `DashboardSummaryDto.notifications` as strings, never delivered | `DashboardService` |
| `SALARY_CREDITED` | large recurring credit lands | `RecurringService` on credits |
| `NEW_RECURRING_DETECTED` | a series reaches 3 occurrences for the first time | `RecurringService` diff against last run |
| `UNUSUAL_SPEND` | category or merchant deviates from personal baseline | Theme 3 anomaly logic |
| `STATEMENT_GAP` | a month with no statement for an account that usually has one | `AccountCoverageService` |
| `WEEKLY_DIGEST` | Sunday evening | all of the above, narrated by `FynInsightsNarrationService` |

**Design points.**
- Dedupe by (user, type, subject, period) so a budget crossing 80 percent notifies once, not
  every sweep. The outbox needs an idempotency key column; the referral grant sweep already
  needed a claim lock, so reuse that pattern.
- Quiet hours and a daily cap per user. Financial pushes that arrive at 2am get the app
  uninstalled.
- Every push deep-links (app links shipped in #1650) to the exact surface: the budget, the
  recurring row, the statement.
- The digest is the one place Fyn narration earns its cost: one LLM call per user per week,
  over `TIER_1_AGGREGATE` data only, inside the existing `FynCostGovernanceService` budget.
- Preferences UI exists per category; add per-type toggles under FINANCIAL.

**Edge cases.** Users with no budgets get no pace alerts, so the digest must still be worth
opening on aggregates alone. Multiple accounts with the same recurring merchant (two Netflix
profiles) must not double-fire. Imports arriving late (a statement uploaded on the 20th
covering the 1st) should not fire a stale `UNUSUAL_SPEND` for a month already reviewed.

**Tier.** Renewal, card due, low balance: free (they are trust). Digest and unusual spend:
`FINO_AI` or a new `PROACTIVE_ALERTS` key. Effort M.

---

### Theme 2. Forecasting and safe-to-spend

**The thesis.** Every number today is retrospective. The single most-asked question in personal
finance is "can I afford this this month". Nothing in the repo projects forward.

**Minimum viable model, no ML.**
```
expected_inflows   = recurring credits with nextEstimate in window (salary, interest)
committed_outflows = recurring debits in window + card dues + EMIs (Theme 4)
discretionary_pace = trailing-3-month average of non-recurring spend, prorated by days left
month_end_balance  = current balance + expected_inflows - committed_outflows - discretionary_pace
safe_to_spend      = current balance - committed_outflows - reserve (user-set floor)
```
All inputs exist: balances (`AccountService`, with the balance convention), recurring
(`RecurringService`), card dues (dashboard), budgets. EMIs need Theme 4.

**Surfaces.**
- Dashboard hero line: "Safe to spend this week: X after Y upcoming obligations."
- Budget page: "at this pace you exceed Dining by Z on the 24th" (the exact example the
  insights vision doc names as the observation-to-decision shift).
- Fyn tool `GET_FORECAST` so "can I buy a 40k phone this month" gets a grounded answer.
- Cash-flow calendar: one month, dots for salary, EMIs, subscriptions, dues. Mobile-first.

**Honesty rules.** Show the confidence band, not a single number, when history is under three
full months (`FinancialMemoryCompleteness` already computes months of history). Never forecast
for an account whose last statement is older than 45 days; say "stale" instead. The
`ImportVerifier` stance against invented confidence weights applies here too: expose the inputs
("based on 3 months, 4 recurring debits") rather than a score.

**Tier.** Month-end estimate free; safe-to-spend and calendar Plus. Effort M.

---

### Theme 3. Intelligence that reaches the user

#### 3a. Subscription Center
`RecurringService` computes merchant, cadence label, average amount, occurrence count, last
date, next estimate. It is computed on demand and never persisted, so users cannot act on it.

Add a `recurring_series` table keyed by (user, merchant fingerprint) with user state:
`ACTIVE | IGNORED | CANCELLED | RENAMED`, user-set `expectedAmount`, and a `firstSeen`. Then:
- Totals per month and per year, grouped Essentials vs Subscriptions vs Loans.
- Price-rise detection: latest amount vs series average beyond 10 percent.
- Dormant: series whose `nextEstimate` passed by two intervals with no charge.
- Cancel tracking: user marks cancelled, Fynora watches for the charge to stop and confirms
  "Adobe has not charged you since June, saved 3 months so far". This is the value loop.
- Trial-ending detection from Gmail parsers once Gmail resumes.

Effort M. Tier: list free, price-rise and dormant alerts Plus.

#### 3b. Goal intelligence
`Goal.targetDate` is stored and used nowhere. `GoalMomentumService` already computes "N of last
M months" contribution momentum. Add:
- Pace: average contribution over the last 3 months, projected completion date.
- Gap: required monthly amount to hit `targetDate`, and the delta from current pace.
- Auto-contribution: link a recurring credit into an INVESTMENT account (SIP) or a detected
  transfer to a goal. `TransactionGraphService` edges can carry the link.
- Milestone timeline events: `TimelineEventType` has four values today; add `GOAL_HALFWAY`,
  `GOAL_AHEAD_OF_PACE`, `GOAL_AT_RISK`, and reuse Journey and Wrapped for free.

Effort S to M. Tier: pace free, auto-contribution Plus.

#### 3c. Budget intelligence
`Budget` is three columns: user, category, monthly limit. Add:
- `alertThresholds` (default 80 and 100), `rolloverEnabled`, `period` (monthly, weekly).
- Suggested limit from personal baseline: median of the last 6 months plus a margin, shown as
  "your usual is X" when creating a budget.
- Merchant budgets: a budget row keyed by merchant instead of category (Swiggy cap).
- Burn-rate bar with day-of-month marker on the Budgets page and mobile screen.

Effort S. Tier: thresholds and burn rate free, suggestions and merchant budgets Plus.

#### 3d. Insights v2
Direct from `docs/vision/insights-evolution.md`, in its own order:
1. Personal baseline instead of the flat 15 percent threshold.
2. Anomaly framing over the same deviation math.
3. Ranking by impact times confidence so the page shows three things, not forty.
4. User-facing trace through `InsightsService.pipeline()`, which `InsightsExplorerService`
   already exercises for admins. Tap a number, see the transactions behind it.
5. Attribution join: for each category mover, the top three merchants explaining the delta.
   This is the keystone the premium roadmap names; every recommendation depends on it.

Effort M. Tier: trace free (trust), baseline and anomaly Plus.

#### 3e. Trust surfaces
- User rules page. `RuleEngineService` exists; only admin routes expose it. A user who sees
  "matched your rule" in `TransactionExplanationService` output needs an Edit button.
- Account activity: user view over `audit_logs`, filtered to their own actions.
- Lineage on every transaction: source (statement id, Gmail message, AA fetch, manual),
  evidence reference, confidence tier. `TransactionSourceService` already exists as the seam.

Effort S each. Free.

---

### Theme 4. Liabilities, real net worth, debt planning

**The hole.** `Account.Type` is SAVINGS, CREDIT_CARD, WALLET, INVESTMENT. `NetWorthService`
sums CREDIT_CARD as the only liability. `FinancialProductType.LOAN`, `INSURANCE`, `FOREX_CARD`
are `NOT_MODELLED_YET`, so a home-loan statement is recognised at import and then dropped.
For the target market this makes net worth wrong for most users over 30.

**Model.**
- New `Account.Type.LOAN` with a `loan_terms` row: principal, rate, tenure, EMI, start date,
  rate type (fixed, floating), lender.
- Amortization schedule generated on demand; outstanding principal at any date; interest paid
  to date and per FY (feeds Theme 7 tax).
- EMI detection: a recurring debit to a lender merchant becomes the link between the loan and
  the transaction stream; `MerchantUnderstandingService` can classify lender counterparties.
- Loan statement import: the parser already recognises the product type; add a loan row
  extractor for the top lenders (HDFC, SBI, ICICI, Bajaj).

**Features unlocked.**
- Net worth with liabilities that move monthly, and the health score's debt factor (20 percent
  weight) computed on real debt instead of card balances only.
- Prepayment simulator: "prepay 1 lakh now, save X interest, finish Y months earlier".
- Debt payoff planner: avalanche vs snowball across cards and loans, with a monthly plan.
- Rate-change alerts on floating loans (from statements or user entry).

Effort L. Tier: tracking free, simulator and planner Plus.

---

### Theme 5. Investments that compute

**The hole.** Investments are manual holdings plus a SIP list. No units, no cost basis, no
NAV, no XIRR or CAGR anywhere. `INVESTMENT_INSIGHTS` gates adding investment accounts but has
nothing to show.

**Approach that plays to the product's strength: import, not integration.**
- CAS import. CAMS and KFintech consolidated account statements are password-protected PDFs
  every MF investor can generate in one minute. The PDF pipeline, OCR routing, and password
  refusal flow (#1673) already exist. One parser gives folios, schemes, units, transactions.
- NAV feed from AMFI's public daily file, cached once a day. No vendor, no key, no cost.
- Holdings model: `holding` (scheme, folio, units, average cost), `holding_transaction`
  (buy, sell, dividend, switch). Current value from NAV times units.
- Returns: XIRR per scheme and portfolio, CAGR, absolute gain, SIP vs lumpsum split.
- Allocation: equity, debt, hybrid, gold, by AMC; overlap between funds later.
- Stocks: a broker contract-note or holdings CSV import (Zerodha, Groww exports) as a second
  parser. Live prices need a vendor; defer.
- Link the SIP debit in the bank statement to the CAS purchase via `TransactionGraphService`,
  so the transfer is not counted as spend (investment spend exclusion already shipped in #1691).

**Effort** M for CAS plus NAV plus XIRR; L with stocks. Tier: holdings free, returns and
allocation `INVESTMENT_INSIGHTS`.

---

### Theme 6. Fyn v2: from four questions to a CFO

**Today.** `FynChatOrchestrationService` with GET_BALANCE, GET_RECENT_TRANSACTIONS_SUMMARY,
GET_SPEND_BY_CATEGORY, GET_BUDGET_STATUS. Data tiers 0 to 4 with account numbers never exposed.
Cost governance per user per day and per org per month. Screenshot OCR with redaction. Import
diagnosis. Insights narration. Categorization fallback and a global merchant-understanding
cache. That is a real foundation; the tool list is the constraint.

**Read tools to add (all Tier 1 aggregate).** GET_GOALS_STATUS, GET_RECURRING, GET_NET_WORTH,
GET_HEALTH_SCORE_BREAKDOWN, GET_FORECAST (Theme 2), GET_MERCHANT_HISTORY (Tier 2),
GET_IMPORT_STATUS, GET_UPCOMING_OBLIGATIONS, COMPARE_PERIODS.

**Confirm-gated actions.** The Fino readiness doc already specifies "confirm before execute".
CREATE_BUDGET, RECATEGORIZE_TRANSACTION, ADD_GOAL_CONTRIBUTION, MARK_DUPLICATE, MARK_RECURRING_
CANCELLED, SET_REMINDER. Each returns a proposal card the user taps to apply; the apply path is
the same service call the UI uses, audited in `ai_audit_log` with the proposal id.

**Contextual Fyn.** Product decisions say AI appears on Dashboard, Reports, Transactions,
Goals, Investments, Debt. Concretely: an "Ask about this" affordance on a ledger row (pre-fills
merchant and month), on a budget card, on an insight. Same chat, seeded context.

**Memory.** Fyn has no conversation memory across sessions and no user facts ("I get paid on
the 1st", "rent is 25k"). A small `user_fact` table, user-editable, feeds the system prompt.
Financial Memory already has the corrections and recurring sections; extend it.

**Guardrails to keep.** Tier 4 never leaves the backend. No advice framed as investment
recommendation (the product is not a licensed advisor; keep the disclaimer surfaced). Monthly
org budget stays the kill switch.

Effort M. Tier: `FINO_AI` with a free daily allowance of a few questions.

---

### Theme 7. India-specific layers

- **Tax view, FY April to March.** Interest income by account from statements, 80C-eligible
  debits (PPF, ELSS, LIC, tuition, home-loan principal from Theme 4), 80D (health insurance),
  HRA rent trail, capital gains from CAS (Theme 5) with the grandfathering date handled.
  Export a CA-ready summary. Effort M after Themes 4 and 5.
- **UPI intelligence.** VPA handles carry merchant and bank hints; the shared corpus
  (`SharedCorpusService`) plus `MerchantUnderstandingService` can enrich P2P and small-merchant
  UPI far beyond what statement narration gives. Effort S, continuous.
- **Account Aggregator, live.** Seven plan documents and the Setu code path exist; config is
  a placeholder pending sandbox measurement. This is the biggest activation lever once the
  FIU registration is done, and it changes Theme 1 from "when you upload" to "as it happens".
- **Credit score.** Bureau partner (Experian or CIBIL API partners). Deferred in product
  decisions; revisit once liabilities exist so the score has something to sit next to.
- **Multi-currency and forex cards.** `Account` has no currency column. NRI and travel users
  need it; `FOREX_CARD` is `NOT_MODELLED_YET`. Effort M, mostly migration and display.

---

### Theme 8. Household and people

`Relationship` already tags contacts as FAMILY, FRIEND, OWN_ACCOUNT and the "Paid a Person"
category exists. Two products hide here:
- **Splits and IOUs.** Mark a P2P debit as "for" a friend, track settle-up, remind. Small.
- **Household workspace.** Shared budgets and goals, per-member accounts, a combined net worth,
  roles (owner, member, view-only). Every service is keyed by `userId` today, so this is a real
  data-model change (a `household_id` on accounts, budgets, goals) and an authorization change.
  Effort L. Tier: Plus for two members, Premium for more.

---

### Theme 9. Retention and growth loops

- **Monthly Wrapped.** `WrappedService` is yearly and thin (landmarks and contributions).
  A monthly, shareable card (spend, top merchant, savings rate, one insight) is a free
  acquisition channel. Effort S.
- **Streaks and momentum** already exist for goals; extend to "months with a complete
  statement" using `FinancialMemoryCompleteness`, and show the completeness ring on the
  dashboard with the next missing month as the call to action.
- **Import reminders.** A statement gap notification (Theme 1) is the single best retention
  push, because the product gets better with each upload.
- **Referral rewards** are restored (#1239); tie them to activation (friend imports a
  statement), not signup.
- **Public benchmarks, anonymised.** "Your dining spend is in the top 30 percent for your
  income band." Needs the consent and anonymisation the product decisions already require.
  Later.

---

### Theme 10. Platform investments that pay for everything above

- **Import learning loop.** Persist user corrections against a layout fingerprint
  (`DocumentContext` already fingerprints), replay them on the next statement from the same
  layout. First slice of the parked document-intelligence roadmap.
- **Identity resolution, minimal.** IFSC plus holder name landing in the existing PROBABLE tier
  when no account number is present, without invented weights (the tension the identity memory
  flags).
- **Product analytics.** Navigation counters shipped in #1717. Add the activation funnel the
  premium roadmap names: first import, first insight viewed, first transaction confirmed,
  first budget or goal, first recommendation accepted.
- **Recommendation engine.** Mostly a join over Themes 2 to 5: a finding, an action, a
  quantified effect on a goal, and a follow-up that shows the outcome. Build it after the
  inputs exist, not before.
- **Public data export and Sheets sync.** `DataExportService` writes JSON; add CSV per table
  and a Google Sheets push for power users. Small.

---

## Part 3. Sequencing proposal

**v2.0, the proactive release.** Theme 1 producers, Theme 2 forecast, Theme 3a to 3c, Theme 3e
rules page. Marketing line: Fynora tells you before it happens.

**v2.1, the truth release.** Theme 4 loans, Theme 5 CAS and returns, Theme 3d insights v2,
health-score trend. Marketing line: your real net worth.

**v2.2, the CFO release.** Theme 6 Fyn tools and actions, recommendation engine, tax view.

**v2.3, the together release.** Household, splits, monthly Wrapped, AA live if registered.

Not building in v2: proprietary models, a chat-first UI replacing screens, crypto, real-time
stock prices, bill payments or any money movement, credit score before liabilities.

## Part 4. Preconditions

- Packaging decision: Premium is hidden and only three gates enforce. Decide which of the
  above are Plus before building gated surfaces, or they ship free by default.
- The seven audit blockers close before any of this starts.
- Gmail resumes only after CASA; Account Aggregator needs sandbox measurement and FIU status.
