# Account Aggregator Gmail Interaction Implementation Plan (Plan 3 of 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up AA-vs-Gmail duplicates in the *ledger*, not the *confirm flow*. A dedicated,
auto-excluding reconciliation pass for high-confidence `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)` matches,
plus a proactive (non-interactive) UI signal so a user picking an account to confirm a Gmail
receipt into sees *why* an AA-linked account isn't offered, instead of only a raw `409` after
submitting.

**Architecture:** No new package. A new pass inside the existing `ReconciliationService.reconcile`
method (alongside Plan 2's AA-vs-manual pass and the pre-existing Gmail-vs-bank pass), one new field
threaded through the existing `AccountDto`/`Account` (frontend) types, and one change to the
existing shared account-picker component every import source already renders through.

**Tech Stack:** Spring Boot, JUnit 5 + Mockito + AssertJ (backend); React/TypeScript, Vitest +
Testing Library (frontend).

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)
— this plan implements the AA-vs-Gmail half of "Reconciliation" (§2). The confirm-time UX half of
that section (§3, the interactive warning dialog) is **not** implemented — see
[2026-09-13-account-aggregator-gmail-interaction-scope.md](2026-09-13-account-aggregator-gmail-interaction-scope.md)'s
"A spec assumption that no longer holds" section for why: Plan 1's `AccountAggregatorGuard` already
hard-blocks confirming any import (Gmail included) into an actively AA-linked account, verified
directly against `origin/main`, so that dialog would never render regardless of whether it's built.

## Global Constraints

- The new reconciliation pass touches **only** `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)` — it must not
  loosen or interact with the pre-existing Gmail-vs-CSV/PDF pass (still conservative, candidate-only)
  or Plan 2's AA-vs-manual pass (still candidate-only). This is the one pass in the whole
  reconciliation method that auto-excludes from totals off a fuzzy match — that is deliberate,
  narrow, and justified only by AA being a live bank feed, not a parsed document.
- Idempotent by construction: a Gmail row already resolved by *any* reconciliation mechanism
  (this pass on a prior run, or the exact-match pass) must be excluded from this pass's own
  candidate selection, mirroring the exact-match pass's own `t.getIsDuplicateOf() != null` guard.
  Not optional — see this plan's own review history for why (a second run must produce zero writes,
  not just correct-but-redundant ones).
- Bootstrap values only for the new pass's threshold/window — plain literals with a comment, not
  named constants, same discipline Plan 2 established for its own AA-vs-manual pass.
- `Account.primarySource` exposure is additive only — one new field on an existing DTO/type, no new
  endpoint, no behavior change to anything already consuming `AccountDto`/`Account`.
- No AI-attribution trailer in any commit message (repository rule, `CLAUDE.md`).

---

## File Structure

```
backend/src/main/java/com/finora/accounts/
  AccountDto.java                 (modify — add primarySource field)

backend/src/main/java/com/finora/service/
  ReconciliationService.java      (modify — new AA-vs-Gmail auto-exclude pass)

backend/openapi/openapi.json      (regenerate)
frontend/src/api/generated-types.ts    (regenerate)
mobile/src/api/generated-types.ts      (regenerate)
admin-portal/src/api/generated-types.ts (regenerate)

frontend/src/types/index.ts       (modify — add primarySource to Account)
frontend/src/pages/Import.tsx     (modify — AccountChoiceFields' <option> rendering)

backend/src/test/java/com/finora/accounts/
  AccountDtoTest.java             (new, or added to an existing AccountDto-adjacent test)
backend/src/test/java/com/finora/service/
  ReconciliationServiceTest.java  (modify — new test section, same file every prior AA pass used)
frontend/src/pages/
  Import.test.tsx                 (modify — new test case)
```

---

### Task 1: Expose `Account.primarySource` via `AccountDto`

**Files:**
- Modify: `backend/src/main/java/com/finora/accounts/AccountDto.java`
- Modify: `backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java` (5 direct
  `new AccountDto(...)` call sites need the new positional argument — verified via
  `grep -rn "new AccountDto(" backend/src/main/java backend/src/test/java`, only this file
  constructs it directly; every other caller goes through `AccountDto.from(...)`, which needs no
  change at its call sites once the one real constructor path is updated)
- Create/modify: a test asserting the new field round-trips from `Account` through `AccountDto.from`

**Interfaces:**
- Produces: `AccountDto.primarySource` — `String`, values `"MANUAL"`/`"ACCOUNT_AGGREGATOR"` (matches
  `Account.PrimarySource.name()`, same convention as the existing `accountType`/`status` fields,
  which are also plain strings, not the frontend's problem to know about a Java enum type).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.accounts;

import com.finora.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDtoPrimarySourceTest {

    @Test
    void defaultsToManualWhenTheAccountIsManuallySourced() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
        // Account.primarySource itself already defaults to MANUAL (Plan 1) -- this test is
        // about AccountDto actually surfacing it, not re-testing that default.

        AccountDto dto = AccountDto.from(account);

        assertThat(dto.primarySource()).isEqualTo("MANUAL");
    }

    @Test
    void surfacesAccountAggregatorWhenTheAccountIsAaLinked() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);

        AccountDto dto = AccountDto.from(account);

        assertThat(dto.primarySource()).isEqualTo("ACCOUNT_AGGREGATOR");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountDtoPrimarySourceTest`
Expected: FAIL to compile — `AccountDto.primarySource()` doesn't exist yet.

- [ ] **Step 3: Add the field**

In `AccountDto.java`, add the new record component (placed next to `status`, the other
computed-state string field) and thread it through the one real constructor path:

```java
        // Always ACTIVE today ...
        String status,

        // MANUAL or ACCOUNT_AGGREGATOR -- see Account.PrimarySource's own doc comment. Plain
        // string, not the Java enum type, same convention as accountType/status above: the
        // frontend account picker (Import.tsx) uses this to disable/label an AA-linked account
        // rather than let the user hit AccountAggregatorGuard's 409 blind.
        String primarySource,
```

```java
    public static AccountDto from(Account a, BankDto bank, Instant lastImportedAt,
                                   LocalDate lastStatementPeriodStart, LocalDate lastStatementPeriodEnd,
                                   int statementsCount, long transactionsCount) {
        return new AccountDto(a.getId(), a.getName(), a.getAccountType().name(),
                a.getBalance(), a.getCreditLimit(), a.getDueDate(), a.getInvestmentKind(),
                a.getAccountHolderName(), a.getAccountNumberMasked(),
                a.getBranchName(), a.getIfscCode(),
                bank,
                lastImportedAt, lastStatementPeriodStart, lastStatementPeriodEnd,
                statementsCount, transactionsCount,
                "ACTIVE",
                a.getPrimarySource().name(),
                a.getPrincipalAmount(), a.getInterestRate(), a.getMaturityDate(), a.getMaturityAmount(),
                a.getInstallmentAmount(), a.getInstallmentsPaid(), a.getInstallmentsTotal());
    }
```

Update the record's field list and the 5 direct `new AccountDto(...)` call sites in
`ImportServiceAskOnceTest.java` to insert `"MANUAL"` (all five construct hand-created,
non-AA-linked test accounts) at the matching position.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountDtoPrimarySourceTest,ImportServiceAskOnceTest`
Expected: PASS

- [ ] **Step 5: Regenerate the OpenAPI spec and client types**

`AccountDto` is a real response schema (the `/accounts` endpoint) — this is API-surface drift, not
optional. Same sequence Plan 2's own CI-drift fix used:

```bash
cd backend && ./mvnw -DskipTests package && bash scripts/generate-openapi-spec.sh
cd ../frontend && npm run generate:types
cd ../mobile && npm run generate:types
cd ../admin-portal && npm run generate:types
```

Diff each regenerated file afterward and confirm the change is exactly the new `primarySource`
field — nothing else. (Needs a reachable Postgres on 5432 with `finora/finora/finora` credentials;
reuse whatever's already running rather than starting a second one — see this repo's own
"Shared Docker Postgres port conflict" precedent if `docker compose up` collides with another
session's container.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/accounts/AccountDto.java \
        backend/src/test/java/com/finora/accounts/AccountDtoPrimarySourceTest.java \
        backend/src/test/java/com/finora/imports/ImportServiceAskOnceTest.java \
        backend/openapi/openapi.json \
        frontend/src/api/generated-types.ts \
        mobile/src/api/generated-types.ts \
        admin-portal/src/api/generated-types.ts
git commit -m "feat(backend): expose Account.primarySource on AccountDto"
```

---

### Task 2: Dedicated AA-vs-Gmail auto-exclude reconciliation pass

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java`
- Modify: `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java`

**Interfaces:**
- Produces: a new pass inside `reconcile(...)`, positioned immediately after Plan 2's AA-vs-manual
  pass (comment block "4b)") and before the CC-payment pass (comment block "5)") — call it "4c)".
  No new public method; this is entirely inside the existing private `reconcile` method, same as
  every other pass.

- [ ] **Step 1: Write the failing tests**

Add to `ReconciliationServiceTest.java`, in a new section immediately after the existing
"AA-vs-manual fuzzy near-duplicate matches" tests (Plan 2's own section):

```java
    // --- AA-vs-Gmail auto-exclude matches (Plan 3 of the Account Aggregator sync feature,
    // docs/superpowers/plans/2026-09-13-account-aggregator-gmail-interaction.md Task 2) ---

    @Test
    void highConfidenceAaGmailMatchAutoExcludesTheGmailRow() {
        UUID accountId = UUID.randomUUID();
        Transaction aa = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "UPI-SWIGGY-PAYMENT-REF123",
                Instant.parse("2026-09-02T10:00:00Z"));
        aa.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        Transaction gmail = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "UPI-SWIGGY-PAYMENT-REF123",
                Instant.parse("2026-09-02T11:00:00Z"));
        gmail.setSource(Transaction.Source.GMAIL_IMPORT);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(aa, gmail));

        reconciliationService.reconcileForUser(userId);

        // Auto-excluded -- unlike every other fuzzy pass in this file, this one DOES touch the
        // legacy columns, because AA is a live bank feed, not a parsed document (see the pass's
        // own comment for the full justification).
        assertThat(gmail.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.DUPLICATE);
        assertThat(gmail.getIsDuplicateOf()).isEqualTo(aa.getId());
        assertThat(aa.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void doesNotFireBelowTheHighConfidenceThreshold() {
        // Real, computed similarity -- verified with TextSimilarity.normalizedSimilarity directly
        // before writing this test, not assumed plausible-looking (see this plan's own review
        // history on why an unverified fixture string is a real trap here).
        UUID accountId = UUID.randomUUID();
        Transaction aa = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "UPI-SWIGGY-PAYMENT-REF123",
                Instant.parse("2026-09-02T10:00:00Z"));
        aa.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        // Clears Plan 2's own AA-vs-manual 0.6 threshold but must NOT clear this pass's stricter
        // one -- compute and confirm the actual similarity value before relying on it here.
        Transaction gmail = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "Swiggy order",
                Instant.parse("2026-09-02T11:00:00Z"));
        gmail.setSource(Transaction.Source.GMAIL_IMPORT);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(aa, gmail));

        reconciliationService.reconcileForUser(userId);

        assertThat(gmail.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(gmail.getIsDuplicateOf()).isNull();
    }

    @Test
    void secondRunIsANoOp() {
        // The idempotency requirement this plan's own review called out explicitly: a Gmail row
        // already resolved must not be re-matched, re-written, or re-counted as a change on a
        // later run.
        UUID accountId = UUID.randomUUID();
        Transaction aa = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "UPI-SWIGGY-PAYMENT-REF123",
                Instant.parse("2026-09-02T10:00:00Z"));
        aa.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        Transaction gmail = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 9, 2),
                new BigDecimal("450.00"), Transaction.Type.EXPENSE, "UPI-SWIGGY-PAYMENT-REF123",
                Instant.parse("2026-09-02T11:00:00Z"));
        gmail.setSource(Transaction.Source.GMAIL_IMPORT);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(aa, gmail));

        reconciliationService.reconcileForUser(userId);
        UUID resolvedTo = gmail.getIsDuplicateOf();
        org.mockito.Mockito.clearInvocations(transactionGraphService, auditService);

        reconciliationService.reconcileForUser(userId);

        assertThat(gmail.getIsDuplicateOf()).isEqualTo(resolvedTo);
        org.mockito.Mockito.verify(transactionGraphService, org.mockito.Mockito.never())
                .linkAll(org.mockito.ArgumentMatchers.anyList());
        // No audit row on a run that changed nothing -- same "recordedBecause" convention every
        // other pass in this file already relies on.
        org.mockito.Mockito.verify(auditService, org.mockito.Mockito.never())
                .record(any(), eq("RECONCILIATION_RUN"), any(), any(), any());
    }

    @Test
    void doesNotTouchTheExistingGmailVsBankPassesOwnCandidates() {
        // This pass must not widen or interact with the pre-existing Gmail-vs-CSV/PDF pass --
        // scoped strictly to (ACCOUNT_AGGREGATOR, GMAIL_IMPORT).
        UUID accountId = UUID.randomUUID();
        Transaction bankTxn = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 10),
                new BigDecimal("499.00"), Transaction.Type.EXPENSE, "AMZN MKTPLACE 4521",
                Instant.parse("2026-07-10T10:00:00Z"));
        bankTxn.setSource(Transaction.Source.CSV_IMPORT);
        Transaction gmailTxn = txn(UUID.randomUUID(), accountId, LocalDate.of(2026, 7, 11),
                new BigDecimal("499.00"), Transaction.Type.EXPENSE, "Amazon",
                Instant.parse("2026-07-11T09:00:00Z"));
        gmailTxn.setSource(Transaction.Source.GMAIL_IMPORT);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(bankTxn, gmailTxn));
        when(gmailReconciliationMatcher.findMatchAmongTransactions(gmailTxn, List.of(bankTxn)))
                .thenReturn(java.util.Optional.of(bankTxn));

        reconciliationService.reconcileForUser(userId);

        // The EXISTING Gmail-vs-bank pass still fires (candidate edge only) -- this new pass must
        // not additionally touch gmailTxn's legacy columns, since there's no ACCOUNT_AGGREGATOR
        // row in this fixture at all.
        assertThat(gmailTxn.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(gmailTxn.getIsDuplicateOf()).isNull();
    }
```

**Before implementing:** compute the real similarity for `"UPI-SWIGGY-PAYMENT-REF123"` vs
`"Swiggy order"` via `TextSimilarity.normalizedSimilarity` directly (same verification discipline
this plan's own review demanded of Plan 2's equivalent test) and confirm it genuinely falls below
whatever threshold Step 3 below picks — adjust either the fixture string or the threshold so the
"below threshold" test is proven, not assumed.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=ReconciliationServiceTest`
Expected: the four new tests FAIL (no such pass exists yet); the rest of the suite still passes.

- [ ] **Step 3: Add the pass**

Insert immediately after Plan 2's AA-vs-manual pass (after its closing `}`, before the "5) Credit
card payment matches" comment):

```java
        // 4c) AA-vs-Gmail auto-exclude matches -- design spec
        // docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Reconciliation" §2.
        // The ONE pass in this method that auto-excludes off a fuzzy text match -- every other
        // fuzzy pass (the Gmail-vs-bank pass above, Plan 2's AA-vs-manual pass) deliberately stays
        // candidate-only, because neither side of those matches is fully trusted. AA changes that:
        // it is a live bank feed, not a parsed document, which is what licenses treating a
        // high-confidence match here differently. Direction is fixed, unlike the exact-match pass's
        // SourceTrust-based canonical selection: the AA row is always canonical, the GMAIL_IMPORT
        // row is always the one marked DUPLICATE -- there is no case where a Gmail receipt outranks
        // a bank feed.
        //
        // Idempotent by construction: candidate selection excludes any Gmail row already resolved
        // by ANY reconciliation mechanism (isDuplicateOf != null), not merely one this pass itself
        // wrote on a prior run -- the exact-match pass resolving a row first must also be respected,
        // mirroring that pass's own top-of-loop guard exactly, since both write to the same legacy
        // columns. Without this, a resolved row would be re-matched and re-written on every
        // subsequent run: not incorrect, but it would mark changedSomething true forever.
        //
        // Threshold/window are BOOTSTRAP VALUES ONLY, same discipline as Plan 2's own thresholds --
        // deliberately NOT promoted to named constants until real data validates one. Stricter than
        // Plan 2's AA-vs-manual 0.6 (spec: "a stricter threshold than the review-only Gmail pass
        // uses") -- 0.85 here is a starting point, not a tuned value.
        //
        // No accountId-null defensive check needed: transactions.account_id has been
        // NOT NULL REFERENCES accounts(id) since V1__init_schema.sql, the very first migration --
        // confirmed, not assumed, before writing this pass.
        List<Transaction> unresolvedGmailExpenses = all.stream()
                .filter(t -> t.getSource() == Transaction.Source.GMAIL_IMPORT)
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .filter(t -> t.getIsDuplicateOf() == null)
                .toList();
        if (!unresolvedGmailExpenses.isEmpty()) {
            Map<BigDecimal, List<Transaction>> aaExpensesByAmount = all.stream()
                    .filter(t -> t.getSource() == Transaction.Source.ACCOUNT_AGGREGATOR)
                    .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                    .collect(java.util.stream.Collectors.groupingBy(Transaction::getAmount));
            int aaGmailWindowDays = 3; // bootstrap value -- see comment above
            double aaGmailSimilarityThreshold = 0.85; // bootstrap value -- see comment above
            for (Transaction gmailTxn : unresolvedGmailExpenses) {
                List<Transaction> aaCandidates = aaExpensesByAmount
                        .getOrDefault(gmailTxn.getAmount(), List.of()).stream()
                        .filter(t -> Math.abs(ChronoUnit.DAYS.between(gmailTxn.getTxnDate(), t.getTxnDate())) <= aaGmailWindowDays)
                        .toList();
                if (aaCandidates.isEmpty()) continue;

                aaCandidates.stream()
                        .filter(candidate -> com.finora.util.TextSimilarity.normalizedSimilarity(
                                gmailTxn.getDescription(), candidate.getDescription()) >= aaGmailSimilarityThreshold)
                        .max(Comparator.<Transaction>comparingDouble(
                                        candidate -> com.finora.util.TextSimilarity.normalizedSimilarity(
                                                gmailTxn.getDescription(), candidate.getDescription()))
                                .thenComparing(candidate -> -Math.abs(
                                        ChronoUnit.DAYS.between(gmailTxn.getTxnDate(), candidate.getTxnDate()))))
                        .ifPresent(matched -> {
                            gmailTxn.setIsDuplicateOf(matched.getId());
                            gmailTxn.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
                            long daysApart = Math.abs(ChronoUnit.DAYS.between(gmailTxn.getTxnDate(), matched.getTxnDate()));
                            Map<String, Object> explanation = new java.util.LinkedHashMap<>();
                            explanation.put("type", "ACCOUNT_AGGREGATOR_GMAIL_AUTO_EXCLUDE");
                            explanation.put("matchedTransactionId", matched.getId().toString());
                            explanation.put("daysApart", daysApart);
                            gmailTxn.setReconciliationExplanation(explanation);
                            dirty.add(gmailTxn);
                            // Status forced to AUTO_CONFIRMED rather than derived via statusFor(...):
                            // MatchType.MERCHANT_AND_AMOUNT's own base score (0.90) combined with a
                            // nonzero date_decay can still land under NEEDS_REVIEW_THRESHOLD, which
                            // would make the graph edge read "CANDIDATE, needs review" while the
                            // legacy columns above already fully excluded the row from totals --
                            // an inconsistent, confusing state. The high-confidence gate above IS
                            // the review; the edge's status should say so, not re-litigate it via a
                            // formula tuned for passes that don't already auto-exclude.
                            int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                                    gmailTxn.getAmount(), BigDecimal.ZERO, daysApart, aaGmailWindowDays);
                            pendingEdges.add(new TransactionGraphService.PendingEdge(userId, gmailTxn.getId(), matched.getId(),
                                    TransactionRelationship.RelationshipType.DUPLICATE, gmailTxn.getAmount(), confidence,
                                    SourceTrust.of(gmailTxn.getSource()), TransactionRelationship.Status.AUTO_CONFIRMED,
                                    TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
                        });
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=ReconciliationServiceTest`
Expected: PASS, all tests (existing + new).

Then run the FULL backend suite — this task edits the same large, heavily-shared method Plan 2's
Task 7 did, and the highest-risk regression is an interaction with one of the other five passes now
sitting alongside it.

Run: `cd backend && ./mvnw test`
Expected: PASS, zero regressions.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/ReconciliationService.java \
        backend/src/test/java/com/finora/service/ReconciliationServiceTest.java
git commit -m "feat(backend): add AA-vs-Gmail auto-exclude reconciliation pass"
```

---

### Task 3: Informational UI in the shared account picker

**Files:**
- Modify: `frontend/src/types/index.ts`
- Modify: `frontend/src/pages/Import.tsx`
- Modify: `frontend/src/pages/Import.test.tsx`

**Interfaces:**
- Consumes: `Account.primarySource` (Task 1, now flowing through `generated-types.ts` and the
  hand-written `Account` interface both).
- Produces: in `AccountChoiceFields`'s existing `<select>`, an AA-linked account's `<option>` is
  `disabled` with a label suffix — the exact copy is a placeholder pending product/copy review (see
  the scope doc's own "open items"), something like `"{name} ({accountType}) — Bank Sync active"`.

- [ ] **Step 1: Add the type field**

In `frontend/src/types/index.ts`, add to the `Account` interface, next to `status`:

```typescript
  // MANUAL or ACCOUNT_AGGREGATOR -- see AccountDto's own comment on the backend. Used by
  // Import.tsx's account picker to disable/label an AA-linked account instead of only surfacing
  // AccountAggregatorGuard's 409 after the user has already tried to confirm into it.
  primarySource: 'MANUAL' | 'ACCOUNT_AGGREGATOR';
```

- [ ] **Step 2: Write the failing test**

**Read `frontend/src/pages/Import.test.tsx` first** to find the existing render harness that gets
the review screen (with the account picker) on screen for a mocked `accountsApi.list()` response —
several `describe` blocks in that file already do this for other account-picker-adjacent
assertions; reuse the real setup verbatim rather than inventing a new one. The new test's shape:

```typescript
describe('Import — account picker signals an AA-linked account', () => {
  it('disables and labels an ACCOUNT_AGGREGATOR-sourced account in the existing-account dropdown', async () => {
    // Arrange: mock accountsApi.list() to return one MANUAL and one ACCOUNT_AGGREGATOR account --
    // reuse whatever this file's existing account-picker tests already do to reach the review
    // screen (upload a file, wait for staging, etc.), then:

    const options = screen.getAllByRole('option');
    const aaOption = options.find((o) => o.textContent?.includes('Bank Sync active'));
    expect(aaOption).toBeDefined();
    expect(aaOption).toBeDisabled();

    const manualOption = options.find((o) => o.textContent?.includes('HDFC Savings'));
    expect(manualOption).not.toBeDisabled();
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd frontend && npm test -- Import.test.tsx`
Expected: FAIL — no disabled state or label exists yet.

- [ ] **Step 4: Update the account picker**

In `Import.tsx`'s `AccountChoiceFields`, change the `<option>` mapping:

```tsx
          {existingAccounts.map((a) => (
            <option
              key={a.id}
              value={a.id}
              disabled={a.primarySource === 'ACCOUNT_AGGREGATOR'}
            >
              {a.name} ({a.accountType.replace('_', ' ')})
              {a.primarySource === 'ACCOUNT_AGGREGATOR' ? ' — Bank Sync active' : ''}
            </option>
          ))}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd frontend && npm test -- Import.test.tsx`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add frontend/src/types/index.ts frontend/src/pages/Import.tsx frontend/src/pages/Import.test.tsx
git commit -m "feat(frontend): disable and label AA-linked accounts in the import account picker"
```

---

## After Task 3: run the full suite

```bash
cd backend && ./mvnw test
cd frontend && npm test
```

Expected: PASS, zero failures. Per this project's standing "mandatory post-implementation
verification" rule, do not stop at green tests — reread the scope doc's "Out of scope" section
afterward and confirm nothing there was accidentally half-built (no confirm-time dialog code, no
guard changes for the outage hatch, no consent-management UI beyond the one picker indicator).

## Addendum: corrections found during implementation

Found during execution, not part of any task's original write-up:

1. **Extra frontend fixture updates required by Task 1's type change (found while executing
   Task 3).** Making `Account.primarySource` a required field broke compilation at 4 existing test
   fixture sites the plan didn't anticipate: `Import.test.tsx` (3 sites — an inline `accountFor`
   helper and two `existingAccount()` factories) and `Ledger.test.tsx` (1 site). Found via the
   actual compiler (`npm run build`), not the grep-based guess the plan's own investigation used —
   the grep found 7 candidate sites across 5 files, but 3 of those turned out to use `as Account`
   type casts that bypass strict checking, so only 4 were real. Fixed by adding
   `primarySource: 'MANUAL'` to each, matching the existing convention each already used for
   `status: 'ACTIVE'`.

2. **Fixed: a real, confirmed null-pointer risk in `TextSimilarity.normalizedSimilarity`,
   affecting Plan 2's already-merged pass too, not just this one.** `transactions.description` is
   nullable at the DB level (`VARCHAR(500)`, no `NOT NULL` — confirmed against
   `V1__init_schema.sql`, not assumed), and genuinely possible to be null for an AA-sourced row
   specifically (`SetuFiDataTransaction.narration()` is documented elsewhere in this codebase as
   unverified against a real Setu sandbox response). Neither this pass nor Plan 2's AA-vs-manual
   pass null-checked before calling `TextSimilarity.normalizedSimilarity` directly on raw
   descriptions — an unguarded `.length()` there threw an uncaught `NullPointerException` inside
   `reconcileForUser`, which has eight production callers (transaction create/update/delete, every
   import path). Fixed at the shared-utility level (returns `0.0` — "no similarity" — for a null
   argument, never a match on absent data), which protects both passes at once rather than
   duplicating a guard in each. Verified concretely: wrote the regression test first, confirmed it
   failed with a real `NullPointerException` (not a plausible-sounding assumption), fixed the
   utility, confirmed it passed, then reverted the fix and confirmed the test failed again before
   restoring it. Two regression tests: `TextSimilarityTest.aNullDescriptionOnEitherSideScoresZeroRatherThanThrowing`
   and `ReconciliationServiceTest.doesNotThrowWhenTheAaCandidatesDescriptionIsNull`.

3. **Fixed: `matchExistingAccount` could silently auto-select an AA-linked account, bypassing
   Task 3's own disabled-dropdown-option signal entirely.** Found by asking a genuinely adversarial
   question about Task 3's own change: does disabling the `<option>` actually stop an AA-linked
   account from being selected, or only stop the user from selecting it *manually*? Traced
   `matchExistingAccount` (`frontend/src/lib/accountMatch.ts`) and confirmed it never considered
   `primarySource` at all — a confident match (same bank + account number, or the sole account of
   that type) would preselect `accountChoice: 'existing'` and `selectedAccountId` pointing at an
   AA-linked account before the user ever opens the dropdown, defeating the entire point of Task 3
   ("the user learns before they submit" — moot if they never had to look). Fixed by filtering AA-
   linked accounts out of the candidate pool before any matching rule runs, so they can never win
   any of the function's existing rules. Two regression tests added, both verified to fail before
   the fix: `never matches an AA-linked account, even with an otherwise-conclusive account number
   match` and `never matches an AA-linked account via the single-same-type-at-bank fallback`.

4. **Found, deliberately not fixed here: the identical dead-end interaction exists via
   `StatementHistory.tsx`'s "Reimport" button.** Same root cause as finding 3 (a path that sets
   `accountChoice`/`selectedAccountId` without going through the account picker at all), but this
   one is genuinely out of Plan 3's scope per this plan's own scope doc ("Any other reconciliation
   UI beyond the one account-picker indicator... is Plan 5") — it needs a new field on a different
   backend DTO (`AccountStatementGroup`, not `AccountDto`) and touches a different page entirely.
   Flagged as a separate follow-up task rather than silently expanding this plan's scope or silently
   leaving it unstated.
