# Account Aggregator bank-side mutation handling — Implementation Plan (Plan 6, Track B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Plan 2's deliberate insert-only gap: a bank-side correction (a pending amount that
changes on posting) or a vanished pre-auth is currently invisible forever once its fetch window has
passed. Detect both via a sliding-window re-fetch + three-way diff (`{new, changed, missing}`),
**without ever silently mutating a transaction the user may already have categorized or reconciled**.

**Depends on:** Track A (`AccountAggregatorReconciliationSweepService`, shipped in #1444) — not a
code dependency, but the same `syncSinceLastAttempt` range-computation method this plan modifies is
shared by both the `data.ready` webhook path and Track A's sweep, so both benefit from the
sliding-window change made here.

**Unblocked 2026-09-13** — see the companion scope doc's "Decisions made (round 3)" for the two
product/technical decisions that were blocking this:

1. **Correction semantics: preserve + review task.** A detected `changed` or `missing` row never
   overwrites the existing `Transaction` row. The event is written to `AuditLog`; the row is flagged
   via a new dedicated field, `pendingBankCorrection`, and surfaces in Ledger for the user to review.
2. **`externalTxnId` reliability: design around the assumption, flag the risk.** Built now, assuming
   `externalTxnId` is populated and stable where present. A transaction with a null or changed
   `externalTxnId` degrades to "looks like a new row" — exactly today's behavior, not a regression.

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)
— "Data corrections and mutations" section. See also
[2026-09-13-account-aggregator-bank-side-mutation-handling-scope.md](2026-09-13-account-aggregator-bank-side-mutation-handling-scope.md)
for the full scoping history (three rounds of review) this plan was written against.

**Tech Stack:** Spring Boot, JPA/Hibernate, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ, React/
TypeScript — same as every prior AA plan.

## Global Constraints

- **Never mutate an existing `Transaction` row's `amount`/`description`/`txnDate`/etc. from diff
  logic.** A `changed` or `missing` detection only ever sets `pendingBankCorrection = true` and
  writes an `AuditLog` row. If any task below is tempted to call a setter for a financial field from
  inside the diff path, stop — that is exactly the silent-overwrite behavior round 3's decision
  rejected.
- **`CREDIT_CARD` stays out of scope**, same as every prior plan — this only ever runs for `DEPOSIT`
  links (unchanged gate, already enforced upstream of `SetuDataFetchService`).
- **The sliding-window size is a documented placeholder, not a real number** (see Task 4) — the
  scope doc is explicit that a real value needs Setu sandbox evidence this environment doesn't have.
  Ship a conservative default behind a named config key so it can be tuned later without a code
  change, and say so in the config's own comment.
- **No automatic resolution of a `missing` row.** v1 is flag-only, per the scope doc's own "missing
  is the harder problem" reasoning. Do not add grace-period or second-confirming-fetch logic here.
- **This plan does not build a general-purpose review-queue UI.** It reuses Ledger's existing
  "badge on the row" pattern (`needsCategoryReview`'s own precedent) with a distinct badge, not a new
  page or endpoint family.

- [ ] **Step 0: Confirm the landscape hasn't shifted underneath this plan**

Run: `git fetch origin && git log --oneline origin/main -5` and confirm Track A (#1444) is present
and nothing else in `integrations/setu/` has changed since. Re-read `SetuDataFetchService.java`,
`AccountAggregatorTransactionMapper.java`, `Transaction.java`, and `TransactionRepository.java` fresh
before starting Task 1 — this plan's own quoted code is a snapshot from 2026-09-13, not a substitute
for the real files.

---

### Task 1: `pendingBankCorrection` field — migration, entity, DTO

**Files:**
- Create: `backend/src/main/resources/db/migration/V204__transaction_pending_bank_correction.sql`
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java`
- Modify: `backend/src/main/java/com/finora/transactions/TransactionDto.java`
- Modify: `frontend/src/types/index.ts`

**Why a new field, not reusing `needsCategoryReview`:** the scope doc's round-3 decision leans on
`needsCategoryReview`'s *pattern* (boolean flag, surfaced as a Ledger badge, cleared by an explicit
user action) — not its literal column. `needsCategoryReview` means "the category is an unconfirmed
guess, please look." A bank correction means something structurally different — "the bank changed
this row's own values, please look" — and conflating the two under one badge would tell a user
"needs review" without telling them *why*, which defeats the whole point of round 3's decision (the
user should see old vs. new, not just a generic flag). A dedicated field keeps the two concerns
independently query-able and independently clearable.

- [ ] **Step 1: Migration**

```sql
-- V204__transaction_pending_bank_correction.sql
-- Plan 6, Track B. Flags a transaction whose upstream (Account Aggregator) value was detected as
-- changed or missing on a later re-fetch, without ever overwriting the row itself -- see
-- docs/superpowers/plans/2026-09-13-account-aggregator-bank-side-mutation-handling.md. Distinct
-- from needs_category_review (V-whatever added that): that flag means "the category is an
-- unconfirmed guess"; this one means "the bank's own reported values may have changed since this
-- row was created." Deliberately NOT NULL DEFAULT FALSE, same as every other review-flag column in
-- this table, so every existing row backfills to "no correction pending" with no ambiguity.
ALTER TABLE transactions ADD COLUMN pending_bank_correction BOOLEAN NOT NULL DEFAULT FALSE;
```

Confirm `V204` is still free before writing this file: `ls backend/src/main/resources/db/migration
| sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -3` — per this repo's CLAUDE.md, never assume a
version number is free without checking fresh.

- [ ] **Step 2: Entity field**

Add alongside `needsCategoryReview` in `Transaction.java` (near line 173):

```java
    // Plan 6, Track B -- see V204's migration comment. Distinct from needsCategoryReview: this
    // means "the bank's own reported value for this row may have changed or the row may have
    // vanished on a later re-fetch," never "the category guess is unconfirmed." Set only by
    // AccountAggregatorTransactionDiffService, cleared only by an explicit user acknowledgment
    // (TransactionService.acknowledgeBankCorrection) -- never touched by category edits, unlike
    // needsCategoryReview, because acknowledging a category is not the same act as acknowledging a
    // value correction the user hasn't necessarily even seen yet.
    @Column(name = "pending_bank_correction", nullable = false)
    private boolean pendingBankCorrection = false;
```

Add the getter/setter pair next to `isNeedsCategoryReview`/`setNeedsCategoryReview`:

```java
    public boolean isPendingBankCorrection() { return pendingBankCorrection; }
    public void setPendingBankCorrection(boolean pendingBankCorrection) { this.pendingBankCorrection = pendingBankCorrection; }
```

- [ ] **Step 3: DTO**

Add `boolean pendingBankCorrection` to the `TransactionDto` record's field list (after
`needsCategoryReview`), and to `from(...)`:

```java
        boolean needsCategoryReview,
        boolean pendingBankCorrection,
        boolean categoryManuallySet,
```

```java
                t.isNeedsCategoryReview(), t.isPendingBankCorrection(), t.isCategoryManuallySet(),
```

- [ ] **Step 4: Frontend type**

In `frontend/src/types/index.ts`, add `pendingBankCorrection: boolean;` next to the existing
`needsCategoryReview: boolean;` (line ~95). Regenerate `frontend/src/api/generated-types.ts` via
this repo's existing OpenAPI contract process (`docs/engineering/openapi-contracts.md` — do not
hand-edit the generated file; the OpenAPI drift check is a blocking CI gate per this repo's recent
history).

- [ ] **Step 5: Compile check**

Run: `cd backend && ./mvnw compile -q` — expect success (no behavior yet, just the new field
threaded through). This task deliberately has no new test of its own: the field is inert until
Task 3 sets it and Task 7's IT reads it.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V204__transaction_pending_bank_correction.sql \
        backend/src/main/java/com/finora/entity/Transaction.java \
        backend/src/main/java/com/finora/transactions/TransactionDto.java \
        frontend/src/types/index.ts frontend/src/api/generated-types.ts
git commit -m "feat(backend): add pendingBankCorrection field to Transaction"
```

---

### Task 2: Repository query for missing-row detection

**Files:**
- Modify: `backend/src/main/java/com/finora/repository/TransactionRepository.java`
- Modify: `backend/src/test/java/com/finora/repository/TransactionRepositoryIT.java`

**Interfaces:**
- Produces: `List<Transaction> findByAccountIdAndSourceAndTxnDateBetween(UUID accountId,
  Transaction.Source source, LocalDate from, LocalDate to)` — every AA-sourced row this account
  already has in the re-fetched window, the set Task 3's diff compares the fresh fetch against.

**Why scoped by `source`:** the window may also contain `MANUAL`/`CSV_IMPORT`/`GMAIL_IMPORT` rows
for the same account (a user who manually logged a cash withdrawal that happens to fall in the same
date range). Those are never candidates for "missing from Setu's re-fetch" — they were never *in*
Setu's data to begin with. Scoping by `source = ACCOUNT_AGGREGATOR` is what keeps missing-detection
from flagging unrelated rows.

- [ ] **Step 1: Write the failing test**

Add to `TransactionRepositoryIT` (mirrors the class's existing fixture-per-test style):

```java
    @Test
    void findsOnlyAccountAggregatorRowsInTheDateRange() {
        Transaction aaInRange = new Transaction();
        aaInRange.setUserId(userId);
        aaInRange.setAccountId(accountId);
        aaInRange.setCategoryId(categoryId);
        aaInRange.setTxnDate(LocalDate.now().minusDays(2));
        aaInRange.setAmount(new BigDecimal("100.00"));
        aaInRange.setTxnType(Transaction.Type.EXPENSE);
        aaInRange.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        transactionRepository.save(aaInRange);

        Transaction manualInRange = new Transaction();
        manualInRange.setUserId(userId);
        manualInRange.setAccountId(accountId);
        manualInRange.setCategoryId(categoryId);
        manualInRange.setTxnDate(LocalDate.now().minusDays(2));
        manualInRange.setAmount(new BigDecimal("50.00"));
        manualInRange.setTxnType(Transaction.Type.EXPENSE);
        manualInRange.setSource(Transaction.Source.MANUAL);
        transactionRepository.save(manualInRange);

        Transaction aaOutOfRange = new Transaction();
        aaOutOfRange.setUserId(userId);
        aaOutOfRange.setAccountId(accountId);
        aaOutOfRange.setCategoryId(categoryId);
        aaOutOfRange.setTxnDate(LocalDate.now().minusDays(30));
        aaOutOfRange.setAmount(new BigDecimal("200.00"));
        aaOutOfRange.setTxnType(Transaction.Type.EXPENSE);
        aaOutOfRange.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        transactionRepository.save(aaOutOfRange);

        List<Transaction> found = transactionRepository.findByAccountIdAndSourceAndTxnDateBetween(
                accountId, Transaction.Source.ACCOUNT_AGGREGATOR,
                LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(found).extracting(Transaction::getId).containsExactly(aaInRange.getId());
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=TransactionRepositoryIT#findsOnlyAccountAggregatorRowsInTheDateRange`
Expected: FAIL to compile — the method doesn't exist yet.

- [ ] **Step 3: Add the method**

In `TransactionRepository.java`, alongside `findByUserIdAndAccountIdIn`:

```java
    List<Transaction> findByAccountIdAndSourceAndTxnDateBetween(
            UUID accountId, Transaction.Source source, LocalDate from, LocalDate to);
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=TransactionRepositoryIT#findsOnlyAccountAggregatorRowsInTheDateRange`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/repository/TransactionRepository.java \
        backend/src/test/java/com/finora/repository/TransactionRepositoryIT.java
git commit -m "feat(backend): add findByAccountIdAndSourceAndTxnDateBetween for AA diff detection"
```

---

### Task 3: `AccountAggregatorTransactionDiffService` — the three-way diff

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffService.java`
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffServiceTest.java`

**Interfaces:**
- Produces: `DiffResult diff(UUID userId, UUID accountId, LocalDate from, LocalDate to,
  List<SetuFiDataTransaction> fetched)` where `DiffResult` is a small record: `List<Transaction>
  newTransactions` (unsaved, mirrors `mapNew`'s existing return contract so `SetuDataFetchService`
  needs a minimal change in Task 4), `int changed`, `int missing`.

**Why a new class, not extending `AccountAggregatorTransactionMapper`:** `mapNew` is Plan 2's
already-shipped, already-tested insert-only path, explicitly documented as "a changed or vanished
upstream transaction is Plan 6's concern, not this class's." Rather than growing `mapNew` into
something that also updates/flags, this plan adds a class with a clearly wider contract (it needs
the account's *existing* AA rows in the window, `mapNew` never needed persisted state beyond
existence checks) and leaves `mapNew` as a private implementation detail this class now owns.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountAggregatorTransactionDiffServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final LocalDate from = LocalDate.now().minusDays(14);
    private final LocalDate to = LocalDate.now();

    @Test
    void aBrandNewTxnIdIsReportedAsNew() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of());
        when(transactions.existsByAccountIdAndExternalTxnId(any(), any())).thenReturn(false);
        when(transactions.existsByAccountIdAndTransactionFingerprint(any(), any())).thenReturn(false);

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-new", "DEBIT", new BigDecimal("100.00"),
                        LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                        "Coffee shop", new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).hasSize(1);
        assertThat(result.changed()).isZero();
        assertThat(result.missing()).isZero();
        verifyNoInteractions(auditService);
    }

    @Test
    void sameTxnIdDifferentAmountIsChangedNotOverwritten() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction existing = new Transaction();
        existing.setId(UUID.randomUUID());
        existing.setUserId(userId);
        existing.setAccountId(accountId);
        existing.setExternalTxnId("txn-corrected");
        existing.setAmount(new BigDecimal("500.00"));
        existing.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(existing));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-corrected", "DEBIT", new BigDecimal("700.00"),
                        LocalDate.now().minusDays(1), LocalDate.now().minusDays(1),
                        "Corrected narration", new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.changed()).isEqualTo(1);
        // The existing row's OWN amount is untouched -- this is the assertion that actually matters
        // for round 3's "preserve, don't overwrite" decision.
        assertThat(existing.getAmount()).isEqualByComparingTo("500.00");
        assertThat(existing.isPendingBankCorrection()).isTrue();
        verify(transactions).save(existing);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED"),
                eq("Transaction"), eq(existing.getId()), anyMap());
    }

    @Test
    void sameTxnIdSameValuesIsANoOp() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction existing = new Transaction();
        existing.setId(UUID.randomUUID());
        existing.setAccountId(accountId);
        existing.setExternalTxnId("txn-unchanged");
        existing.setAmount(new BigDecimal("500.00"));
        existing.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(existing));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of(
                new SetuFiDataTransaction("txn-unchanged", "DEBIT", new BigDecimal("500.00"),
                        existing.getTxnDate(), existing.getTxnDate(),
                        existing.getDescription(), new BigDecimal("900.00"), null)));

        assertThat(result.newTransactions()).isEmpty();
        assertThat(result.changed()).isZero();
        assertThat(existing.isPendingBankCorrection()).isFalse();
        verifyNoInteractions(auditService);
    }

    @Test
    void anExistingRowWithATxnIdAbsentFromTheFreshFetchIsMissing() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction vanished = new Transaction();
        vanished.setId(UUID.randomUUID());
        vanished.setUserId(userId);
        vanished.setAccountId(accountId);
        vanished.setExternalTxnId("txn-vanished");
        vanished.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(vanished));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of());

        assertThat(result.missing()).isEqualTo(1);
        assertThat(vanished.isPendingBankCorrection()).isTrue();
        verify(transactions).save(vanished);
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_TRANSACTION_MISSING"),
                eq("Transaction"), eq(vanished.getId()), anyMap());
    }

    @Test
    void anExistingRowWithNoExternalTxnIdIsNeverReportedAsMissing() {
        // The identity ceiling the scope doc names: without a reliable externalTxnId, "this exact
        // row disappeared" is indistinguishable from "we never had a stable way to track it" --
        // flagging it would be a false positive on exactly the rows least able to support the claim.
        TransactionRepository transactions = mock(TransactionRepository.class);
        AuditService auditService = mock(AuditService.class);
        Transaction noStableId = new Transaction();
        noStableId.setId(UUID.randomUUID());
        noStableId.setAccountId(accountId);
        noStableId.setExternalTxnId(null);
        noStableId.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        when(transactions.findByAccountIdAndSourceAndTxnDateBetween(
                eq(accountId), eq(Transaction.Source.ACCOUNT_AGGREGATOR), eq(from), eq(to)))
                .thenReturn(List.of(noStableId));

        AccountAggregatorTransactionDiffService diffService =
                new AccountAggregatorTransactionDiffService(transactions, auditService);

        var result = diffService.diff(userId, accountId, from, to, List.of());

        assertThat(result.missing()).isZero();
        assertThat(noStableId.isPendingBankCorrection()).isFalse();
        verifyNoInteractions(auditService);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorTransactionDiffServiceTest`
Expected: FAIL to compile — the class doesn't exist yet.

- [ ] **Step 3: Implement the diff service**

```java
package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The three-way diff -- {new, changed, missing} -- that replaces AccountAggregatorTransactionMapper
 * .mapNew's pure insert-only behavior for the sliding-window re-fetch path. See this plan's own
 * "Global Constraints": a changed or missing row NEVER has its own amount/narration/etc. mutated --
 * only pendingBankCorrection is set and an AuditLog row written. The identity ceiling from the
 * design spec applies throughout: a row can only be detected as "changed" or "missing" if it has a
 * non-null externalTxnId, since transactionFingerprint itself changes the moment amount or
 * narration does (see fingerprint()'s own doc comment) and so cannot serve as the "same real-world
 * transaction" signal a correction needs.
 */
@Component
public class AccountAggregatorTransactionDiffService {

    private final TransactionRepository transactionRepository;
    private final AuditService auditService;

    public AccountAggregatorTransactionDiffService(TransactionRepository transactionRepository,
                                                     AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
    }

    public record DiffResult(List<Transaction> newTransactions, int changed, int missing) {}

    public DiffResult diff(UUID userId, UUID accountId, LocalDate from, LocalDate to,
                            List<SetuFiDataTransaction> fetched) {
        List<Transaction> existing = transactionRepository.findByAccountIdAndSourceAndTxnDateBetween(
                accountId, Transaction.Source.ACCOUNT_AGGREGATOR, from, to);
        Map<String, Transaction> existingByTxnId = new HashMap<>();
        for (Transaction t : existing) {
            if (t.getExternalTxnId() != null) {
                existingByTxnId.put(t.getExternalTxnId(), t);
            }
        }

        List<Transaction> newTransactions = new ArrayList<>();
        Set<String> seenTxnIds = new HashSet<>();
        int changed = 0;

        for (SetuFiDataTransaction source : fetched) {
            if (source.txnId() != null) {
                seenTxnIds.add(source.txnId());
            }
            String fingerprint = AccountAggregatorTransactionMapper.fingerprint(accountId, source);

            Transaction matched = source.txnId() != null ? existingByTxnId.get(source.txnId()) : null;
            if (matched != null) {
                if (!fingerprint.equals(matched.getTransactionFingerprint())) {
                    matched.setPendingBankCorrection(true);
                    transactionRepository.save(matched);
                    auditService.record(userId, "ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED", "Transaction",
                            matched.getId(), Map.of(
                                    "previousAmount", matched.getAmount(),
                                    "newAmount", source.amount(),
                                    "previousNarration", matched.getDescription(),
                                    "newNarration", source.narration()));
                    changed++;
                }
                // Identical values -- already seen, nothing to do. Matches mapNew's old skip.
                continue;
            }

            if (source.txnId() != null
                    && transactionRepository.existsByAccountIdAndExternalTxnId(accountId, source.txnId())) {
                continue; // Seen outside this window (e.g. re-fetch overlap) -- not this window's concern.
            }
            if (transactionRepository.existsByAccountIdAndTransactionFingerprint(accountId, fingerprint)) {
                continue; // No reliable txnId to detect a correction against -- the identity ceiling.
            }

            Transaction txn = new Transaction();
            txn.setUserId(userId);
            txn.setAccountId(accountId);
            txn.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
            txn.setExternalTxnId(source.txnId());
            txn.setTransactionFingerprint(fingerprint);
            txn.setTxnDate(source.transactionDate() != null ? source.transactionDate() : source.valueDate());
            txn.setAmount(source.amount());
            txn.setTxnType("CREDIT".equalsIgnoreCase(source.type())
                    ? Transaction.Type.INCOME : Transaction.Type.EXPENSE);
            txn.setDescription(source.narration());
            txn.setBalanceAfter(source.currentBalance());
            txn.setReferenceNumber(source.reference());
            newTransactions.add(txn);
        }

        int missing = 0;
        for (Transaction t : existing) {
            if (t.getExternalTxnId() == null) {
                continue; // No stable identity -- never flagged as missing, see the test's own doc comment.
            }
            if (!seenTxnIds.contains(t.getExternalTxnId()) && !t.isPendingBankCorrection()) {
                t.setPendingBankCorrection(true);
                transactionRepository.save(t);
                auditService.record(userId, "ACCOUNT_AGGREGATOR_TRANSACTION_MISSING", "Transaction",
                        t.getId(), Map.of(
                                "amount", t.getAmount(),
                                "narration", t.getDescription() == null ? "" : t.getDescription(),
                                "txnDate", t.getTxnDate().toString()));
                missing++;
            }
        }

        return new DiffResult(newTransactions, changed, missing);
    }
}
```

**Note on `fingerprint(...)` visibility:** `AccountAggregatorTransactionMapper.fingerprint` is
currently `private static`. Change it to `static` (package-private) so this class can reuse the
*exact* same fingerprint computation — two independently-drifting copies of that hash is exactly the
bug class Track A's own scope doc research flagged for `@Scheduled` sweeps, and applies identically
here. Do not duplicate the method body.

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorTransactionDiffServiceTest,AccountAggregatorTransactionMapperTest`
Expected: PASS — including the existing mapper test, unaffected by the visibility change.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffService.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorTransactionMapper.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffServiceTest.java
git commit -m "feat(backend): add AccountAggregatorTransactionDiffService three-way diff"
```

---

### Task 4: Sliding-window fetch range + wire the diff into `SetuDataFetchService`

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceTest.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceSyncSinceLastAttemptTest.java`

**The two changes, and why both are needed together:**

1. **`syncSinceLastAttempt`'s range must never be narrower than the sliding window**, or a
   correction landing inside a window already fetched-and-moved-past stays invisible — exactly the
   gap this whole plan exists to close. Today: `from = lastSyncedAt + 1 day`. New: `from =
   min(lastSyncedAt + 1 day, to - slidingWindowDays)` — i.e. never go forward of the window floor,
   only ever wider.
2. **`sync()` must run the diff even when zero "new" transactions come back.** Today,
   `reconciliationService.reconcileForImport` only runs `if (!newTransactions.isEmpty())` — but a
   `changed`/`missing` detection can fire with the *new* count at zero (nothing new, but something in
   the window changed). The diff must run unconditionally on every successful fetch.

**Known, accepted cost tradeoff (flagged, not silently absorbed — matches the scope doc's
"Decisions still needed" item 3):** because the window floor no longer depends on `lastSyncedAt`, a
`data.ready` webhook firing shortly after the previous sync **now re-fetches the trailing window
every time**, not just the incremental gap. `reconciliationService.reconcileForImport` also re-runs
over that whole window on every call. Both are correctness-safe (idempotent re-evaluation, see
`sync()`'s own doc comment) but not currently cost-measured. This is v1's accepted behavior per round
3's "design around the assumption, flag the risk" pattern — a debounce (e.g. "don't re-run the
window diff if the last one was under N hours ago") is real future work, explicitly not built here
speculatively.

- [ ] **Step 1: Write the failing tests**

Extend `SetuDataFetchServiceSyncSinceLastAttemptTest` with the sliding-window case:

```java
    @Test
    void neverFetchesLessThanTheSlidingWindowEvenWhenRecentlySynced() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setLastSyncedAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));

        SetuDataFetchService spied = spy(service);
        boolean attempted = spied.syncSinceLastAttempt(link);

        // Default sliding-window-days is 14 (see application.yml) -- from must be at most
        // to.minusDays(14), even though lastSyncedAt was an hour ago and the pure incremental
        // range would have started tomorrow.
        assertThat(attempted).isTrue();
        verify(spied).sync(eq(link), eq(LocalDate.now().minusDays(14)), eq(LocalDate.now()));
    }
```

Add to `SetuDataFetchServiceTest` (the existing `sync()`-level test class — read it fresh first to
match its exact mocking style, not assumed here):

```java
    @Test
    void runsTheDiffEvenWhenThereAreNoNewTransactions() {
        // Set up a link, a gateway fetch that returns zero new-looking transactions (mapper/diff
        // finds nothing new), and assert diffService.diff(...) was still called with the fetch's
        // range -- reconciliation staying empty must not also skip diff detection.
        // ... construct per this test class's own existing fixture/mocking conventions ...
        verify(diffService).diff(eq(link.getUserId()), eq(link.getAccountId()), eq(from), eq(to), any());
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceSyncSinceLastAttemptTest,SetuDataFetchServiceTest`
Expected: FAIL — compile error (`diffService` not yet a constructor param) or assertion failure on
the window-floor test.

- [ ] **Step 3: Implement**

```java
    private final AccountAggregatorTransactionDiffService diffService;

    @org.springframework.beans.factory.annotation.Value("${app.integrations.setu.sliding-window-days:14}")
    private int slidingWindowDays;

    public SetuDataFetchService(SetuDataFetchGateway gateway, AccountAggregatorTransactionMapper mapper,
                                 AccountAggregatorTransactionDiffService diffService,
                                 TransactionRepository transactionRepository, AccountAggregatorLinkRepository links,
                                 EntitlementService entitlementService, AuditService auditService,
                                 ReconciliationService reconciliationService) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.diffService = diffService;
        this.transactionRepository = transactionRepository;
        this.links = links;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
    }

    public boolean syncSinceLastAttempt(AccountAggregatorLink link) {
        LocalDate to = LocalDate.now();
        LocalDate incrementalFrom = link.getLastSyncedAt() != null
                ? link.getLastSyncedAt().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
                : to.minusMonths(3);
        // Plan 6, Track B: never fetch less than the sliding window, even if incrementalFrom would
        // otherwise be narrower (a recent sync) -- see this plan's Task 4 for the full reasoning and
        // the accepted cost tradeoff of always re-covering the window.
        LocalDate windowFloor = to.minusDays(Math.max(0, slidingWindowDays));
        LocalDate from = incrementalFrom.isBefore(windowFloor) ? incrementalFrom : windowFloor;
        if (from.isAfter(to)) {
            return false;
        }
        sync(link, from, to);
        return true;
    }

    public void sync(AccountAggregatorLink link, LocalDate from, LocalDate to) {
        if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            log.info("Skipping AA sync for link {}: user no longer entitled.", link.getId());
            return;
        }
        if (!gateway.isConfigured()) {
            log.info("Skipping AA sync for link {}: gateway not configured.", link.getId());
            return;
        }

        List<Transaction> newTransactions;
        try {
            SetuFiDataFetchResult fetched = gateway.fetchTransactions(link.getConsentHandleId(), from, to);
            var diffResult = diffService.diff(link.getUserId(), link.getAccountId(), from, to, fetched.transactions());
            newTransactions = diffResult.newTransactions();
            if (!newTransactions.isEmpty()) {
                transactionRepository.saveAll(newTransactions);
            }
        } catch (RuntimeException e) {
            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
            links.save(link);
            auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_SYNC_FAILED",
                    "AccountAggregatorLink", link.getId());
            log.error("AA sync failed for link {}.", link.getId(), e);
            return;
        }

        link.setLastSyncedAt(Instant.now());
        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
        links.save(link);

        if (!newTransactions.isEmpty()) {
            try {
                reconciliationService.reconcileForImport(link.getUserId(), from, to);
            } catch (RuntimeException e) {
                log.error("Reconciliation failed after AA sync for link {} persisted {} new transaction(s).",
                        link.getId(), newTransactions.size(), e);
            }
        }
    }
```

Replace the direct `mapper.mapNew(...)` call with `diffService.diff(...)` as shown — `mapper` stays
a constructor param only if something else in the class still calls it directly (it should not,
after this change; if the compiler flags an unused field, remove the `mapper` field and its
constructor param, and update every call site — including `SetuDataFetchServiceTest`'s own
constructor calls, all of Track A's tests that construct this service, and
`AccountAggregatorReconciliationSweepServiceIT` if it wires this service directly).

Add `app.integrations.setu.sliding-window-days: 14` to `application.yml` with an explicit comment:

```yaml
    setu:
      # Plan 6, Track B. PLACEHOLDER -- not evidence-based. The scope doc is explicit that a real
      # number needs Setu sandbox measurement of how long after first appearance corrections
      # actually land, which this environment cannot do. 14 is a conservative guess, not a
      # validated default -- revisit once real sandbox data exists.
      sliding-window-days: 14
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceSyncSinceLastAttemptTest,SetuDataFetchServiceTest,AccountAggregatorWebhookDispatcherTest,AccountAggregatorReconciliationSweepServiceTest`
Expected: PASS — this touches a widely-shared constructor, so re-run every test class that
constructs `SetuDataFetchService` directly, not just the two new tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceSyncSinceLastAttemptTest.java \
        backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceTest.java
git commit -m "feat(backend): sliding-window re-fetch + wire three-way diff into sync()"
```

---

### Task 5: Explicit acknowledgment endpoint — clearing `pendingBankCorrection`

**Files:**
- Modify: `backend/src/main/java/com/finora/transactions/TransactionService.java`
- Modify: `backend/src/main/java/com/finora/transactions/TransactionController.java`
- Create: `backend/src/test/java/com/finora/transactions/TransactionServiceAcknowledgeBankCorrectionTest.java`

**Why a dedicated endpoint, not piggybacking on the existing category-edit clear:** every existing
`setNeedsCategoryReview(false)` call site is "an explicit edit always resolves the review flag" —
but a category edit is not the same act as acknowledging a *value* correction. A user could change a
transaction's category without ever having seen that the bank corrected its amount. Clearing
`pendingBankCorrection` needs its own explicit action so the user provably saw the old-vs-new
comparison (surfaced via the `AuditLog` metadata Task 3 wrote) before it disappears from their
review queue.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.transactions;

// ... imports matching this package's existing TransactionService test conventions ...

class TransactionServiceAcknowledgeBankCorrectionTest {

    @Test
    void clearsThePendingFlagAndRecordsAcknowledgment() {
        // Build a Transaction owned by a user with pendingBankCorrection=true, call
        // transactionService.acknowledgeBankCorrection(userId, transactionId), assert the flag is
        // now false and auditService.record(userId, "ACCOUNT_AGGREGATOR_CORRECTION_ACKNOWLEDGED",
        // "Transaction", transactionId) was called.
    }

    @Test
    void rejectsAcknowledgingSomeoneElsesTransaction() {
        // Same ownership-check discipline every other TransactionService method already has --
        // read an existing method (e.g. markTransfer or delete) for the exact exception type and
        // message this codebase uses for a cross-user access attempt, and match it exactly rather
        // than inventing a new one.
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=TransactionServiceAcknowledgeBankCorrectionTest`
Expected: FAIL to compile — the method doesn't exist yet.

- [ ] **Step 3: Implement**

Read `TransactionService`'s existing single-transaction mutation methods first (e.g. `markTransfer`,
`updateCategory`) to match this class's exact ownership-check and `@Transactional` conventions, then
add:

```java
    @Transactional
    public void acknowledgeBankCorrection(UUID userId, UUID transactionId) {
        Transaction t = requireOwned(userId, transactionId); // reuse whatever helper the existing
                                                               // single-transaction methods already use
        t.setPendingBankCorrection(false);
        transactionRepository.save(t);
        auditService.record(userId, "ACCOUNT_AGGREGATOR_CORRECTION_ACKNOWLEDGED", "Transaction", transactionId);
    }
```

Add the controller endpoint, matching `TransactionController`'s existing single-action pattern (e.g.
`mark-transfer`):

```java
    @PostMapping("/{id}/acknowledge-bank-correction")
    public ResponseEntity<Void> acknowledgeBankCorrection(@PathVariable UUID id, Authentication auth) {
        transactionService.acknowledgeBankCorrection(currentUserId(auth), id);
        return ResponseEntity.noContent().build();
    }
```

(Match the controller's actual current-user-extraction helper name — read the file first rather than
assuming `currentUserId` is correct.)

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=TransactionServiceAcknowledgeBankCorrectionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/transactions/TransactionService.java \
        backend/src/main/java/com/finora/transactions/TransactionController.java \
        backend/src/test/java/com/finora/transactions/TransactionServiceAcknowledgeBankCorrectionTest.java
git commit -m "feat(backend): add explicit acknowledge-bank-correction action"
```

---

### Task 6: Ledger badge + correction detail (frontend)

**Files:**
- Modify: `frontend/src/pages/Ledger.tsx`
- Modify or create: a small correction-detail affordance reading the transaction's own recent
  `AuditLog` entries (a new lightweight backend read endpoint is needed — see Step 1 below — this
  plan does not assume one already exists; verify against `AuditLogRepository`/`AuditLogController`
  fresh before writing this task's code, not against this plan's earlier research pass).

- [ ] **Step 1: Backend read endpoint**

Add `AuditLogRepository.findTop20ByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID
entityId)` (mirrors the existing `findTop50ByUserIdAndActionInOrderByCreatedAtDesc` naming
convention in `AuditService`), and a thin controller method — `GET
/api/v1/transactions/{id}/correction-history` — that verifies the transaction belongs to the
authenticated user (same `requireOwned` helper as Task 5) before returning its `AuditLog` rows
filtered to `entityType = "Transaction"`. Do not expose raw `AuditLog` rows for a transaction the
caller doesn't own — this is a per-resource read, not a general audit-log API.

Write a test proving the ownership check (`TransactionControllerCorrectionHistoryTest` or similar,
matching this package's existing controller-test conventions) before wiring the frontend.

- [ ] **Step 2: Ledger badge**

In `Ledger.tsx`, alongside the existing `needsCategoryReview` badge (~line 197):

```typescript
if (t.pendingBankCorrection) badges.push({ label: 'Bank Correction', tone: 'warning' });
```

Clicking the badge (or a "View details" affordance next to it) calls the new
`correction-history` endpoint and renders the old-vs-new values from the most recent
`ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED`/`_MISSING` audit row's metadata, with a button that calls
the Task 5 `acknowledge-bank-correction` endpoint and removes the badge on success.

- [ ] **Step 3: Manual verification in the browser preview**

Per this project's UI-change discipline: start the dev server, seed a transaction with
`pendingBankCorrection = true` (directly via a test/dev script, since there's no real Setu sandbox
to trigger it organically), confirm the badge renders, the detail view shows old vs. new, and
acknowledging clears it and updates the Ledger row without a full page reload.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/finora/repository/AuditLogRepository.java \
        backend/src/main/java/com/finora/transactions/TransactionController.java \
        frontend/src/pages/Ledger.tsx
git commit -m "feat(frontend): surface pending bank corrections in Ledger"
```

---

### Task 7: Real-Postgres IT — the full sliding-window fetch → diff → persist round trip

**Files:**
- Create: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffServiceIT.java`

**Why this task exists:** same discipline this session has required for every AA plan so far — a
sweep or sync path that mutates transaction rows on a schedule needs a real-Postgres proof, not just
mocked units. This is the highest-value one yet: it's the first AA test proving a `Transaction` row
is *deliberately left unmutated* while still being flagged, which a mock can assert but only a real
`save`/`findById` round trip actually proves.

- [ ] **Step 1: Write the IT**

Cover, against `AbstractIntegrationTest`'s real Postgres, with `SetuDataFetchGateway` and
`EntitlementService` mocked (same seams every prior AA IT has mocked, for the same reason — no real
Setu sandbox access):

1. **New transaction persists exactly as before** — unchanged behavior, a regression guard.
2. **A corrected transaction (same `txnId`, different amount) is flagged, not overwritten** — seed a
   transaction via a first `sync()` call, then a second `sync()` call with the gateway returning a
   different amount for the same `txnId`; assert the reloaded row's `amount` is still the original,
   `pendingBankCorrection` is `true`, and an `AuditLog` row with action
   `ACCOUNT_AGGREGATOR_TRANSACTION_CORRECTED` and the right `entityId` exists.
3. **A vanished transaction is flagged as missing** — seed via a first `sync()`, then a second
   `sync()` whose gateway response omits that `txnId`; assert the row still exists, unmutated, with
   `pendingBankCorrection = true` and an `AuditLog` row with action
   `ACCOUNT_AGGREGATOR_TRANSACTION_MISSING`.
4. **A transaction with no `externalTxnId` is never flagged as missing** even when a second sync
   omits it — the identity-ceiling case from Task 3's unit test, proven once more against real
   persistence.

Same shared-Postgres discipline as every existing AA IT in this suite: assert only on this test's
own fixtures by id, never on a global count (`AccountAggregatorReconciliationSweepServiceIT`'s own
doc comment explains why).

- [ ] **Step 2: Run the IT**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorTransactionDiffServiceIT`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionDiffServiceIT.java
git commit -m "test(backend): prove the three-way diff against real Postgres"
```

---

## After Task 7: run the full suite and re-verify against this plan's own Global Constraints

```bash
cd backend && ./mvnw test
cd ../frontend && npm test && npm run lint && npm run type-check && npm run build
```

Expected: PASS, zero failures, zero errors. Per this project's standing "mandatory
post-implementation verification" rule, do not stop at green tests:

- **Grep for any setter call on a financial field (`setAmount`, `setDescription`, `setTxnDate`)
  inside `AccountAggregatorTransactionDiffService`** — there should be none. This is the single
  highest-value check for this whole plan: round 3's decision was specifically "preserve, don't
  overwrite," and a passing test suite alone would not catch a diff service that quietly started
  mutating rows in a way its own tests happened not to assert against.
- Confirm `CREDIT_CARD` links are still never reached by any of this — no new code path in this plan
  should read `link.getFiType()` at all; if it does, something has scope-crept beyond `DEPOSIT`.
- Confirm the sliding-window cost tradeoff (Task 4) is documented in the PR description, not just
  the code comment — this is a real, uncosted behavior change (`data.ready` now re-fetches a window
  on every call, not an incremental delta) and should be visible to whoever reviews the PR, not
  buried.
- Re-read `AccountAggregatorTransactionMapper.mapNew` — it should be untouched in its public
  contract (Task 3 only widened `fingerprint`'s visibility), so anything still calling `mapNew`
  directly (if such a caller exists outside this plan's own new diff service) keeps working exactly
  as before.
- Confirm `needsCategoryReview` and `pendingBankCorrection` are independently settable and
  independently clearable — a test that sets one and asserts the other is untouched, if one doesn't
  already exist from Task 1–3's own coverage.
