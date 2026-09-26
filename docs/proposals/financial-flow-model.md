# Fynora Financial Flow Model — Product, Data and UX Review

Date: 2026-09-26 · Read-only review against `origin/main` @ `8421d19d` · No code changed

---

## 0. Evidence base, and a correction to the brief

The brief says every credit is treated as income and every debit as expense. That is **not quite
what the code does**, and the difference changes the recommendation. What `origin/main` actually has:

| Mechanism | Where | What it does today |
|---|---|---|
| `Transaction.Type { INCOME, EXPENSE }` | `entity/Transaction.java:30` | Direction only. Every total starts from this. |
| `ReconciliationStatus { OK, DUPLICATE, TRANSFER, REFUND, REVERSAL, INVESTMENT_TRANSFER, SUPERSEDED }` | `Transaction.java:45` | Exclusion flags. |
| `RefundNetting.reportable()` | `service/RefundNetting.java` | Drops duplicates, `isTransfer`, refund legs, SUPERSEDED rows, and savings-side legs of `CC_PAYMENT` graph edges. |
| `RefundNetting.excludingInvestmentTransfers()` | same | Drops `INVESTMENT_TRANSFER` from top-line totals only. |
| Refund netting | same | A matched refund is netted against its purchase, in the **purchase's** period. |
| Transfer pass | `ReconciliationService` ~L520–640 | Pairs two **imported** legs (amount + date window + a "looks like transfer" gate). Salary is guarded out. |
| Investment pass | `ReconciliationService.isInvestmentOutflow` L1378 | **EXPENSE rows only**, keyed on the "Investments" category. |
| `RelationshipType` `EMI, SALARY, LOAN_REPAYMENT, CASH_WITHDRAWAL, CASH_DEPOSIT` | `TransactionRelationship.java:29` | Declared but never constructed. `git grep` finds no producer. Deferred on purpose (`dead-relationship-types-audit.md`). |
| `counterparty_type` (PERSON/BUSINESS/FINANCIAL_INSTITUTION/GOVERNMENT) + `counterparty_key` (`vpa:` / `name:`) | `Transaction.java:96–106` | Who is on the other side. Direction is left out on purpose. |
| Shared corpus | `SharedCorpusService.isEligible` | Cross-user learning only for `vpa:` keys of **BUSINESS** counterparties, and only with at least 3 distinct voters. |
| Manual "mark transfer" | `TransactionController:140` | Requires `pairedTransactionId` (`@NotNull`). A one-sided transfer **cannot** be marked. |
| Categories | `entity/Category.java` | Name, icon and colour only. There is **no type or flow attribute**. V123 states that "no dashboard, budget or analytics path keys off a category-name literal". The one exception is the `"Investments"` name used by the investment pass. |

**The accurate description of the current model:** totals come from **direction minus a
denylist of detected exceptions**. Anything the detectors miss falls back to credit = income and
debit = expense. So the problem isn't that no model exists. The problem is that the model is a
denylist over direction, and that its default for anything unrecognised is a confident claim
(income or expense) when it should be "unknown".

Gaps confirmed in the code:

1. **Money received from people counts as income.** "Personal Transfer" is a label only (V123/V124).
2. **Investment inflows count as income**: FD maturity, MF redemption, broker payout. The investment
   pass only looks at EXPENSE rows.
3. **Loan disbursals count as income, and EMIs count fully as expense** (principal and interest together). There is no LOAN detector.
4. **One-sided self-transfers count as income and expense** when the other account isn't imported. The manual override can't express them either, because it needs a paired row.
5. **ATM withdrawals count as expense.** The "Cash Withdrawal" category has no effect on arithmetic. As argued below, this one is actually the **right default**.
6. Reimbursements have **no concept at all**. They count as income.

**Not established in this review** (measure before building, per the repo's own practice):
the share of credit *value* that falls into each gap above, across the real corpus or production
users. That number decides the order of work in §11, and I haven't measured it.

---

## 1. Executive summary

- **Stop deriving metrics from direction.** Add a first-class **flow class**: the economic
  nature of the money. It sits alongside direction, category (purpose) and counterparty (who), and
  has its own source, confidence and classifier version. It follows the same pattern as
  `counterparty_classifier_version`.
- **Make "unknown" a real value.** Credits from people and unexplained large credits default to
  `UNRESOLVED`, which is **excluded from Income** and shown on its own line, not silently counted.
  Honest uncertainty beats confident inflation.
- **Allocations, not single labels.** An EMI is part principal, part interest. An FD maturity is
  part principal, part interest. A friend's UPI can be half dinner share and half loan repayment. A
  transaction needs 1..n allocations, each with its own flow class.
- **Track the perimeter.** Whether a movement counts as a "transfer" depends on whether the other
  end is a *tracked* account. A card bill paid from savings is a transfer only if the card's own
  spend is imported. If it isn't, the payment is the only evidence of spend and should count as spend.
  The same logic applies to wallet loads and ATM withdrawals.
- **Metrics become queries over flow classes**, with a published truth table (§4). Savings rate
  uses *earned* income and *net* spend.
- **Learning is per user for people and cross-user for businesses and patterns**, scoped by
  (counterparty identity, direction), held as a prior that user confirmations update. It is never
  "ask once, apply forever" without conditions.
- **Long-term target: a lightweight double-entry event model** with virtual accounts (Cash,
  Receivable:Rahul, Loan:HDFC, Untracked-own). Flow class becomes a derived view of postings. Ship
  it in stages. Don't start with the ledger.

---

## 2. Core concept validation

**Credit = Income / Debit = Expense should go.** Direction describes how money moved relative to
one account. Income and expense describe what happened to the user's wealth. These are different
axes, and the brief's table shows they disagree in about a third of common Indian transaction types.

The industry-standard alternative is **category-typed** analytics: each category belongs to
Income, Expense or Transfer (Tiller, Monarch, Lunch Money, YNAB in effect). That alternative is
**also wrong for Fynora**, and this is the main disagreement with copying the West:

- UPI person-to-person payments make up a large share of Indian transaction *count*. Fynora's own V123
  commentary explains why: a payment to a named individual might be the maid, driver, landlord or
  tutor (real spend), or a friend or parent (a transfer). A category type forces one answer for the
  whole "Personal Transfer" category. Either spend gets inflated or real spend disappears.
- Category answers **what the money was for**. Flow class answers **what the money did to your wealth**.
  "Groceries" can be an expense, a reimbursement (a flatmate paid you back) or a refund. The axes are
  orthogonal, so they need separate fields.

**Recommended architecture: four orthogonal axes per allocation.**

| Axis | Question | Exists today? |
|---|---|---|
| Direction | Did money enter or leave *this account*? | Yes (`txn_type`) |
| Counterparty | Who is on the other side? | Yes (`counterparty_type/key`) |
| Category | What was it for? | Yes |
| **Flow class** | What did it do to the user's wealth? | **No.** Approximated by the `reconciliation_status` denylist |

`reconciliation_status` and the relationship graph stay as **evidence and linkage**: why a row is
a transfer, and which row it pairs with. They stop being the arithmetic.

---

## 3. Transaction taxonomy

Two levels: a small, stable **flow class**, which drives metrics, and an open **subtype**, which
drives UX and insights. Keep the top level small, because every metric formula enumerates it.

| Flow class | Wealth effect | Subtypes (examples) | Direction |
|---|---|---|---|
| `INCOME` | + net worth, earned or received | `SALARY`, `BUSINESS`, `FREELANCE`, `INTEREST`, `DIVIDEND`, `RENT`, `GIFT_RECEIVED`, `REWARD_CASHBACK`, `TAX_REFUND`, `OTHER` | credit |
| `EXPENSE` | − net worth, consumption | `PURCHASE`, `BILL`, `FEE_CHARGE`, `LOAN_INTEREST`, `CASH_SPEND` (proxy), `CARD_SPEND_UNITEMIZED` (proxy), `GIFT_GIVEN`, `TAX` | debit |
| `REFUND` | contra-expense | `MERCHANT_REFUND`, `CHARGEBACK`, `CANCELLATION` | credit |
| `REIMBURSEMENT` | contra-expense (someone paid their share) | `SPLIT_SHARE`, `EMPLOYER_CLAIM` | credit |
| `TRANSFER` | neutral, inside the perimeter | `OWN_ACCOUNT`, `CC_PAYMENT`, `WALLET_LOAD`, `CASH_WITHDRAWAL`, `CASH_DEPOSIT`, `FD_BOOKING`† | either |
| `INVESTMENT` | neutral (asset swap) | `CONTRIBUTION`, `WITHDRAWAL` (principal only) | either |
| `LIABILITY` | neutral (cash vs debt) | `DRAWDOWN` (loan disbursal, BNPL), `PRINCIPAL_REPAYMENT` | either |
| `LENDING` | neutral (cash vs receivable) | `LENT`, `REPAID_TO_ME`, `BORROWED`, `REPAID_BY_ME` | either |
| `ADJUSTMENT` | correction, not activity | `REVERSAL`, `BANK_CORRECTION`, `OPENING_BALANCE`, `ROUNDING` | either |
| `UNRESOLVED` | unknown, kept out of Income/Expense until resolved | `PERSON_IN`, `PERSON_OUT`, `LARGE_UNEXPLAINED` | either |

† Treat FD as `INVESTMENT` or `TRANSFER` depending on whether FDs are modelled as a tracked asset. Pick one and write it down.

Rules the taxonomy has to obey:

1. **Flow class is independent of category.** A default mapping from category to flow class is fine
   (Salary to `INCOME.SALARY`), but a flow class set by the user or a detector always wins.
2. **Compound transactions get allocations**: an EMI of ₹12,000 becomes ₹8,400 `LIABILITY.PRINCIPAL_REPAYMENT`
   plus ₹3,600 `EXPENSE.LOAN_INTEREST`, and an FD maturity of ₹1,07,000 becomes ₹1,00,000 `INVESTMENT.WITHDRAWAL`
   plus ₹7,000 `INCOME.INTEREST`. If the split is unknown, store one allocation and mark `split_pending`.
3. **Proxy classes are explicit.** `EXPENSE.CASH_SPEND` and `EXPENSE.CARD_SPEND_UNITEMIZED` say
   "we're using this outflow as a stand-in for spend we can't see". They flip to `TRANSFER` once
   the destination becomes tracked (§8).
4. **Asymmetric default for people.** Unexplained `PERSON_OUT` defaults to `EXPENSE`, matching V123's
   reasoning that payments to individuals are often spend. Unexplained `PERSON_IN` defaults to
   `UNRESOLVED`, which is excluded from income. This is deliberately conservative: it understates
   income and savings rate and never overstates them. See §12 for who this hurts.

---

## 4. Analytics calculation framework

Everything is computed over **allocations** in **reportable** rows. Duplicates and superseded rows
stay excluded exactly as `RefundNetting.reportable` does today.

**Truth table: which flow classes feed which metric**

| Metric | INCOME | EXPENSE | REFUND | REIMB. | TRANSFER | INVEST. | LIABILITY | LENDING | ADJUST. | UNRESOLVED |
|---|---|---|---|---|---|---|---|---|---|---|
| Income (earned) | + (excl. GIFT/REWARD/TAX_REFUND subtypes) | | | | | | | | | shown separately |
| Other income | + GIFT/REWARD/TAX_REFUND | | | | | | | | | |
| Gross spend | | + | | | | | | | | |
| Net spend | | + | − | − | | | | | | |
| Savings | + | − | + | + | | | | | | |
| Net cash flow (liquid) | + | − | + | + | 0 if both ends liquid; else ± | ± | ± | ± | ± | ± |
| Investment contributions (net) | | | | | | +CONTRIB −WITHDRAW | | | | |
| Debt service | | LOAN_INTEREST | | | | | +PRINCIPAL_REPAYMENT | | | |

**Formulas**

- **Income (earned)** = Σ `INCOME` excluding gift, reward and tax-refund subtypes. Reversals of income
  (`ADJUSTMENT.REVERSAL` linked to an income row) are subtracted in the *original* period.
- **Total income** = earned income + other income. Show both. Savings rate uses earned income.
- **Gross spend** = Σ `EXPENSE`.
- **Net spend** = Gross spend − linked refunds − linked reimbursements (netted into the purchase's period
  and category, which is today's `RefundNetting` rule) − unlinked refunds and reimbursements (netted in
  the period received, under the counterparty's inferred category, or "Unallocated offsets").
- **Savings** = Earned income + Other income − Net spend.
- **Savings rate** = Savings ÷ Earned income. Undefined (show "—", not 0 % or ∞) when earned income ≤ 0.
  Show a **coverage indicator** when `UNRESOLVED` exceeds, for example, 10 % of credits (the threshold must be calibrated).
- **Net cash flow** = Δ balance of *liquid* accounts (savings, current, wallet, tracked cash), meaning
  every flow class counts except transfers whose two ends are both liquid. This is **not** savings: a
  loan disbursal raises cash flow and leaves savings unchanged, and a SIP lowers cash flow and leaves
  savings unchanged. Reconcile it against actual balance deltas. Fynora already stores `balance_after`,
  so the check is free.
- **Burn rate** comes in two variants, and both should exist:
  - *Consumption burn* = trailing-3-month average net spend.
  - *Committed burn* = consumption burn + average `LIABILITY.PRINCIPAL_REPAYMENT`. This answers "how much
    cash leaves each month". Runway = liquid balance ÷ committed burn.
- **Investment contributions** = Σ `INVESTMENT.CONTRIBUTION` − Σ `INVESTMENT.WITHDRAWAL` (principal).
- **Investment returns** **cannot be computed from bank flows alone.** Returns = Δ holdings value −
  net contributions. Holdings data is needed (AA MF/equity FI types, CAS, broker statements). From bank
  rows, only *realised* income is visible (`INCOME.INTEREST`, `INCOME.DIVIDEND`). Don't show a returns
  number without holdings data. Show "realised investment income" instead.
- **Net worth change** = Δ Σ asset balances − Δ Σ liability balances, from balances, not flows.
  **Identity check:** ΔNW ≈ Savings + unrealised investment gains − accrued, unpaid interest. A
  persistent gap means a misclassified flow. Transfers, contributions, drawdowns and principal
  repayments are all net-worth neutral, so if they're classified correctly they can't create a gap.
  This is the strongest automated QA signal the new model gives you.

**Period attribution.** Refunds and reimbursements linked to a purchase belong to the purchase's
period, as today. That means closed months can be restated. Keep the rule, but snapshot monthly
metrics that users see as "final" (Wrapped, monthly report) and show "restated: −₹500 refund arrived
later" rather than changing numbers silently.

---

## 5. Counterparty intelligence

The detectors produce **features**. A scorer turns the features into a flow-class posterior. The
UX turns low-confidence cases into questions. Every detector writes an explanation, reusing the
`reconciliation_explanation` pattern that already exists.

### 5.1 Detectors (heuristics and features)

**Salary (`INCOME.SALARY`)**
- Credit from a BUSINESS counterparty over NEFT/RTGS/ACH/IMPS.
- Narration tokens: SAL, SALARY, PAYROLL, `SAL FOR <MON>`, `BY SALARY`.
- Recurs monthly: same counterparty key, date within ±4 days of the prior month, amount within
  ±20 % (bonus months break this, so allow one outlier a year).
- Usually the largest recurring credit, landing in the last week or first week of the month.
- Existing asset: the `looksLikeSalary` guard in the transfer pass.
- Ask once: "Is ACME TECHNOLOGIES your employer?" A yes binds the counterparty to `INCOME.SALARY`
  for credits.

**Self-transfer (`TRANSFER.OWN_ACCOUNT`)**, strongest signals first:
1. A paired leg in another tracked account (the existing pass).
2. **Account fingerprint match**: masked account number, last 4 digits, card last 4 or IFSC parsed from the
   narration matches a tracked account's own fingerprint. This needs an account-identity registry,
   which the corpus audit reports as missing ("no card identity key").
3. **Own-VPA registry**: VPAs that appear as the *payer* on the user's own debits, or on credits into
   another of the user's accounts.
4. **Holder-name match**: counterparty name equals the account holder name taken from the statement
   header. Use a fuzzy match on normalised tokens, and require both surname and given name.
5. Keywords: SELF, OWN A/C, TPT, "TO SELF", "SWEEP".
- An equal amount inside a date window is **confirmation only, never sole evidence**. Round
  ₹5,000/₹10,000 transfers collide all the time.
- If the fingerprint matches an account the user owns but doesn't track, the result is
  `TRANSFER.OWN_ACCOUNT` to an **untracked-own** virtual account. This fixes gap #4 and needs the
  manual endpoint to stop requiring `pairedTransactionId`.

**Family / friend (`PERSON` counterparties)**
- *Don't infer "family" from a shared surname and act on it.* It's culturally unreliable and
  sensitive. Use it only to phrase a suggestion.
- Useful features: whether money flows both ways, how often, how regularly (a monthly fixed amount
  to the same person suggests an allowance, EMI to a relative or rent), how round the amounts are,
  and **proximity to a split-shaped expense** (a credit ≈ expense ÷ N within 10 days of a
  dining/travel/entertainment debit).
- Relationship is a **fact about the counterparty** ("Rahul — friend"). Flow class is a **fact
  about a transaction**. Store them separately. The relationship is a feature for the flow classifier, not a
  substitute for it.

**Refund (`REFUND`)**: this already exists and is good. Extend it with:
- Matching reference numbers (RRN/UTR/ARN) when both rows carry them. That's much stronger than amount.
- Card-side credits: "REFUND", "REV", "CREDIT ADJ" from the same merchant key.
- **Unlinked refunds**: credit from a BUSINESS counterparty with a refund keyword and no matching
  purchase in the window (the purchase is older than the window or on an untracked card). Classify it as `REFUND`
  anyway and net it in the period received.

**Loan disbursal (`LIABILITY.DRAWDOWN`)**
- A large credit from a FINANCIAL_INSTITUTION or known NBFC. Tokens: DISB, DISBURSAL, LOAN A/C, LAN,
  PL/HL/AL.
- **Retroactive confirmation**: NACH/ECS debits of a stable amount to the same institution start 20–45 days later. When
  a repayment series is detected, raise the disbursal's confidence after the fact.
- Ask when the amount is material: "₹3,00,000 from Bajaj Finance: is this a loan?"

**Loan repayment (`LIABILITY.PRINCIPAL_REPAYMENT` + `EXPENSE.LOAN_INTEREST`)**
- NACH/ECS/ACH/SI debit, fixed amount, monthly, FINANCIAL_INSTITUTION counterparty, tokens EMI,
  LOAN, LAN, loan account number.
- Principal/interest split: exact if the user enters the loan terms (principal, rate, tenure, then an
  amortisation schedule) or if a loan statement or AA feed is imported. Otherwise keep one allocation with
  `split_pending`. Don't invent a split.

### 5.2 Confidence scoring

- Each detector emits features. The scorer produces P(flow class | features). Start with a
  **transparent weighted log-odds model** whose weights can be read and tuned, not an ML black box.
- **Three action tiers:**
  - `AUTO`: apply silently, show a subtle badge and explanation, and allow undo.
  - `AUTO_REVIEW`: apply, and add to the review queue if the amount is material.
  - `ASK`: leave as `UNRESOLVED` (credits) or the conservative default (debits), and queue a question.
- **Thresholds must be calibrated, not chosen.** Use user confirmations and corrections as labels
  and measure precision per tier. The target is for `AUTO` precision to reach at least 98 % on
  money-weighted value. The repo already has a relationship `Status { CANDIDATE, AUTO_CONFIRMED,
  USER_CONFIRMED, REJECTED }` and a `ConfidenceScorer`. Extend them rather than building a parallel system.
- **Materiality gates the question, confidence gates the automation.** A ₹40 UPI to an unknown person
  never earns a question. A ₹2,00,000 unexplained credit always does.

### 5.3 Machine learning opportunities, in order

1. **Calibrating the rule scorer** (isotonic or Platt) from confirmation data. Cheap, and the largest win.
2. A **gradient-boosted flow classifier** over non-identifying features (rail, amount shape,
   recurrence, counterparty type, time, direction, pairing signals, the user's own history for this
   key). Train it globally on features and personalise it with per-user priors. **Never** use raw
   person names or VPAs as global features.
3. **Recurrence and series detection** (salary, EMI, SIP, rent) with a sequence model. This is also
   the basis for forecasting.
4. **Split-shape matching** for reimbursements (credit ≈ k/N × expense).
5. LLM use is **narration parsing only**, as a fallback, and never the final flow classification. It
   isn't needed at millions of rows, and the corpus audit already shows the live engine "fabricates merchants".

---

## 6. Learning system: "RAHUL UPI ₹1,000"

**Recommendation: a hierarchical, scoped, Bayesian memory.** It's per user for people and
cross-user only for businesses and structural patterns.

**Key hierarchy** (use the strongest key available, and fall back down the list):
1. `vpa:rahul.s@okhdfc` or the account number: a real identity.
2. `name:rahul` is weak. The code already says a `name:` key "must never be presented to a user as
   an identity". Two Rahuls in one user's contacts is normal. Learn at this level only with a
   lower weight, and never auto-apply from it alone.
3. Narration template, for example `UPI/<num>/<name>/<vpa>/<bank>/<note>`. Useful for structure and the
   rail, not for purpose.

**Memory unit:** (user, counterparty key, **direction**) → a distribution over flow classes, held
as counts (Dirichlet/Beta). User confirmations add a large weight, and silent acceptance of an auto
label adds a small one.

- Direction is essential. Credits from Rahul might be reimbursements while debits to Rahul are splits
  you paid, or loans. One memory entry per direction.
- **Amount conditioning** is optional but valuable. "Rahul, under ₹5,000, credit" can be REIMBURSEMENT
  while "Rahul, ₹50,000" might be LENDING.
- Auto-apply only when posterior mass for the top class is at or above the calibrated threshold and n ≥ 3 observations,
  or when an explicit "always" rule exists.

**"Ask once and remember forever?" No.** Ask once with an **explicit scope choice**:

> ₹1,000 from **Rahul S** — what was this?
> [Paid me back] [Gift] [Repaid a loan] [Income/work] [Own account]
> ☐ Treat future money from Rahul S like this

- Ticked: create an explicit user rule scoped to (key, direction). It's reversible, and shown in the counterparty's page.
- Unticked: only updates the prior.
- **Contradiction handling:** if the user relabels a transaction against an "always" rule twice,
  downgrade the rule to a prior and ask once whether to keep it.

**Cross-user learning:**
- **PERSON: never.** It's meaningless (my Rahul isn't yours) and it leaks privacy. The current
  `SharedCorpusService` already restricts to BUSINESS `vpa:` keys. Keep that boundary.
- **BUSINESS: yes**, for flow class as well as category. "Credits from `vpa:bajajfinance@...` are
  `LIABILITY.DRAWDOWN` for 94 % of 212 users."
- **Structural patterns: yes**, as features. "NACH debits with a stable amount to FINANCIAL_INSTITUTION
  counterparties are EMI in X % of confirmed cases." These are aggregate model weights, not per-key facts.

---

## 7. Reimbursements

Scenario: dinner ₹2,000 paid by the user, and later a friend sends ₹1,000.

| Approach | How it works | Accuracy | UX cost | Verdict |
|---|---|---|---|---|
| A. Income (status quo) | Credit counts as income | Income +₹1,000, spend +₹1,000 overstated | None | Wrong |
| B. Unlinked contra-expense | Credit becomes `REIMBURSEMENT`, reducing spend in the month received | Total right, category and period can be off | Low | Good fallback |
| C. Linked netting | Credit linked to the ₹2,000 row, dining net ₹1,000 in the dinner's month | Right total, category and period | Medium (link suggestion) | **Default** |
| D. Split at spend time | User marks the dinner "split ×2" and a ₹1,000 receivable is created; the credit settles it | Also right for net worth (receivable is an asset) | High | Power feature |
| E. Per-person ledger | Receivables/payables per person, across many events | Handles lending too | Highest | Later (with D) |

**Recommendation: C by default, B as fallback, D and E later.**

- When a `PERSON_IN` credit arrives, look for split-shaped candidates: expense × k/N for N ∈ 2..6 within
  about 14 days, in dining/travel/entertainment/groceries. Suggest with one tap: "Rahul paid you back for **Dinner at
  Toit (₹2,000)**?"
- If accepted, link it using a `REIMBURSEMENT` relationship edge (add the type to the enum). The dinner shows "₹2,000 paid ·
  ₹1,000 your share".
- If the user says "paid me back" but picks no match, file it as `REIMBURSEMENT` unlinked (B).
- **Mirror case the brief missed:** the friend paid the full bill and **you** send them ₹1,000. That debit is
  your `EXPENSE` (your share of dinner), not a transfer, even though the counterparty is a PERSON.
  This is another reason not to type the "Personal Transfer" category as a transfer.
- **Keep repayment of money lent separate.** If you lent Rahul ₹5,000 last month and he returns it, that's
  `LENDING.REPAID_TO_ME`: neutral to net worth, and **not** a reduction of spend. Treating it as a reimbursement
  would understate spend.

---

## 8. Refunds

Options assessed:

| Option | Problem |
|---|---|
| Count as income | Inflates income and savings rate, and shows a ₹500 order as ₹500 spent plus ₹500 earned. Wrong. |
| Separate metric only | Visible, but spend stays overstated unless it's also netted. |
| **Reduce spend (contra-expense)** | Correct. Needs period and category attribution rules. |

**Recommendation** (mostly what `RefundNetting` already does, plus three additions):

1. Linked refund: net against the purchase in the purchase's period and category. **This already exists.** Keep it.
2. Unlinked refund (purchase outside the window or untracked): `REFUND`, netted in the period received, category taken from the
   merchant's usual category, else "Unallocated offsets". Today the refund pass only matches inside
   `REFUND_WINDOW_DAYS`, so an unmatched refund falls back to **income**. That fallback is the bug to fix.
3. Show the arithmetic: **Gross spend − Refunds = Net spend** as a disclosed breakdown on the
   dashboard, not only the net figure.
4. Cashback and rewards: `INCOME.REWARD_CASHBACK` (other income, excluded from earned income).
   Don't net it into the category. It's usually unrelated to one purchase, and card statement credits
   are often batched.

---

## 9. Internal transfer detection

**Principle: a movement is a transfer only if both ends are inside the user's tracking perimeter.**
This is where the brief's table is wrong in two rows:

- **Credit card bill payment = transfer** holds *only if the card's spend is imported*. If the user
  imports only the savings account, the card payment is the **only evidence of ₹38,000 of spending**.
  Classifying it as a transfer makes that spend disappear. Correct behaviour: `EXPENSE.CARD_SPEND_UNITEMIZED`
  (a proxy) until the card statement arrives, then re-class it to `TRANSFER.CC_PAYMENT` and let the
  itemised card rows carry the spend. The graph already does the second half through `ccPaymentFromTransactionIds`.
- **ATM withdrawal = cash movement** is accurate as accounting and wrong as product. Almost nobody
  tracks cash. If ATM is a transfer, cash spend never appears anywhere. Default: `EXPENSE.CASH_SPEND` (proxy). Users
  who track cash wallets flip it to `TRANSFER.CASH_WITHDRAWAL` and log cash expenses. This is roughly what
  today's code already does by accident, and it should become a stated policy.

**Detection by movement type**

| Movement | Debit-side signals | Credit-side signals | Pairing | If other end untracked |
|---|---|---|---|---|
| Savings ↔ savings | Holder-name match, account mask/IFSC in narration, own VPA, SELF/TPT | Same, mirrored | Amount equal, 0–3 days (NEFT batch or holiday) | `TRANSFER.OWN_ACCOUNT` to an untracked-own virtual account (ask once: "Is ••4821 your account?") |
| Savings to credit card | Card last 4 in narration, CRED/BBPS/"CC PAYMENT", bank-name billpay handle | Card statement: "PAYMENT RECEIVED", "THANK YOU" | Amount equal, 0–5 days; one payment can settle many | `EXPENSE.CARD_SPEND_UNITEMIZED` |
| Savings to wallet | "ADD MONEY", "WALLET LOAD", wallet handles | Wallet statement: "Added from bank" | Amount equal, same day | `EXPENSE` proxy (a wallet is usually spent in full) |
| Savings ↔ investment | Broker/AMC/clearing handles (Groww, Zerodha, ICCL, BSE, NSE clearing, AMC names), NACH SIP | Redemption: "REDEMPTION", AMC names, ICCL payout | Usually single-leg (broker statement not imported) | `INVESTMENT.CONTRIBUTION` / `INVESTMENT.WITHDRAWAL`. **Withdrawal side missing today (gap #2).** |
| Savings ↔ FD | "TO FD", "FD BOOKED", "FD CLOSURE", "MATURITY" (same bank) | Principal plus interest on maturity | Booking-to-maturity link via FD number | Maturity split: principal `INVESTMENT.WITHDRAWAL`, interest `INCOME.INTEREST` |

**Infrastructure this needs**
1. **Account identity registry**: per tracked account: masked number, last 4, IFSC, holder name, card
   last 4, known VPAs, plus user-declared untracked-own accounts. This is the single highest-leverage piece and
   is currently missing.
2. **One-sided transfers**: the manual endpoint accepts an untracked-own destination, and
   `transfer_pair_id` becomes optional when the destination is a virtual account.
3. **Many-to-one** (one card payment settles many charges, one transfer split across two) is already
   handled for CC through graph edges. Generalise it.
4. **Re-classification when the perimeter changes**: importing a new account must re-run
   classification over the overlapping history. This is how proxies flip to transfers.

---

## 10. UX design

**Principles**
1. **Classify silently when confident, show the reasoning on demand.** Every row has a "Why?"
   explanation (the data already exists in `reconciliation_explanation`).
2. **Ask by counterparty, not by transaction.** "14 transactions from Rahul S (₹18,400): what are these?"
   One answer resolves many rows and trains the memory.
3. **Question budget:** no more than 3 at onboarding, and no more than 5 a week afterwards, ranked by
   money-weighted uncertainty × how widely the answer generalises. Never ask about anything below a materiality floor.
4. **Show uncertainty, don't hide it.** The dashboard shows "₹18,400 received from people, not yet
   classified". It's excluded from income and one tap from review. The savings rate carries a coverage
   indicator.
5. **Every automatic decision can be undone** in one tap, and undo writes a label (training signal).

**Moments to ask**
- **Onboarding, after the first import (3 questions max):**
  1. "Is **ACME TECHNOLOGIES** your salary?" (top recurring business credit)
  2. "Are these your own accounts? ••4821, ••0913" (holder-name/fingerprint matches in untracked accounts)
  3. "Do you pay a credit card from this account? Add its statement for exact spend." (the CC proxy)
- **After each import:** a "3 things to confirm" card, ranked as above.
- **Inline at the moment of value:** a transaction row with an `UNRESOLVED` chip gets tappable
  classification chips: Paid me back · Gift · Loan · Income · Own account.
- **Counterparty page:** relationship label, flow rules for each direction, history, "always" toggles.
- **Never:** blocking modals, questions about sub-materiality amounts, or asking the same
  counterparty twice in a week.

**Dashboard presentation**
- Income: **Earned ₹1,20,000** · Other ₹2,300 · *Received from people (unclassified) ₹18,400*
- Spend: **Net ₹64,200** (Gross ₹71,000 − Refunds ₹3,800 − Paid back ₹3,000)
- Savings rate 45.6 % · coverage 91 %
- Separate from spend: Invested ₹15,000 · Loan principal repaid ₹8,400 · Moved between your accounts ₹42,000

---

## 11. Industry benchmarking

*Source caveat: this section is from general product knowledge up to my training data. I did
**not** re-verify it against current versions of these products in this session. Treat the details
as indicative, not authoritative.*

| Product | Transfers | Refunds | Reimbursements | Salary / income | Model |
|---|---|---|---|---|---|
| YNAB | Explicit transfer payee between accounts; off-budget accounts break symmetry | Inflow to the spending category (reduces spend) | Same as refunds, manual | Inflow to "Ready to Assign" | Envelope; manual discipline |
| Monarch | Transfer category type, excluded from cash flow; auto-detects between linked accounts | Categorised into the expense category (negative spend) | Same, plus splits | Income category group | **Category-typed** |
| Copilot | Internal transfer type, auto-detected across linked accounts; "excluded" state | Reduces category spend | Manual / exclude | Income type | Transaction type + category |
| Lunch Money | Transfer groups; categories flagged "treat as income" / "exclude from totals" | Negative expense in category | Transaction groups, splits | Category flagged as income | Category flags + grouping |
| Tiller | Category Type = Transfer, "hide from reports" | Negative in category | Manual | Type = Income | Category-typed spreadsheet |
| Quicken | Transfer via `[Account]` category, both legs linked | Negative in category | Splits, reimbursable tracking | Income categories, paycheck splits | Double-entry-like |
| Empower (Personal Capital) | Auto-excluded transfers; cash flow separate | Negative expense | Weak | Income category | Net-worth first |
| Wealthica | n/a (investment aggregator) | n/a | n/a | Separates **deposits/withdrawals from income/gains** | Contributions vs returns |
| CRED-style (India) | Card-bill-centric; the bill payment *is* the product | Card statement credits | n/a | n/a | Card statements as source of truth |

**Best practices worth copying**
- Refund = negative expense in the original category (everyone does this, and Fynora already does it better with period netting).
- Transfers excluded from cash flow, auto-detected when both legs are linked.
- Wealthica's split between contributions and returns for investments.
- Quicken's linked two-leg transfers and paycheck splits (salary allocations), which are closest to the allocation model.

**Where Fynora should deliberately diverge**
- **Don't adopt category-typed analytics.** It fits US spending, where P2P is a minority, and fails on India's UPI P2P volume (§2).
- **Statement-first, not API-first.** Western apps rely on both legs being linked via Plaid.
  Fynora mostly sees one leg (PDF/CSV imports), so the tracking perimeter and proxy classes (§9) are a
  necessity that competitors mostly don't face.

---

## 12. Migration strategy

**Phase 0 — measure (no user-visible change).**
- Run the new classifier in **shadow** over the real corpus and production data.
- Produce, per user and in aggregate: old vs new income, spend and savings rate. Distribution of value by flow class.
  The ten largest reclassifications per user.
- This answers the "not established" question in §0 and decides whether P2P credits, investment inflows or
  loans should be fixed first. It follows the repo's own rule (build the six deferred relationship
  types "once actual usage data … shows which … users actually need first").

**Phase 1 — schema, additive only.**
- New `transaction_allocations` table (transaction_id, amount, flow_class, flow_subtype,
  category_id, source, confidence, classifier_version, rule_id). Every existing transaction gets one allocation.
  Follow the V143 pattern (`counterparty_classifier_version`), where a null version means "never classified".
- Keep `txn_type`, `reconciliation_status`, `is_transfer`, `refund_of_transaction_id` as evidence.
- Check Flyway version collisions against `origin/main` before numbering. This repo has hit that three times.

**Phase 2 — deterministic backfill (source = `LEGACY_MAPPED`).**

| Existing state | New allocation |
|---|---|
| `is_transfer` / TRANSFER status | `TRANSFER.OWN_ACCOUNT` |
| savings-side leg of a CC_PAYMENT edge | `TRANSFER.CC_PAYMENT` |
| REFUND status | `REFUND.MERCHANT_REFUND` (linked) |
| INVESTMENT_TRANSFER | `INVESTMENT.CONTRIBUTION` |
| REVERSAL | `ADJUSTMENT.REVERSAL` |
| DUPLICATE / SUPERSEDED | unchanged, still excluded before allocation |
| `category_manually_set` = true | user's category's default flow class, source `USER_LEGACY`, **never** overridden by later automation |
| Salary category, credit | `INCOME.SALARY` |
| Cash Withdrawal category | `EXPENSE.CASH_SPEND` (unchanged arithmetic) |
| PERSON counterparty, credit, not otherwise matched | `UNRESOLVED.PERSON_IN` **(biggest visible change)** |
| everything else | `INCOME.OTHER` / `EXPENSE.PURCHASE` by direction, confidence LOW |

**Phase 3 — probabilistic backfill.** Run the detectors from §5 over history. Apply only at `AUTO`
confidence. Everything else goes to the review queue, *not* applied.

**Phase 4 — dual-run behind a per-user flag.** Metrics endpoints serve both; the UI shows the new
figures with a one-time **"Your numbers got more accurate"** card that itemises the change: "August
income ₹1,58,000 to ₹1,20,000: ₹25,000 moved between your accounts, ₹13,000 from people (review)".
Never change a user's headline number without saying why.

**Phase 5 — cut-over.** Remove direction-based totals from `DashboardService`, `ReportService`,
`AnalyticsService`, `BudgetService` and `InsightsService` (the `Type.INCOME/EXPENSE` call sites
found by grep). Budgets are affected only where contra-expense netting changes category spend.

**Historical reports and snapshots.** Whether Wrapped, Journey and monthly reports persist computed
figures or recompute them was **not established** in this review. If they persist, keep old snapshots
labelled with their classifier version and restate on demand. If they recompute, the change shows up
automatically, and the Phase 4 card is the only disclosure.

**Rollback.** Allocations are additive, and the old evidence columns stay. Turning the flag off reverts
the metrics exactly.

---

## 13. Challenging this design

**Weaknesses of what I proposed**

1. **`UNRESOLVED.PERSON_IN` hurts freelancers, tutors, doctors and small traders** paid by individuals over UPI.
   Their real income would disappear by default. *Mitigation:* an onboarding persona question ("Do people
   pay you for work?") that flips the default for `PERSON_IN` to `INCOME.FREELANCE` below a recurrence
   threshold. It still misfires for mixed users.
2. **Asymmetric defaults bias the savings rate downwards.** Conservative, but a consistently pessimistic
   number is still wrong, and it demotivates users. The coverage indicator helps, but only if people read it.
3. **Proxy classes make classification depend on history.** The same card payment is spend in
   June and a transfer in July (after the card is imported). Users will see restated months. That's
   correct, but confusing.
4. **Allocations multiply complexity**: every query, export, budget and test fixture. For a small team this is a real cost.
   The corpus audit still reports broken ids and garbage in a large share of parsed rows. **Classification quality is capped by parse quality**, and parser P0 work should keep precedence.
5. **Thresholds and weights are guesses until calibrated.** Anything shipped before Phase 0 data exists is
   exactly the guessing this repo forbids.
6. **Net worth can't be derived from flows.** Holdings and liability balances are needed. Without them, the identity check in §4 can't run.
7. **Person-level learning is fragile.** Name keys collide, VPAs change, one friend uses three handles.
   Merging counterparties needs a user-driven "these are the same person" action.
8. **Tax and fiscal-year views** (Indian FY Apr–Mar, 80C, TDS on interest) want different
   attribution from the cash view. The design doesn't address them.
9. **Joint accounts and household finance**: a spouse's transfer is neither "own account" nor "friend". The
   perimeter becomes a *household* perimeter. Not modelled.
10. **Freelancer business vs personal expenses** in one account. Flow class doesn't separate
    entities.

**Edge cases the design has to handle explicitly**
- Refund larger than the purchase (price protection, compensation): the excess is `INCOME.OTHER`.
- Refund for a purchase made before the user's first imported statement: unlinked `REFUND`.
- Salary reversed and re-credited: `ADJUSTMENT.REVERSAL` pair, net zero.
- Salary credit that includes reimbursement of travel expenses: allocation split (asks the user; this can't be inferred).
- EMI bounce, then penalty, then re-debit: reversal pair plus `EXPENSE.FEE_CHARGE`.
- BNPL (Simpl, LazyPay): purchase at checkout is `EXPENSE`, and the later BNPL bill payment is a `LIABILITY.PRINCIPAL_REPAYMENT`. The
  double count is avoided only if the BNPL drawdown is tracked. Otherwise the bill payment is a proxy expense.
- Credit card EMI conversion (large purchase converted to 6 EMIs on the card).
- Rent paid to a landlord over UPI (PERSON, recurring, fixed): `EXPENSE.BILL`, not a transfer.
- Parent sends a monthly allowance to a student: for the student this is `INCOME.GIFT_RECEIVED` or earned income depending on persona.
- Sweep-in/sweep-out FDs (auto-sweep accounts): very high frequency; must be `TRANSFER`, never `INVESTMENT` noise.
- Cash deposit at the bank from an untracked cash source: `UNRESOLVED` (income? loan repaid in cash?).
- Crypto exchange deposits (P2P USDT via UPI to individuals!) look like PERSON transfers.

**Better version: the financial event model (target end state)**

The flow-class-plus-allocation design is a **projection** of a proper **double-entry event model**,
and I recommend building towards it rather than stopping at a column:

- **Accounts** include tracked real accounts plus virtual ones: `Cash`, `Untracked-own:••4821`,
  `Receivable:Rahul`, `Payable:Rahul`, `Loan:HDFC-PL`, `Investment:Groww`, `Income:Salary:ACME`,
  `Expense:Dining`, `Offsets:Refunds`.
- **A financial event** (for example "Dinner split with Rahul") groups one or more bank transactions and
  posts **balanced legs**: dinner = −₹2,000 Savings, +₹1,000 Expense:Dining, +₹1,000 Receivable:Rahul.
  Rahul's UPI = +₹1,000 Savings, −₹1,000 Receivable:Rahul.
- **Every metric becomes an account-type query**: Income = Σ postings from `Income:*`, Spend = Σ postings to
  `Expense:*` net of `Offsets:*`, Net worth = Σ asset − Σ liability balances. Transfers, investments,
  loans and lending are net-worth neutral **by construction**, not by exclusion lists.
- Unresolved flows post to `Suspense:*`. Its balance *is* the coverage indicator.
- The identity checks (ΔNW = savings + gains) become ledger invariants that can be asserted in tests.

Why not build this first: users don't think in double entry, the UI has to hide it completely, and
the migration is heavier. **Path:** flow class and allocations now (Phases 1–5), designed so every
allocation already names a *counter-account* (`to_account_ref`, nullable). The later step to
postings is then a derivation, not a rewrite.

---

## 14. Alternatives considered

| Alternative | Why rejected / deferred |
|---|---|
| Keep direction + grow the exclusion denylist | This is the current trajectory. Each gap is another status value. It defaults unknowns to confident income/expense and doesn't scale to allocations. |
| Category-typed analytics (Monarch/Tiller) | Fails on Indian P2P (§2). Couples purpose and economic nature. |
| Single `flow_class` column on `transactions` | Simpler than allocations, but can't represent EMI, FD maturity or split salary. Acceptable as Phase 1 *only if* the table is designed so it can later become allocations. |
| Full double-entry ledger now | Correct end state. Too heavy before parse quality and Phase 0 data exist. |
| Ask users to classify everything (YNAB-style) | Accurate, but causes fatigue that loses mainstream Indian users. |
| LLM classifies every transaction | Cost at scale, non-deterministic, already observed fabricating merchants in this corpus, and hard to calibrate. |
| Cross-user learning for persons | No signal (my Rahul isn't yours), and a privacy risk. |

---

## 15. Final recommendation

1. **Run Phase 0 first.** Shadow-classify the real corpus and production data and publish the value
   distribution by flow class. No design commitment on sequencing until those numbers exist.
2. **Adopt flow class as an independent axis**, stored as **allocations** with provenance and
   classifier version. Every allocation carries a nullable counter-account so it can later become ledger postings.
3. **Honest defaults:** `PERSON_IN` becomes `UNRESOLVED`, excluded from income and shown on its own line.
   `PERSON_OUT`, ATM withdrawals and card payments for untracked cards become explicit **proxy expenses**.
4. **Fix the confirmed gaps in value order (to be set by Phase 0):** likely unresolved P2P credits,
   investment/FD inflows, one-sided own-account transfers (account identity registry plus optional pair),
   then loans.
5. **Metrics** follow the §4 truth table. Earned-income savings rate with a coverage indicator, two burn
   rates, and no investment-returns figure without holdings data.
6. **Learning:** per-user, direction-scoped Bayesian memory with explicit "always" rules. Cross-user
   only for BUSINESS VPAs and structural features, extending the existing `SharedCorpusService` boundary.
7. **UX:** ask by counterparty, within a question budget, at natural moments. Show uncertainty
   instead of hiding it.
8. **Migration:** additive schema, deterministic backfill, shadow, per-user flag, an explained
   "numbers changed" card, then cut-over. Rollback is a flag.
9. **End state:** a lightweight double-entry financial event model with virtual accounts. That's what
   makes Fynora a system of record rather than a budgeting app.

---

## Decisions after review (2026-09-26)

1. **Allocations deferred to Phase B.** EMI and FD principal/interest splits are not part of the first implementation; flow class is single-valued per transaction until then.
2. **No dual-run.** There are no real users yet, so there is no per-user flag, no parallel old/new metrics and no "your numbers changed" card. The switch happens in one change before launch.
3. **Phase 0 measures the real statement corpus, not the database.** The development database holds only test data (a handful of card transactions) and cannot size any gap.
4. **A credit on a credit-card account is never earned income.** It is a payment, a refund, an adjustment, a reward, or unresolved. Evidence: a card statement showed two same-day merchant credits printed in the issuer's payment/credits section with a merchant category; they were counted as income because no matching purchase existed on that card.
5. **Flow class is derived at read time, not stored.** Only user decisions (overrides, per-counterparty rules) are persisted, from Plan 2 on.
6. **People who earn from people** (traders on personal UPI, landlords, tutors) are handled by an onboarding persona question, an account-level setting and a recurring-payer prompt in Plan 2; the classifier default for money from a person stays "unresolved".

Implementation plan: `docs/superpowers/plans/2026-09-26-financial-flow-classification.md` (to be added with the plan's execution).

---

## Plan 0 measurement (2026-09-26, classifier v1)

Probe: `FlowClassCorpusProbe` over the real out-of-tree corpus, 31 statements after removing two that
re-download the same periods (identical parsed rows): 10 credit-card, 21 savings/other. Single-statement
view (no reconciliation context, no OCR), so classes are upper bounds and lending is a lower bound.
Row-level output stays outside the repository.

| | Value |
|---|---|
| Credits (old income) | ₹16,92,246 |
| New income | ₹12,67,160 |
| Removed | ₹4,25,086 (25.1%), 123 rows |

Removed, by class/reason:

| Class / reason | Rows | Value | Share |
|---|---|---|---|
| UNRESOLVED / person inflow | 85 | ₹2,26,765 | 53.3% |
| TRANSFER / card payment received | 11 | ₹1,65,949 | 39.0% |
| UNRESOLVED / unexplained card credit | 10 | ₹22,918 | 5.4% |
| REFUND / unlinked | 10 | ₹7,011 | 1.6% |
| INVESTMENT / withdrawal | 6 | ₹2,416 | 0.6% |
| ADJUSTMENT / card | 1 | ₹27 | 0.0% |

Severity (share of a statement's credit value removed): card statements 10/10 affected, median 100%
(by design); non-card statements 12 of 16 with credits affected, median 4.3%, p95 100%.

Candidate mechanisms (within one statement): counterparty ledger 0 candidates; cash round-trips 0;
pass-through 15 rows / ₹24,078 in 2 sections (exploratory). Rhythm: no statement shows a trader-like
shape -- the most distinct person payers in any statement is 8, and the three sections labelled
STEADY are ordinary personal accounts with 1-3 small payers a week, so the STEADY rule as written
is too loose to drive a "looks like a shop" suggestion.

Kept as income but visibly not earned income, found by reading the kept rows (not yet classified):
transfers naming people who are the holders of other statements in this corpus -- own or family
accounts moving money (largest single group, about ₹2.3L), person payments in UPI narration shapes the counterparty classifier does not
read as a person (about ₹0.7L), a cash deposit, merchant credits without a refund word, a UPI return,
and a clearing-corporation payout. These point at the counterparty classifier's coverage and at
Plan 3's holder-name/self-transfer detection, not at more classifier keywords.
