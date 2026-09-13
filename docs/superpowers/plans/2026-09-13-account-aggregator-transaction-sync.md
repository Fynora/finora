# Account Aggregator Transaction Sync Implementation Plan (Plan 2 of 6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For an `ACTIVE` `AccountAggregatorLink` (built by Plan 1, merged in PR #1396/#1400),
actually pull `DEPOSIT` transaction data from Setu and land it as `Transaction` rows — the gap Plan
1 explicitly left open. Insert-only: no bank-side correction/removal handling (Plan 6), no AA-vs-Gmail
canonicalization (Plan 3), no outage fallback (Plan 4), no cost caps or consent-management UX
(Plan 5), no `CREDIT_CARD` FI type.

**Architecture:** Extends `integrations/setu/` with a second gateway seam
(`SetuDataFetchGateway`, mirroring Plan 1's `SetuConsentGateway`) so the fetch+decrypt HTTP call
stays out of anything business-logic-bearing, a mapper that turns Setu's FI-data JSON into
`Transaction` rows, and an orchestration service wired to the existing `data.ready` webhook case
(currently unhandled — `AccountAggregatorWebhookDispatcher`'s `switch` has no branch for it) and to
the moment a link first reaches `ACTIVE` (for the 3-month backfill).

**Tech Stack:** Spring Boot, JPA/Hibernate, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ — same
as Plan 1.

**Spec:** [docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md](../specs/2026-09-12-account-aggregator-sync-design.md)
— this plan implements "Transaction identity and idempotency" and the `SetuDataFetchService`/
`AccountAggregatorTransactionMapper` parts of "Architecture," plus the AA-vs-manual half of
"Reconciliation" (not the AA-vs-Gmail half — that's Plan 3). See also
[2026-09-13-account-aggregator-transaction-sync-scope.md](2026-09-13-account-aggregator-transaction-sync-scope.md)
for the full in-scope/out-of-scope boundary this plan was scoped against.

## Known spec divergence — flagged, not silently resolved

`docs/proposals/reconciliation-evolution-roadmap-proposal.md` (merged 2026-08-28, predates the AA
sync spec by two weeks) already anticipates an Account Aggregator source in its own `SourceTrust`
section, at **trust 100** — above `CSV_IMPORT`'s 95 — as part of a materially different Phase 4
("Account Aggregator, only once reconciliation is trustworthy") that gates AA integration behind
that roadmap's own Phases 1–3 (a canonical-transaction layer, a confidence-scoring engine) and
proposes extending `duplicateKey()` matching rather than a dedicated new fuzzy pass.

This plan **deliberately follows the AA sync spec instead**: `SourceTrust.ACCOUNT_AGGREGATOR = 70`,
a dedicated new fuzzy pass (Task 7), built now rather than waiting on the other roadmap's Phases
1–3. This was an explicit choice, not an oversight — see the conversation that scoped this plan.
Reconciling the two documents (and possibly refactoring Task 7's pass once the other roadmap's
canonical-transaction layer exists) is follow-up work, not blocking this plan.

## Global Constraints

- `DEPOSIT` only. No `CREDIT_CARD` handling anywhere in this plan — the mapper and gateway are
  structured so adding it later doesn't need a rewrite, but no card-specific logic is written now.
- Insert-only. A transaction whose upstream value changes, or that disappears from a later fetch,
  is not detected by anything in this plan — that is Plan 6, an accepted, explicit gap here, not a
  bug to chase.
- Every Setu HTTP call goes through `SetuDataFetchGateway`, never a raw HTTP client called directly
  — mirrors Plan 1's `SetuConsentGateway` seam, so every task below is unit-testable with a
  hand-rolled fake or fixture, no real Setu sandbox required.
- Entitlement re-checked immediately before every fetch actually runs (webhook-triggered or
  backfill-triggered) — mirrors Plan 1's `resolveAndAttach` re-check. A downgrade between `ACTIVE`
  and the next tick must not pull data for a lapsed user.
- Every fetch attempt (success or failure) updates `AccountAggregatorLink.lastSyncedAt` and the new
  `lastSyncStatus` — this is the only visibility this plan provides into whether sync is working;
  Plan 4's alerting builds on these fields, not this plan.
- No AI-attribution trailer in any commit message (repository rule, `CLAUDE.md`).

---

## File Structure

```
backend/src/main/java/com/finora/entity/
  Transaction.java                (modify — Source.ACCOUNT_AGGREGATOR, externalTxnId, transactionFingerprint)

backend/src/main/java/com/finora/service/
  SourceTrust.java                (modify — ACCOUNT_AGGREGATOR case, trust 70)

backend/src/main/java/com/finora/integrations/setu/
  AccountAggregatorLink.java      (modify — lastSyncStatus field)
  SetuFiDataTransaction.java      (new — record, one Setu-reported transaction)
  SetuFiDataFetchResult.java      (new — record, a fetch response: account summary + transaction list)
  SetuDataFetchGateway.java       (new — interface, the fetch+decrypt HTTP seam)
  SetuDataFetchGatewayImpl.java   (new — placeholder bean, mirrors SetuConsentGatewayImpl)
  AccountAggregatorTransactionMapper.java (new — FI-data JSON -> Transaction rows, dedup lookup)
  SetuDataFetchService.java       (new — orchestrates fetch -> map -> persist -> reconcile)
  AccountAggregatorWebhookDispatcher.java (modify — data.ready case)
  AccountAggregatorIdentityResolutionService.java (modify — trigger backfill on ACTIVE)

backend/src/main/java/com/finora/service/
  ReconciliationService.java      (modify — new AA-vs-manual fuzzy pass)

backend/src/main/resources/db/migration/
  V198__transaction_account_aggregator_source.sql (new)
  V199__account_aggregator_link_sync_status.sql   (new)

backend/src/test/java/com/finora/integrations/setu/
  (one test class per new/modified class above)
backend/src/test/java/com/finora/service/
  ReconciliationServiceAccountAggregatorFuzzyMatchTest.java (new)
```

---

### Task 1: Data model — `Transaction.Source.ACCOUNT_AGGREGATOR`, identity columns, link sync status

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/Transaction.java`
- Modify: `backend/src/main/java/com/finora/service/SourceTrust.java`
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java`
- Create: `backend/src/main/resources/db/migration/V198__transaction_account_aggregator_source.sql`
- Create: `backend/src/main/resources/db/migration/V199__account_aggregator_link_sync_status.sql`
- Test: `backend/src/test/java/com/finora/service/SourceTrustAccountAggregatorTest.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkSyncStatusTest.java`

**Interfaces:**
- Produces: `Transaction.Source.ACCOUNT_AGGREGATOR`; `Transaction.getExternalTxnId()/setExternalTxnId(String)`
  (nullable); `Transaction.getTransactionFingerprint()/setTransactionFingerprint(String)` (always set
  for an AA-sourced row); `SourceTrust.of(Transaction.Source.ACCOUNT_AGGREGATOR)` → `70`;
  `AccountAggregatorLink.getLastSyncStatus()/setLastSyncStatus(SyncStatus)`, new nested enum
  `AccountAggregatorLink.SyncStatus { SUCCESS, FAILED }`, defaulting to `null` until the first fetch
  attempt. Every later task in this plan reads/writes these.
- Deliberately new, separate columns from the existing `Transaction.idempotencyKey` (used elsewhere
  for a different idempotency mechanism) — this plan does not touch or reuse that field, to avoid
  coupling AA's dedup semantics to a column whose other callers weren't audited for this plan.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.service;

import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTrustAccountAggregatorTest {

    @Test
    void accountAggregatorRanksAboveGmailAndBelowCsv() {
        // Trust 70 per the AA sync design spec -- deliberately below CSV_IMPORT/95 despite AA being
        // a live bank feed, because the decrypt/map layer (this plan) ships with zero production
        // mileage. See this plan doc's "Known spec divergence" section for why this isn't 100.
        int aaTrust = invokeOf(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(aaTrust).isEqualTo(70);
        assertThat(aaTrust).isGreaterThan(invokeOf(Transaction.Source.GMAIL_IMPORT));
        assertThat(aaTrust).isLessThan(invokeOf(Transaction.Source.CSV_IMPORT));
    }

    // SourceTrust.of is package-private by design (see its own class doc) -- same-package test,
    // no reflection needed.
    private static int invokeOf(Transaction.Source source) {
        return SourceTrust.of(source);
    }
}
```

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountAggregatorLinkSyncStatusTest {

    @Test
    void lastSyncStatusIsNullUntilTheFirstFetchAttempt() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        assertThat(link.getLastSyncStatus()).isNull();
    }

    @Test
    void canRecordASuccessfulOrFailedFetch() {
        AccountAggregatorLink link = new AccountAggregatorLink();

        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);

        link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.FAILED);
    }
}
```

Also add a `Transaction` test for the two new fields (`getExternalTxnId`/`setExternalTxnId`,
`getTransactionFingerprint`/`setTransactionFingerprint`) to whichever existing `TransactionTest`
class already covers similar fields — a new top-level test class isn't needed for two plain
accessor pairs on an entity that already has one.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=SourceTrustAccountAggregatorTest,AccountAggregatorLinkSyncStatusTest`
Expected: FAIL to compile — `Transaction.Source.ACCOUNT_AGGREGATOR` and `AccountAggregatorLink.SyncStatus`
don't exist yet.

- [ ] **Step 3: Add the enum value, columns, and migrations**

In `Transaction.java`, extend the existing `Source` enum and add the two new columns near the
existing `idempotencyKey` field:

```java
    // ACCOUNT_AGGREGATOR added Plan 2 of the AA sync feature -- see
    // docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Data model". No default
    // branch in SourceTrust.of()'s switch: adding this case without updating that switch is a
    // compile error, not a silent trust-0 transaction (see that class's own doc comment).
    public enum Source { MANUAL, CSV_IMPORT, GMAIL_IMPORT, ACCOUNT_AGGREGATOR }
```

```java
    // Setu's own transaction id, when the FIP populates it. NOT trusted as the sole dedup key --
    // see transactionFingerprint below and the design spec's "Transaction identity" section for why
    // (unverified whether every FIP populates it, or whether it survives a pending->posted
    // transition). Null for every non-AA-sourced row.
    @Column(name = "external_txn_id")
    private String externalTxnId;

    // Always computed for an AA-sourced row: hash(accountId, amount, direction, valueDate,
    // normalize(narration)). Fallback dedup key when externalTxnId is absent or a webhook redelivers
    // the same fetch window under a different (or missing) txnId. Null for every non-AA-sourced row.
    @Column(name = "transaction_fingerprint")
    private String transactionFingerprint;
```

```java
    public String getExternalTxnId() { return externalTxnId; }
    public void setExternalTxnId(String externalTxnId) { this.externalTxnId = externalTxnId; }
    public String getTransactionFingerprint() { return transactionFingerprint; }
    public void setTransactionFingerprint(String transactionFingerprint) { this.transactionFingerprint = transactionFingerprint; }
```

In `SourceTrust.java`, extend the switch (this is the one line the compiler forces once the enum
value exists):

```java
    static int of(Transaction.Source source) {
        return switch (source) {
            case CSV_IMPORT -> 95;
            case ACCOUNT_AGGREGATOR -> 70;
            case GMAIL_IMPORT -> 60;
            case MANUAL -> 30;
        };
    }
```

Update that class's own doc comment too — it currently says "does not exist in `Transaction.Source`
yet" and proposes trust 100; correct both now that the value exists, and add a one-line pointer to
this plan's "Known spec divergence" section so a future reader isn't left with two contradictory
docs saying different things about the same constant.

In `AccountAggregatorLink.java`, add the nested enum, field, and accessors near `lastSyncedAt`:

```java
    /** Outcome of the most recent fetch attempt (webhook-triggered or backfill-triggered) --
     *  null until the first attempt. See SetuDataFetchService, the only writer. */
    public enum SyncStatus { SUCCESS, FAILED }
```

```java
    @Enumerated(EnumType.STRING)
    @Column(name = "last_sync_status", length = 16)
    private SyncStatus lastSyncStatus;
```

```java
    public SyncStatus getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(SyncStatus lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; touch(); }
```

`V198__transaction_account_aggregator_source.sql`:

```sql
-- Identity columns for AA-sourced transactions -- see
-- docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Transaction identity and
-- idempotency". Both nullable: only ever populated for Source.ACCOUNT_AGGREGATOR rows.
ALTER TABLE transactions ADD COLUMN external_txn_id VARCHAR(255);
ALTER TABLE transactions ADD COLUMN transaction_fingerprint VARCHAR(128);

-- Primary dedup lookup path (falls back to the fingerprint index below when absent/unseen). Not
-- unique: Setu's txnId reliability across FIPs is unverified (see the spec) -- a non-unique index
-- lets the mapper's own two-step lookup logic decide what "already seen" means, rather than the
-- database enforcing a guarantee this codebase isn't confident actually holds.
CREATE INDEX idx_transactions_external_txn_id ON transactions (account_id, external_txn_id)
    WHERE external_txn_id IS NOT NULL;

CREATE INDEX idx_transactions_fingerprint ON transactions (account_id, transaction_fingerprint)
    WHERE transaction_fingerprint IS NOT NULL;
```

`V199__account_aggregator_link_sync_status.sql`:

```sql
-- Outcome of the most recent fetch attempt against this link -- see
-- docs/superpowers/plans/2026-09-13-account-aggregator-transaction-sync.md, Task 1.
ALTER TABLE account_aggregator_links ADD COLUMN last_sync_status VARCHAR(16);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=SourceTrustAccountAggregatorTest,AccountAggregatorLinkSyncStatusTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/entity/Transaction.java \
        backend/src/main/java/com/finora/service/SourceTrust.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorLink.java \
        backend/src/main/resources/db/migration/V198__transaction_account_aggregator_source.sql \
        backend/src/main/resources/db/migration/V199__account_aggregator_link_sync_status.sql \
        backend/src/test/java/com/finora/service/SourceTrustAccountAggregatorTest.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorLinkSyncStatusTest.java
git commit -m "feat(backend): add ACCOUNT_AGGREGATOR transaction source, identity columns, link sync status"
```

---

### Task 2: `SetuDataFetchGateway` seam + FI-data DTOs

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuFiDataTransaction.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuFiDataFetchResult.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuDataFetchGateway.java`
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuDataFetchGatewayImpl.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/SetuDataFetchGatewayImplTest.java`

**Interfaces:**
- Produces:
  - `record SetuFiDataTransaction(String txnId, String type, BigDecimal amount, LocalDate valueDate,
    LocalDate transactionDate, String narration, BigDecimal currentBalance, String reference)` —
    field names and shape follow the publicly-documented ReBIT/Sahamati AA FI-data JSON schema for a
    `DEPOSIT` account's transaction array; **unverified against a real Setu sandbox response**,
    flagged exactly like Plan 1 flagged its own unverified webhook-signature scheme. `type` is the
    raw string Setu sends (`"CREDIT"`/`"DEBIT"`) rather than an enum, so a value this codebase
    hasn't anticipated doesn't crash JSON deserialization — the mapper (Task 3) is the one place
    that interprets it.
  - `record SetuFiDataFetchResult(String maskedAccountNumber, BigDecimal currentBalance,
    List<SetuFiDataTransaction> transactions)`.
  - `interface SetuDataFetchGateway` — `boolean isConfigured()`, `SetuFiDataFetchResult
    fetchTransactions(String consentHandleId, LocalDate from, LocalDate to)`. No production
    implementation in this task, same reasoning as Plan 1's `SetuConsentGateway`: a real HTTP client
    against Setu's actual fetch+ECDH-decrypt endpoint needs real sandbox credentials to build
    correctly, and is a named follow-up, not guessed here.
  - `SetuDataFetchGatewayImpl` — placeholder `@Component`, mirrors Plan 1's `SetuConsentGatewayImpl`:
    `isConfigured()` delegates to `SetuProperties`, `fetchTransactions(...)` throws
    `UnsupportedOperationException` until a real implementation replaces it. Exists only so the
    Spring context boots (an interface with no implementing bean breaks the whole application, the
    exact regression Plan 1's Task 12 fixed for `SetuConsentGateway`).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetuDataFetchGatewayImplTest {

    @Test
    void isConfiguredDelegatesToProperties() {
        SetuProperties properties = new SetuProperties();
        SetuDataFetchGatewayImpl gateway = new SetuDataFetchGatewayImpl(properties);

        assertThat(gateway.isConfigured()).isFalse();

        properties.setClientId("id");
        properties.setClientSecret("secret");
        properties.setWebhookSecret("whsecret");
        assertThat(gateway.isConfigured()).isTrue();
    }

    @Test
    void fetchTransactionsIsNotYetImplemented() {
        SetuDataFetchGatewayImpl gateway = new SetuDataFetchGatewayImpl(new SetuProperties());

        assertThatThrownBy(() -> gateway.fetchTransactions("consent-handle-1",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchGatewayImplTest`
Expected: FAIL to compile — none of these types exist yet.

- [ ] **Step 3: Create the DTOs, interface, and placeholder implementation**

`SetuFiDataTransaction.java`:

```java
package com.finora.integrations.setu;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction as Setu's FI-data response reports it for a DEPOSIT account. Field shape follows
 * the public ReBIT/Sahamati AA FI-data JSON schema -- NOT verified against a real Setu sandbox
 * response (see this plan's Task 2 and the design spec's "Explicitly out of scope" section on
 * CREDIT_CARD sandbox validation; the same "flag, don't guess" treatment applies to DEPOSIT's exact
 * field names here). AccountAggregatorTransactionMapper is the only reader of {@code type}'s raw
 * string value.
 */
public record SetuFiDataTransaction(String txnId, String type, BigDecimal amount, LocalDate valueDate,
                                     LocalDate transactionDate, String narration,
                                     BigDecimal currentBalance, String reference) {}
```

`SetuFiDataFetchResult.java`:

```java
package com.finora.integrations.setu;

import java.math.BigDecimal;
import java.util.List;

/** One fetch's worth of data for a single linked account: enough of the account summary to sanity-
 *  check against the Account this link is attached to, plus the transaction list itself. */
public record SetuFiDataFetchResult(String maskedAccountNumber, BigDecimal currentBalance,
                                     List<SetuFiDataTransaction> transactions) {}
```

`SetuDataFetchGateway.java`:

```java
package com.finora.integrations.setu;

import java.time.LocalDate;

/**
 * The only Setu-facing seam the transaction-sync pipeline depends on -- mirrors SetuConsentGateway's
 * role for the link-lifecycle side. SetuDataFetchService (Task 4) programs against this interface
 * only, never against an HTTP client directly, so it's unit-testable with a hand-rolled fake.
 */
public interface SetuDataFetchGateway {

    boolean isConfigured();

    /** @param from inclusive, @param to inclusive -- the trailing window being (re-)fetched. Plan 2
     *              only ever calls this with the initial 3-month backfill range or the webhook's
     *              implied "since last successful sync" range; a genuinely sliding, overlapping
     *              re-fetch window is Plan 6's concern, not this method's contract today. */
    SetuFiDataFetchResult fetchTransactions(String consentHandleId, LocalDate from, LocalDate to);
}
```

`SetuDataFetchGatewayImpl.java`:

```java
package com.finora.integrations.setu;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Placeholder bean -- exists only so the Spring context has something to inject wherever
 * SetuDataFetchGateway is wired, the same reasoning as SetuConsentGatewayImpl (Plan 1, Task 12: an
 * interface with no implementing bean breaks the whole application, not just this feature). A real
 * implementation (HTTP client, request signing, ECDH decrypt of Setu's FI-data payload) needs real
 * Setu sandbox credentials to build correctly and is tracked as a named follow-up, not guessed here.
 */
@Component
public class SetuDataFetchGatewayImpl implements SetuDataFetchGateway {

    private final SetuProperties properties;

    public SetuDataFetchGatewayImpl(SetuProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public SetuFiDataFetchResult fetchTransactions(String consentHandleId, LocalDate from, LocalDate to) {
        throw new UnsupportedOperationException(
                "Real Setu FI-data fetch is not implemented yet -- needs sandbox credentials.");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchGatewayImplTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuFiDataTransaction.java \
        backend/src/main/java/com/finora/integrations/setu/SetuFiDataFetchResult.java \
        backend/src/main/java/com/finora/integrations/setu/SetuDataFetchGateway.java \
        backend/src/main/java/com/finora/integrations/setu/SetuDataFetchGatewayImpl.java \
        backend/src/test/java/com/finora/integrations/setu/SetuDataFetchGatewayImplTest.java
git commit -m "feat(backend): add SetuDataFetchGateway seam for AA transaction fetch"
```

---

### Task 3: `AccountAggregatorTransactionMapper`

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorTransactionMapper.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionMapperTest.java`

**Interfaces:**
- Consumes: `TransactionRepository` (existing — needs new finder methods, added in this task; see
  below), `SetuFiDataTransaction` (Task 2).
- Produces: `AccountAggregatorTransactionMapper.mapNew(UUID userId, UUID accountId,
  List<SetuFiDataTransaction> fetched)` → `List<Transaction>`, containing only the rows genuinely
  new to this account (already-seen rows, by `externalTxnId` then by fingerprint, are filtered out
  — never returned, never re-inserted). Callers (`SetuDataFetchService`, Task 4) persist whatever
  this returns.
- Also adds to `TransactionRepository`: `existsByAccountIdAndExternalTxnId(UUID, String)`,
  `existsByAccountIdAndTransactionFingerprint(UUID, String)` — both new, both scoped by
  `accountId` (not `userId`) since the dedup question is always "has this account already seen
  this transaction," matching the fingerprint's own definition (`hash(accountId, amount, ...)`).

- [ ] **Step 1: Write the failing test**

```java
package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorTransactionMapperTest {

    private TransactionRepository transactionRepository;
    private AccountAggregatorTransactionMapper mapper;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        mapper = new AccountAggregatorTransactionMapper(transactionRepository);
        when(transactionRepository.existsByAccountIdAndExternalTxnId(any(), any())).thenReturn(false);
        when(transactionRepository.existsByAccountIdAndTransactionFingerprint(any(), any())).thenReturn(false);
    }

    @Test
    void mapsADebitTransactionToAnExpense() {
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-1", "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).hasSize(1);
        Transaction txn = mapped.get(0);
        assertThat(txn.getUserId()).isEqualTo(userId);
        assertThat(txn.getAccountId()).isEqualTo(accountId);
        assertThat(txn.getTxnType()).isEqualTo(Transaction.Type.EXPENSE);
        assertThat(txn.getAmount()).isEqualByComparingTo("450.00");
        assertThat(txn.getSource()).isEqualTo(Transaction.Source.ACCOUNT_AGGREGATOR);
        assertThat(txn.getExternalTxnId()).isEqualTo("txn-1");
        assertThat(txn.getTransactionFingerprint()).isNotBlank();
        assertThat(txn.getBalanceAfter()).isEqualByComparingTo("10450.00");
        assertThat(txn.getDescription()).isEqualTo("UPI-SWIGGY-PAYMENT");
    }

    @Test
    void mapsACreditTransactionToIncome() {
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-2", "CREDIT", new BigDecimal("50000.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "NEFT-SALARY", new BigDecimal("60000.00"), "ref-2");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped.get(0).getTxnType()).isEqualTo(Transaction.Type.INCOME);
    }

    @Test
    void skipsATransactionAlreadySeenByExternalTxnId() {
        when(transactionRepository.existsByAccountIdAndExternalTxnId(accountId, "txn-1")).thenReturn(true);
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                "txn-1", "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).isEmpty();
    }

    @Test
    void fallsBackToFingerprintWhenExternalTxnIdIsAbsent() {
        // No txnId at all -- some FIPs may not populate it (see the design spec's "Transaction
        // identity" section). Dedup must still work off the fingerprint alone.
        SetuFiDataTransaction fetched = new SetuFiDataTransaction(
                null, "DEBIT", new BigDecimal("450.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT", new BigDecimal("10450.00"), "ref-1");
        when(transactionRepository.existsByAccountIdAndTransactionFingerprint(eq(accountId), any()))
                .thenReturn(true);

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(fetched));

        assertThat(mapped).isEmpty();
    }

    @Test
    void twoTransactionsWithTheSameFingerprintInputsStillGetTheSameFingerprint() {
        SetuFiDataTransaction a = new SetuFiDataTransaction(
                "txn-a", "DEBIT", new BigDecimal("100.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-REFCODE-PAYMENT", new BigDecimal("900.00"), "ref-a");
        SetuFiDataTransaction b = new SetuFiDataTransaction(
                "txn-b", "DEBIT", new BigDecimal("100.00"), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1), "UPI-REFCODE-PAYMENT", new BigDecimal("800.00"), "ref-b");

        List<Transaction> mapped = mapper.mapNew(userId, accountId, List.of(a, b));

        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).getTransactionFingerprint())
                .isEqualTo(mapped.get(1).getTransactionFingerprint());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorTransactionMapperTest`
Expected: FAIL to compile — `AccountAggregatorTransactionMapper` and the two new repository methods
don't exist yet.

- [ ] **Step 3: Add the repository methods and implement the mapper**

In `TransactionRepository.java`, add:

```java
    boolean existsByAccountIdAndExternalTxnId(UUID accountId, String externalTxnId);

    boolean existsByAccountIdAndTransactionFingerprint(UUID accountId, String transactionFingerprint);
```

`AccountAggregatorTransactionMapper.java`:

```java
package com.finora.integrations.setu;

import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Maps Setu's FI-data transaction list into new Transaction rows for one account, filtering out
 * anything already seen. See the design spec's "Transaction identity and idempotency" section --
 * externalTxnId is checked first (a hint, not a guarantee: not established whether every FIP
 * populates it, or whether it survives a pending->posted transition), falling back to a fingerprint
 * that is always computed and always checked.
 *
 * <p>Insert-only, per this plan's scope: a row that already exists (by either key) is silently
 * skipped, not updated. A changed or vanished upstream transaction is Plan 6's concern, not this
 * class's.
 */
@Component
public class AccountAggregatorTransactionMapper {

    private final TransactionRepository transactionRepository;

    public AccountAggregatorTransactionMapper(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<Transaction> mapNew(UUID userId, UUID accountId, List<SetuFiDataTransaction> fetched) {
        List<Transaction> result = new ArrayList<>();
        for (SetuFiDataTransaction source : fetched) {
            String fingerprint = fingerprint(accountId, source);

            if (source.txnId() != null
                    && transactionRepository.existsByAccountIdAndExternalTxnId(accountId, source.txnId())) {
                continue;
            }
            if (transactionRepository.existsByAccountIdAndTransactionFingerprint(accountId, fingerprint)) {
                continue;
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
            result.add(txn);
        }
        return result;
    }

    /** hash(accountId, amount, direction, valueDate, normalize(narration)) -- see the design spec.
     *  Deliberately excludes txnId and reference: both are the least reliable fields across FIPs
     *  (per the same spec section), and including an unreliable field in the fallback that exists
     *  specifically to cover for that field's unreliability would defeat the point. */
    private static String fingerprint(UUID accountId, SetuFiDataTransaction source) {
        String normalizedNarration = source.narration() == null ? "" :
                source.narration().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String raw = String.join("|",
                accountId.toString(),
                source.amount().stripTrailingZeros().toPlainString(),
                source.type() == null ? "" : source.type().toUpperCase(Locale.ROOT),
                String.valueOf(source.valueDate()),
                normalizedNarration);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every supported JVM", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorTransactionMapperTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/repository/TransactionRepository.java \
        backend/src/main/java/com/finora/integrations/setu/AccountAggregatorTransactionMapper.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorTransactionMapperTest.java
git commit -m "feat(backend): add AccountAggregatorTransactionMapper with externalTxnId/fingerprint dedup"
```

---

### Task 4: `SetuDataFetchService` — orchestration

**Files:**
- Create: `backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java`
- Test: `backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceTest.java`

**Interfaces:**
- Consumes: `SetuDataFetchGateway` (Task 2), `AccountAggregatorTransactionMapper` (Task 3),
  `TransactionRepository.saveAll` (existing), `AccountAggregatorLinkRepository` (Plan 1),
  `EntitlementService.hasEntitlement` (existing), `AuditService.record` (existing),
  `ReconciliationService.reconcileForImport` (existing — signature
  `reconcileForImport(UUID userId, LocalDate earliestImported, LocalDate latestImported)`, already
  used by `ImportService` after a manual import completes; reused here rather than a new entry
  point, since "new transactions landed for this account over this date range" is exactly the same
  situation, regardless of source).
- Produces: `SetuDataFetchService.sync(AccountAggregatorLink link, LocalDate from, LocalDate to)` —
  void; updates `link.lastSyncedAt`/`lastSyncStatus` as a side effect and persists the link itself.
  Called by the webhook dispatcher (Task 5, `to` = today, `from` = the day after
  `lastSyncedAt`'s date, or 3 months back if `lastSyncedAt` is null) and by identity resolution
  (Task 6, backfill: `from`/`to` = the 3-month window).

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import com.finora.service.ReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SetuDataFetchServiceTest {

    private SetuDataFetchGateway gateway;
    private AccountAggregatorTransactionMapper mapper;
    private TransactionRepository transactionRepository;
    private AccountAggregatorLinkRepository links;
    private EntitlementService entitlementService;
    private AuditService auditService;
    private ReconciliationService reconciliationService;
    private SetuDataFetchService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private AccountAggregatorLink link;

    @BeforeEach
    void setUp() {
        gateway = mock(SetuDataFetchGateway.class);
        mapper = mock(AccountAggregatorTransactionMapper.class);
        transactionRepository = mock(TransactionRepository.class);
        links = mock(AccountAggregatorLinkRepository.class);
        entitlementService = mock(EntitlementService.class);
        auditService = mock(AuditService.class);
        reconciliationService = mock(ReconciliationService.class);
        service = new SetuDataFetchService(gateway, mapper, transactionRepository, links,
                entitlementService, auditService, reconciliationService);

        link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setAccountId(accountId);
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setConsentHandleId("consent-handle-1");
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());

        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(true);
        when(links.save(any(AccountAggregatorLink.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fetchesMapsPersistsAndReconciles() {
        SetuFiDataTransaction raw = new SetuFiDataTransaction("txn-1", "DEBIT", new BigDecimal("100"),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1), "desc", new BigDecimal("900"), "ref");
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions("consent-handle-1", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1)))
                .thenReturn(new SetuFiDataFetchResult("XXXX1234", new BigDecimal("900"), List.of(raw)));
        Transaction mapped = new Transaction();
        when(mapper.mapNew(userId, accountId, List.of(raw))).thenReturn(List.of(mapped));

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(transactionRepository).saveAll(List.of(mapped));
        verify(reconciliationService).reconcileForImport(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
        assertThat(link.getLastSyncedAt()).isNotNull();
        verify(links).save(link);
    }

    @Test
    void doesNotReconcileWhenNothingNewWasIngested() {
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(any(), any(), any()))
                .thenReturn(new SetuFiDataFetchResult("XXXX1234", new BigDecimal("900"), List.of()));
        when(mapper.mapNew(any(), any(), any())).thenReturn(List.of());

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(transactionRepository, never()).saveAll(any());
        verify(reconciliationService, never()).reconcileForImport(any(), any(), any());
        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.SUCCESS);
    }

    @Test
    void marksTheLinkFailedWhenTheGatewayThrows() {
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.fetchTransactions(any(), any(), any())).thenThrow(new RuntimeException("Setu 500"));

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        assertThat(link.getLastSyncStatus()).isEqualTo(AccountAggregatorLink.SyncStatus.FAILED);
        verify(transactionRepository, never()).saveAll(any());
        verify(auditService).record(eq(userId), eq("ACCOUNT_AGGREGATOR_SYNC_FAILED"),
                eq("AccountAggregatorLink"), any());
    }

    @Test
    void skipsTheFetchEntirelyWhenTheUserIsNoLongerEntitled() {
        when(entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC))
                .thenReturn(false);

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verifyNoInteractions(gateway);
        // Deliberately does not touch lastSyncStatus here -- an entitlement lapse is not a sync
        // failure, it's a reason not to have attempted one. AccountAggregatorWebhookDispatcher's
        // own re-entrancy/entitlement handling (Plan 1) is what actually pauses the link; this
        // service just declines to do costly work for a user who can't use it.
        verify(links, never()).save(any());
    }

    @Test
    void skipsWhenTheGatewayItselfIsNotConfigured() {
        when(gateway.isConfigured()).thenReturn(false);

        service.sync(link, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1));

        verify(gateway, never()).fetchTransactions(any(), any(), any());
        verify(links, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceTest`
Expected: FAIL to compile — `SetuDataFetchService` doesn't exist.

- [ ] **Step 3: Implement `SetuDataFetchService`**

```java
package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import com.finora.service.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class SetuDataFetchService {

    private static final Logger log = LoggerFactory.getLogger(SetuDataFetchService.class);

    private final SetuDataFetchGateway gateway;
    private final AccountAggregatorTransactionMapper mapper;
    private final TransactionRepository transactionRepository;
    private final AccountAggregatorLinkRepository links;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final ReconciliationService reconciliationService;

    public SetuDataFetchService(SetuDataFetchGateway gateway, AccountAggregatorTransactionMapper mapper,
                                 TransactionRepository transactionRepository, AccountAggregatorLinkRepository links,
                                 EntitlementService entitlementService, AuditService auditService,
                                 ReconciliationService reconciliationService) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.transactionRepository = transactionRepository;
        this.links = links;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * Fetches [from, to] for one ACTIVE link, maps and persists whatever's new, and re-reconciles
     * that range against any pre-existing manually-imported rows for the same account. Called by
     * the data.ready webhook (Task 5) and by the initial backfill (Task 6).
     *
     * <p>Entitlement is re-checked here, immediately before the costly (billable) gateway call --
     * same discipline as Plan 1's resolveAndAttach re-check, for the same reason: a downgrade
     * between ACTIVE and this tick must not pull data for a lapsed user. This method does not pause
     * the link itself on a lapsed entitlement -- that is the dispatcher/sweep's job (Plan 1); this
     * service only declines to do the costly work.
     */
    public void sync(AccountAggregatorLink link, LocalDate from, LocalDate to) {
        if (!entitlementService.hasEntitlement(link.getUserId(), FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            log.info("Skipping AA sync for link {}: user no longer entitled.", link.getId());
            return;
        }
        if (!gateway.isConfigured()) {
            log.info("Skipping AA sync for link {}: gateway not configured.", link.getId());
            return;
        }

        try {
            SetuFiDataFetchResult fetched = gateway.fetchTransactions(link.getConsentHandleId(), from, to);
            List<Transaction> newTransactions =
                    mapper.mapNew(link.getUserId(), link.getAccountId(), fetched.transactions());

            if (!newTransactions.isEmpty()) {
                transactionRepository.saveAll(newTransactions);
                reconciliationService.reconcileForImport(link.getUserId(), from, to);
            }

            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.SUCCESS);
            links.save(link);
        } catch (RuntimeException e) {
            link.setLastSyncedAt(Instant.now());
            link.setLastSyncStatus(AccountAggregatorLink.SyncStatus.FAILED);
            links.save(link);
            auditService.record(link.getUserId(), "ACCOUNT_AGGREGATOR_SYNC_FAILED",
                    "AccountAggregatorLink", link.getId());
            log.error("AA sync failed for link {}.", link.getId(), e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SetuDataFetchServiceTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/SetuDataFetchService.java \
        backend/src/test/java/com/finora/integrations/setu/SetuDataFetchServiceTest.java
git commit -m "feat(backend): add SetuDataFetchService orchestrating fetch, map, persist, reconcile"
```

---

### Task 5: Wire `data.ready` into the webhook dispatcher

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java`

**Interfaces:**
- Consumes: `SetuDataFetchService.sync(AccountAggregatorLink, LocalDate, LocalDate)` (Task 4) — new
  constructor dependency on the dispatcher.
- Produces: the dispatcher's `switch` gains a `case "data.ready"` branch. Range: `to` = today;
  `from` = the day after `link.getLastSyncedAt()`'s date, or 3 months back if `lastSyncedAt` is
  null (first sync after backfill already ran — see Task 6 for why backfill is triggered
  separately, at `ACTIVE` transition, not here). Only dispatches for a link already `ACTIVE` — a
  `data.ready` event for any other status (e.g. arriving before `consent.approved` finished
  resolving identity) is logged and ignored, not force-processed.

- [ ] **Step 1: Extend the failing test**

Add to `AccountAggregatorWebhookDispatcherTest`:

```java
    @Test
    void dataReadyTriggersAFetchForAnActiveLink() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        link.setLastSyncedAt(Instant.parse("2026-09-01T00:00:00Z"));
        when(links.findByConsentHandleId("consent-handle-1")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-1");

        verify(fetchService).sync(eq(link), eq(LocalDate.of(2026, 9, 2)), any());
    }

    @Test
    void dataReadyIsIgnoredForALinkNotYetActive() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION);
        when(links.findByConsentHandleId("consent-handle-2")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-2");

        verifyNoInteractions(fetchService);
    }

    @Test
    void dataReadyOnFirstSyncUsesTheThreeMonthWindow() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        link.setAccountId(UUID.randomUUID());
        // lastSyncedAt left null -- shouldn't happen in practice (backfill sets it, Task 6), but
        // the dispatcher must not NPE if it somehow does.
        when(links.findByConsentHandleId("consent-handle-3")).thenReturn(Optional.of(link));

        dispatcher.dispatch("data.ready", "consent-handle-3");

        verify(fetchService).sync(eq(link), eq(LocalDate.now().minusMonths(3)), any());
    }
```

Update the constructor call in `setUp()` (and the class field list) to add `fetchService = mock(SetuDataFetchService.class);`
and pass it as the dispatcher's new final constructor argument.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: FAIL to compile — no `data.ready` handling, no `SetuDataFetchService` constructor
argument yet.

- [ ] **Step 3: Add the dependency and the case**

```java
    private final SetuDataFetchService fetchService;

    public AccountAggregatorWebhookDispatcher(AccountAggregatorLinkRepository links, AccountRepository accountRepository,
                                               AuditService auditService,
                                               AccountAggregatorIdentityResolutionService identityResolutionService,
                                               SetuDataFetchService fetchService) {
        this.links = links;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.identityResolutionService = identityResolutionService;
        this.fetchService = fetchService;
    }
```

```java
            case "consent.approved" -> identityResolutionService.resolveAndAttach(link);
            case "data.ready" -> {
                if (link.getStatus() != AccountAggregatorLinkStatus.ACTIVE) {
                    log.info("Ignoring data.ready for link {} not yet ACTIVE (status {}).",
                            link.getId(), link.getStatus());
                } else {
                    java.time.LocalDate to = java.time.LocalDate.now();
                    java.time.LocalDate from = link.getLastSyncedAt() != null
                            ? link.getLastSyncedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(1)
                            : to.minusMonths(3);
                    fetchService.sync(link, from, to);
                }
            }
            default -> log.info("Unhandled Setu webhook event type {}, ignoring.", LogSanitizer.sanitize(eventType));
```

Every existing test that constructs `new AccountAggregatorWebhookDispatcher(...)` elsewhere in the
suite (there should be none outside this one test class, per Plan 1's file list — confirm with a
repo-wide grep before assuming) needs the new constructor argument added.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorWebhookDispatcherTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcher.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorWebhookDispatcherTest.java
git commit -m "feat(backend): wire data.ready webhook to trigger AA transaction sync"
```

---

### Task 6: Trigger the 3-month backfill on `ACTIVE` transition

**Files:**
- Modify: `backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java`
- Modify: `backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java`

**Interfaces:**
- Consumes: `SetuDataFetchService.sync(AccountAggregatorLink, LocalDate, LocalDate)` (Task 4) — new
  constructor dependency.
- Produces: `attach(AccountAggregatorLink link, Account account)` — the one method every path to
  `ACTIVE` already funnels through (`resolveAndAttach`'s `MATCHED`/`NEW` branches,
  `confirmExistingAccount`, `confirmNewAccount`) — now also calls
  `fetchService.sync(link, today.minusMonths(3), today)` after saving the link as `ACTIVE`. One
  change point covers every path to `ACTIVE`, rather than four call sites each remembering to
  trigger a backfill.

- [ ] **Step 1: Extend the failing test**

Add to `AccountAggregatorIdentityResolutionServiceTest`:

```java
    @Test
    void attachTriggersTheThreeMonthBackfill() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());

        service.attach(link, account);

        verify(fetchService).sync(eq(link), eq(LocalDate.now().minusMonths(3)), eq(LocalDate.now()));
    }
```

Update `setUp()` to add `fetchService = mock(SetuDataFetchService.class);` and pass it into the
service's constructor.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: FAIL to compile — no `SetuDataFetchService` constructor argument yet.

- [ ] **Step 3: Add the dependency and the call**

```java
    private final SetuDataFetchService fetchService;

    public AccountAggregatorIdentityResolutionService(SetuConsentGateway gateway, AccountRepository accountRepository,
                                                        AccountService accountService,
                                                        ProductIdentityResolver productIdentityResolver,
                                                        AccountAggregatorLinkRepository links,
                                                        EntitlementService entitlementService,
                                                        SetuDataFetchService fetchService) {
        this.gateway = gateway;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.productIdentityResolver = productIdentityResolver;
        this.links = links;
        this.entitlementService = entitlementService;
        this.fetchService = fetchService;
    }
```

```java
    void attach(AccountAggregatorLink link, Account account) {
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);
        accountRepository.save(account);
        link.setAccountId(account.getId());
        link.setStatus(AccountAggregatorLinkStatus.ACTIVE);
        links.save(link);

        // First sync happens right here rather than waiting on Setu's first data.ready webhook --
        // the 3-month backfill is this plan's own responsibility to trigger, not something to
        // assume a webhook will eventually ask for. See the design spec's "Scope" section.
        java.time.LocalDate today = java.time.LocalDate.now();
        fetchService.sync(link, today.minusMonths(3), today);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AccountAggregatorIdentityResolutionServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionService.java \
        backend/src/test/java/com/finora/integrations/setu/AccountAggregatorIdentityResolutionServiceTest.java
git commit -m "feat(backend): trigger 3-month AA backfill when a link reaches ACTIVE"
```

---

### Task 7: AA-vs-manual fuzzy near-duplicate reconciliation pass

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java`
- Create: `backend/src/test/java/com/finora/service/ReconciliationServiceAccountAggregatorFuzzyMatchTest.java`

**Interfaces:**
- Produces: a new pass inside `reconcile(...)`, alongside the existing exact-match/refund/Gmail/
  CC-payment passes — same shape as the Gmail cross-source pass (candidate `FUZZY`-tier graph edge
  only, never auto-excluding via `isDuplicateOf`/`reconciliationStatus`, per the design spec's "AA
  vs. manually-imported history" section: *"Ambiguous ties never auto-resolve — land both, flagged
  for review."*). Trigger: same account, same amount, same direction, tight date window (starting
  value: 3 days, same as the Gmail matcher's own window — the spec explicitly says this is not
  known to transfer and needs its own tuning against real data; 3 days is a documented starting
  point, not a final answer), normalized-description similarity above a starting threshold (0.6,
  same starting point as the Gmail matcher, same "needs tuning" caveat).
- Scope: only between `ACCOUNT_AGGREGATOR` and `{MANUAL, CSV_IMPORT}` — explicitly NOT
  `GMAIL_IMPORT` (that pair is Plan 3's dedicated rule, which behaves differently: it DOES
  auto-exclude at high confidence, unlike this pass). This pass must not fire for a
  `GMAIL_IMPORT`-vs-anything pair; that's the existing pass above it, untouched.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconciliationServiceAccountAggregatorFuzzyMatchTest {

    // Constructed the same way the rest of ReconciliationServiceTest already does -- see that
    // class for the full mock wiring this plan reuses verbatim; only the new pass's behavior is
    // exercised here, not the whole constructor surface again.

    @Test
    void aaAndManualTransactionsWithSimilarDescriptionsGetAFuzzyGraphEdgeNotAutoExcluded() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Transaction manual = transaction(userId, accountId, Transaction.Source.CSV_IMPORT,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), "UPI-SWIGGY-PAYMENT-REF123");
        Transaction aa = transaction(userId, accountId, Transaction.Source.ACCOUNT_AGGREGATOR,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 2), "UPI-SWIGGY-PAYMENT-REF123");

        ReconciliationServiceHarness harness = ReconciliationServiceHarness.forTransactions(List.of(manual, aa));

        harness.service.reconcileForUser(userId);

        // Never auto-excluded -- both rows still count toward totals.
        assertThat(manual.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(aa.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        harness.assertGraphEdgeRecorded(manual.getId(), aa.getId());
    }

    @Test
    void doesNotFireBetweenAccountAggregatorAndGmailImport() {
        // That pair is Plan 3's dedicated rule -- this pass must stay out of its way entirely.
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Transaction gmail = transaction(userId, accountId, Transaction.Source.GMAIL_IMPORT,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), "Swiggy order #123");
        Transaction aa = transaction(userId, accountId, Transaction.Source.ACCOUNT_AGGREGATOR,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 2), "UPI-SWIGGY-PAYMENT-REF123");

        ReconciliationServiceHarness harness = ReconciliationServiceHarness.forTransactions(List.of(gmail, aa));

        harness.service.reconcileForUser(userId);

        harness.assertNoNewFuzzyAaEdge();
    }

    @Test
    void ambiguousCandidatesResolveToTheHigherSimilarityOneNotListOrder() {
        // Regression test for the .max(...)-vs-.findFirst() distinction: two manual candidates,
        // same account/amount/window, both above the similarity threshold but at different
        // similarity scores. If this pass ever regresses back to .findFirst(), this test fails
        // regardless of which candidate happens to come first in `all`'s iteration order.
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        Transaction aa = transaction(userId, accountId, Transaction.Source.ACCOUNT_AGGREGATOR,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 2), "UPI-SWIGGY-PAYMENT-REF123");
        // Weaker match: same amount/window, lower description similarity.
        Transaction weakerCandidate = transaction(userId, accountId, Transaction.Source.MANUAL,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 1), "SWIGGY PAYMENT XYZ999");
        // Stronger match: higher description similarity to the AA row.
        Transaction strongerCandidate = transaction(userId, accountId, Transaction.Source.CSV_IMPORT,
                new BigDecimal("450.00"), LocalDate.of(2026, 9, 3), "UPI-SWIGGY-PAYMENT-REF123");

        ReconciliationServiceHarness harness =
                ReconciliationServiceHarness.forTransactions(List.of(weakerCandidate, aa, strongerCandidate));

        harness.service.reconcileForUser(userId);

        harness.assertGraphEdgeRecorded(strongerCandidate.getId(), aa.getId());
        harness.assertNoGraphEdgeRecorded(weakerCandidate.getId(), aa.getId());
    }

    private static Transaction transaction(UUID userId, UUID accountId, Transaction.Source source,
                                            BigDecimal amount, LocalDate date, String description) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setSource(source);
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(date);
        t.setDescription(description);
        return t;
    }
}
```

`ReconciliationServiceHarness` here is a placeholder name for whatever test-construction helper
`ReconciliationServiceTest` already uses to build a `ReconciliationService` with mocked
`TransactionRepository`/`AccountRepository`/etc. and assert on recorded graph edges — **read the
existing `ReconciliationServiceTest` first** and reuse its actual helper/mock pattern verbatim
rather than inventing a new one; this plan doc can't name it exactly without having read that
(large, 1400+ line) file's test companion in full.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ReconciliationServiceAccountAggregatorFuzzyMatchTest`
Expected: FAIL — no such pass exists yet (compile failure if the harness helper name is wrong;
adjust to match the real one).

- [ ] **Step 3: Add the pass**

Add a new block to `reconcile(...)` immediately after the existing Gmail cross-source pass (comment
block "4) Gmail cross-source matches"), following the identical shape but scoped to
`ACCOUNT_AGGREGATOR` on one side and `{MANUAL, CSV_IMPORT}` (never `GMAIL_IMPORT`) on the other:

```java
        // 4b) AA-vs-manual fuzzy near-duplicate matches -- design spec
        // docs/superpowers/specs/2026-09-12-account-aggregator-sync-design.md, "Reconciliation" §1.
        // Same shape as the Gmail cross-source pass immediately above (candidate FUZZY edge only,
        // never auto-excluded -- ambiguous ties are flagged for review, not auto-resolved), but for
        // the (ACCOUNT_AGGREGATOR, MANUAL|CSV_IMPORT) pair specifically. Deliberately excludes
        // GMAIL_IMPORT on either side -- that pair has its own dedicated rule (Plan 3 of the AA
        // roadmap), which DOES auto-exclude at high confidence once it exists; this pass must not
        // pre-empt it.
        //
        // Threshold/window below (0.6 similarity, 3-day window) are BOOTSTRAP VALUES ONLY, carried
        // over from the Gmail matcher's own tuned constants -- the spec is explicit these are not
        // known to transfer (generic bank narrations like "UPI-REFCODE-PAYMENT" repeat across many
        // unrelated transactions in a way a merchant-domain token doesn't). Deliberately kept as
        // plain literals here, NOT promoted to named class-level constants (e.g.
        // AA_MATCH_SIMILARITY_THRESHOLD) -- a named constant reads as "this was chosen deliberately
        // for AA," which isn't true yet. Promote them once real AA data has actually validated a
        // value; until then a literal with this comment is the more honest signal.
        //
        // Selection among multiple same-amount/same-window candidates: BEST match by similarity,
        // not first-in-list-order -- mirrors GmailReconciliationMatcher.findMatchAmongTransactions's
        // own .max(similarity, thenComparing(closest date)) exactly, so two ambiguous-but-plausible
        // candidates resolve the same way this codebase already resolves that situation for Gmail,
        // rather than depending on incidental stream/list ordering.
        List<Transaction> aaExpenses = all.stream()
                .filter(t -> t.getSource() == Transaction.Source.ACCOUNT_AGGREGATOR)
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();
        if (!aaExpenses.isEmpty()) {
            Map<BigDecimal, List<Transaction>> manualExpensesByAmount = all.stream()
                    .filter(t -> eligibleForAaManualFuzzyMatch(t.getSource()))
                    .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                    .collect(java.util.stream.Collectors.groupingBy(Transaction::getAmount));
            int aaManualWindowDays = 3; // bootstrap value -- see comment above
            for (Transaction aaTxn : aaExpenses) {
                List<Transaction> candidates = manualExpensesByAmount
                        .getOrDefault(aaTxn.getAmount(), List.of()).stream()
                        .filter(t -> Math.abs(ChronoUnit.DAYS.between(aaTxn.getTxnDate(), t.getTxnDate())) <= aaManualWindowDays)
                        .toList();
                if (candidates.isEmpty()) continue;

                candidates.stream()
                        .filter(candidate -> descriptionSimilarity(aaTxn.getDescription(), candidate.getDescription()) >= 0.6)
                        .max(Comparator.<Transaction>comparingDouble(
                                        candidate -> descriptionSimilarity(aaTxn.getDescription(), candidate.getDescription()))
                                .thenComparing(candidate -> -Math.abs(
                                        ChronoUnit.DAYS.between(aaTxn.getTxnDate(), candidate.getTxnDate()))))
                        .ifPresent(matched -> {
                            long daysApart = Math.abs(ChronoUnit.DAYS.between(aaTxn.getTxnDate(), matched.getTxnDate()));
                            int confidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.FUZZY,
                                    aaTxn.getAmount(), BigDecimal.ZERO, daysApart, aaManualWindowDays);
                            Map<String, Object> explanation = new java.util.LinkedHashMap<>();
                            explanation.put("type", "ACCOUNT_AGGREGATOR_MANUAL_CROSS_SOURCE_MATCH");
                            explanation.put("matchedTransactionId", matched.getId().toString());
                            explanation.put("daysApart", daysApart);
                            pendingEdges.add(new TransactionGraphService.PendingEdge(userId, aaTxn.getId(), matched.getId(),
                                    TransactionRelationship.RelationshipType.DUPLICATE, aaTxn.getAmount(), confidence,
                                    SourceTrust.of(aaTxn.getSource()), statusFor(confidence),
                                    TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
                        });
            }
        }
```

`eligibleForAaManualFuzzyMatch` — a small private static helper, deliberately an exhaustive
`switch` with **no `default` branch**, mirroring `SourceTrust.of()`'s own established discipline
(that class's doc comment explains why: adding a case without updating the switch is a compile
error, not a silent wrong answer). A hardcoded `== MANUAL || == CSV_IMPORT` OR-chain would instead
silently exclude any future fifth `Transaction.Source` from this pass with no signal that a
decision was ever needed:

```java
    /** Which sources this pass fuzzy-matches an ACCOUNT_AGGREGATOR row against. No default branch,
     *  deliberately: adding a fifth Transaction.Source without updating this switch is a compile
     *  error here, not a silent gap in the AA-vs-manual pass -- same discipline SourceTrust.of()
     *  already uses for the identical class of problem (see that method's own doc comment). */
    private static boolean eligibleForAaManualFuzzyMatch(Transaction.Source source) {
        return switch (source) {
            case MANUAL, CSV_IMPORT -> true;
            case GMAIL_IMPORT, ACCOUNT_AGGREGATOR -> false;
        };
    }
```

Add a `ReconciliationServiceAccountAggregatorFuzzyMatchTest` case for the ambiguity scenario
directly (two manual candidates, same account/amount/window, both above the similarity threshold,
different similarity scores) asserting the higher-similarity one is chosen — this is the concrete
regression test for the `.max(...)` selection above; without it, a future edit back to
`.findFirst()` would pass every other test in this file and still reintroduce the bug.

**Verified, not assumed:** `GmailReconciliationMatcher`'s own normalized-similarity primitive
(`private static double similarity(String a, String b)`, Levenshtein-based, line ~177) is
`private` — not callable from `ReconciliationService` as-is. Extract it (plus its private
`levenshteinDistance` helper) into a small new shared class,
`backend/src/main/java/com/finora/util/TextSimilarity.java`, with one public method
`static double normalizedSimilarity(String a, String b)`. Update `GmailReconciliationMatcher` to
call the extracted version instead of its own private copy (delete the two now-dead private
methods there), and have this task's new pass call the same extracted method as
`descriptionSimilarity(...)`. One shared implementation, not two independently-drifting copies of
"how similar are two bank narrations" — add a small `TextSimilarityTest` covering a few known
distance pairs, and re-run `GmailReconciliationMatcherTest` (or wherever its existing behavior is
tested) to confirm the extraction changed nothing about Gmail matching itself.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ReconciliationServiceAccountAggregatorFuzzyMatchTest`
Expected: PASS

Then run the FULL existing `ReconciliationServiceTest` suite — this task edits a large, heavily-
tested method, and the highest-risk regression in this whole plan is this pass interacting badly
with one of the four existing passes it now sits alongside (in particular: confirm the existing
Gmail pass's `GMAIL_IMPORT`-vs-anything matching is completely unaffected, and that an AA row with
no manual-side candidate doesn't somehow get caught by the CC-payment or transfer passes below it).

Run: `cd backend && ./mvnw test -Dtest=ReconciliationServiceTest`
Expected: PASS, zero regressions.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/service/ReconciliationService.java \
        backend/src/test/java/com/finora/service/ReconciliationServiceAccountAggregatorFuzzyMatchTest.java
git commit -m "feat(backend): add AA-vs-manual fuzzy near-duplicate reconciliation pass"
```

---

## After Task 7: run the full suite

```bash
cd backend && ./mvnw test
```

Expected: PASS, zero failures, zero errors — same bar Plan 1 held throughout. Per this project's
standing "mandatory post-implementation verification" rule, do not stop at green tests: reread this
plan's own scope doc's "Out of scope" section afterward and confirm nothing on that list was
accidentally half-built (e.g. no accidental update-in-place logic that would blur into Plan 6's
territory, no accidental CREDIT_CARD branch anywhere in the mapper).

## Addendum: corrections found during implementation

Found during a self-review pass after Task 7, before declaring the plan done — not part of any
task's original write-up:

1. **Fixed: the pre-existing Gmail cross-source pass silently gained AA as a match candidate.**
   `ReconciliationService`'s existing Gmail pass filtered its bank-side candidates with
   `source != GMAIL_IMPORT` — equivalent to "MANUAL or CSV_IMPORT" back when `Transaction.Source`
   had only three values. Task 1 added `ACCOUNT_AGGREGATOR` without updating that filter, so it
   silently started admitting AA-sourced rows as valid Gmail-match candidates: exactly the
   `(ACCOUNT_AGGREGATOR, GMAIL_IMPORT)` pair the AA sync spec reserves for its own dedicated rule
   (Plan 3), leaking into Plan 2 through a negative filter nobody updated. Fixed by excluding
   `ACCOUNT_AGGREGATOR` explicitly in that filter, with a regression test
   (`doesNotFireBetweenAccountAggregatorAndGmailImport`, strengthened to assert
   `verifyNoInteractions(gmailReconciliationMatcher)` — which fails without the fix, since the
   matcher was actually being invoked, just returning an unstubbed empty result, letting the test
   pass for the wrong reason before this was caught).

2. **Fixed: `data.ready`'s date-range computation could invert.** The dispatcher computed
   `from = lastSyncedAt's date + 1 day`, `to = today`. If the last successful sync landed earlier
   the same day (a same-day re-notification, or two `data.ready` events in one calendar day),
   `from` lands on tomorrow while `to` stays today — an inverted range passed straight to the
   gateway. Fixed: when the computed range is inverted, there is nothing new to fetch (today was
   already covered by the sync that set `lastSyncedAt`), so the dispatcher now skips the fetch
   entirely instead of calling `sync()` with a backwards range. Regression test:
   `dataReadyDoesNotInvertTheRangeWhenAlreadySyncedToday`.

3. **Not fixed, deliberately flagged instead: a narrow concurrency race in `SetuDataFetchService.sync()`.**
   Two genuinely concurrent calls to `sync()` for the same link and an overlapping date range
   (e.g. the backfill triggered by `attach()` racing against an early `data.ready` webhook arriving
   before the backfill's own `links.save(link)` commits) could both read "not yet seen" from
   `AccountAggregatorTransactionMapper`'s dedup check before either has persisted, and both then
   `saveAll(...)` the same transactions — real duplicate `Transaction` rows, since neither
   `external_txn_id` nor `transaction_fingerprint` has a database-level uniqueness constraint (see
   Task 1's migration comment: deliberately non-unique, because two genuinely different real-world
   transactions can legitimately collide on fingerprint, and a hard unique constraint would reject
   the second one outright rather than "land both, flagged for review" as the design intends).
   Closing this properly needs a decision this plan doesn't have standing to make on its own: would
   a genuinely-colliding pair of *different* real transactions be an acceptable rare loss under a
   stricter constraint, or must the "land both" policy hold even at the cost of a same-transaction
   race window staying open? Given how narrow the window is in Plan 2's actual call paths (the
   backfill can only ever fire once per link, gated by the same re-entrancy status checks Plan 1
   already relies on; a genuinely concurrent second trigger for the same link needs two requests
   racing within milliseconds), this is flagged as a known, accepted gap for Plan 2 rather than
   fixed speculatively here — a candidate for Plan 5 (cost controls) or its own follow-up, not
   silently left unstated.

4. **Fixed (found on a second, fresh review pass): a reconciliation failure was mischaracterized as
   a sync failure.** `SetuDataFetchService.sync()` originally wrapped `reconcileForImport(...)`
   inside the same try/catch as the fetch and persist steps. If reconciliation threw *after*
   `transactionRepository.saveAll(...)` had already succeeded, the whole sync was marked `FAILED` --
   misleading, since the transactions were genuinely, safely in the ledger at that point, and
   `lastSyncStatus`'s own doc comment defines it as "outcome of the most recent fetch attempt," not
   "outcome of fetch-plus-reconciliation." This matters more here than in `ImportService`'s
   equivalent call (which isn't specially isolated either): `ImportService` is a synchronous
   foreground request a user is watching and can retry, while this path is invoked from an
   unattended webhook handler where `lastSyncStatus` is the only signal anyone has. Fixed by saving
   `SUCCESS` immediately once the persist step completes, then running reconciliation in its own
   try/catch that logs but does not revert that status on failure -- a later write (another sync, a
   manual edit) re-evaluates reconciliation from scratch anyway, since `ReconciliationService`'s
   passes are idempotent full re-evaluations, not incremental deltas. Regression test:
   `reconciliationFailureDoesNotOverwriteASuccessfulPersist`.

5. **Fixed (found on a third review pass): `ambiguousCandidatesResolveToTheHigherSimilarityOneNotListOrder`
   wasn't actually testing what its name claimed.** That test's "weaker" candidate
   (`"SWIGGY PAYMENT XYZ999"`) was assumed to score above the 0.6 similarity threshold against the
   AA row's description without checking -- when actually computed via
   `TextSimilarity.normalizedSimilarity` directly (0.52), it fell *below* the threshold and was
   silently excluded from the candidate pool entirely. The test still passed, but only because
   there was ever just one qualifying candidate, not because `.max(...)`'s tie-break between two
   ambiguous candidates was correctly exercised -- a `.findFirst()` regression would have slipped
   past it undetected. Verified this concretely: temporarily reverted the code to `.findFirst()`,
   confirmed the ORIGINAL test still passed (false confidence), then fixed the test's weaker
   candidate to `"UPI-SWIGGY-PMT-REF123"` (verified similarity 0.84, genuinely above threshold and
   below the exact match's 1.0), confirmed THIS version fails against `.findFirst()`, then restored
   the correct `.max(...)` implementation and confirmed it passes. A reminder that a passing test
   is not proof of what it claims to test -- the assertion has to be checked against real, computed
   values, not assumed plausible-looking fixture strings.
