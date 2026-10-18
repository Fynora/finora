package com.finora.people;

import com.finora.entity.LoanEntry;
import com.finora.entity.ShareLink;
import com.finora.entity.SplitRule;
import com.finora.entity.Transaction;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PeopleSuggestionsTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RENT = UUID.randomUUID();
    private static final UUID FOOD = UUID.randomUUID();
    private static final UUID REPAY = UUID.randomUUID();
    private static final UUID TRANSFER = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);

    private final List<Transaction> rows = new ArrayList<>();
    private final List<ShareLink> links = new ArrayList<>();
    private final List<SplitRule> rules = new ArrayList<>();
    private final List<LoanEntry> entries = new ArrayList<>();
    private final Set<String> acks = new HashSet<>();
    private final Set<String> family = new HashSet<>();
    private final Set<String> repaymentKeys = new HashSet<>();
    private final Map<UUID, String> categories = new HashMap<>(Map.of(
            RENT, "Rent", FOOD, "Food", REPAY, "Friend Repayment", TRANSFER, "Personal Transfer"));

    private Transaction row(Transaction.Type type, int amount, LocalDate date, String key, UUID category, boolean manual) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(USER);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(date);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(category);
        t.setCategoryManuallySet(manual);
        t.setDescription("UPI/" + key);
        rows.add(t);
        return t;
    }

    private Transaction credit(int amount, LocalDate date, String key, UUID category) {
        return row(Transaction.Type.INCOME, amount, date, key, category, true);
    }

    private Transaction debit(int amount, LocalDate date, String key, UUID category) {
        return row(Transaction.Type.EXPENSE, amount, date, key, category, false);
    }

    private LoanEntry entry(Transaction t, LoanEntry.Direction d) {
        LoanEntry e = new LoanEntry(USER, t.getCounterpartyKey(), t.getId(), d, t.getAmount());
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        entries.add(e);
        return e;
    }

    private PeopleSuggestions.PeopleSuggestionsResult build() {
        PeopleLedger ledger = PeopleLedger.build(rows, links, rules, entries, repaymentKeys);
        return PeopleSuggestions.build(new PeopleSuggestions.Input(ledger, rows, categories, acks, family,
                rules, entries, repaymentKeys, TODAY));
    }

    private List<String> kinds() {
        return build().items().stream().map(PeopleSuggestions.Suggestion::kind).toList();
    }

    @Test
    void shareCheckOnSpendingCategory() {
        debit(30000, TODAY.minusDays(5), "vpa:owner.b", RENT);
        Transaction c = credit(7500, TODAY.minusDays(3), "vpa:flatmate.a", RENT);
        PeopleSuggestions.Suggestion s = build().items().get(0);
        assertThat(s.kind()).isEqualTo("SHARE_CHECK");
        assertThat(s.txnId()).isEqualTo(c.getId());
        assertThat(s.ackKey()).isEqualTo(c.getId().toString());
        assertThat(s.category()).isEqualTo("Rent");
    }

    @Test
    void shareCheckOnRepaymentWithNoEarlierPayment() {
        credit(500, TODAY.minusDays(2), "vpa:friend.k", REPAY);
        assertThat(kinds()).containsExactly("SHARE_CHECK");
    }

    @Test
    void noShareCheckOnRepaymentWithAnEarlierPayment() {
        debit(500, TODAY.minusDays(20), "vpa:friend.k", TRANSFER);
        credit(500, TODAY.minusDays(2), "vpa:friend.k", REPAY);
        assertThat(kinds()).doesNotContain("SHARE_CHECK");
    }

    @Test
    void shareCheckOnNoteWord() {
        Transaction c = row(Transaction.Type.INCOME, 800, TODAY.minusDays(1), "vpa:friend.k", null, false);
        c.setDescription("UPI/vpa:friend.k/dinner share");
        assertThat(kinds()).containsExactly("SHARE_CHECK");
    }

    @Test
    void noShareCheckWhenLinked() {
        Transaction bill = debit(30000, TODAY.minusDays(5), "vpa:owner.b", RENT);
        Transaction c = credit(7500, TODAY.minusDays(3), "vpa:flatmate.a", RENT);
        links.add(new ShareLink(USER, c.getId(), bill.getId(), BigDecimal.valueOf(7500)));
        assertThat(kinds()).doesNotContain("SHARE_CHECK");
    }

    @Test
    void billsListedBeforeThenAfter() {
        Transaction after = debit(2000, TODAY.minusDays(1), "vpa:shop.x", FOOD);
        Transaction before = debit(30000, TODAY.minusDays(9), "vpa:owner.b", RENT);
        debit(100, TODAY.minusDays(30), "vpa:shop.y", FOOD);
        credit(7500, TODAY.minusDays(4), "vpa:flatmate.a", RENT);
        List<PeopleSuggestions.BillOption> bills = build().items().get(0).bills();
        // Same category first, then the rest; within each, before the credit then after.
        assertThat(bills).extracting(PeopleSuggestions.BillOption::billId).containsExactly(before.getId(), after.getId());
    }

    @Test
    void lentCheckAfter30DaysOnly() {
        debit(2000, TODAY.minusDays(29), "vpa:friend.k", null);
        assertThat(kinds()).doesNotContain("LENT_CHECK");
        rows.clear();
        debit(2000, TODAY.minusDays(30), "vpa:friend.k", null);
        assertThat(kinds()).containsExactly("LENT_CHECK");
    }

    @Test
    void noLentCheckWhenMoneyCameBack() {
        Transaction d = debit(2000, TODAY.minusDays(40), "vpa:friend.k", null);
        credit(2000, d.getTxnDate().plusDays(10), "vpa:friend.k", REPAY);
        assertThat(kinds()).doesNotContain("LENT_CHECK");
    }

    @Test
    void noLentCheckForFamily() {
        debit(2000, TODAY.minusDays(40), "vpa:mother.m", null);
        family.add("vpa:mother.m");
        assertThat(kinds()).doesNotContain("LENT_CHECK");
    }

    @Test
    void noLentCheckForRepeatingPayment() {
        for (int m = 0; m < 3; m++) debit(9000, TODAY.minusMonths(m + 1).withDayOfMonth(1), "vpa:owner.b", RENT);
        assertThat(kinds()).doesNotContain("LENT_CHECK");
    }

    /** Situation 8: the user's own rent share sent to the flatmate who pays the owner is Rent, not a loan. */
    @Test
    void noLentCheckWhenTheUserFiledItAsSpending() {
        Transaction d = debit(9000, TODAY.minusDays(40), "vpa:flatmate.a", RENT);
        d.setCategoryManuallySet(true);
        assertThat(kinds()).doesNotContain("LENT_CHECK");
    }

    @Test
    void paybackCheckWhenOwed() {
        Transaction lent = debit(2000, TODAY.minusDays(40), "vpa:friend.k", null);
        entry(lent, LoanEntry.Direction.LENT);
        Transaction back = credit(1500, TODAY.minusDays(2), "vpa:friend.k", null);
        PeopleSuggestions.Suggestion s = build().items().get(0);
        assertThat(s.kind()).isEqualTo("PAYBACK_CHECK");
        assertThat(s.txnId()).isEqualTo(back.getId());
        assertThat(s.owed()).isEqualByComparingTo("2000");
    }

    @Test
    void paybackCheckForUnansweredPaymentWithin180Days() {
        Transaction out = debit(2000, TODAY.minusDays(180), "vpa:friend.k", null);
        credit(2000, TODAY, "vpa:friend.k", null);
        PeopleSuggestions.Suggestion s = build().items().get(0);
        assertThat(s.kind()).isEqualTo("PAYBACK_CHECK");
        assertThat(s.relatedTxnId()).isEqualTo(out.getId());
        rows.clear();
        debit(2000, TODAY.minusDays(181), "vpa:friend.k", null);
        credit(2000, TODAY, "vpa:friend.k", null);
        assertThat(kinds()).doesNotContain("PAYBACK_CHECK");
    }

    @Test
    void settleAfter60Days() {
        Transaction lent = debit(2000, TODAY.minusDays(59), "vpa:friend.k", null);
        entry(lent, LoanEntry.Direction.LENT);
        assertThat(kinds()).doesNotContain("SETTLE");
        rows.clear();
        entries.clear();
        Transaction older = debit(2000, TODAY.minusDays(60), "vpa:friend.k", null);
        LoanEntry e = entry(older, LoanEntry.Direction.LENT);
        PeopleSuggestions.Suggestion s = build().items().get(0);
        assertThat(s.kind()).isEqualTo("SETTLE");
        assertThat(s.ackKey()).isEqualTo("vpa:friend.k|" + e.getId());
    }

    @Test
    void settleSilencedUntilBalanceChanges() {
        Transaction lent = debit(2000, TODAY.minusDays(90), "vpa:friend.k", null);
        LoanEntry e = entry(lent, LoanEntry.Direction.LENT);
        acks.add("SETTLE|vpa:friend.k|" + e.getId());
        assertThat(kinds()).doesNotContain("SETTLE");
        Transaction back = credit(500, TODAY.minusDays(80), "vpa:friend.k", null);
        LoanEntry newer = entry(back, LoanEntry.Direction.PAID_BACK);
        ReflectionTestUtils.setField(newer, "createdAt", Instant.now().plusSeconds(60));
        assertThat(kinds()).contains("SETTLE");
    }

    @Test
    void everyMonthNeedsAnotherMonth() {
        Transaction bill = debit(30000, TODAY.minusDays(10), "vpa:owner.b", RENT);
        Transaction c = credit(7500, TODAY.minusDays(8), "vpa:flatmate.a", RENT);
        links.add(new ShareLink(USER, c.getId(), bill.getId(), BigDecimal.valueOf(7500)));
        assertThat(kinds()).doesNotContain("EVERY_MONTH");
        debit(30000, TODAY.minusDays(40), "vpa:owner.b", RENT);
        credit(7500, TODAY.minusDays(38), "vpa:flatmate.a", RENT);
        PeopleSuggestions.Suggestion s = build().items().stream().filter(i -> i.kind().equals("EVERY_MONTH")).findFirst().orElseThrow();
        assertThat(s.ackKey()).isEqualTo("vpa:flatmate.a|" + RENT);
        assertThat(s.txnId()).isEqualTo(c.getId());
        assertThat(s.relatedTxnId()).isEqualTo(bill.getId());
    }

    @Test
    void acknowledgedItemsRemoved() {
        Transaction c = credit(500, TODAY.minusDays(2), "vpa:friend.k", REPAY);
        acks.add("SHARE_CHECK|" + c.getId());
        assertThat(kinds()).isEmpty();
    }

    @Test
    void expectedShareLateAfterUsualDay() {
        rules.add(new SplitRule(USER, "vpa:flatmate.a", RENT, "vpa:owner.b", null));
        debit(30000, TODAY.minusMonths(1).withDayOfMonth(1), "vpa:owner.b", RENT);
        credit(7500, TODAY.minusMonths(1).withDayOfMonth(4), "vpa:flatmate.a", RENT);
        PeopleSuggestions.ExpectedShare e = build().expected().get(0);
        assertThat(e.usualAmount()).isEqualByComparingTo("7500");
        assertThat(e.usualDay()).isEqualTo(4);
        assertThat(e.late()).isTrue();
    }

    @Test
    void quietForUserWithNoPersonRows() {
        Transaction shop = debit(500, TODAY.minusDays(40), "vpa:shop.x", FOOD);
        shop.setCounterpartyType(CounterpartyType.BUSINESS);
        assertThat(build().items()).isEmpty();
        assertThat(build().expected()).isEmpty();
    }

    @Test
    void atMostFive() {
        for (int i = 0; i < 8; i++) credit(500, TODAY.minusDays(i + 1), "vpa:friend." + i, REPAY);
        assertThat(build().items()).hasSize(5);
    }
}
