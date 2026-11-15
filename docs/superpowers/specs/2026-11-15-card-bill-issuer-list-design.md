# Card bills named "<issuer> Bank Credit…": file them as Transfer

Status: draft for Sid's approval, 2026-11-15. Idea 4 of the categorization roadmap, redirected
with Sid's agreement.

## Why

Idea 4 was "seed the shared corpus from a curated merchant list". Production counts (2026-11-15,
read-only, kept out of this repo) showed that would barely help:

- The shared corpus has never fired: no shop has the three voters it needs.
- It can only answer shops paid by UPI ID, which are a small slice of the payments waiting for a
  category. Most of those are shops only one customer uses.

The biggest single group of money waiting under "Other" among payments to banks was credit card
bills paid from a savings account. No rule caught them, because the bank text names the
payee rather than saying "credit card payment":

- The Paytm app: `UPI/ICICI Bank Cred/<ref>/Sent using Payt…`. Paytm cuts the payee "ICICI Bank
  Credit Card" to about 15 letters. The same shape appears for SBI, IDFC, Axis, HDFC and Yes Bank.
- ICICI net banking: `BIL/<ref>/ICICI BANK CREDIT CA/<ref>`.

A card bill is a month of card purchases paid at once. The existing card-bill rules
(`BankActivityCategory`: "cc billpay", "cred club", "ib billpay" with a masked card number; and
`CategoryRules`' "credit card payment", "card bill payment" and "cc payment") already file such a
payment as Transfer, so it is not counted as spending a second time. These two shapes slip past
them.

## Approaches considered

- **A. A curated list of card-issuing banks** (chosen by Sid). Match the payee only when it reads
  "<known issuer> bank cred…". An unknown "… Bank Credit …" line can't be mistaken for a card
  bill. The cost is that a missing issuer stays uncaught until it is added.
- **B. Any "<word> bank cred…"**. This catches unlisted issuers, but a loose match on these amounts
  could move large non-card payments out of spending.
- (The original idea 4, seeding the shared corpus, was dropped for the reason above.)

## What it does

- `BankActivityCategory` treats a debit as a card bill when its normalized text has
  `<issuer> bank cred`, optionally followed by more letters of "credit card" (cred, credi, credit,
  credit c, credit ca, credit car, credit card), ending at a word boundary.
- The issuer is one word from a curated list.
  - Seen in production: icici, hdfc, sbi, axis, idfc, yes.
  - Added as well-known Indian card issuers (my call): kotak, indusind, rbl, federal, hsbc, citi,
    idbi, canara, pnb, union, bob.
- As today, a payment to a person is never a card bill. The same test feeds
  `BankActivityCategory.of` (category "Transfer") and `paysCardBill` (Insights' card-bill check), so
  the two can't disagree.
- Not caught, on purpose:
  - "HDFC Credila" (an education-loan company): "credila" is not a prefix of "credit card".
  - "State Bank Collect" (a payments portal for fees).
  - Any income row.
- Rows already waiting: raise `CategorizationService.SUGGESTION_VERSION` so the existing
  `CategorySuggestionSweepService` re-checks them. It changes only rows that are waiting, not
  chosen by the customer and still "Other" or the person guess, exactly as a fresh import would,
  and reconciles each changed customer afterwards. It never calls the AI.

## Verification (required by the repo rules for categorization changes)

- Unit tests for each shape, each issuer form, the cut-off lengths, and the not-caught cases.
- A check that `CategorizationService` files the production shapes as Transfer end to end, not
  only that the pattern matches.
- Value-level measurement over the real statement corpus before and after, listing every row
  whose category changes. Any change outside these card-bill shapes must be read and explained
  before merge.

## Non-goals

- No change to the shared corpus or its three-voter rule.
- No new category: card bills stay "Transfer", as the existing rules file them.
- No lender, insurer or EMI list. Production showed no waiting loan or insurance payments to
  institutions.

## Left to the builder

- Where exactly the pattern sits in `BankActivityCategory`, and the test layout.
