package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.InflowKind;
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
        return FlowTotals.context(accounts, List.of(salary, dining), InflowChoices.NONE);
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
        assertThat(FlowTotals.context(List.of(), List.of(c), InflowChoices.NONE).salaryCategoryIds()).containsExactly(c.getId());
    }

    // ---- which credits give spend back ----

    @Test void offsetsSpend_unlinkedRefundReversalAndCardAdjustment() {
        Account savings = account(Account.Type.SAVINGS);
        Account card = account(Account.Type.CREDIT_CARD);
        FlowTotals.Context c = ctx(List.of(savings, card));
        assertThat(FlowTotals.offsetsSpend(credit(savings, "300.00", "REFUND FROM MERCHANTCO ORDER 1"), c)).isTrue();
        assertThat(FlowTotals.offsetsSpend(credit(card, "26.59", "FUEL SURCHARGE WAIVER"), c)).isTrue();
        assertThat(FlowTotals.offsetsSpend(credit(card, "30000.00", "EMI CONVERSION CREDIT"), c)).isTrue();
    }

    @Test void offsetsSpend_neverForLinkedRefundsIncomeTransfersOrPeople() {
        Account savings = account(Account.Type.SAVINGS);
        Account card = account(Account.Type.CREDIT_CARD);
        FlowTotals.Context c = ctx(List.of(savings, card));
        Transaction linked = credit(savings, "300.00", "REFUND FROM MERCHANTCO ORDER 1");
        linked.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        Transaction reversedLinked = credit(savings, "300.00", "REVERSAL OF TXN 1");
        reversedLinked.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
        Transaction taxRefund = credit(savings, "12000.00", "ITD TAX REFUND AY 2026");
        taxRefund.setCounterpartyType(CounterpartyType.GOVERNMENT);

        assertThat(FlowTotals.offsetsSpend(linked, c)).isFalse();
        assertThat(FlowTotals.offsetsSpend(reversedLinked, c)).isFalse();
        assertThat(FlowTotals.offsetsSpend(taxRefund, c)).as("a tax refund is income").isFalse();
        assertThat(FlowTotals.offsetsSpend(credit(savings, "50000.00", "NEFT ACME SALARY JUL"), c)).isFalse();
        assertThat(FlowTotals.offsetsSpend(credit(card, "20000.00", "PAYMENT RECEIVED THANK YOU"), c)).isFalse();
        assertThat(FlowTotals.offsetsSpend(fromAPerson(savings, "800.00"), c)).isFalse();
        Transaction debit = credit(savings, "300.00", "REFUND FROM MERCHANTCO ORDER 1");
        debit.setTxnType(Transaction.Type.EXPENSE);
        assertThat(FlowTotals.offsetsSpend(debit, c)).isFalse();
    }

    // ---- the user's inflow kinds (Plan 2) ----

    @Test void aSenderRuleMovesAPersonsCreditIntoIncomeUnderTheKindName() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = fromAPerson(savings, "5000.00");
        t.setCounterpartyKey("vpa:asha");
        InflowKind family = new InflowKind();
        ReflectionTestUtils.setField(family, "id", UUID.randomUUID());
        family.setName("Family support");
        family.setCountsAsIncome(true);
        family.setBuiltIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        FlowTotals.Context ctx = FlowTotals.context(List.of(savings), List.of(),
                new InflowChoices(Map.of(family.getId(), family), Map.of("vpa:asha", family.getId())));

        assertThat(FlowTotals.isUnresolvedInflow(t, ctx)).isFalse();
        assertThat(FlowTotals.countsAsIncome(t, ctx)).isTrue();
        assertThat(FlowTotals.incomeLabel(t, ctx)).isEqualTo("Family support");
    }

    @Test void incomeLabelNamesTheAutomaticReason() {
        Account savings = account(Account.Type.SAVINGS);
        Transaction t = credit(savings, "100.00", "SB INT CREDIT");
        assertThat(FlowTotals.incomeLabel(t, FlowTotals.context(List.of(savings), List.of(), InflowChoices.NONE)))
                .isEqualTo("Interest");
    }

    @Test void aRefundKindOffsetsSpend() {
        Account card = account(Account.Type.CREDIT_CARD);
        Transaction t = credit(card, "300.00", "MERCHANTCO 1001");
        t.setInflowKindId(UUID.randomUUID());
        InflowKind refund = new InflowKind();
        ReflectionTestUtils.setField(refund, "id", t.getInflowKindId());
        refund.setName("Refund");
        refund.setBuiltIn(InflowKind.BuiltIn.REFUND);
        FlowTotals.Context ctx = FlowTotals.context(List.of(card), List.of(),
                new InflowChoices(Map.of(refund.getId(), refund), Map.of()));
        assertThat(FlowTotals.offsetsSpend(t, ctx)).isTrue();
        assertThat(FlowTotals.isUnresolvedInflow(t, ctx)).isFalse();
    }

    // ---- money paid back: answered by the category, but not netted against spend yet ----

    private static Category category(String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        c.setName(name);
        return c;
    }

    private static Transaction lentTo(Account on, String amount, Category personTransfer) {
        Transaction t = credit(on, amount, "UPI/DR/111111111111/ASHA VERMA/HDFC/asha@okbank/");
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(personTransfer.getId());
        return t;
    }

    private static Transaction paidBack(Account on, String amount, Category repayment, boolean usersChoice) {
        Transaction t = fromAPerson(on, amount);
        t.setCategoryId(repayment.getId());
        t.setCategoryManuallySet(usersChoice);
        t.setDecisionSource(usersChoice ? Transaction.DecisionSource.MANUAL : Transaction.DecisionSource.STRUCTURAL_P2P);
        return t;
    }

    @Test void aRepaymentTheUserFiled_isNeitherUnresolvedNorIncome_andDoesNotYetLowerSpend() {
        // Netting it against Personal Transfer as a whole was measured to cancel unrelated spending;
        // it is matched per person in a follow-up. Until then it leaves spend exactly as it was.
        Account savings = account(Account.Type.SAVINGS);
        Category personTransfer = category("Personal Transfer");
        Category repayment = category("Friend Repayment");
        FlowTotals.Context ctx = FlowTotals.context(List.of(savings), List.of(personTransfer, repayment), InflowChoices.NONE);
        Transaction lent = lentTo(savings, "1000.00", personTransfer);
        Transaction back = paidBack(savings, "600.00", repayment, true);

        assertThat(FlowTotals.isUnresolvedInflow(back, ctx)).isFalse();
        assertThat(FlowTotals.countsAsIncome(back, ctx)).isFalse();
        assertThat(FlowTotals.offsetsSpend(back, ctx)).isFalse();
        assertThat(RefundNetting.NONE.withUnlinkedOffsets(List.of(lent, back), ctx).spendTotal(List.of(lent, back)))
                .isEqualByComparingTo("1000.00");
    }

    @Test void aRepaymentCategoryTheAppGuessed_staysUnresolved() {
        Account savings = account(Account.Type.SAVINGS);
        Category repayment = category("Friend Repayment");
        FlowTotals.Context ctx = FlowTotals.context(List.of(savings), List.of(repayment), InflowChoices.NONE);
        Transaction back = paidBack(savings, "600.00", repayment, false);

        assertThat(FlowTotals.isUnresolvedInflow(back, ctx)).isTrue();
        assertThat(FlowTotals.offsetsSpend(back, ctx)).isFalse();
    }

    @Test void thePaidBackKind_doesNotLowerSpendEither() {
        Account savings = account(Account.Type.SAVINGS);
        Category personTransfer = category("Personal Transfer");
        Transaction lent = lentTo(savings, "1000.00", personTransfer);
        Transaction back = fromAPerson(savings, "300.00");
        back.setInflowKindId(UUID.randomUUID());
        InflowKind paidBackKind = new InflowKind();
        ReflectionTestUtils.setField(paidBackKind, "id", back.getInflowKindId());
        paidBackKind.setName("Paid back to me");
        paidBackKind.setBuiltIn(InflowKind.BuiltIn.PAID_BACK);
        FlowTotals.Context ctx = FlowTotals.context(List.of(savings), List.of(personTransfer),
                new InflowChoices(Map.of(paidBackKind.getId(), paidBackKind), Map.of()));

        assertThat(FlowTotals.isUnresolvedInflow(back, ctx)).isFalse();
        assertThat(RefundNetting.NONE.withUnlinkedOffsets(List.of(lent, back), ctx).spendTotal(List.of(lent, back)))
                .isEqualByComparingTo("1000.00");
    }

    @Test void theRepaymentCategoryIsMatchedCaseInsensitively() {
        Category fr = category(" FRIEND REPAYMENT ");
        FlowTotals.Context ctx = FlowTotals.context(List.of(), List.of(fr), InflowChoices.NONE);
        assertThat(ctx.repaymentCategoryIds()).containsExactly(fr.getId());
    }
}
