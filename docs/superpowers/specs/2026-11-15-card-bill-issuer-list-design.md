# Card bills named "<issuer> Bank Credit…": file them as Transfer

Status: approved by Sid 2026-11-15 (option A for card bills, "Build both fixes", option B for linked transfers). Idea 4 of the categorization roadmap, redirected
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

## Fix 2: a payment linked as an own-account transfer stops needing review

Found while checking whether these card bills could be linked to card statements: a payment
that reconciliation, a user's rule or the user had linked as a transfer between the customer's
own accounts still kept `needs_category_review`. It already counted as a transfer in totals
(`FlowClassifier` reads `isTransfer`), but it was still treated as waiting:

- Quick sort (full and Dashboard) kept asking about it.
- The Needs review list and its group view listed it.
- "Sort with AI" after an import would send it to the AI.
- The Ledger badged it "Needs review".

Production had a few dozen such payments across a couple of customers.

Sid chose (2026-11-15) to clear the flag when the link is made, rather than skipping such
payments in each reader. Every reader then agrees, including the Ledger badge.

- `Transaction.setTransfer(true)`, on a payment not yet a transfer that is waiting, clears
  `needs_category_review` and records that the link cleared it, in a new column
  `review_cleared_by_transfer`. Every link path calls this setter (reconciliation's pair and
  one-sided passes, a `MARK_TRANSFER` rule, the user marking a pair), and nothing updates the
  transfer flag in bulk. So one place covers them all.
- `setTransfer(false)` (the user's "not a transfer", a statement removed or re-imported, a link
  released) puts the flag back only when the link had cleared it, and only if the user hasn't
  since chosen a category by hand. A payment that wasn't waiting before the link is left as it was.
- Migration `V324`:
  - adds the column (not null, default false);
  - clears the flag on payments already linked and still waiting, marking them as cleared by the
    link, so undoing those links works the same way;
  - bumps `updated_at`, as V319/V320 did, so client caches see the change.
  - It can safely run again: a cleared row no longer matches.

## Verification (required by the repo rules for categorization changes)

- Unit tests for each shape, each issuer form, the cut-off lengths, and the not-caught cases.
- A check that `CategorizationService` files the production shapes as Transfer end to end, not
  only that the pattern matches.
- Value-level measurement over the real statement corpus before and after, listing every row
  whose category changes. Any change outside these card-bill shapes must be read and explained
  before merge.
- Fix 2:
  - Tests that each link path clears the flag and each unlink path restores it.
  - A payment that wasn't waiting stays untouched on unlink.
  - A category the user chose while the payment was linked survives the unlink.
  - The migration is checked on a local database copy with linked and unlinked rows.

## Non-goals

- No change to the shared corpus or its three-voter rule.
- No new category: card bills stay "Transfer", as the existing rules file them.
- No lender, insurer or EMI list. Production showed no waiting loan or insurance payments to
  institutions.

## Left to the builder

- Where exactly the pattern sits in `BankActivityCategory`, and the test layout.
