package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
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
        t.setSource(Transaction.Source.CSV_IMPORT); // imported unless a test says otherwise
        return t;
    }

    @Test void countsAsIncome_salaryOnSavings() {
        Account savings = account(Account.Type.SAVINGS);
        FlowTotals.Context types = ctx(List.of(savings));
        assertThat(FlowTotals.countsAsIncome(credit(savings, "50000.00", "NEFT ACME SALARY JUL"), types)).isTrue();
    }

    @Test void countsAsIncome_neverForAnUnexplainedCardCredit() {
        Account card = account(Account.Type.CREDIT_CARD);
        FlowTotals.Context types = ctx(List.of(card));
        assertThat(FlowTotals.countsAsIncome(credit(card, "1479.00", "UPI MERCHANTCO 111111111111"), types)).isFalse();
    }

    @Test void countsAsIncome_neverForADebit() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = credit(savings, "10.00", "NEFT ACME SALARY");
        t.setTxnType(Transaction.Type.EXPENSE);
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).isFalse();
    }

    @Test void countsAsIncome_rowWithUnknownAccount_isTreatedAsNonCard() {
        assertThat(FlowTotals.countsAsIncome(credit(null, "100.00", "NEFT CLIENTCO PVT LTD"), ctx(List.of()))).isTrue();
    }

    @Test void unresolvedInflow_sumsOnlyUnresolvedCredits() {
        Account savings = account(Account.Type.SAVINGS);
        Account card = account(Account.Type.CREDIT_CARD);
        Transaction person = credit(savings, "1000.00", "UPI/1/A PERSON/p@okbank");
        person.setCounterpartyType(CounterpartyType.PERSON);
        Transaction cardCredit = credit(card, "12.00", "UPI MERCHANTCO 1");
        Transaction salary = credit(savings, "50000.00", "NEFT ACME SALARY");
        Transaction payment = credit(card, "5000.00", "PAYMENT RECEIVED THANK YOU");
        List<Transaction> rows = List.of(person, cardCredit, salary, payment);
        FlowTotals.Context types = ctx(List.of(savings, card));

        assertThat(FlowTotals.unresolvedInflow(rows, types)).isEqualByComparingTo("1012.00");
        assertThat(FlowTotals.unresolvedInflowCount(rows, types)).isEqualTo(2);
        assertThat(FlowTotals.unresolvedTopReason(rows, types)).isEqualTo(FlowClassifier.FlowReason.PERSON_INFLOW);
    }

    @Test void unresolvedTopReason_isNullWhenNothingIsUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        assertThat(FlowTotals.unresolvedTopReason(List.of(credit(savings, "100.00", "NEFT ACME SALARY")),
                ctx(List.of(savings)))).isNull();
    }

    @Test void context_skipsAccountsWithNoType() {
        assertThat(ctx(List.of(account(null))).accountTypes()).isEmpty();
    }

    // ---- the user's own word outranks a person-shaped narration ----

    private static final UUID SALARY_ID = UUID.randomUUID();

    private static FlowTotals.Context ctx(List<Account> accounts) {
        Category salary = new Category();
        ReflectionTestUtils.setField(salary, "id", SALARY_ID);
        salary.setName("Salary");
        Category dining = new Category();
        ReflectionTestUtils.setField(dining, "id", UUID.randomUUID());
        dining.setName("Dining");
        return FlowTotals.context(accounts, List.of(salary, dining));
    }

    private static Transaction fromAPerson(Account on, String amount) {
        Transaction t = credit(on, amount, "UPI-SUNIL VERMA-sampleuser@ybl-REF1");
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setSource(Transaction.Source.CSV_IMPORT);
        return t;
    }

    @Test void personInflow_importedAndUncategorised_isUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "20000.00");
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).isFalse();
        assertThat(FlowTotals.isUnresolvedInflow(t, ctx(List.of(savings)))).isTrue();
    }

    @Test void personInflow_enteredByHandAsIncome_countsAsIncome() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "20000.00");
        t.setSource(Transaction.Source.MANUAL);
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).isTrue();
        assertThat(FlowTotals.isUnresolvedInflow(t, ctx(List.of(savings)))).isFalse();
    }

    @Test void personInflow_putInSalaryByTheUser_countsAsIncome() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "45000.00");
        t.setCategoryId(SALARY_ID);
        t.setCategoryManuallySet(true);
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).isTrue();
    }

    @Test void personInflow_putInSalaryByARuleTheUserTaught_countsAsIncome() {
        Account savings = account(Account.Type.SAVINGS);
        for (Transaction.DecisionSource taught : List.of(Transaction.DecisionSource.USER_RULE,
                Transaction.DecisionSource.LEARNED_PATTERN, Transaction.DecisionSource.MANUAL)) {
            Transaction t = fromAPerson(savings, "45000.00");
            t.setCategoryId(SALARY_ID);
            t.setDecisionSource(taught);
            assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).as(taught.name()).isTrue();
        }
    }

    @Test void personInflow_putInSalaryByTheAiFallbackOrAGlobalRule_staysUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        for (Transaction.DecisionSource guessed : List.of(Transaction.DecisionSource.AI_FALLBACK,
                Transaction.DecisionSource.GLOBAL_RULE, Transaction.DecisionSource.SHARED_CORPUS)) {
            Transaction t = fromAPerson(savings, "45000.00");
            t.setCategoryId(SALARY_ID);
            t.setDecisionSource(guessed);
            assertThat(FlowTotals.isUnresolvedInflow(t, ctx(List.of(savings)))).as(guessed.name()).isTrue();
        }
    }

    @Test void personInflow_inANonSalaryCategorySetByTheUser_staysUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "800.00");
        t.setCategoryId(UUID.randomUUID());
        t.setCategoryManuallySet(true);
        assertThat(FlowTotals.isUnresolvedInflow(t, ctx(List.of(savings)))).isTrue();
    }

    @Test void enteredByHand_aRefundIsStillARefund() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = credit(savings, "499.00", "Refund from store");
        t.setSource(Transaction.Source.MANUAL);
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(savings)))).isFalse();
    }

    @Test void enteredByHandOnACard_isStillNotIncome() {
        Account card = account(Account.Type.CREDIT_CARD);
        Transaction t = credit(card, "2000.00", "Money from a friend");
        t.setSource(Transaction.Source.MANUAL);
        assertThat(FlowTotals.countsAsIncome(t, ctx(List.of(card)))).isFalse();
    }

    @Test void context_findsSalaryByNameIgnoringCaseAndSpaces() {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setName(" salary ");
        assertThat(FlowTotals.context(List.of(), List.of(c)).salaryCategoryIds()).containsExactly(c.getId());
    }
}
