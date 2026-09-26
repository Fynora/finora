# Financial Flow Classification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop counting every credit as income. Classify each inflow by its economic nature (income, refund, transfer, investment, loan, adjustment, or *unresolved*) and make every income figure count only true income.

**Architecture:** A pure, versioned `FlowClassifier` derives a flow class for a transaction from state that already exists: direction, reconciliation status, `counterparty_type`, description, and account type. It is **derived at read time, not stored**, so it can never go stale when reconciliation, categorisation or counterparty classification re-runs. A single choke point, `FlowTotals`, replaces every `txnType == INCOME` total. Persisted state (user overrides, account identity, reimbursement links) arrives in later plans and feeds the same classifier.

**Tech Stack:** Java 21 / Spring Boot backend (Maven wrapper `backend/mvnw`), JUnit 5 + AssertJ + Mockito, Flyway (later plans only), React web (`frontend/`), React Native (`mobile/`), OpenAPI-generated client types.

**Spec:** the review sent 2026-09-26, `fynora-financial-flow-model-review.md` (scratchpad), together with the decisions taken after it in conversation:
- Allocations (EMI/FD principal-vs-interest splits) are deferred to Phase B.
- There are no real users yet, so there is no dual-run, no per-user flag and no "numbers changed" card.
- Phase 0 measures against the real statement corpus, not the database.
- A card-account credit is never earned income.

Before execution, copy the spec into `docs/proposals/` alongside this plan (Task 1, Step 1).

**Grounded on:** `origin/main` @ `8421d19d`. Every file/line reference below was read at that commit. Re-check line numbers after `git fetch`; they drift.

---

## Program overview (read first)

This is one program in seven plans. **This document fully details Plans 0 and 1**, which are executable now. Plans 2–6 are specified to the level of files, interfaces and acceptance tests. Following the writing-plans scope rule (one plan per subsystem), each one **must be expanded into its own detailed plan, re-grounded on then-current `origin/main`, before execution.**

| Plan | Delivers | Depends on | Launch-blocking? |
|---|---|---|---|
| 0 | Corpus measurement: value share of credits by flow class across the real statements | Plan 1 Task 2 (the classifier) | Yes — gates keyword lists |
| 1 | `FlowClassifier` + `FlowTotals`; income totals switch; `unresolvedInflow`/count in DTOs; display-only banner (Task 8b) | — | Yes |
| 2 | Resolve UX: user override persisted, per-counterparty "always" rule, chips on web + mobile, dashboard line | 1 | Yes (1 without 2 hides money with no way to fix it) |
| 3 | Account identity registry + one-sided own-account transfers | 1 | Recommended |
| 4 | Unlinked refunds and reimbursements net against spend across all spend consumers | 1, 2 | Recommended |
| 5 | Reimbursement linking (split-shaped suggestions) | 2, 4 | No |
| 6 | Phase B: allocations (EMI principal/interest, FD maturity principal/interest) | 1–4 | No |

Plans 0 and 1 are one branch and one PR. Plan 0's probe is part of it: the probe's output is the evidence that the keyword lists are right.

## Global Constraints

- **Worktree only.** The primary checkout `/Users/sid/Downloads/finora` is read-only for writes. Before the first write: `git fetch origin && git worktree add ../finora-flow-class -b feature/flow-class origin/main`. Use absolute worktree paths for every git and build command.
- **No AI attribution in commit messages.** No `Co-Authored-By: Claude` trailer, ever (repo CLAUDE.md absolute rule).
- **No Flyway migration in Plans 0–1.** Later plans must list `backend/src/main/resources/db/migration` on fresh `origin/main` before numbering (the highest at planning time was `V229`), and never reuse or renumber a version.
- **Real statements never enter the repo.** The corpus lives outside the working tree. Test fixtures use synthetic narrations only, and code comments describe real evidence without quoting literal values from real documents.
- **Direction is unchanged.** `Transaction.Type` stays as it is. It still drives sign, colour and balance maths (`AccountBalanceConvention`), and nothing in this program rewrites `txn_type`.
- **Debit-side totals are unchanged in Plan 1.** Plan 1 only changes what counts as *income*. Spend figures, budgets, category breakdowns, insights and admin platform analytics are byte-for-byte unchanged. (Netting unlinked refunds into spend is Plan 4, and it needs all spend consumers changed together to keep screens consistent.)
- **The OpenAPI drift check is blocking in CI.** Any DTO change requires regenerating `backend/openapi/openapi.json` and all three `generated-types.ts` files (`frontend/`, `mobile/`, `admin-portal/`), via `scripts/generate-openapi-spec.sh` and then `openapi-typescript@7.13.0`, run from `mobile/` with `typescript` installed `--no-save` (a local Node 26 quirk).
- **Reproduce CI under Node 22** for any client-side test (`npx node@22`).
- **Full backend verification is `./mvnw verify`**, not `test`, because `test` skips about 1,400 ITs. Copy the jar out of `backend/target` before running it as a server.

## Review Focus

These are the five input classes most likely to bite, none of them exercised by the spec's own examples. Each has a test pinned in the owning task.

1. **A transaction whose account is missing from the account-type map** (null `accountId` in test fixtures, or a row whose account was deleted mid-request). It must classify as a non-card account, not throw. Pinned in Task 2 (`nullAccountType_isTreatedAsNonCard`) and Task 3 (`countsAsIncome_rowWithUnknownAccount`).
2. **Null or blank description** (manual transactions, AA rows with empty narration). It must fall through to counterparty and default rules, not NPE. Pinned in Task 2 (`nullDescription_incomeFallsToOtherIncome`).
3. **A government tax refund.** The word "refund" must not turn an income-tax refund into a merchant refund. Pinned in Task 2 (`governmentRefund_isTaxRefundIncome`).
4. **Salary paid by a person-typed counterparty** (a small employer that pays from a proprietor's account). The salary signal must win over `PERSON`. Pinned in Task 2 (`salaryKeyword_beatsPersonCounterparty`).
5. **Savings rate when every credit in the month is unresolved.** Income becomes 0. The existing `savingsRatePct` returns `0`, not a divide-by-zero. Pinned in Task 5 (`summarize_allCreditsUnresolved_incomeZeroNoException`). Changing 0 to "—" is a Plan 2 UX change and is deliberately not done here.

---

## File structure (Plans 0–1)

| File | Status | Responsibility |
|---|---|---|
| `backend/src/main/java/com/finora/service/FlowClassifier.java` | Create | Pure rules: one transaction plus its account type gives a `FlowDecision`. No I/O. |
| `backend/src/main/java/com/finora/service/FlowTotals.java` | Create | The single choke point every income total goes through. Builds the account-type map. |
| `backend/src/main/java/com/finora/service/ReconciliationService.java` | Modify `:1897-1905` | `looksLikeRefund` / `looksLikeReversal` become `static` package-private so the classifier reuses the same vocabulary. There is no second copy. |
| `backend/src/main/java/com/finora/service/ReportService.java` | Modify `:60-121` | Income totals through `FlowTotals`. New `unresolvedInflow`. |
| `backend/src/main/java/com/finora/dto/ReportDto.java` | Modify | Add the `unresolvedInflow` component. |
| `backend/src/main/java/com/finora/service/DashboardService.java` | Modify `:146-149, :403-409, :573-574, :337` | Income totals through `FlowTotals`. New `unresolvedInflow`. |
| `backend/src/main/java/com/finora/dto/DashboardSummaryDto.java` | Modify | Add the `unresolvedInflow` component (last). |
| `backend/src/main/java/com/finora/service/AnalyticsService.java` | Modify `:434-441` | `activeIncomeTransactions` filters through `FlowTotals`. |
| `backend/src/test/java/com/finora/service/FlowClassifierTest.java` | Create | Rule table tests. |
| `backend/src/test/java/com/finora/service/FlowTotalsTest.java` | Create | Choke-point tests. |
| `backend/src/test/java/com/finora/service/ReportServiceTest.java` | Modify | New income-semantics tests. |
| `backend/src/test/java/com/finora/service/DashboardServiceTest.java` | Modify | New income-semantics tests. |
| `backend/src/test/java/com/finora/service/AnalyticsServiceTest.java` | Modify | New income-semantics test. |
| `backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java` | Modify `:45` | Pass the new DTO argument. |
| `backend/src/test/java/com/finora/imports/analysis/FlowClassCorpusProbe.java` | Create | Plan 0 probe (manual `main`, not a `@Test`). |
| `backend/openapi/openapi.json`, `{frontend,mobile,admin-portal}/src/api/generated-types.ts` | Regenerate | DTO drift. |
| `docs/proposals/financial-flow-model.md` | Create | The spec (review plus decisions), copied in. |

---

### Task 1: Worktree, spec in repo, baseline

**Files:**
- Create: `docs/proposals/financial-flow-model.md`

**Interfaces:** Produces the branch `feature/flow-class` that every later task commits to.

- [ ] **Step 1: Create the worktree and verify it**

```bash
cd /Users/sid/Downloads/finora && git fetch origin
git worktree add ../finora-flow-class -b feature/flow-class origin/main
cd /Users/sid/Downloads/finora-flow-class && pwd && git branch --show-current && git worktree list && git status --short
```
Expected: `pwd` is `/Users/sid/Downloads/finora-flow-class`, the branch is `feature/flow-class`, and the status is empty.

- [ ] **Step 2: Copy the spec in**

Copy the scratchpad review to `docs/proposals/financial-flow-model.md`, then append a `## Decisions after review (2026-09-26)` section listing the four decisions from this plan's **Spec** line. Don't paste real narrations. Describe the motivating card example as "two same-day merchant credits on a card statement".

- [ ] **Step 3: Record the baseline backend run**

```bash
cd /Users/sid/Downloads/finora-flow-class/backend && ./mvnw -q test -Dtest='ReportServiceTest,DashboardServiceTest,AnalyticsServiceTest,RefundNettingTest,ReconciliationServiceTest' 2>&1 | tail -20
```
Expected: all pass. Save the tail to the scratchpad as `baseline-tests.txt`. If anything fails on a clean `origin/main`, stop and report it: it's out of scope and needs flagging, not fixing.

- [ ] **Step 4: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add docs/proposals/financial-flow-model.md
git -C /Users/sid/Downloads/finora-flow-class commit -m "docs(proposals): financial flow model review and decisions"
```

---

### Task 2: `FlowClassifier`: pure rules

**Files:**
- Create: `backend/src/main/java/com/finora/service/FlowClassifier.java`
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java:1897-1905`
- Test: `backend/src/test/java/com/finora/service/FlowClassifierTest.java`

**Interfaces:**
- Consumes:
  - `CategoryRules.normalize(String)` and `CategoryRules.suggestCategory(String)` (`com.finora.util`).
  - `Transaction` getters `getTxnType()`, `isTransfer()`, `getReconciliationStatus()`, `getDescription()`, `getCounterpartyType()`.
  - `Account.Type`.
  - `ReconciliationService.looksLikeRefund(String)` and `ReconciliationService.looksLikeReversal(String)` (made static in this task).
- Produces:
  - `FlowClassifier.VERSION` (`short`, 1).
  - `enum FlowClassifier.FlowClass { INCOME, EXPENSE, REFUND, TRANSFER, INVESTMENT, LIABILITY, ADJUSTMENT, UNRESOLVED }`.
  - `enum FlowClassifier.FlowReason` (values below).
  - `record FlowClassifier.FlowDecision(FlowClass flowClass, FlowReason reason)`.
  - `static FlowDecision classify(Transaction t, Account.Type accountType)`, where `accountType` may be null.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier.FlowClass;
import com.finora.service.FlowClassifier.FlowDecision;
import com.finora.service.FlowClassifier.FlowReason;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a transaction did to the user's wealth, independent of its direction. Every narration below
 * is synthetic -- shaped like a real rail's narration, never copied from a real statement.
 */
class FlowClassifierTest {

    private static Transaction credit(String description) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(new BigDecimal("100.00"));
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    private static Transaction debit(String description) {
        Transaction t = credit(description);
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    private static FlowDecision savings(Transaction t) { return FlowClassifier.classify(t, Account.Type.SAVINGS); }
    private static FlowDecision card(Transaction t) { return FlowClassifier.classify(t, Account.Type.CREDIT_CARD); }

    // ---- status-driven: reconciliation already decided, the classifier only names it ----

    @Test void pairedTransferCredit_isTransfer() {
        Transaction t = credit("NEFT FROM OWN ACCOUNT"); t.setTransfer(true);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER));
    }

    @Test void matchedRefundLeg_isLinkedRefund() {
        Transaction t = credit("MERCHANTCO ORDER 1001"); t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.LINKED_REFUND));
    }

    @Test void matchedReversalLeg_isAdjustment() {
        Transaction t = credit("MERCHANTCO 1001"); t.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.REVERSAL));
    }

    // ---- keyword-driven, any account ----

    @Test void unmatchedRefundKeyword_isUnlinkedRefund() {
        assertThat(savings(credit("REFUND MERCHANTCO ORDER 1001")))
                .isEqualTo(new FlowDecision(FlowClass.REFUND, FlowReason.UNLINKED_REFUND));
    }

    @Test void unmatchedReversalKeyword_isAdjustment() {
        assertThat(savings(credit("UPI PAYMENT REVERSED 111111111111")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.REVERSAL));
    }

    @Test void governmentRefund_isTaxRefundIncome() {
        Transaction t = credit("TAX REFUND CPC AY 2026");
        t.setCounterpartyType(CounterpartyType.GOVERNMENT);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.TAX_REFUND));
    }

    // ---- savings-side inflows ----

    @Test void investmentRedemption_isInvestmentWithdrawal() {
        assertThat(savings(credit("NEFT MF REDEMPTION FUNDHOUSE")))
                .isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL));
    }

    @Test void fdClosure_isInvestmentWithdrawal() {
        assertThat(savings(credit("FD CLOSURE PROCEEDS 000123")))
                .isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL));
    }

    @Test void loanDisbursal_isLiabilityDrawdown() {
        assertThat(savings(credit("LOAN DISBURSAL LENDERCO LAN 99887766")))
                .isEqualTo(new FlowDecision(FlowClass.LIABILITY, FlowReason.LOAN_DRAWDOWN));
    }

    @Test void salary_isIncome() {
        assertThat(savings(credit("NEFT ACME TECHNOLOGIES SALARY JUL")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.SALARY));
    }

    @Test void salaryKeyword_beatsPersonCounterparty() {
        Transaction t = credit("IMPS SALARY FOR JULY A PROPRIETOR");
        t.setCounterpartyType(CounterpartyType.PERSON);
        assertThat(savings(t).flowClass()).isEqualTo(FlowClass.INCOME);
    }

    @Test void savingsInterest_isIncome() {
        assertThat(savings(credit("INT.PD:01-07-2026 TO 30-09-2026")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.INTEREST));
    }

    @Test void personInflow_isUnresolved() {
        Transaction t = credit("UPI/111111111111/A PERSON/person@okbank");
        t.setCounterpartyType(CounterpartyType.PERSON);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW));
    }

    @Test void businessInflowWithNoSignal_isOtherIncome() {
        Transaction t = credit("NEFT CLIENTCO PVT LTD INV 42");
        t.setCounterpartyType(CounterpartyType.BUSINESS);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.OTHER_INCOME));
    }

    @Test void nullDescription_incomeFallsToOtherIncome() {
        assertThat(savings(credit(null))).isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.OTHER_INCOME));
    }

    @Test void nullAccountType_isTreatedAsNonCard() {
        assertThat(FlowClassifier.classify(credit("NEFT CLIENTCO PVT LTD"), null).flowClass()).isEqualTo(FlowClass.INCOME);
    }

    // ---- credit-card account: a credit is never earned income by default ----

    @Test void cardPaymentReceived_isTransfer() {
        assertThat(card(credit("PAYMENT RECEIVED THANK YOU")))
                .isEqualTo(new FlowDecision(FlowClass.TRANSFER, FlowReason.CARD_PAYMENT_RECEIVED));
    }

    @Test void cardBbpsPayment_isTransfer() {
        assertThat(card(credit("BBPS PAYMENT 123456"))).extracting(FlowDecision::flowClass).isEqualTo(FlowClass.TRANSFER);
    }

    @Test void cardFeeWaiver_isAdjustment() {
        assertThat(card(credit("FUEL SURCHARGE WAIVER")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT));
    }

    @Test void cardEmiConversionCredit_isAdjustment() {
        assertThat(card(credit("EMI CONVERSION MERCHANTCO 1001")))
                .isEqualTo(new FlowDecision(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT));
    }

    @Test void cardCashback_isRewardIncome() {
        assertThat(card(credit("CASHBACK CREDITED")))
                .isEqualTo(new FlowDecision(FlowClass.INCOME, FlowReason.REWARD));
    }

    @Test void cardMerchantCreditWithNoSignal_isUnresolved() {
        // Shape of a merchant credit on a card statement with no refund word: honest unknown, never income.
        assertThat(card(credit("UPI MERCHANTCO 111111111111")))
                .isEqualTo(new FlowDecision(FlowClass.UNRESOLVED, FlowReason.CARD_UNEXPLAINED_CREDIT));
    }

    @Test void cardSalaryLookalike_isStillNotIncome() {
        assertThat(card(credit("NEFT SALARY ADVANCE")).flowClass()).isNotEqualTo(FlowClass.INCOME);
    }

    // ---- outflows: named only, totals unchanged in Plan 1 ----

    @Test void ordinaryDebit_isExpense() {
        assertThat(savings(debit("UPI MERCHANTCO"))).isEqualTo(new FlowDecision(FlowClass.EXPENSE, FlowReason.PURCHASE));
    }

    @Test void pairedTransferDebit_isTransfer() {
        Transaction t = debit("NEFT TO OWN ACCOUNT"); t.setTransfer(true);
        assertThat(savings(t).flowClass()).isEqualTo(FlowClass.TRANSFER);
    }

    @Test void investmentTransferDebit_isInvestmentContribution() {
        Transaction t = debit("NACH SIP FUNDHOUSE");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
        assertThat(savings(t)).isEqualTo(new FlowDecision(FlowClass.INVESTMENT, FlowReason.INVESTMENT_CONTRIBUTION));
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `cd /Users/sid/Downloads/finora-flow-class/backend && ./mvnw -q test -Dtest=FlowClassifierTest`
Expected: compilation failure, `cannot find symbol: class FlowClassifier`.

- [ ] **Step 3: Make the keyword helpers static and shareable**

In `ReconciliationService.java`, replace the two helpers (currently `:1897-1905`) with:

```java
    /** Package-visible and static so {@link FlowClassifier} reads the exact same refund vocabulary
     *  this pass matches on -- one word list, not two that can drift. */
    static boolean looksLikeRefund(String description) {
        String normalized = CategoryRules.normalize(description);
        return REFUND_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /** See {@link #looksLikeRefund}. */
    static boolean looksLikeReversal(String description) {
        String normalized = CategoryRules.normalize(description);
        return REVERSAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }
```
(`REFUND_KEYWORDS`/`REVERSAL_KEYWORDS` are already `private static final`. Static methods in the same class can read them, and the existing instance call sites compile unchanged.)

- [ ] **Step 4: Write the implementation**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyType;

import java.util.List;

/**
 * What a transaction did to the user's wealth -- its FLOW CLASS -- as distinct from its direction
 * ({@link Transaction.Type}), its purpose (category) and who was on the other side (counterparty).
 *
 * <h2>Why this exists</h2>
 * Every income figure used to be "credits minus the exceptions reconciliation had detected". Any
 * credit no detector recognised -- money from a person, an FD maturity, a loan disbursal, any credit
 * on a credit-card account -- fell back to income. This class makes the default for an unknown credit
 * {@link FlowClass#UNRESOLVED} rather than a confident claim of income.
 *
 * <h2>Derived, never stored</h2>
 * Every input is state other passes already maintain (reconciliation status, transfer flag,
 * counterparty type, narration, account type). Computing it at read time means it can never
 * disagree with them after a re-run. User overrides (Plan 2) will be an input here, not a cache.
 *
 * <h2>Scope of Plan 1</h2>
 * Outflows are NAMED here but every spend total is unchanged; only income totals read this.
 * Bump {@link #VERSION} whenever a rule changes what an existing row classifies as.
 */
public final class FlowClassifier {

    public static final short VERSION = 1;

    public enum FlowClass { INCOME, EXPENSE, REFUND, TRANSFER, INVESTMENT, LIABILITY, ADJUSTMENT, UNRESOLVED }

    public enum FlowReason {
        SALARY, INTEREST, REWARD, TAX_REFUND, OTHER_INCOME,
        PURCHASE,
        LINKED_REFUND, UNLINKED_REFUND, REVERSAL, CARD_ADJUSTMENT,
        OWN_ACCOUNT_TRANSFER, CARD_PAYMENT_RECEIVED,
        INVESTMENT_CONTRIBUTION, INVESTMENT_WITHDRAWAL,
        LOAN_DRAWDOWN,
        PERSON_INFLOW, CARD_UNEXPLAINED_CREDIT
    }

    public record FlowDecision(FlowClass flowClass, FlowReason reason) {}

    // Word-start matches against CategoryRules.normalize output (lower-case, punctuation -> space).
    // Initial lists; Plan 0's corpus probe is the evidence that confirms or corrects them before merge.
    static final List<String> CARD_PAYMENT_KEYWORDS = List.of(
            "payment received", "payment recd", "payment thank", "thank you", "bbps", "autopay", "auto debit");
    static final List<String> CARD_ADJUSTMENT_KEYWORDS = List.of(
            "waiver", "waived", "surcharge", "emi conversion", "converted to emi", "conv to emi");
    static final List<String> REWARD_KEYWORDS = List.of("cashback", "cash back", "reward");
    static final List<String> INVESTMENT_INFLOW_KEYWORDS = List.of(
            "redemption", "redeem", "fd closure", "fd maturity", "maturity proceeds", "iccl");
    static final List<String> LOAN_DRAWDOWN_KEYWORDS = List.of("loan disb", "disbursal", "disbursement");
    static final List<String> INTEREST_KEYWORDS = List.of("int pd", "interest", "int cr", "int credit", "sb int");

    private FlowClassifier() {}

    /** @param accountType the owning account's type; null is treated as a non-card account */
    public static FlowDecision classify(Transaction t, Account.Type accountType) {
        return t.getTxnType() == Transaction.Type.EXPENSE ? outflow(t) : inflow(t, accountType);
    }

    private static FlowDecision outflow(Transaction t) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.INVESTMENT_TRANSFER) {
            return of(FlowClass.INVESTMENT, FlowReason.INVESTMENT_CONTRIBUTION);
        }
        return of(FlowClass.EXPENSE, FlowReason.PURCHASE);
    }

    private static FlowDecision inflow(Transaction t, Account.Type accountType) {
        if (t.isTransfer()) return of(FlowClass.TRANSFER, FlowReason.OWN_ACCOUNT_TRANSFER);
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND) {
            return of(FlowClass.REFUND, FlowReason.LINKED_REFUND);
        }
        if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL) {
            return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        }

        String description = t.getDescription();
        String text = " " + CategoryRules.normalize(description) + " ";

        // Before the refund word: an income-tax refund is income, not money back from a merchant.
        if (t.getCounterpartyType() == CounterpartyType.GOVERNMENT && ReconciliationService.looksLikeRefund(description)) {
            return of(FlowClass.INCOME, FlowReason.TAX_REFUND);
        }
        if (ReconciliationService.looksLikeReversal(description)) return of(FlowClass.ADJUSTMENT, FlowReason.REVERSAL);
        if (ReconciliationService.looksLikeRefund(description)) return of(FlowClass.REFUND, FlowReason.UNLINKED_REFUND);

        if (accountType == Account.Type.CREDIT_CARD) {
            // A card is a liability: a credit on it pays the debt down, gives money back, or rewards
            // spend. None of those is earned income, so an unexplained one is UNRESOLVED, never INCOME.
            if (hasAny(text, CARD_ADJUSTMENT_KEYWORDS)) return of(FlowClass.ADJUSTMENT, FlowReason.CARD_ADJUSTMENT);
            if (hasAny(text, REWARD_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.REWARD);
            if (hasAny(text, CARD_PAYMENT_KEYWORDS)) return of(FlowClass.TRANSFER, FlowReason.CARD_PAYMENT_RECEIVED);
            return of(FlowClass.UNRESOLVED, FlowReason.CARD_UNEXPLAINED_CREDIT);
        }

        String suggested = CategoryRules.suggestCategory(description);
        if (ReconciliationService.INVESTMENTS_CATEGORY.equals(suggested) || hasAny(text, INVESTMENT_INFLOW_KEYWORDS)) {
            return of(FlowClass.INVESTMENT, FlowReason.INVESTMENT_WITHDRAWAL);
        }
        if (hasAny(text, LOAN_DRAWDOWN_KEYWORDS)) return of(FlowClass.LIABILITY, FlowReason.LOAN_DRAWDOWN);
        if ("Salary".equals(suggested)) return of(FlowClass.INCOME, FlowReason.SALARY);
        if (hasAny(text, INTEREST_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.INTEREST);
        if (hasAny(text, REWARD_KEYWORDS)) return of(FlowClass.INCOME, FlowReason.REWARD);
        if (t.getCounterpartyType() == CounterpartyType.PERSON) return of(FlowClass.UNRESOLVED, FlowReason.PERSON_INFLOW);
        return of(FlowClass.INCOME, FlowReason.OTHER_INCOME);
    }

    /** Word-START match on the space-padded normalised text: "reward" matches "rewards", but
     *  "disbursement" does not match "reimbursement". */
    static boolean hasAny(String paddedNormalized, List<String> keywords) {
        for (String k : keywords) {
            if (paddedNormalized.contains(" " + k)) return true;
        }
        return false;
    }

    private static FlowDecision of(FlowClass c, FlowReason r) { return new FlowDecision(c, r); }
}
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `cd /Users/sid/Downloads/finora-flow-class/backend && ./mvnw -q test -Dtest='FlowClassifierTest,ReconciliationServiceTest'`
Expected: all pass. If `salary_isIncome` fails, print `CategoryRules.suggestCategory("NEFT ACME TECHNOLOGIES SALARY JUL")` in a scratch test and read what the rule table actually returns before touching either side. Don't guess.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/main/java/com/finora/service/FlowClassifier.java backend/src/main/java/com/finora/service/ReconciliationService.java backend/src/test/java/com/finora/service/FlowClassifierTest.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(analytics): flow classifier names what each transaction did to the user's wealth"
```

---

### Task 3: Plan 0: corpus measurement probe, and the keyword gate

**Files:**
- Create: `backend/src/test/java/com/finora/imports/analysis/FlowClassCorpusProbe.java`

**Interfaces:**
- Consumes:
  - `FlowClassifier.classify` (Task 2).
  - The staging pipeline, constructed exactly as `ProductIdentityCorpusProbe.probeOne` builds it (`PdfPreviewGenerator` with `stubbedNormalizer()`, `generateSectionsWithContext`).
  - `ImportDto.StagedAccountSection.rows()` / `StagedRow.description()`, `.amount()`, `.type()`.
  - `DetectedAccountInfo.detectedProduct()`.
  - `CounterpartyClassifier.classify(String)`.
- Produces: a manual `main` that prints, per statement and in total, credit count and ₹ value by `FlowClass`/`FlowReason`. Also a keyword-hit table: for each keyword list, the number of hits and 5 hit narrations, **redacted** to their normalised merchant token via `CategoryRules.extractMerchant`, so no names or account numbers are printed.

**Trader/landlord detection data.** Per statement, the probe also prints how many distinct person payers credited it (distinct `CounterpartyIdentity` keys among `PERSON_INFLOW` rows), their median credit amount, and the credit-to-debit row ratio. That's the evidence for calibrating Plan 2's "this account looks like a shop" suggestion, and it shows whether such statements exist in the corpus at all. If none do, say so; don't assume they're rare.

**Severity is measured per statement, not per user.** The corpus has no user ids and one person can own several statements, so "users affected" can't be computed honestly from it. The probe reports statements affected and the median, p95 and max share of each statement's credit value removed. A per-user view needs the Plan 1 code running against real accounts after launch.

**Limits to state in the output header, because they're true:** a single statement has no reconciliation context. Transfer pairs, matched refunds and CC_PAYMENT edges don't exist, so `LINKED_REFUND`/`OWN_ACCOUNT_TRANSFER` read 0 here, and the remaining classes are an **upper bound** on what the live system would show.

- [ ] **Step 1: Write the probe**

```java
package com.finora.imports.analysis;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.service.FlowClassifier;
import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyClassifier;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Plan 0 of the financial-flow program: across the real, out-of-tree statement corpus, how much
 * credit VALUE lands in each flow class? Answers which gap (money from people, card credits,
 * investment inflows, loans, unmatched refunds) is worth fixing first -- measured, not assumed.
 *
 * <p>Single-statement view: no reconciliation context, so paired transfers and matched refunds are
 * absent and every other class is an upper bound. Prints merchant tokens only, never raw narrations.
 * Run manually; not a {@code @Test}, same as {@link CorpusProbe}.
 */
public final class FlowClassCorpusProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: FlowClassCorpusProbe <path-to.pdf> [<path-to.pdf> ...]");
            System.exit(2);
        }
        System.out.println("# single-statement view: LINKED_REFUND / OWN_ACCOUNT_TRANSFER cannot appear; other classes are upper bounds");
        Map<String, BigDecimal[]> total = new TreeMap<>();
        for (String arg : args) {
            Map<String, BigDecimal[]> one = probeOne(Path.of(arg));
            one.forEach((k, v) -> total.merge(k, v, (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])}));
        }
        System.out.println("== TOTAL (count, value) by class/reason");
        total.forEach((k, v) -> System.out.printf("  %-45s %6s  %15s%n", k, v[0].toPlainString(), v[1].toPlainString()));

        // The before/after headline: what "income" was (every credit) vs what it becomes (INCOME class).
        BigDecimal allCredits = total.values().stream().map(v -> v[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stillIncome = total.entrySet().stream().filter(e -> e.getKey().contains(" INCOME/"))
                .map(e -> e.getValue()[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal removed = allCredits.subtract(stillIncome);
        System.out.printf("== credits (old income) %s | new income %s | removed %s (%s%%) | rows affected %d%n",
                allCredits.toPlainString(), stillIncome.toPlainString(), removed.toPlainString(),
                pct(removed, allCredits), ROWS_AFFECTED[0]);

        // Severity per STATEMENT, not per user: the corpus has no user ids, and several statements
        // can belong to one person -- so this is "how hard does one statement's income get cut".
        List<BigDecimal> cuts = PER_STATEMENT_CUT_PCT.stream().sorted().toList();
        long hit = cuts.stream().filter(c -> c.signum() > 0).count();
        System.out.printf("== statements with credits %d | affected %d | median cut %s%% | p95 cut %s%% | max cut %s%%%n",
                cuts.size(), hit, percentile(cuts, 50), percentile(cuts, 95),
                cuts.isEmpty() ? "-" : cuts.get(cuts.size() - 1).toPlainString());

        // "Which transactions caused that?" -- the largest removed-from-income counterparties.
        // Contains real names/merchants: this output stays in the scratchpad, never in the repo or PR.
        System.out.println("== TOP 25 counterparties removed from income (class, token, count, value)");
        REMOVED_BY_TOKEN.entrySet().stream()
                .sorted((a, b) -> b.getValue()[1].compareTo(a.getValue()[1])).limit(25)
                .forEach(e -> System.out.printf("  %-60s %6s  %15s%n", e.getKey(),
                        e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString()));
    }

    /** "<class/reason> <merchant token>" -> {count, value}, for every credit that is no longer INCOME. */
    private static final Map<String, BigDecimal[]> REMOVED_BY_TOKEN = new java.util.HashMap<>();
    /** One entry per statement that had any credits: % of its credit value removed from income. */
    private static final List<BigDecimal> PER_STATEMENT_CUT_PCT = new java.util.ArrayList<>();
    private static final int[] ROWS_AFFECTED = {0};

    static String pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? "-" : part.multiply(BigDecimal.valueOf(100))
                .divide(whole, 1, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Nearest-rank percentile over an ascending list; "-" when empty. */
    static String percentile(List<BigDecimal> ascending, int p) {
        if (ascending.isEmpty()) return "-";
        int rank = (int) Math.ceil(p / 100.0 * ascending.size());
        return ascending.get(Math.max(0, rank - 1)).toPlainString();
    }

    static Map<String, BigDecimal[]> probeOne(Path pdf) throws Exception {
        byte[] bytes = Files.readAllBytes(pdf);
        // Construct the generator EXACTLY as ProductIdentityCorpusProbe.probeOne does (copy that
        // block verbatim, including its private stubbedNormalizer()), so this probe cannot drift
        // onto a different pipeline from the one already validated against the corpus.
        var generator = ProbePipelines.standardGenerator();
        List<StagedAccountSection> sections = generator
                .generateSectionsWithContext(UUID.randomUUID(), pdf.getFileName().toString(), bytes, null).sections();

        Map<String, BigDecimal[]> byKey = new TreeMap<>();
        for (StagedAccountSection section : sections) {
            Account.Type accountType = section.detectedAccount() != null
                    && "CREDIT_CARD".equals(section.detectedAccount().detectedProduct())
                    ? Account.Type.CREDIT_CARD : Account.Type.SAVINGS;
            for (StagedRow row : section.rows()) {
                if (!"INCOME".equals(row.type())) continue;
                Transaction t = new Transaction();
                t.setTxnType(Transaction.Type.INCOME);
                t.setAmount(row.amount());
                t.setDescription(row.description());
                t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
                t.setCounterpartyType(CounterpartyClassifier.classify(row.description()));
                FlowClassifier.FlowDecision d = FlowClassifier.classify(t, accountType);
                String key = accountType + " " + d.flowClass() + "/" + d.reason();
                byKey.merge(key, new BigDecimal[]{BigDecimal.ONE, row.amount()},
                        (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
                if (d.flowClass() != FlowClassifier.FlowClass.INCOME) {
                    ROWS_AFFECTED[0]++;
                    REMOVED_BY_TOKEN.merge(d.flowClass() + "/" + d.reason() + " " + CategoryRules.extractMerchant(row.description()),
                            new BigDecimal[]{BigDecimal.ONE, row.amount()},
                            (a, b) -> new BigDecimal[]{a[0].add(b[0]), a[1].add(b[1])});
                }
                System.out.printf("  %s  %-45s %12s  %s%n", pdf.getFileName(), key, row.amount().toPlainString(),
                        CategoryRules.extractMerchant(row.description()));
            }
        }
        // Trader/landlord shape: distinct person payers, their median credit, credit:debit row ratio.
        java.util.Set<String> payers = new java.util.HashSet<>();
        List<BigDecimal> personAmounts = new java.util.ArrayList<>();
        int creditRows = 0, debitRows = 0;
        for (StagedAccountSection section : sections) {
            for (StagedRow row : section.rows()) {
                if (!"INCOME".equals(row.type())) { debitRows++; continue; }
                creditRows++;
                if (CounterpartyClassifier.classify(row.description()) == com.finora.util.CounterpartyType.PERSON) {
                    String key = com.finora.util.CounterpartyIdentity.keyOf(row.description());
                    if (key != null) payers.add(key);
                    personAmounts.add(row.amount());
                }
            }
        }
        System.out.printf("  %s  distinctPersonPayers=%d medianPersonCredit=%s credit:debit=%d:%d%n",
                pdf.getFileName(), payers.size(), percentile(personAmounts.stream().sorted().toList(), 50),
                creditRows, debitRows);

        BigDecimal credits = byKey.values().stream().map(v -> v[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (credits.signum() > 0) {
            BigDecimal kept = byKey.entrySet().stream().filter(e -> e.getKey().contains(" INCOME/"))
                    .map(e -> e.getValue()[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            PER_STATEMENT_CUT_PCT.add(credits.subtract(kept).multiply(BigDecimal.valueOf(100))
                    .divide(credits, 1, java.math.RoundingMode.HALF_UP));
        }
        return byKey;
    }
}
```

Also create `ProbePipelines` in the same package. Move the generator construction block and `stubbedNormalizer()` from `ProductIdentityCorpusProbe.java:84-95,189-200` into `static PdfPreviewGenerator standardGenerator()`, and change `ProductIdentityCorpusProbe` to call it. This is a pure move (one copy, not three), so it gets its own commit.

Before relying on them, verify the two string literals by reading the source:
- the `detectedProduct` value used for cards (`FinancialProductType` names, `backend/src/main/java/com/finora/imports/product/FinancialProductType.java`);
- the `StagedRow.type` values.

If either differs from `"CREDIT_CARD"` / `"INCOME"`, use the real value.

- [ ] **Step 2: Compile and smoke-test on one statement**

```bash
cd /Users/sid/Downloads/finora-flow-class/backend && ./mvnw -q test-compile
./mvnw -q exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.finora.imports.analysis.FlowClassCorpusProbe -Dexec.args="<ONE-CORPUS-PDF-OUTSIDE-REPO>"
```
Expected: a header line, per-row lines, a TOTAL block, and no raw narrations. If `exec:java` isn't configured in `pom.xml`, run the class the way `CorpusProbe` is documented to run (see its class doc and `scripts/corpus-run.py`). Don't add a plugin just for this.

- [ ] **Step 3: Run over the full corpus and save the output out of the repo** (run this only after Task 3b is committed, so a single run carries every measurement)

Run over every PDF in the corpus directory. Save stdout to the scratchpad as `flow-class-corpus-v1.txt`. **Never commit it.**

- [ ] **Step 4: Keyword gate (value-level evidence, not counts)**

Read the per-row lines for each list and record the results in the scratchpad:
- (a) every `UNRESOLVED` and `OTHER_INCOME` row with its merchant token, looking for recognisable salary, interest, redemption, payment or refund narrations the lists missed;
- (b) every non-`INCOME` row, checking that none is plainly income.

Change the keyword lists only where a specific row proves the need, and add a `FlowClassifierTest` case with a synthetic narration of the same shape for each change. Re-run Step 3 and diff against v1. Every changed row must be one you intended to change.

- [ ] **Step 5: Report the Plan 0 numbers**

Use the top-counterparties list to validate the rules row by row. It never leaves the scratchpad, because it names real people and merchants. Put the TOTAL block (class/reason, count and value), the before/after headline and the upper-bound caveat in the PR description, and add it to `docs/proposals/financial-flow-model.md` under `## Plan 0 measurement`, with values aggregated and no narrations.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/test/java/com/finora/imports/analysis/ProbePipelines.java backend/src/test/java/com/finora/imports/analysis/ProductIdentityCorpusProbe.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "refactor(imports): one shared staging pipeline for corpus probes"
git -C /Users/sid/Downloads/finora-flow-class add backend/src/test/java/com/finora/imports/analysis/FlowClassCorpusProbe.java backend/src/main/java/com/finora/service/FlowClassifier.java backend/src/test/java/com/finora/service/FlowClassifierTest.java docs/proposals/financial-flow-model.md
git -C /Users/sid/Downloads/finora-flow-class commit -m "test(analytics): corpus probe measuring credit value by flow class"
```

---

### Task 3b: Pattern measurements for the candidate mechanisms

**Run order:** Task 3 Steps 1–2, then this task, then Task 3 Steps 3–6.

**Why:** the edge-scenario catalogue proposes four new mechanisms. Each one gets a plan only if the corpus shows enough volume. These measurements decide that:
1. the counterparty net-position ledger;
2. rhythm, which decides whether the "looks like a shop" suggestion is viable;
3. cash round-trips;
4. pass-through, which is exploratory.

**What is deliberately not measured:** persona, business account, household, and partner-vs-customer. Only the user can answer those, so statements can't show them.

**Honest limits, printed in the probe header:**
- Everything is **within one statement**. A lending cycle longer than the statement period is missed, so ledger volume is a **lower bound**.
- The corpus has no user ids, so "affected users" prints `n/a`, and "affected accounts" means statement sections.
- Every threshold below is an **exploratory parameter**, printed with the output, not a calibrated value. The output is used to calibrate them later.

**Files:**
- Create: `backend/src/test/java/com/finora/imports/analysis/FlowPatternAnalysis.java` (pure: rows in, findings out, no I/O)
- Create: `backend/src/test/java/com/finora/imports/analysis/FlowPatternAnalysisTest.java`
- Modify: `backend/src/test/java/com/finora/imports/analysis/FlowClassCorpusProbe.java` (feed each section's rows in, then print per-mechanism summaries)

**Interfaces:**
- Consumes:
  - `CounterpartyClassifier.classify(String)`;
  - `CounterpartyIdentity.keyOf(String)` (returns `vpa:`/`name:`, or null);
  - `CategoryRules.suggestCategory(String)` (`"Cash Withdrawal"` is an existing rule: "atm withdrawal", "atm wdl", "cash withdrawal", "cash wdl", "nwd");
  - `CategoryRules.normalize(String)`.
- Produces:
  - `record Row(LocalDate date, BigDecimal amount, boolean credit, String description)`, with derived `counterpartyType()` and `counterpartyKey()`;
  - `record Position(String key, BigDecimal out, BigDecimal in, int outCount, int inCount, LocalDate first, LocalDate last)` with `net()`, `gross()`, `bothDirections()`;
  - `static List<Position> personPositions(List<Row>)`;
  - `static List<Position> ledgerCandidates(List<Position>, BigDecimal minGross, BigDecimal maxNetShare)`;
  - `enum Rhythm { NONE, ONE_OFF, BURST, MONTHLY, STEADY, MIXED }`;
  - `record RhythmStats(Rhythm rhythm, double creditsPerActiveWeek, double payersPerActiveWeek, BigDecimal median, double coefficientOfVariation, int activeWeeks, int spanWeeks)`;
  - `static RhythmStats personCreditRhythm(List<Row>)`;
  - `static List<Row> cashRoundTripDeposits(List<Row>, int windowDays)`;
  - `static List<List<Row>> passThroughs(List<Row>, int windowDays, BigDecimal tolerance, BigDecimal minAmount)`.

- [ ] **Step 1: Write the failing tests** (synthetic narrations only)

```java
package com.finora.imports.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlowPatternAnalysisTest {

    private static final LocalDate D0 = LocalDate.of(2026, 7, 1);

    private static FlowPatternAnalysis.Row in(int day, String amount, String description) {
        return new FlowPatternAnalysis.Row(D0.plusDays(day), new BigDecimal(amount), true, description);
    }

    private static FlowPatternAnalysis.Row out(int day, String amount, String description) {
        return new FlowPatternAnalysis.Row(D0.plusDays(day), new BigDecimal(amount), false, description);
    }

    // ---- counterparty net position ----

    @Test void lendThenRepaid_isALedgerCandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "20000.00", "UPI/111111111111/A FRIEND/friend@okbank"),
                in(30, "10000.00", "UPI/222222222222/A FRIEND/friend@okbank"),
                in(60, "10000.00", "UPI/333333333333/A FRIEND/friend@okbank"));
        var candidates = FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("5000"), new BigDecimal("0.2"));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).net()).isEqualByComparingTo("0");
    }

    @Test void oneDirectionOnly_isNotACandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "3000.00", "UPI/1/HOUSE HELP/help@okbank"),
                out(30, "3000.00", "UPI/2/HOUSE HELP/help@okbank"));
        assertThat(FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("1000"), new BigDecimal("0.2"))).isEmpty();
    }

    @Test void lopsidedTwoWay_isNotACandidate() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "20000.00", "UPI/1/A FRIEND/friend@okbank"),
                in(5, "500.00", "UPI/2/A FRIEND/friend@okbank"));
        assertThat(FlowPatternAnalysis.ledgerCandidates(
                FlowPatternAnalysis.personPositions(rows), new BigDecimal("5000"), new BigDecimal("0.2"))).isEmpty();
    }

    // ---- rhythm ----

    @Test void manyPayersInOneWeek_isBurst() {
        List<FlowPatternAnalysis.Row> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(in(i % 3, "1100.00", "UPI/" + i + "/GUEST " + i + "/guest" + i + "@okbank"));
        rows.add(out(60, "100.00", "UPI MERCHANTCO")); // statement spans ~9 weeks
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.BURST);
    }

    @Test void manyPayersEveryWeek_isSteady() {
        List<FlowPatternAnalysis.Row> rows = new ArrayList<>();
        for (int w = 0; w < 8; w++) for (int k = 0; k < 4; k++) {
            int n = w * 4 + k;
            rows.add(in(w * 7 + k, String.valueOf(40 + n * 7) + ".00", "UPI/" + n + "/BUYER " + n + "/buyer" + n + "@okbank"));
        }
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.STEADY);
    }

    @Test void samePayersOncePerMonth_isMonthly() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(2, "15000.00", "UPI/1/TENANT ONE/t1@okbank"), in(3, "12000.00", "UPI/2/TENANT TWO/t2@okbank"),
                in(33, "15000.00", "UPI/3/TENANT ONE/t1@okbank"), in(34, "12000.00", "UPI/4/TENANT TWO/t2@okbank"),
                in(63, "15000.00", "UPI/5/TENANT ONE/t1@okbank"), in(64, "12000.00", "UPI/6/TENANT TWO/t2@okbank"));
        assertThat(FlowPatternAnalysis.personCreditRhythm(rows).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.MONTHLY);
    }

    @Test void fewerThanThreePersonCredits_isNoneOrOneOff() {
        assertThat(FlowPatternAnalysis.personCreditRhythm(List.of()).rhythm()).isEqualTo(FlowPatternAnalysis.Rhythm.NONE);
        assertThat(FlowPatternAnalysis.personCreditRhythm(List.of(in(1, "5000.00", "UPI/1/A BUYER/b@okbank"))).rhythm())
                .isEqualTo(FlowPatternAnalysis.Rhythm.ONE_OFF);
    }

    // ---- cash round-trips ----

    @Test void cashDepositAfterAtm_withinWindow_isRoundTrip_upToUnspent() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                out(0, "10000.00", "ATM WDL 0001 CITY"),
                in(10, "8000.00", "CASH DEPOSIT CDM 0002"),
                in(12, "5000.00", "CASH DEPOSIT CDM 0003"),   // only 2000 of the withdrawal is left: not a round-trip
                in(50, "1000.00", "CASH DEPOSIT CDM 0004"));  // outside the 30-day window
        assertThat(FlowPatternAnalysis.cashRoundTripDeposits(rows, 30))
                .extracting(FlowPatternAnalysis.Row::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("8000.00"));
    }

    // ---- pass-through ----

    @Test void severalPersonCreditsThenOneMatchingDebit_isPassThrough() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(0, "2000.00", "UPI/1/COLLEAGUE A/a@okbank"),
                in(1, "2000.00", "UPI/2/COLLEAGUE B/b@okbank"),
                in(2, "2000.00", "UPI/3/COLLEAGUE C/c@okbank"),
                out(4, "6000.00", "UPI GIFTSHOP PVT LTD"));
        assertThat(FlowPatternAnalysis.passThroughs(rows, 14, new BigDecimal("0.05"), new BigDecimal("2000"))).hasSize(1);
    }

    @Test void unrelatedCreditsAndDebit_isNotPassThrough() {
        List<FlowPatternAnalysis.Row> rows = List.of(
                in(0, "2000.00", "UPI/1/COLLEAGUE A/a@okbank"),
                out(4, "9000.00", "UPI GIFTSHOP PVT LTD"));
        assertThat(FlowPatternAnalysis.passThroughs(rows, 14, new BigDecimal("0.05"), new BigDecimal("2000"))).isEmpty();
    }
}
```

Before relying on them, run `CounterpartyClassifier.classify` and `CounterpartyIdentity.keyOf` once on each synthetic narration shape above (a scratch `main` or a debugger) and confirm they return `PERSON` and a `vpa:` key. If a shape doesn't classify as PERSON, change the synthetic narration to a shape the real classifier recognises. Don't weaken the analysis.

- [ ] **Step 2: Run and confirm they fail**

Run: `./mvnw -q test -Dtest=FlowPatternAnalysisTest`
Expected: `cannot find symbol: class FlowPatternAnalysis`.

- [ ] **Step 3: Implement**

```java
package com.finora.imports.analysis;

import com.finora.util.CategoryRules;
import com.finora.util.CounterpartyClassifier;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.CounterpartyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Measurements for the candidate mechanisms in the flow edge-scenario catalogue. Pure: rows in,
 * findings out. Every threshold is a caller-supplied EXPLORATORY parameter -- the probe prints the
 * values it used -- because the point of this class is to produce the data they get calibrated from.
 */
final class FlowPatternAnalysis {

    private FlowPatternAnalysis() {}

    record Row(LocalDate date, BigDecimal amount, boolean credit, String description) {
        CounterpartyType counterpartyType() { return CounterpartyClassifier.classify(description); }
        String counterpartyKey() { return CounterpartyIdentity.keyOf(description); }
        boolean person() { return counterpartyType() == CounterpartyType.PERSON; }
    }

    record Position(String key, BigDecimal out, BigDecimal in, int outCount, int inCount, LocalDate first, LocalDate last) {
        BigDecimal net() { return in.subtract(out); }
        BigDecimal gross() { return in.add(out); }
        boolean bothDirections() { return outCount > 0 && inCount > 0; }
    }

    /** Per person counterparty key: money out, money in, counts and date span. Rows with no key are skipped. */
    static List<Position> personPositions(List<Row> rows) {
        Map<String, Position> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.person()) continue;
            String key = r.counterpartyKey();
            if (key == null) continue;
            Position p = byKey.getOrDefault(key, new Position(key, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, r.date(), r.date()));
            byKey.put(key, new Position(key,
                    r.credit() ? p.out() : p.out().add(r.amount()),
                    r.credit() ? p.in().add(r.amount()) : p.in(),
                    r.credit() ? p.outCount() : p.outCount() + 1,
                    r.credit() ? p.inCount() + 1 : p.inCount(),
                    r.date().isBefore(p.first()) ? r.date() : p.first(),
                    r.date().isAfter(p.last()) ? r.date() : p.last()));
        }
        return new ArrayList<>(byKey.values());
    }

    /** Both directions present, gross at least {@code minGross}, and |net| no more than
     *  {@code maxNetShare} of the larger side -- money that mostly came back. Largest gross first. */
    static List<Position> ledgerCandidates(List<Position> positions, BigDecimal minGross, BigDecimal maxNetShare) {
        return positions.stream()
                .filter(Position::bothDirections)
                .filter(p -> p.gross().compareTo(minGross) >= 0)
                .filter(p -> p.net().abs().compareTo(p.out().max(p.in()).multiply(maxNetShare)) <= 0)
                .sorted(Comparator.comparing(Position::gross).reversed())
                .toList();
    }

    enum Rhythm { NONE, ONE_OFF, BURST, MONTHLY, STEADY, MIXED }

    record RhythmStats(Rhythm rhythm, double creditsPerActiveWeek, double payersPerActiveWeek, BigDecimal median,
                       double coefficientOfVariation, int activeWeeks, int spanWeeks) {}

    /**
     * Shape of person-to-person CREDITS over the statement's span (the span uses every row, so a
     * statement with a burst in week 1 and nothing after still spans all its weeks).
     *   BURST   >= 5 credits and >= 70% of them inside one 7-day bucket
     *   STEADY  >= 5 distinct payers and active in >= 60% of the span's weeks
     *   MONTHLY >= 2 credits and no payer credits more than once in any 25-day stretch
     *   ONE_OFF 1-2 credits;  NONE no credits;  MIXED anything else
     */
    static RhythmStats personCreditRhythm(List<Row> rows) {
        List<Row> credits = rows.stream().filter(Row::credit).filter(Row::person).toList();
        if (credits.isEmpty()) return new RhythmStats(Rhythm.NONE, 0, 0, BigDecimal.ZERO, 0, 0, 0);
        LocalDate start = rows.stream().map(Row::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate end = rows.stream().map(Row::date).max(LocalDate::compareTo).orElseThrow();
        int spanWeeks = (int) (ChronoUnit.DAYS.between(start, end) / 7) + 1;

        Map<Long, List<Row>> byWeek = new HashMap<>();
        for (Row r : credits) byWeek.computeIfAbsent(ChronoUnit.DAYS.between(start, r.date()) / 7, w -> new ArrayList<>()).add(r);
        int activeWeeks = byWeek.size();
        double creditsPerWeek = (double) credits.size() / activeWeeks;
        double payersPerWeek = byWeek.values().stream()
                .mapToInt(ws -> (int) ws.stream().map(Row::counterpartyKey).distinct().count()).average().orElse(0);
        List<BigDecimal> amounts = credits.stream().map(Row::amount).sorted().toList();
        BigDecimal median = amounts.get((amounts.size() - 1) / 2);
        double mean = amounts.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double sd = Math.sqrt(amounts.stream().mapToDouble(a -> Math.pow(a.doubleValue() - mean, 2)).average().orElse(0));
        double cv = mean == 0 ? 0 : sd / mean;
        Set<String> payers = new HashSet<>();
        credits.forEach(r -> payers.add(r.counterpartyKey()));

        Rhythm rhythm;
        int largestWeek = byWeek.values().stream().mapToInt(List::size).max().orElse(0);
        if (credits.size() <= 2) rhythm = Rhythm.ONE_OFF;
        else if (credits.size() >= 5 && largestWeek >= 0.7 * credits.size()) rhythm = Rhythm.BURST;
        else if (payers.size() >= 5 && activeWeeks >= 0.6 * spanWeeks) rhythm = Rhythm.STEADY;
        else if (noPayerRepeatsWithin(credits, 25)) rhythm = Rhythm.MONTHLY;
        else rhythm = Rhythm.MIXED;
        return new RhythmStats(rhythm, creditsPerWeek, payersPerWeek, median, cv, activeWeeks, spanWeeks);
    }

    private static boolean noPayerRepeatsWithin(List<Row> credits, int days) {
        Map<String, LocalDate> last = new HashMap<>();
        for (Row r : credits.stream().sorted(Comparator.comparing(Row::date)).toList()) {
            LocalDate prev = last.put(String.valueOf(r.counterpartyKey()), r.date());
            if (prev != null && ChronoUnit.DAYS.between(prev, r.date()) < days) return false;
        }
        return true;
    }

    static final List<String> CASH_DEPOSIT_KEYWORDS = List.of("cash deposit", "cash dep", "by cash", "cdm");

    /** Cash deposits that fit inside unspent, earlier ATM withdrawals (FIFO pool, each withdrawal
     *  usable for {@code windowDays}). A deposit larger than what is left in the pool is not a round-trip. */
    static List<Row> cashRoundTripDeposits(List<Row> rows, int windowDays) {
        List<Row> sorted = rows.stream().sorted(Comparator.comparing(Row::date)).toList();
        List<Object[]> pool = new ArrayList<>(); // {date, remaining}
        List<Row> matched = new ArrayList<>();
        for (Row r : sorted) {
            if (!r.credit() && "Cash Withdrawal".equals(CategoryRules.suggestCategory(r.description()))) {
                pool.add(new Object[]{r.date(), r.amount()});
                continue;
            }
            if (!r.credit()) continue;
            String text = " " + CategoryRules.normalize(r.description()) + " ";
            if (CASH_DEPOSIT_KEYWORDS.stream().noneMatch(k -> text.contains(" " + k))) continue;
            pool.removeIf(p -> ChronoUnit.DAYS.between((LocalDate) p[0], r.date()) > windowDays);
            BigDecimal available = pool.stream().map(p -> (BigDecimal) p[1]).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (r.amount().compareTo(available) > 0) continue;
            BigDecimal need = r.amount();
            for (Object[] p : pool) {
                BigDecimal take = need.min((BigDecimal) p[1]);
                p[1] = ((BigDecimal) p[1]).subtract(take);
                need = need.subtract(take);
                if (need.signum() == 0) break;
            }
            matched.add(r);
        }
        return matched;
    }

    /**
     * Collector shape: one debit of at least {@code minAmount} whose preceding {@code windowDays} hold
     * two or more person credits summing to within {@code tolerance} (a share, e.g. 0.05) of it -- and
     * the mirror (one credit, then two or more person debits). Exploratory: expect false positives.
     */
    static List<List<Row>> passThroughs(List<Row> rows, int windowDays, BigDecimal tolerance, BigDecimal minAmount) {
        List<List<Row>> found = new ArrayList<>();
        for (Row anchor : rows) {
            if (anchor.amount().compareTo(minAmount) < 0) continue;
            List<Row> legs = rows.stream()
                    .filter(r -> r.credit() != anchor.credit() && r.person())
                    .filter(r -> anchor.credit()
                            ? !r.date().isBefore(anchor.date()) && ChronoUnit.DAYS.between(anchor.date(), r.date()) <= windowDays
                            : !r.date().isAfter(anchor.date()) && ChronoUnit.DAYS.between(r.date(), anchor.date()) <= windowDays)
                    .toList();
            if (legs.size() < 2) continue;
            BigDecimal sum = legs.stream().map(Row::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gap = sum.subtract(anchor.amount()).abs();
            if (gap.compareTo(anchor.amount().multiply(tolerance).setScale(2, RoundingMode.HALF_UP)) <= 0) {
                List<Row> group = new ArrayList<>(legs);
                group.add(anchor);
                found.add(group);
            }
        }
        return found;
    }
}
```

- [ ] **Step 4: Run and confirm the tests pass**

Run: `./mvnw -q test -Dtest=FlowPatternAnalysisTest`
Expected: PASS. If a rhythm test fails, print the `RhythmStats` for its fixture and read the numbers before changing a threshold or the fixture.

- [ ] **Step 5: Wire it into the probe**

In `FlowClassCorpusProbe.probeOne`, build `List<FlowPatternAnalysis.Row>` per section from `StagedRow` (`credit = "INCOME".equals(row.type())`, using the value verified in Task 3). Then run the four analyses per section with the **exploratory parameters**:
- `minGross = 5000`, `maxNetShare = 0.2`;
- cash `windowDays = 30`;
- pass-through `windowDays = 14`, `tolerance = 0.05`, `minAmount = 2000`.

Print the parameters once in the header. Per section, print:
- the rhythm stats line (rhythm, credits and payers per active week, median, CV, active/span weeks);
- the ledger candidates (key replaced by its `extractMerchant` token, out, in, net, counts, date span).

At the end, add a **mechanism summary** block. Each line is `transactions | value | sections | users`, with users always `n/a` (no user ids in the corpus):

```
== MECHANISM SUMMARY (within-statement; ledger is a lower bound)
  counterparty ledger candidates  <txns> | <value> | <sections> | n/a
  cash round-trip deposits        <txns> | <value> | <sections> | n/a
  pass-through groups             <txns> | <value> | <sections> | n/a   (exploratory)
  rhythm: STEADY <n> sections, MONTHLY <n>, BURST <n>, ONE_OFF <n>, MIXED <n>, NONE <n>
```
where:
- **ledger:** txns = the candidates' `outCount + inCount`, value = `gross`;
- **cash:** the matched deposits;
- **pass-through:** every row in every group, with a row counted once even if it's in several groups.

Keep the accumulators in static fields beside `REMOVED_BY_TOKEN`.

- [ ] **Step 6: Decision rule (record it with the output, don't act on it yet)**

A mechanism is proposed for its own plan only if its summary line shows material value compared with the corpus's total credit value (the headline). The cut-off is Sid's call when he reads the numbers; this plan doesn't set it. A mechanism with near-zero volume is recorded as "no plan".

- [ ] **Step 7: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/test/java/com/finora/imports/analysis/FlowPatternAnalysis.java backend/src/test/java/com/finora/imports/analysis/FlowPatternAnalysisTest.java backend/src/test/java/com/finora/imports/analysis/FlowClassCorpusProbe.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "test(analytics): measure lending, rhythm, cash round-trip and pass-through shapes in the corpus"
```

---

### Task 4: `FlowTotals`: the income choke point

**Files:**
- Create: `backend/src/main/java/com/finora/service/FlowTotals.java`
- Test: `backend/src/test/java/com/finora/service/FlowTotalsTest.java`

**Interfaces:**
- Consumes: `FlowClassifier.classify` (Task 2), `Account.getId()`, `Account.getAccountType()`, `Transaction.getAccountId()`.
- Produces:
  - `static Map<UUID, Account.Type> accountTypes(Collection<Account> accounts)`
  - `static boolean countsAsIncome(Transaction t, Map<UUID, Account.Type> accountTypes)`
  - `static boolean isUnresolvedInflow(Transaction t, Map<UUID, Account.Type> accountTypes)`
  - `static BigDecimal unresolvedInflow(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes)`
  - `static int unresolvedInflowCount(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes)`
  - `static FlowClassifier.FlowReason unresolvedTopReason(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes)`, which returns null when nothing is unresolved

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlowTotalsTest {

    private static Account account(Account.Type type) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setAccountType(type);
        return a;
    }

    private static Transaction credit(Account on, String amount, String description) {
        Transaction t = new Transaction();
        t.setAccountId(on == null ? null : on.getId());
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(new BigDecimal(amount));
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    @Test void countsAsIncome_salaryOnSavings() {
        Account savings = account(Account.Type.SAVINGS);
        Map<UUID, Account.Type> types = FlowTotals.accountTypes(List.of(savings));
        assertThat(FlowTotals.countsAsIncome(credit(savings, "50000.00", "NEFT ACME SALARY JUL"), types)).isTrue();
    }

    @Test void countsAsIncome_neverForAnUnexplainedCardCredit() {
        Account card = account(Account.Type.CREDIT_CARD);
        Map<UUID, Account.Type> types = FlowTotals.accountTypes(List.of(card));
        assertThat(FlowTotals.countsAsIncome(credit(card, "1479.00", "UPI MERCHANTCO 111111111111"), types)).isFalse();
    }

    @Test void countsAsIncome_neverForADebit() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = credit(savings, "10.00", "NEFT ACME SALARY");
        t.setTxnType(Transaction.Type.EXPENSE);
        assertThat(FlowTotals.countsAsIncome(t, FlowTotals.accountTypes(List.of(savings)))).isFalse();
    }

    @Test void countsAsIncome_rowWithUnknownAccount_isTreatedAsNonCard() {
        assertThat(FlowTotals.countsAsIncome(credit(null, "100.00", "NEFT CLIENTCO PVT LTD"), Map.of())).isTrue();
    }

    @Test void unresolvedInflow_sumsOnlyUnresolvedCredits() {
        Account savings = account(Account.Type.SAVINGS);
        Account card = account(Account.Type.CREDIT_CARD);
        Transaction person = credit(savings, "1000.00", "UPI/1/A PERSON/p@okbank");
        person.setCounterpartyType(CounterpartyType.PERSON);
        Transaction cardCredit = credit(card, "12.00", "UPI MERCHANTCO 1");
        Transaction salary = credit(savings, "50000.00", "NEFT ACME SALARY");
        Transaction payment = credit(card, "5000.00", "PAYMENT RECEIVED THANK YOU");

        BigDecimal unresolved = FlowTotals.unresolvedInflow(List.of(person, cardCredit, salary, payment),
                FlowTotals.accountTypes(List.of(savings, card)));

        assertThat(unresolved).isEqualByComparingTo("1012.00");
        assertThat(FlowTotals.unresolvedInflowCount(List.of(person, cardCredit, salary, payment),
                FlowTotals.accountTypes(List.of(savings, card)))).isEqualTo(2);
        assertThat(FlowTotals.unresolvedTopReason(List.of(person, cardCredit, salary, payment),
                FlowTotals.accountTypes(List.of(savings, card)))).isEqualTo(FlowClassifier.FlowReason.PERSON_INFLOW);
    }

    @Test void unresolvedTopReason_isNullWhenNothingIsUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        assertThat(FlowTotals.unresolvedTopReason(List.of(credit(savings, "100.00", "NEFT ACME SALARY")),
                FlowTotals.accountTypes(List.of(savings)))).isNull();
    }

    @Test void accountTypes_skipsAccountsWithNoIdOrType() {
        Account noType = account(null);
        assertThat(FlowTotals.accountTypes(List.of(noType))).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./mvnw -q test -Dtest=FlowTotalsTest`
Expected: `cannot find symbol: class FlowTotals`.

- [ ] **Step 3: Write the implementation**

```java
package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The one place an income total decides which rows count. Every income sum in the app used to
 * filter {@code txnType == INCOME} by hand -- six copies -- which is how "every credit is income"
 * survived every fix to the exclusions around it. Callers pass rows that have ALREADY been through
 * {@link RefundNetting#reportable}; this narrows them further by {@link FlowClassifier}.
 */
public final class FlowTotals {

    private FlowTotals() {}

    public static Map<UUID, Account.Type> accountTypes(Collection<Account> accounts) {
        Map<UUID, Account.Type> types = new HashMap<>();
        for (Account a : accounts) {
            if (a.getId() != null && a.getAccountType() != null) types.put(a.getId(), a.getAccountType());
        }
        return types;
    }

    public static boolean countsAsIncome(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, accountTypes).flowClass() == FlowClassifier.FlowClass.INCOME;
    }

    public static boolean isUnresolvedInflow(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return t.getTxnType() == Transaction.Type.INCOME
                && decide(t, accountTypes).flowClass() == FlowClassifier.FlowClass.UNRESOLVED;
    }

    /** Money that came in and that Fynora cannot yet say is income -- shown beside income, never in it. */
    public static BigDecimal unresolvedInflow(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes) {
        return reportable.stream().filter(t -> isUnresolvedInflow(t, accountTypes))
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** How many rows {@link #unresolvedInflow} summed -- the "N transactions need classification" count. */
    public static int unresolvedInflowCount(Collection<Transaction> reportable, Map<UUID, Account.Type> accountTypes) {
        return (int) reportable.stream().filter(t -> isUnresolvedInflow(t, accountTypes)).count();
    }

    /** The reason carrying the most unresolved VALUE -- the banner's "Mostly money from people". Null when none. */
    public static FlowClassifier.FlowReason unresolvedTopReason(Collection<Transaction> reportable,
                                                                Map<UUID, Account.Type> accountTypes) {
        Map<FlowClassifier.FlowReason, BigDecimal> byReason = new java.util.EnumMap<>(FlowClassifier.FlowReason.class);
        for (Transaction t : reportable) {
            if (!isUnresolvedInflow(t, accountTypes)) continue;
            byReason.merge(decide(t, accountTypes).reason(), t.getAmount(), BigDecimal::add);
        }
        // Ties break on enum declaration order, which EnumMap iterates in -- deterministic.
        return byReason.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static FlowClassifier.FlowDecision decide(Transaction t, Map<UUID, Account.Type> accountTypes) {
        return FlowClassifier.classify(t, t.getAccountId() == null ? null : accountTypes.get(t.getAccountId()));
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./mvnw -q test -Dtest='FlowTotalsTest,FlowClassifierTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/main/java/com/finora/service/FlowTotals.java backend/src/test/java/com/finora/service/FlowTotalsTest.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(analytics): one choke point for which rows count as income"
```

---

### Task 5: `ReportService` and `ReportDto`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReportService.java:60-121`
- Modify: `backend/src/main/java/com/finora/dto/ReportDto.java`
- Test: `backend/src/test/java/com/finora/service/ReportServiceTest.java`

**Interfaces:**
- Consumes: `FlowTotals.accountTypes`, `FlowTotals.countsAsIncome`, `FlowTotals.unresolvedInflow` (Task 4).
- Produces: `ReportDto(String month, BigDecimal income, BigDecimal expense, List<CategoryAmount> categories, BigDecimal unresolvedInflow)`. `RangeTotals` keeps its shape, but its `income` is now flow-classified.

- [ ] **Step 1: Write the failing tests** (append to `ReportServiceTest`; this uses the existing `txn(...)` helper and `liveAccount`)

```java
    @Test
    void forMonth_moneyFromAPersonIsUnresolved_notIncome() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(liveAccount.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction fromPerson = txn(new BigDecimal("1000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(liveAccount.getId());
        fromPerson.setDescription("UPI/111111111111/A PERSON/person@okbank");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(salary, fromPerson));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("50000.00");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("1000.00");
    }

    @Test
    void forMonth_creditCardCreditsNeverCountAsIncome() {
        Account card = new Account();
        ReflectionTestUtils.setField(card, "id", UUID.randomUUID());
        card.setUserId(userId);
        card.setAccountType(Account.Type.CREDIT_CARD);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(liveAccount, card));
        Transaction billPaid = txn(new BigDecimal("20000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        billPaid.setAccountId(card.getId());
        billPaid.setDescription("PAYMENT RECEIVED THANK YOU");
        Transaction merchantCredit = txn(new BigDecimal("1479.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        merchantCredit.setAccountId(card.getId());
        merchantCredit.setDescription("UPI MERCHANTCO 111111111111");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(billPaid, merchantCredit));

        ReportDto report = reportService.forMonth(userId, "2026-07");

        assertThat(report.income()).isEqualByComparingTo("0");
        assertThat(report.unresolvedInflow()).isEqualByComparingTo("1479.00"); // the payment is a transfer, not unresolved
    }

    @Test
    void forRange_incomeUsesTheSameFlowRules() {
        liveAccount.setAccountType(Account.Type.SAVINGS);
        Transaction redemption = txn(new BigDecimal("100000.00"), Transaction.Type.INCOME, Transaction.ReconciliationStatus.OK);
        redemption.setAccountId(liveAccount.getId());
        redemption.setDescription("FD CLOSURE PROCEEDS 000123");
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(any(), any(), any(), any()))
                .thenReturn(List.of(redemption));

        var totals = reportService.forRange(userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(totals.income()).isEqualByComparingTo("0");
    }
```
Add the imports `com.finora.entity.Account` and `java.time.LocalDate` if they're missing.

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./mvnw -q test -Dtest=ReportServiceTest`
Expected: compile error on `report.unresolvedInflow()`.

- [ ] **Step 3: Implement**

`ReportDto.java`:
```java
public record ReportDto(
        String month,
        BigDecimal income,
        BigDecimal expense,
        List<CategoryAmount> categories,
        /* Credits Fynora cannot yet call income (money from a person, an unexplained card credit).
         * Shown beside income so it is never silently dropped; resolved in Plan 2. */
        BigDecimal unresolvedInflow
) {
    public record CategoryAmount(String category, BigDecimal amount) {}
}
```

`ReportService.forMonth`: replace the `liveAccountIds` derivation and the income sum:
```java
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        java.util.Map<UUID, com.finora.entity.Account.Type> accountTypes = FlowTotals.accountTypes(accounts);
        // ... unchanged RefundNetting / monthTxns / txns / txnsForTotals ...
        BigDecimal income = txnsForTotals.stream().filter(t -> FlowTotals.countsAsIncome(t, accountTypes))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // expense and byCategory: unchanged
        BigDecimal unresolvedInflow = FlowTotals.unresolvedInflow(txnsForTotals, accountTypes);
        return new ReportDto(monthStr, income, expense, categories, unresolvedInflow);
```
Apply the same `accounts`/`accountTypes` change and income filter in `forRange`, leaving the expense line untouched.

- [ ] **Step 4: Run and read every failure**

Run: `./mvnw -q test -Dtest='ReportServiceTest,*Report*Test'`
Expected: the new tests pass. For any **pre-existing** test that now fails, open it and check the fixture row. If the row is genuinely non-income under the new rules (for example a fixture credit on a card account), update the expectation and say so in the commit body. If it's plainly income, the classifier is wrong: fix the classifier and add a `FlowClassifierTest` case. Never change an expectation without reading the row.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/main/java/com/finora/service/ReportService.java backend/src/main/java/com/finora/dto/ReportDto.java backend/src/test/java/com/finora/service/ReportServiceTest.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(reports): income counts only flow-classified income; unresolved inflow reported beside it"
```

---

### Task 6: `DashboardService` and `DashboardSummaryDto`

**Files:**
- Modify: `backend/src/main/java/com/finora/service/DashboardService.java`: `:146-149` (the four `sumForMonth` calls), `:403-409` (`sumForMonth`), `:573-574` (`computeHealthScore` monthly income), `:337` (DTO construction)
- Modify: `backend/src/main/java/com/finora/dto/DashboardSummaryDto.java`
- Modify: `backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java:45`
- Test: `backend/src/test/java/com/finora/service/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `FlowTotals` (Task 4).
- Produces: `DashboardSummaryDto` gains a last component, `BigDecimal unresolvedInflow` (current reporting month).

- [ ] **Step 1: Write the failing tests** (append to `DashboardServiceTest`, which uses the existing `txn(...)` helper and `savings` account)

```java
    @Test
    void summarize_moneyFromAPersonIsNotMonthlyIncome() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction salary = txn(new BigDecimal("50000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        salary.setAccountId(savings.getId());
        salary.setDescription("NEFT ACME TECHNOLOGIES SALARY JUL");
        Transaction fromPerson = txn(new BigDecimal("10000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(savings.getId());
        fromPerson.setDescription("UPI/111111111111/A PERSON/person@okbank");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(salary, fromPerson));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.monthlyIncome()).isEqualByComparingTo("50000.00");
        assertThat(summary.unresolvedInflow()).isEqualByComparingTo("10000.00");
    }

    @Test
    void summarize_allCreditsUnresolved_incomeZeroNoException() {
        LocalDate july = LocalDate.of(2026, 7, 15);
        Transaction fromPerson = txn(new BigDecimal("10000.00"), Transaction.Type.INCOME, july, Transaction.ReconciliationStatus.OK);
        fromPerson.setAccountId(savings.getId());
        fromPerson.setDescription("UPI/1/A PERSON/p@okbank");
        fromPerson.setCounterpartyType(com.finora.util.CounterpartyType.PERSON);
        Transaction rent = txn(new BigDecimal("15000.00"), Transaction.Type.EXPENSE, july, Transaction.ReconciliationStatus.OK);
        rent.setAccountId(savings.getId());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(fromPerson, rent));

        DashboardSummaryDto summary = dashboardService.summarize(userId);

        assertThat(summary.monthlyIncome()).isEqualByComparingTo("0");
        assertThat(summary.savingsRatePct()).isEqualByComparingTo("0");
        assertThat(summary.monthlyExpense()).isEqualByComparingTo("15000.00"); // spend unchanged by Plan 1
    }
```

- [ ] **Step 2: Run and confirm they fail**

Run: `./mvnw -q test -Dtest=DashboardServiceTest`
Expected: compile error on `summary.unresolvedInflow()`.

- [ ] **Step 3: Implement**

`DashboardSummaryDto.java`: append as the **last** record component:
```java
        /*
         * This reporting month's credits Fynora cannot yet call income -- money from a person, an
         * unexplained credit-card credit. Excluded from monthlyIncome and savingsRatePct; surfaced
         * here so it is never silently lost. See FlowClassifier.
         */
        BigDecimal unresolvedInflow,

        /* How many transactions make up unresolvedInflow -- the banner's "N need classification". */
        int unresolvedInflowCount,

        /* FlowReason name carrying the most unresolved value (e.g. PERSON_INFLOW), null when none --
         * the banner's "Mostly money from people". A string, not the enum, so a reason added later
         * degrades on an old client instead of failing to parse. */
        String unresolvedTopReason
```

`DashboardService.sumForMonth`: take a predicate instead of a direction:
```java
    private BigDecimal sumForMonth(List<Transaction> txns, String month,
                                    java.util.function.Predicate<Transaction> counts, RefundNetting refunds) {
        if (month == null) return BigDecimal.ZERO;
        return txns.stream()
                .filter(t -> counts.test(t) && YearMonth.from(t.getTxnDate()).toString().equals(month))
                .map(refunds::reportableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
```

In `summarize`, right after `accounts` is loaded:
```java
        java.util.Map<UUID, Account.Type> accountTypes = FlowTotals.accountTypes(accounts);
        java.util.function.Predicate<Transaction> isIncome = t -> FlowTotals.countsAsIncome(t, accountTypes);
        java.util.function.Predicate<Transaction> isExpense = t -> t.getTxnType() == Transaction.Type.EXPENSE;
```
Replace `:146-149`:
```java
        BigDecimal incomeCur = sumForMonth(activeForTotals, currentMonth, isIncome, refunds);
        BigDecimal expenseCur = sumForMonth(activeForTotals, currentMonth, isExpense, refunds);
        BigDecimal incomePrior = sumForMonth(activeForTotals, priorMonth, isIncome, refunds);
        BigDecimal expensePrior = sumForMonth(activeForTotals, priorMonth, isExpense, refunds);
        List<Transaction> currentMonthRows = currentMonth == null ? List.of()
                : activeForTotals.stream().filter(t -> YearMonth.from(t.getTxnDate()).toString().equals(currentMonth)).toList();
        BigDecimal unresolvedCur = FlowTotals.unresolvedInflow(currentMonthRows, accountTypes);
        int unresolvedCountCur = FlowTotals.unresolvedInflowCount(currentMonthRows, accountTypes);
        FlowClassifier.FlowReason topReason = FlowTotals.unresolvedTopReason(currentMonthRows, accountTypes);
        String unresolvedTopReasonCur = topReason == null ? null : topReason.name();
```
In `computeHealthScore` (`:573-574`), build the same `accountTypes` from its `accounts` parameter and pass `isIncome`/`isExpense` predicates to `sumForMonth`.

Pass `unresolvedCur, unresolvedCountCur, unresolvedTopReasonCur` as the last three arguments to `new DashboardSummaryDto(...)` at `:337`. In `HealthScoreSnapshotSweepServiceTest.java:45`, append `BigDecimal.ZERO, 0, null`. In `summarize_moneyFromAPersonIsNotMonthlyIncome`, also assert that `summary.unresolvedInflowCount()` equals 1 and `summary.unresolvedTopReason()` equals `"PERSON_INFLOW"`.

- [ ] **Step 4: Run and read every failure** (same rule as Task 5, Step 4)

Run: `./mvnw -q test -Dtest='DashboardServiceTest,HealthScoreSnapshotSweepServiceTest,WorkspaceDashboardServiceTest,*Dashboard*Test'`
Expected: all pass, and every changed pre-existing expectation is justified row by row in the commit body.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/main/java/com/finora/service/DashboardService.java backend/src/main/java/com/finora/dto/DashboardSummaryDto.java backend/src/test/java/com/finora/service/DashboardServiceTest.java backend/src/test/java/com/finora/service/HealthScoreSnapshotSweepServiceTest.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(dashboard): monthly income, savings rate and health score count only flow-classified income"
```

---

### Task 7: `AnalyticsService` income series

**Files:**
- Modify: `backend/src/main/java/com/finora/service/AnalyticsService.java:434-441`
- Test: `backend/src/test/java/com/finora/service/AnalyticsServiceTest.java`

**Interfaces:** Consumes `FlowTotals` (Task 4). Produces no new API; `sumByMonth(activeIncomeTransactions(...))` (`:463`) now counts only flow-classified income.

- [ ] **Step 1: Write the failing test**

Before writing, read `AnalyticsServiceTest`'s existing setup and the public method that reaches `:463` (the method around `:455-470` that builds `incomeByMonth`). Write one test through that public method, in the file's own fixture style, asserting that a month containing a ₹50,000 salary credit and a ₹10,000 `PERSON` credit, both on a `SAVINGS` account, reports ₹50,000 of income for that month. Name it `incomeSeries_excludesMoneyFromAPerson`.

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw -q test -Dtest=AnalyticsServiceTest`
Expected: the new test fails with 60000 ≠ 50000.

- [ ] **Step 3: Implement**

```java
    private List<Transaction> activeIncomeTransactions(UUID userId, LocalDate from, LocalDate to) {
        List<com.finora.entity.Account> accounts = accountRepository.findByUserId(userId);
        List<UUID> liveAccountIds = accounts.stream().map(com.finora.entity.Account::getId).toList();
        Map<UUID, com.finora.entity.Account.Type> accountTypes = FlowTotals.accountTypes(accounts);
        List<Transaction> rangeTxns = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        return RefundNetting.reportable(rangeTxns, transactionGraphService.ccPaymentFromTransactionIds(rangeTxns)).stream()
                .filter(t -> FlowTotals.countsAsIncome(t, accountTypes))
                .toList();
    }
```

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw -q test -Dtest=AnalyticsServiceTest`
Expected: PASS, with pre-existing failures handled under the Task 5, Step 4 rule.

- [ ] **Step 5: Make "one entry point for income" a permanent test, not a one-off grep**

Create `backend/src/test/java/com/finora/service/IncomeSingleEntryPointTest.java`:

```java
package com.finora.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Income must be decided in ONE place (FlowTotals.countsAsIncome). Dashboard, report and trend
 * each deciding "income" by hand is how they drifted apart before. Direction is still legitimate
 * for matching, sign and balances -- those files are allow-listed by name, with the reason.
 */
class IncomeSingleEntryPointTest {

    private static final Set<String> ALLOWED = Set.of(
            "FlowClassifier.java",        // the classifier itself
            "FlowTotals.java",            // the choke point
            "ReconciliationService.java", // matching legs by direction, not totals
            "AccountBalanceConvention.java", // balance sign
            "AccountAggregatorTransactionDiffService.java", // mapping AA debit/credit to direction
            "Transaction.java");          // the enum's declaration

    @Test
    void noOtherMainSourceFiltersOnTheIncomeDirection() throws Exception {
        Path root = Path.of("src/main/java/com/finora");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .filter(p -> {
                        try { return Files.readString(p).contains("Type.INCOME"); }
                        catch (Exception e) { throw new IllegalStateException(e); }
                    })
                    .map(Path::toString).toList();
        }
        assertThat(offenders).as("route income through FlowTotals.countsAsIncome").isEmpty();
    }
}
```

Run: `./mvnw -q test -Dtest=IncomeSingleEntryPointTest`
Expected: PASS. If it fails, read each offender. A total must move to `FlowTotals`. A legitimate direction use gets added to `ALLOWED` with a comment giving the reason. Surefire runs with the module directory as the working directory, which is why the relative `src/main/java` path works; confirm this by making the test fail once on purpose.

Add the test file to this task's commit.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/src/main/java/com/finora/service/AnalyticsService.java backend/src/test/java/com/finora/service/AnalyticsServiceTest.java
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(analytics): income trend counts only flow-classified income"
```

---

### Task 8: OpenAPI and client types

**Files:** Regenerate `backend/openapi/openapi.json`, `frontend/src/api/generated-types.ts`, `mobile/src/api/generated-types.ts`, `admin-portal/src/api/generated-types.ts`.

- [ ] **Step 1: Regenerate**

```bash
cd /Users/sid/Downloads/finora-flow-class && ./scripts/generate-openapi-spec.sh
```
Then run `openapi-typescript@7.13.0` for each client exactly as the CI drift check does. Read `.github/workflows` for the drift job's command and copy it rather than improvising. Run it from `mobile/` after `npm i --no-save typescript`.

- [ ] **Step 2: Check the diff is additive only**

```bash
git -C /Users/sid/Downloads/finora-flow-class diff --stat
git -C /Users/sid/Downloads/finora-flow-class diff -- '*generated-types.ts' | grep -E '^[-+][^-+]' | head -40
```
Expected: only `+ unresolvedInflow` lines (on ReportDto and DashboardSummaryDto schemas). Any `-` line needs investigating.

- [ ] **Step 3: Check hand-written client mirrors**

Mobile keeps a hand-written `DashboardSummary` mirror that has drifted before. Grep for it:
```bash
git -C /Users/sid/Downloads/finora-flow-class grep -nE "monthlyIncome" -- mobile/src frontend/src admin-portal/src | grep -v generated-types
```
Add `unresolvedInflow?: number` to each hand-written mirror found. Plan 2 renders it.

- [ ] **Step 4: Client type-checks under Node 22**

```bash
cd /Users/sid/Downloads/finora-flow-class/frontend && npx -y node@22 node_modules/.bin/tsc --noEmit
cd /Users/sid/Downloads/finora-flow-class/mobile && npx -y node@22 node_modules/.bin/tsc --noEmit
cd /Users/sid/Downloads/finora-flow-class/admin-portal && npx -y node@22 node_modules/.bin/tsc --noEmit
```
Expected: no errors. (Install dependencies first in a fresh worktree: `npm ci` in each.)

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add backend/openapi/openapi.json frontend mobile admin-portal
git -C /Users/sid/Downloads/finora-flow-class commit -m "chore(api): regenerate client types for unresolvedInflow"
```

---

### Task 8b: Unresolved-income banner (web and mobile dashboard)

Why this is in PR 1: once Plan 1 lands, a ₹50,000 transfer from a parent stops counting as income. Without a visible line the user just sees income drop by 40 % and can't tell why. The banner is display-only. The review action arrives in Plan 2, and until then the banner explains the change but offers no action.

**Files:**
- Modify: `frontend/src/pages/Dashboard.tsx` (the income metric card area)
- Modify: `mobile/src/screens/DashboardScreen.tsx` (the equivalent summary section)
- Test: the dashboard test file each client already has (find it with `git grep -l "Dashboard" -- 'frontend/src/**/*.test.tsx' 'mobile/src/**/*.test.tsx'`)

**Interfaces:** Consumes `DashboardSummaryDto.unresolvedInflow` and `unresolvedInflowCount` (Task 6), both in the regenerated types (Task 8).

- [ ] **Step 1: Read before writing**

Read how each dashboard renders the income `MetricCard` (web) and its mobile equivalent, and which formatter it uses for rupees (the existing `fmt`). Put the banner directly under the income figure, using the existing muted/info styling tokens. No new colours (the palette is fixed and graphite/cream).

- [ ] **Step 2: Write the failing tests** (one per client, in the file's own style)

- Given a summary with `unresolvedInflow = 84500`, `unresolvedInflowCount = 12`, the text "12 transactions need classification · ₹84,500 not counted as income" is rendered.
- Given `unresolvedInflowCount = 0`, no banner is rendered.

Web: mock `react-chartjs-2` (a known jsdom trap: a failed Chart construct unmounts the root). Mobile: assert absence with `.not.toBeOnTheScreen()`, never `waitFor(() => expect(...).toBeNull())` (it starves timers).

- [ ] **Step 3: Implement**

Copy is exact and singular-aware: "1 transaction needs classification" / "N transactions need classification", followed by " · ₹X not counted as income". A third line comes from `unresolvedTopReason`:

| Reason | Line |
|---|---|
| `PERSON_INFLOW` | "Mostly money received from people" |
| `CARD_UNEXPLAINED_CREDIT` | "Mostly credits on your cards" |
| null or any other value | no third line (an unknown reason from a newer server degrades silently) |

Add a test case per mapping row, plus the unknown-value row. Plan 2 turns the banner into a link to the review list.

- [ ] **Step 4: Run client tests, lint and tsc under Node 22** — all green.

- [ ] **Step 5: Commit**

```bash
git -C /Users/sid/Downloads/finora-flow-class add frontend/src/pages/Dashboard.tsx mobile/src/screens/DashboardScreen.tsx <the two test files>
git -C /Users/sid/Downloads/finora-flow-class commit -m "feat(dashboard): show money received but not counted as income"
```

---

### Task 9: Full verification loop (repo CLAUDE.md completion criteria)

- [ ] **Step 1: Full backend suite including ITs**

```bash
cd /Users/sid/Downloads/finora-flow-class/backend && ./mvnw verify 2>&1 | tail -40
```
Expected: BUILD SUCCESS. Record test counts next to the Task 1 baseline.

- [ ] **Step 2: Lint and client tests**

Run each client's `npm run lint` and `npm test` under Node 22. Expected: clean.

- [ ] **Step 3: Value-level corpus check, before and after**

Re-run the Task 3 probe. Its TOTAL block must equal the one recorded in the PR, since nothing classifier-related should have changed since Task 3. Then:
- run `scripts/corpus-run.py` on `origin/main` and on this branch;
- run `scripts/corpus-diff.py` on the two outputs.

Expected: **zero parser-output differences**. This PR must not change what is parsed, only how it is summed.

- [ ] **Step 4: End-to-end on a local stack with a real card statement**

Use the local recipe from the `mvn-test-skips-it` memory:
- throwaway `postgres:16-alpine` on a spare port;
- copy the jar out of `target/` first;
- `SPRING_PROFILES_ACTIVE=dev FINORA_BOOTSTRAP_ENABLED=false`;
- register, then set `phone_verified`.

Import one credit-card PDF from the corpus that has a merchant credit (the one with the two same-day merchant credits under "Payment Details" is the known case), then call `GET` on the dashboard summary and the monthly report.

Expected:
- `monthlyIncome` excludes those credits;
- `unresolvedInflow` equals their sum;
- `monthlyExpense` equals the value from the same import on `origin/main`.

Record the three numbers. **Don't run this while Spring ITs are running** (shared local ports and database).

- [ ] **Step 5: Edge cases from Review Focus, confirmed by the runs above**

Tick each Review Focus line only once its named test has run green in Step 1's output. Name the test in the PR.

- [ ] **Step 6: Self-review the diff before pushing**

`git diff origin/main...HEAD`. Read every hunk for:
- a leftover `txnType == INCOME` sum;
- a DTO argument in the wrong position;
- a comment quoting a real narration;
- any change to a spend figure.

Fix anything found and re-run Steps 1–4 for what it touched.

- [ ] **Step 7: Merge-state check, push, open the PR**

```bash
cd /Users/sid/Downloads/finora-flow-class && git fetch origin && git log --oneline origin/main..HEAD
git ls-tree -r --name-only origin/main | grep -c FlowClassifier   # expect 0 before merge
git push -u origin feature/flow-class
gh pr create --title "Flow classification: income counts only real income" --body "<summary, Plan 0 numbers with the upper-bound caveat, Review Focus test names, e2e numbers from Step 4, explicit list of pre-existing test expectations changed and why>"
```
The commit messages carry no AI trailer. The PR body ends with the harness's PR attribution line only if the repo owner hasn't ruled it out; the CLAUDE.md rule covers commits, not PRs.

---

## Plans 2–6: specified, expand before executing

Each item below gets its own `docs/superpowers/plans/` document, re-grounded on then-current `origin/main`, before any code.

### Plan 2: Resolve UX and the per-counterparty rule (launch-blocking)

- **Migration** (next free `V`):
  - `transactions.flow_class_override VARCHAR(24) NULL`, `flow_class_override_at TIMESTAMPTZ NULL`;
  - new table `counterparty_flow_rules(id, user_id, counterparty_key, direction, flow_class, created_at, UNIQUE(user_id, counterparty_key, direction))`.
- **Classifier input:** `FlowClassifier.classify(t, accountType, Optional<FlowClass> override)`. The override wins over everything except `isDuplicateOf`/SUPERSEDED, which `reportable` already drops. The rule lookup is done by `FlowTotals` from a per-request map `(counterpartyKey, direction) → FlowClass`, loaded once per user.
- **People who earn from people** (a vegetable seller or tutor paid over personal UPI, a landlord paid rent by tenants). For these users, `PERSON_INFLOW` *is* their income, so the Plan 1 default would cut their income close to 100 %. Plan 2 must ship three mechanisms together:
  1. **Onboarding question:** "How do you mostly earn?" with Salary / Business or shop / Rent / Freelance or tuition / Mix. The answer sets the default for `PERSON_INFLOW` on the user's accounts (`users.person_inflow_default`: `UNRESOLVED` or `INCOME`). Salaried users keep `UNRESOLVED`.
  2. **Account-level toggle:** "Money from people into this account is income" (`accounts.person_inflow_is_income BOOLEAN NULL`, where null means use the user default). Suggest it automatically when an account's credits look like a trader's: many distinct person payers a month, small median amount, credits far outnumbering debits. The threshold must be calibrated from the Plan 0 probe's distinct-payer numbers, not chosen. **Suggest only, never auto-apply.**
  3. **Recurring-payer suggestion:** the same `vpa:` payer, roughly monthly, a stable amount (rent, tuition fees). Prompt "Is this rent or fees you receive?" and a yes creates an "always" rule. Don't auto-apply: the same shape is also a flatmate paying their share, or a parent's allowance.
  Classifier input becomes `classify(t, accountType, override, personInflowIsIncome)`. When true, `PERSON_INFLOW` becomes `INCOME` with the new reason `PERSON_INCOME`. A user's explicit per-row override or per-counterparty rule still wins over the account default.
- **Rule scope:** `vpa:` keys only. A `name:` key never becomes an "always" rule; the code already says a name key "must never be presented to a user as an identity".
- **API:**
  - `POST /api/v1/transactions/{id}/flow-class {flowClass, applyToCounterparty: boolean}`;
  - `DELETE` of the same resource to clear the override;
  - `GET /api/v1/flow/unresolved?month=` returns unresolved rows grouped by counterparty key, largest value first.
  - Audit every write the way `markTransfer` is audited.
  - Beware the `@Transactional(readOnly = true)` trap, which silently drops writes.
- **Web and mobile:**
  - the Plan 1 banner (Task 8b) becomes a link to a review list grouped by counterparty;
  - income breakdown by `FlowReason` (earned vs interest, tax refund, rewards) is **only if the Plan 0 probe shows it matters**, meaning non-earned reasons are a material share of income value. Otherwise it stays data-only. No classifier change is needed either way;
  - chips: *Paid me back* (REFUND for Plan 2, REIMBURSEMENT once Plan 4 lands) · *Gift* (INCOME) · *Loan repaid to me* (LIABILITY) · *Income / work* (INCOME) · *Own account* (TRANSFER);
  - checkbox "Treat future money from this person like this";
  - savings rate shows "—" when income is 0;
  - rename the Ledger "Income/Expense" direction labels to "Money in/Money out".
  - Every modal must be `AppModal` and every alert an `AppAlert` on mobile (existing rule).
- **Acceptance:**
  - resolving a row moves its amount from `unresolvedInflow` to the chosen class in the same response;
  - an "always" rule classifies a new import of the same VPA without a prompt;
  - clearing it restores `UNRESOLVED`.

### Plan 3: Account identity registry and one-sided transfers

- **Migration:** `account_identifiers(account_id, kind [MASKED_NUMBER|LAST4|CARD_LAST4|VPA|HOLDER_NAME], value, source [STATEMENT|USER], UNIQUE(account_id, kind, value))`, backfilled from the fields statement import already extracts (masked number, card last 4, holder name). Read `ProductIdentity` and `PdfMetadataExtractor` first. Plus `user_untracked_accounts(id, user_id, label, identifier_kind, identifier_value)`.
- **Detector:** a new pass in `ReconciliationService`, after the transfer pass. A credit or debit whose narration names a registry identifier of another of the user's accounts, or of a declared untracked account, becomes `isTransfer=true` with `transfer_pair_id` null.
- **Endpoint:** `MarkTransferRequest.pairedTransactionId` becomes nullable, and `untrackedAccountId` is added.
- **Card credits:** a payment credit whose narration carries the paying account's mask flips from `CARD_PAYMENT_RECEIVED` to a paired transfer when the savings side is imported.
- **Acceptance:** a savings debit "NEFT TO ••4821" with ••4821 declared as untracked is excluded from spend and appears under "Moved to your other accounts".

### Plan 4: Unlinked refunds and reimbursements net against spend

- Every spend consumer changes together:
  - `DashboardService` (`:147,:149,:191,:222,:285,:424,:573`);
  - `ReportService` (`:77,:81,:119`);
  - `AnalyticsService` (`:613,:630`);
  - `BudgetService` (`:90,:187`);
  - `InsightsService` (`:303`);
  - the admin platform queries in `TransactionRepository` (`:633,:639`).
- They share a `FlowTotals.signedSpendAmount(t, types, refunds)`:
  - EXPENSE-class debit: `refunds.reportableAmount(t)`;
  - unlinked REFUND/REIMBURSEMENT credit: `amount.negate()`, in the credit's own month and category.
- Add `REIMBURSEMENT` to `FlowClass`.
- **Decision to take in that plan's spec:** whether a category's monthly net spend may go below zero. The recommendation is to allow it and label it, because flooring breaks the sum of categories matching the total.
- **Acceptance:** the dashboard total, report total, sum of categories and budgets all agree on the same fixture month.

### Plan 5: Reimbursement linking

- Add `REIMBURSEMENT` to `TransactionRelationship.RelationshipType`, which reuses the graph and the `RefundNetting` offset map.
- Suggestion: for a `PERSON_INFLOW` or `REIMBURSEMENT` credit, candidates are EXPENSE rows within 14 days before it, in dining/travel/entertainment/groceries, where the credit is within ±₹1 of `amount × k / N` for N in 2..6.
- The user accepts or picks. Accepting nets against the purchase's period and category, exactly like a linked refund.
- **Acceptance:** a ₹2,000 dinner plus a ₹1,000 accepted reimbursement gives dining ₹1,000 in the dinner's month, and income is unchanged.

### Plan 6 (Phase B): Allocations

- `transaction_allocations(transaction_id, amount, flow_class, category_id, source, counter_account_ref)`. Every row gets one implicit allocation, so there's no table row unless it's split.
- EMI split from user-entered loan terms (an amortisation schedule), or from an imported loan statement.
- FD maturity split from booking plus maturity rows.
- `FlowTotals` sums allocations when present.
- **Acceptance:** an EMI of ₹12,000 with a known schedule shows ₹3,600 interest in spend and ₹8,400 in "Loan principal repaid".

---

## Self-review (done while writing)

- **Spec coverage:**
  - card credits: Tasks 2, 5, 6;
  - P2P UNRESOLVED: Tasks 2 and 4–7 and Plan 2;
  - investment inflows and loans: Task 2;
  - unmatched refunds: Task 2 (out of income) and Plan 4 (netted into spend);
  - one-sided transfers and registry: Plan 3;
  - reimbursements: Plans 4–5;
  - allocations: Plan 6;
  - Phase 0: Task 3;
  - no dual-run: satisfied by omission (no users).
  - Earned-vs-other income split (review §4): the data is in Plan 1 (`FlowReason`); the display is in Plan 2, and only if the probe shows it's material. The two burn rates are **not covered**; they're deferred because nothing consumes them yet.
  - Single entry point: `FlowClassifier.classify` is the only classifier, `FlowTotals.countsAsIncome` is the only income filter, and `IncomeSingleEntryPointTest` (Task 7) fails the build if a new screen decides income by hand.
  - Type safety: `FlowClass` and `FlowReason` are Java enums, so a `switch` over them is exhaustiveness-checked. They are never persisted in Plan 1; Plan 2 stores only the user's override, as the enum name.
- **Placeholder scan:**
  - Task 7, Step 1 deliberately sends the implementer to read `AnalyticsServiceTest` first, because its fixture wasn't read while planning. It says what to assert and why.
  - Task 3 depends on two string literals (`"CREDIT_CARD"` product name, `"INCOME"` row type) that must be verified against source. The step says so explicitly.
  - Task 8, Step 1 defers to the CI job's exact command instead of restating it.
- **Type consistency:**
  - `FlowDecision(FlowClass, FlowReason)`, `classify(Transaction, Account.Type)`, `countsAsIncome(Transaction, Map<UUID, Account.Type>)` and `unresolvedInflow(Collection<Transaction>, Map<UUID, Account.Type>)` are used identically in Tasks 2–7.
  - `ReportDto` and `DashboardSummaryDto` add `unresolvedInflow` as the last component in both.
