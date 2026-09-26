# Flow Classification: Edge Scenarios Catalogue

Companion to `2026-09-26-financial-flow-classification-plan.md`. For each scenario:
- **Plan 1 does:** what the Plan 1 classifier and totals do today, read from the rules in the plan (not run).
- **Correct:** what the money really is.
- **Signal:** how the app could tell.
- **Fix:** which plan fixes it, or which new mechanism is needed.

How common each scenario is in the real corpus is **not established**. The probe measures some of this (see the end).

---

## A. Income that arrives as person-to-person (Plan 1 undercounts income)

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| A1 | Vegetable or kirana seller paid to a **personal** UPI ID | Every customer credit becomes `UNRESOLVED` and income drops to ~0 | Business income | Many distinct payers a month, small median, credits ≫ debits, **steady every week** | Persona "Business/shop" plus account toggle (Plan 2) |
| A2 | Same seller on a **business QR** (PhonePe or Paytm for Business) | Settlement credit from the payment company counts as `OTHER_INCOME` | Business income | Settlement narration, business counterparty | Already right; confirm with the probe |
| A3 | Landlord, rent from 2–3 tenants | `UNRESOLVED` | Rent income | Same payer monthly, stable amount, early in the month | Recurring-payer prompt (Plan 2) |
| A4 | Tutor, coach, doctor, lawyer or beautician paid by individuals | `UNRESOLVED` | Professional income | Many repeat payers, a weekly or monthly rhythm | Persona "Freelance/tuition" (Plan 2) |
| A5 | Salary paid from a proprietor's personal account, with no "salary" word | `UNRESOLVED` | Salary | Same payer, monthly, the largest credit, on a fixed date | Recurring-payer prompt ("Is this your salary?") |
| A6 | Freelancer paid by a foreign individual through Wise or Remitly | Remittance from a business counts as income (fine). A payment direct from a person abroad becomes `UNRESOLVED` | Income | Inward remittance narration | Persona plus per-payer rule |
| A7 | Farmer paid by mandi traders (persons) | `UNRESOLVED`. PM-KISAN (government) counts as income | Agricultural income | Seasonal bursts, a few large payers | Persona "Farming" |
| A8 | Retiree supported by children abroad or in another city | `UNRESOLVED` | For their budget it behaves like income ("family support") | Same payer, monthly | Persona "Family support", which counts as income for this user only |
| A9 | Student or homemaker receiving a monthly allowance | `UNRESOLVED` | Household transfer: income for a personal budget, internal for a family view | Same payer (parent or spouse), monthly | Persona "Supported by family", and later the household perimeter (G) |

**Rule this group teaches:** what money from a person *means* depends on who the user is. A default can't get it right, but one question can.

## B. Informal lending and borrowing: both legs wrong

Plan 1 keeps debits to persons as spend. That's V123's rationale, because they're often real spend (maid, driver). It's also why these cases break.

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| B1 | User lends Rahul ₹20k, who repays ₹20k over 3 months | Out: **spend +₹20k**. In: `UNRESOLVED` | `LENDING`, neutral to net worth | Out then in with the same payer, roughly equal totals over time | **Counterparty net-position ledger** (new, see below) |
| B2 | User borrows from a friend or relative, repays later | In: `UNRESOLVED` (fine). Repayments: **spend +** | `LENDING` (borrowed, repaid) | In then out, roughly equal | Same ledger |
| B3 | **Chit fund / committee / BC** (kitty): pay ₹5k a month to an organiser, receive a ₹60k lump sum once | Contributions: **spend +₹60k/yr**. Payout: `UNRESOLVED` | Savings scheme (investment-like), neutral apart from the interest or discount | A fixed monthly debit to one person, then one large credit from the same person | Keyword "chit" or "committee", plus the ledger, plus a user label |
| B4 | Rent security deposit paid to landlord, returned when leaving | Paid: **spend +₹1L** (once). Returned: `UNRESOLVED` | Asset (a receivable), neutral | A large debit to the landlord equal to 2–10× the monthly rent; a return months later | Keyword "deposit" plus a user label; the ledger for the return |
| B5 | Salary advance or loan from employer | In: counts as income (business counterparty). Deductions are invisible (netted in salary) | `LIABILITY` | "Advance" or "loan" from the employer's payer key | Keyword "advance" on employer-key credits |

**Rule this group teaches:** a person is a *balance*, not a stream. If money out to a person and money back from them roughly cancel over months, both legs are lending or splitting, not spend or income.

## C. Pass-through (custodial) money: user is a collector, not a spender

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| C1 | Trip organiser pays the hotel ₹30k; 5 friends each send ₹6k | Hotel: **spend ₹30k**. Friends: `UNRESOLVED` | Spend ₹6k (own share), rest reimbursed | Several person credits within about 14 days summing to ≈ k/N of one expense | Reimbursement linking (Plan 5), multi-payer version |
| C2 | Office colleague collects for a farewell gift, then pays the vendor | In: `UNRESOLVED`. Out: **spend** | Pass-through, neutral apart from own contribution | Many person credits, then one debit ≈ their sum, within days | **Pass-through detector** (new) |
| C3 | RWA or society treasurer collecting maintenance into a personal account | Monthly `UNRESOLVED` from dozens of residents: looks like **A1 (a shop)**. Payments to vendors count as spend | Custodial, neutral | Many payers, **same amount each**, a monthly burst, outflows to vendors ≈ inflows | Account role "Collection/custodial" |
| C4 | Parent sends money so the child pays the parent's bill | In: `UNRESOLVED`. Out (bill): **spend** | Pass-through (parent's expense) | Credit from a person, then a bill debit ≈ the same amount within days | Pass-through detector |
| C5 | Crowdfunding or medical collection for someone else | Same as C2 | Pass-through | Burst, then one outflow | Pass-through detector |

**Trap:** C3 and a wedding (E1) look exactly like A1 (a shop) on "many distinct payers". The shop suggestion needs **rhythm** (steady across weeks, varied amounts) and not just count. Treasurer collections are equal amounts in a monthly burst; wedding gifts are a one-off burst.

## D. Contra-income: money back that is neither income nor a merchant refund

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| D1 | Health insurance claim reimbursing a hospital bill | Insurer (business) counts as `OTHER_INCOME` | Contra-expense against medical spend | "Claim", insurer name, after a large hospital debit | Keyword "claim", then link to the expense (Plan 4/5) |
| D2 | Employer travel or fuel reimbursement, paid separately from salary | `OTHER_INCOME` | Contra-expense (`REIMBURSEMENT`) | "Reimb", "exp claim" from the employer key | Keyword "reimb" to `REIMBURSEMENT` (add to Plan 1 lists if the probe shows it) |
| D3 | LIC or endowment policy maturity | `OTHER_INCOME` (unless "maturity" matches) | Investment withdrawal plus a gain | "Maturity", insurer | The Plan 1 keyword "maturity proceeds" is too narrow; widen it if the probe shows these |
| D4 | Ride or food refund without the word "refund" (rider side) | Refund matcher catches it if the purchase is on the same account within the window; otherwise **income** | Refund | Same merchant token as a recent debit | Plan 4 (unlinked refund by merchant) |
| D5 | Security deposit returned by a company (gas, electricity, broadband) | `OTHER_INCOME` | Asset return | "Deposit refund", utility name | The keyword "refund" already makes it `REFUND` (acceptable) |
| D6 | Selling an old phone or bike to a person (OLX) | `UNRESOLVED` | One-off asset sale: not earned income, not spend | A single large credit from a new payer | User label "Sold something" becomes `INCOME` subtype `ASSET_SALE`, excluded from the earned-income view |

## E. Bursts and one-offs that distort trends

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| E1 | Wedding or festival gifts (shagun) from 60 people in a week | 60 × `UNRESOLVED`. **False-positive risk for the shop suggestion** | Gift income, one-off | Burst over a few days, then silence | Burst detection excludes it from trader suggestions; label "Gifts" |
| E2 | Bonus or arrears on top of salary | Salary (fine) | Salary, but it spikes savings rate | Amount far above the payer's median | Trend smoothing (a display concern) |
| E3 | Large one-off loan disbursal feeding a big purchase (car, gold) | Loan: `LIABILITY` (if keyword matched). Purchase: spend | Correct, but that month's spend ≫ income | — | Display: "financed" tag on the matching purchase |

## F. Cash round-trips and self-movements that aren't paired

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| F1 | ATM withdrawal ₹10k, then ₹8k cash deposited back later | ATM: **spend ₹10k** (proxy). Deposit: `OTHER_INCOME` ₹8k. **Both inflated** | Spend ₹2k, rest a round-trip | Cash deposit after a cash withdrawal, same or lower amount, within about 30 days | **Cash round-trip matcher** (new): cash deposit becomes `TRANSFER` up to the unspent ATM total |
| F2 | Shopkeeper depositing the day's cash takings | `OTHER_INCOME` (right for a shop) | Business income | Frequent deposits and no matching ATM withdrawals | Persona decides whether F1's matcher applies |
| F3 | UPI to own second bank's VPA (a self-transfer through UPI) | Out: **spend**. In (if imported): paired transfer only if the gate matches | Transfer | Own-VPA registry | Plan 3 |
| F4 | Moving money through a spouse's account (a family float) | Out: spend. In: `UNRESOLVED` | Household transfer | Same person, both directions, equal amounts | Ledger (B) plus the household perimeter |

## G. Account role and perimeter

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| G1 | Sole proprietor's current account: client receipts and supplier payments | Gross revenue counts as income, supplier payments as spend. Scale is inflated; savings rate is roughly right | Business profit is the user's income; business spend isn't personal spend | Current-account product type, GST/vendor narrations | **Account role "Business"**: dashboard shows only the profit drawn to personal |
| G2 | Joint account with spouse | Both salaries count as the user's income | Household income | Two salary payers | Account role "Joint", household view |
| G3 | Account managed for elderly parents | Their pension counts as the user's income | Not the user's money | Pension narration, different holder name | Account role "Managed for someone", excluded from personal totals |
| G4 | Business credit card used for company expenses, reimbursed by the employer | Card spend counts as spend; reimbursement counts as `OTHER_INCOME` (savings) | Neither is personal | Employer reimbursement ≈ card total | Account role "Work card" |

## H. Markets and "P2P-looking" trades

| # | Scenario | Plan 1 does | Correct | Signal | Fix |
|---|---|---|---|---|---|
| H1 | Crypto bought or sold P2P (UPI to individuals on an exchange's P2P desk) | Buy: **spend**. Sell: `UNRESOLVED` | Investment movement | Many unrelated persons, round amounts, exchange names in the note | Keyword or user label, then `INVESTMENT` |
| H2 | Intraday or F&O broker payouts | Clearing-corporation credit becomes an investment withdrawal (fine) | Investment movement; P&L isn't bank-visible | — | None (returns need holdings) |
| H3 | Reseller (Meesho or Instagram shop) paid by customers | Like A1 | Business income | Like A1 | Persona |

## I. Same merchant, opposite meaning (persona decides)

| # | Scenario | Rider/customer | Partner/seller |
|---|---|---|---|
| I1 | Uber, Ola, Rapido credit | Refund | **Income** (driver payout) |
| I2 | Swiggy or Zomato credit | Refund | **Income** (delivery-partner payout, restaurant settlement) |
| I3 | Amazon or Flipkart credit | Refund | **Income** (seller settlement) |
| I4 | Urban Company credit | Refund | **Income** (service partner) |

Plan 1 sends a credit with no keyword from these merchants to `OTHER_INCOME`. That's right for partners and wrong for customers who got an unmatched refund. **Signal:** the size of the merchant's credits compared with that user's debits to the same merchant. A partner receives much more than they spend there; a customer mostly spends. That's a per-user, per-merchant balance, the same idea as the person ledger in B.

---

## Cross-cutting mechanisms these scenarios point to

1. **Persona (multi-select), asked once:** Salary / Business or shop / Rent / Freelance or tuition / Pension / Supported by family / Farming / Gig or delivery partner. It sets defaults for person inflows (A), cash-deposit handling (F2 vs F1) and the partner-vs-customer reading of merchant credits (I). *Extends Plan 2.*
2. **Account role:** Personal / Business / Joint / Managed for someone / Collection (custodial) / Work card. It decides whether an account's flows are the user's personal income and spend at all (G, C3). *New, and belongs with the Plan 3 registry.*
3. **Counterparty net-position ledger:** per (user, counterparty key), the running sum of money out and money in over time. Near-zero net with both directions present points to `LENDING` or splitting, and the app suggests relabelling both legs. It generalises B1–B4, F4 and, with merchant keys, I. **Probably the single most valuable new mechanism here:** one idea covers lending, chits, deposits and household floats. *New plan (between Plans 3 and 4).*
4. **Pass-through detector:** several person credits followed within N days by one debit ≈ their sum (or the reverse). Both sides are pass-through. *New, and it pairs with Plan 5's multi-payer reimbursement.*
5. **Cash round-trip matcher:** cash deposits offset earlier ATM withdrawals up to the unspent amount. *New, small, rule-based.*
6. **Rhythm features, not just counts:** steady (shop, tutor), monthly (rent, salary, allowance), burst (wedding, collection), one-off (asset sale, deposit return). The "looks like a shop" suggestion must use rhythm, or weddings and RWA collections will trigger it.
7. **Debit-side resolution:** Plan 2's review screen must also cover money *out* to persons (lending, chit contributions, deposits). Plan 1 deliberately keeps those as spend, and B/C/F show that's often wrong in the other direction.

## Keyword candidates to test in the probe (not to add blindly)

"reimb", "claim", "chit", "committee", "deposit", "advance", "cash dep", "maturity", "settlement", "payout". Each gets added only if the probe's per-row output shows real rows it would fix, **and** shows no rows it would break.

## What the probe can measure now vs later

| Question | Measurable from the corpus (single statement)? |
|---|---|
| Distinct person payers, rhythm (weekly spread vs burst) | Yes: add a per-week payer histogram per statement |
| Counterparty net position (out vs in per payer key) | Yes, within one statement; across statements only for the same account |
| Cash deposit after ATM withdrawal | Yes, within one statement |
| Pass-through (burst in, one out ≈ sum) | Yes, within one statement |
| Persona or account role | No: needs the user's answer |
| Partner-vs-customer merchant balance | Partly (per statement) |

These are worth adding to the Task 3 probe before running it, because they tell us which of the new mechanisms are worth a plan.
