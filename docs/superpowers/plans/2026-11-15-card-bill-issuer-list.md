# Card Bills By Issuer + Linked Transfers Stop Waiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** File card bills named "<issuer> Bank Cred…" as Transfer, and stop treating a payment linked as an own-account transfer as waiting for a category.

**Architecture:** One more card-bill pattern in `BankActivityCategory`, plus a `SUGGESTION_VERSION` bump so the existing sweep re-checks waiting rows. `Transaction.setTransfer` clears and restores `needsCategoryReview`, using a new column `review_cleared_by_transfer`. Migration V324 adds the column and fixes rows already linked.

**Tech Stack:** Spring Boot + JPA + Flyway, JUnit (unit and `AbstractIntegrationTest` ITs).

**Spec:** `docs/superpowers/specs/2026-11-15-card-bill-issuer-list-design.md`

## Global Constraints

- Issuers, exactly: icici, hdfc, sbi, axis, idfc, yes, kotak, indusind, rbl, federal, hsbc, citi, idbi, canara, pnb, union, bob.
- The pattern runs on normalized text: `\b(<issuers>) bank cred(?:i(?:t(?: c(?:a(?:r(?:d)?)?)?)?)?)?\b`. It applies to EXPENSE only. A PERSON counterparty is never a card bill (existing guard).
- The card-bill result is the category "Transfer". `paysCardBill` and `of` use the same test.
- `CategorizationService.SUGGESTION_VERSION` goes from 7 to 8, with a comment line in the existing style.
- Migration number V324. No other migration may change.
- Test fixtures use synthetic values (`100000000001` style, `// synthetic-ok` where the data guard needs it). No production values in the repo.
- Commits have no AI trailer and lower-case subjects.

## Review Focus

1. "HDFC CREDILA …", "STATE BANK COLLECT …", "<issuer> BANK CREDIT" on an INCOME row, and a person's name followed by "bank cred" must all stay uncaught.
2. A linked payment the user gave a category to by hand while it was linked must not go back to waiting on unlink.
3. A payment linked while it was not waiting must not start waiting on unlink.
4. Setting the transfer flag to true twice, or false on a non-transfer, must change nothing.
5. The V324 cleanup touches only rows that are linked, waiting and not deleted, and is safe to run twice.

---

### Task 1: Card bills named by their issuer

**Files:**
- Modify: `backend/src/main/java/com/finora/util/BankActivityCategory.java` (`namesCardBillPaid`, a new pattern `ISSUER_CARD_BILL`)
- Modify: `backend/src/main/java/com/finora/service/CategorizationService.java` (`SUGGESTION_VERSION` 7 → 8, with a comment)
- Test: `backend/src/test/java/com/finora/util/BankActivityCategoryTest.java`, `backend/src/test/java/com/finora/util/CardBillsTest.java`, `backend/src/test/java/com/finora/service/CategorizationServiceTest.java`

- [ ] **Step 1: Failing tests in `BankActivityCategoryTest`:**
  - `aCardBillNamedByItsIssuer_isTransfer`: `of("UPI/ICICI Bank Cred/100000000001/Sent using Paytm", EXPENSE)`, the same with "SBI Bank Credit", "IDFC Bank Credi", "Axis Bank Credi", "HDFC Bank Credi" and "Yes Bank Credit", and `of("BIL/100000000001/ICICI BANK CREDIT CA/100000000002", EXPENSE)`, all `"Transfer"`.
  - `issuerCardBill_notCaught`: each of the following is null.
    - `of("UPI/HDFC Bank Credila/100000000001/fees", EXPENSE)`
    - `of("UPI-STATE BANK COLLECT-STATEBANKCOLLECT@SBI-SBIN0XXXXXX-100000000001-PAYMENT", EXPENSE)`
    - `of("UPI/ICICI Bank Credit/100000000001/refund", INCOME)`
    - `of("UPI/SAMPLE Bank Cred/100000000001/x", EXPENSE)` (not a listed issuer)
  - `paysCardBill_trueForIssuerNamedBill`: `paysCardBill` is true for the Paytm and BIL shapes.

- [ ] **Step 2: Run them and see them fail.** Run: `cd backend && ./mvnw -q test -Dtest=BankActivityCategoryTest`. Expected: FAIL on the new tests only.

- [ ] **Step 3: Implement.** Add `ISSUER_CARD_BILL`, a `Pattern` from the constraint above. In `namesCardBillPaid`, OR it in against the `normalized` text.

- [ ] **Step 4: Failing end-to-end test in `CategorizationServiceTest`:** `suggestReadOnly` of the Paytm shape with direction EXPENSE suggests "Transfer", the way the existing card-bill tests in that file do. Run it (FAIL until Step 3 is in, then PASS).

- [ ] **Step 5: Bump `SUGGESTION_VERSION` to 8.** Comment: "8: a card bill named by its issuing bank ("<issuer> Bank Cred…", Paytm and ICICI net banking) is "Transfer" (BankActivityCategory, 2026-11-15)."

- [ ] **Step 6: Run `BankActivityCategoryTest`, `CardBillsTest`, `CategorizationServiceTest`, `CategorySuggestionSweepServiceTest` and `CategorySuggestionSweepIT`.** Expected: all pass.

- [ ] **Step 7: Corpus measurement.** In the scratchpad, write a throwaway JUnit probe (never committed). It parses every statement in `~/Downloads/Bank statement` with `ProbePipelines` and prints `description-shape | direction | BankActivityCategory.of | paysCardBill` per row. Run it at BASE and at HEAD, and diff the two. Expected: the only changed rows are card bills in these shapes. Read and explain every other change before going on.

- [ ] **Step 8: Commit** `feat(backend): card bills named by their issuing bank are filed as transfer`

### Task 2: A linked transfer stops waiting

**Files:**
- Create: `backend/src/main/resources/db/migration/V324__review_cleared_by_transfer.sql`
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java` (field `reviewClearedByTransfer`, column `review_cleared_by_transfer`, `setTransfer` logic)
- Test: a new `backend/src/test/java/com/finora/entity/TransactionTransferReviewTest.java`; `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java` or an IT that runs a real link; `backend/src/test/java/com/finora/transactions/TransactionServiceTest.java` (`unmarkTransfer`)

**Interfaces:**
- Produces: `Transaction.isReviewClearedByTransfer()`. The behaviour of `setTransfer(boolean)`:
  - false → true while waiting: clears `needsCategoryReview` and sets the marker.
  - true → false with the marker set: `needsCategoryReview = !categoryManuallySet`, and clears the marker.
  - Any other call changes nothing else.

- [ ] **Step 1: Failing unit tests (`TransactionTransferReviewTest`):**
  - `linkingAWaitingPaymentClearsItsReview`;
  - `unlinkingPutsTheReviewBack`;
  - `unlinkKeepsACategoryChosenByHand` (set `categoryManuallySet` true while linked, then unlink: not waiting);
  - `linkingAPaymentThatWasNotWaitingChangesNothingOnUnlink`;
  - `repeatedCallsChangeNothing` (true twice, false twice).

- [ ] **Step 2: Run them and see them fail.** Run: `./mvnw -q test -Dtest=TransactionTransferReviewTest`.

- [ ] **Step 3: Implement** the field, `@Column(name = "review_cleared_by_transfer", nullable = false)` and the setter logic.

- [ ] **Step 4: Write V324.**
  - `ALTER TABLE transactions ADD COLUMN review_cleared_by_transfer BOOLEAN NOT NULL DEFAULT FALSE;`
  - `UPDATE transactions SET needs_category_review = FALSE, review_cleared_by_transfer = TRUE, updated_at = now() WHERE is_transfer AND needs_category_review AND deleted_at IS NULL;`
  - A header comment in the V320 style: why, which rows, why `updated_at`, safe to rerun.

- [ ] **Step 5: Failing ITs on a real database:**
  - A reconciliation pair of two waiting rows: after reconciliation both are no longer waiting, and both have the marker set.
  - `unmarkTransfer` puts both back to waiting.
  - A row that was given a category by hand while linked stays not waiting.

  Put them where the existing transfer ITs live. Run them; they pass once Steps 3–4 are in.

- [ ] **Step 6: Check the migration** on a throwaway local Postgres with Flyway at V323. Seed rows: linked and waiting, linked and not waiting, unlinked and waiting, deleted, linked and waiting. Run V324 twice. Expected: only the linked, waiting, live rows change, and the second run changes nothing.

- [ ] **Step 7: Run the full backend suite** (`./mvnw verify`). Expected: 0 failures.

- [ ] **Step 8: Commit** `fix(backend): a payment linked as an own-account transfer stops waiting for a category`
