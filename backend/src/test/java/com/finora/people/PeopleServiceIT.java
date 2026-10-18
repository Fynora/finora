package com.finora.people;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.LoanEntry;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.ReportService;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PeopleService's writes (spec §5, §7), against the real database and the real figures. */
class PeopleServiceIT extends AbstractIntegrationTest {

    @Autowired private PeopleService people;
    @Autowired private ReportService reportService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;

    private UUID userId;
    private UUID accountId;
    private Category rent;
    private final YearMonth month = YearMonth.now();

    @BeforeEach
    void setUp() {
        userId = newUser();
        Account a = new Account();
        a.setUserId(userId);
        a.setName("People Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(a).getId();
        rent = new Category();
        rent.setUserId(userId);
        rent.setName("Rent");
        rent = categoryRepository.save(rent);
    }

    private UUID newUser() {
        User u = new User();
        u.setEmail("people-service-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("People Service User");
        return userRepository.save(u).getId();
    }

    private Transaction txn(Transaction.Type type, int amount, String key, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(month.atDay(1));
        t.setDescription("UPI " + key);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    private BigDecimal monthSpend() {
        return reportService.forMonth(userId, month.toString()).expense();
    }

    private static ErrorCode codeOf(Throwable t) { return ((ApiException) t).getCode(); }

    @Test
    void linkDefaultsToCap() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 1500, "vpa:flatmate.a", rent.getId());
        PeopleDtos.ShareDto share = people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThat(share.amount()).isEqualByComparingTo("1000");
    }

    @Test
    void linkRefusesTheWrongWayRound() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 500, "vpa:flatmate.a", rent.getId());
        assertThatThrownBy(() -> people.link(userId, new PeopleDtos.LinkShareRequest(bill.getId(), credit.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_WRONG_DIRECTION));
    }

    @Test
    void anotherUsersTransactionIsNotFound() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 1000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 500, "vpa:flatmate.a", rent.getId());
        UUID stranger = newUser();
        assertThatThrownBy(() -> people.link(stranger, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.TXN_NOT_FOUND));
    }

    @Test
    void linkThenUndoRestoresFigures() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "vpa:flatmate.a", rent.getId());
        BigDecimal before = monthSpend();
        people.link(userId, new PeopleDtos.LinkShareRequest(credit.getId(), bill.getId(), null));
        assertThat(monthSpend()).isEqualByComparingTo("22500");
        people.unlink(userId, credit.getId(), bill.getId());
        assertThat(monthSpend()).isEqualByComparingTo(before);
    }

    @Test
    void entryOnAWeakKeyIsRefused() {
        Transaction credit = txn(Transaction.Type.INCOME, 500, "cut:abc", null);
        assertThatThrownBy(() -> people.addEntry(userId, new PeopleDtos.EntryRequest(credit.getId(), LoanEntry.Direction.PAID_BACK)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOT_ONE_PAYEE));
    }

    @Test
    void lentIsNotSpendingAndSettleCashClosesTheBalance() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        BigDecimal before = monthSpend();
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        assertThat(monthSpend()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2000)));
        assertThat(people.people(userId)).extracting(PeopleDtos.PersonDto::personKey).containsExactly("vpa:friend.k");
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.CASH));
        assertThat(people.people(userId)).isEmpty();
        assertThat(monthSpend()).isEqualByComparingTo(before.subtract(BigDecimal.valueOf(2000)));
    }

    @Test
    void letGoRefilesUnderGiftsAndCountsOnlyWhatNeverCameBack() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        Transaction back = txn(Transaction.Type.INCOME, 1500, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.addEntry(userId, new PeopleDtos.EntryRequest(back.getId(), LoanEntry.Direction.PAID_BACK));
        BigDecimal withoutLoan = monthSpend();
        people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.LET_GO));
        Transaction refiled = transactionRepository.findById(lent.getId()).orElseThrow();
        assertThat(categoryRepository.findById(refiled.getCategoryId()).orElseThrow().getName()).isEqualTo("Gifts & Donations");
        assertThat(monthSpend()).isEqualByComparingTo(withoutLoan.add(BigDecimal.valueOf(500)));
        assertThat(people.people(userId)).isEmpty();
    }

    @Test
    void settlingWhenNothingIsOwedIsRefused() {
        assertThatThrownBy(() -> people.settle(userId, new PeopleDtos.SettleRequest("vpa:friend.k", PeopleDtos.SettleHow.CASH)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOTHING_OWED));
    }

    @Test
    void splitRuleRefusesAWeakSender() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "cut:abc", rent.getId());
        assertThatThrownBy(() -> people.addSplitRule(userId, new PeopleDtos.SplitRuleRequest(credit.getId(), bill.getId())))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(ErrorCode.PEOPLE_NOT_ONE_PAYEE));
    }

    @Test
    void splitRuleLinksAndRemovingItUndoes() {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, "vpa:owner.b", rent.getId());
        Transaction credit = txn(Transaction.Type.INCOME, 7500, "vpa:flatmate.a", rent.getId());
        PeopleDtos.SplitRuleDto rule = people.addSplitRule(userId, new PeopleDtos.SplitRuleRequest(credit.getId(), bill.getId()));
        assertThat(rule.billPayeeKey()).isEqualTo("vpa:owner.b");
        assertThat(monthSpend()).isEqualByComparingTo("22500");
        assertThat(people.billShares(userId, bill.getId())).singleElement()
                .satisfies(s -> assertThat(s.fromRule()).isTrue());
        people.removeSplitRule(userId, rule.id());
        assertThat(monthSpend()).isEqualByComparingTo("30000");
    }

    @Test
    void personDetailHasTimelineAndFamilySwitch() {
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, "vpa:friend.k", null);
        people.addEntry(userId, new PeopleDtos.EntryRequest(lent.getId(), LoanEntry.Direction.LENT));
        people.setPersonSettings(userId, new PeopleDtos.PersonSettingsRequest("vpa:friend.k", true));
        PeopleDtos.PersonDetailDto detail = people.person(userId, "vpa:friend.k");
        assertThat(detail.family()).isTrue();
        assertThat(detail.owedToUser()).isEqualByComparingTo("2000");
        assertThat(detail.timeline()).singleElement().satisfies(i -> assertThat(i.kind()).isEqualTo("LENT"));
    }

    @Test
    void ownUpiIdIsSaved() {
        people.setPreferences(userId, new PeopleDtos.PreferencesRequest("someone@upi"));
        assertThat(people.preferences(userId).ownUpiId()).isEqualTo("someone@upi");
    }
}
