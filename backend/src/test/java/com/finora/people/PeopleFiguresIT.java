package com.finora.people;

import com.finora.AbstractIntegrationTest;
import com.finora.budgets.BudgetDto;
import com.finora.budgets.BudgetService;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.LoanEntry;
import com.finora.entity.ShareLink;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.LoanEntryRepository;
import com.finora.repository.ShareLinkRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AnalyticsService;
import com.finora.service.DashboardService;
import com.finora.service.InsightsService;
import com.finora.service.ReportService;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every figure-reading service counts only the user's share of a shared bill, and leaves lent money
 * out of spending (spec §5). One user, this calendar month: a Rent bill of 30000 paid to the owner,
 * three flatmates' shares of 7500 linked to it, and 2000 lent to a friend. The user's Rent is 7500.
 */
class PeopleFiguresIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;
    @Autowired private LoanEntryRepository loanEntryRepository;
    @Autowired private DashboardService dashboardService;
    @Autowired private ReportService reportService;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private InsightsService insightsService;
    @Autowired private BudgetService budgetService;
    @Autowired private com.finora.service.RecurringService recurringService;

    private UUID userId;
    private UUID accountId;
    private Category rent;
    private final YearMonth month = YearMonth.now();

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("people-figures-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("People Figures User");
        userId = userRepository.save(u).getId();
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

    private Transaction txn(Transaction.Type type, int amount, LocalDate date, String key, UUID categoryId) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnType(type);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnDate(date);
        t.setDescription("UPI " + key);
        t.setCounterpartyKey(key);
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCategoryId(categoryId);
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    /** The bill, three linked shares and a lent payment, all on the 1st of {@code m}. */
    private void sharedRent(YearMonth billMonth, YearMonth shareMonth) {
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, billMonth.atDay(1), "vpa:owner.b", rent.getId());
        for (String who : new String[]{"vpa:flatmate.a", "vpa:flatmate.c", "vpa:flatmate.d"}) {
            Transaction share = txn(Transaction.Type.INCOME, 7500, shareMonth.atDay(1), who, rent.getId());
            shareLinkRepository.save(new ShareLink(userId, share.getId(), bill.getId(), BigDecimal.valueOf(7500)));
        }
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, billMonth.atDay(1), "vpa:friend.k", null);
        loanEntryRepository.save(new LoanEntry(userId, "vpa:friend.k", lent.getId(), LoanEntry.Direction.LENT, BigDecimal.valueOf(2000)));
    }

    @Test
    void reportMonthCountsTheShare() {
        sharedRent(month, month);
        assertThat(reportService.forMonth(userId, month.toString()).expense()).isEqualByComparingTo("7500");
    }

    @Test
    void reportRangeCountsTheShare() {
        sharedRent(month, month);
        assertThat(reportService.forRange(userId, month.atDay(1), month.atEndOfMonth()).expense()).isEqualByComparingTo("7500");
    }

    @Test
    void dashboardMonthSpendCountsTheShare() {
        sharedRent(month, month);
        var summary = dashboardService.summarize(userId);
        assertThat(summary.reportingMonth()).isEqualTo(month.toString());
        assertThat(summary.monthlyExpense()).isEqualByComparingTo("7500");
        assertThat(summary.spendByCategory().get("Rent")).isEqualByComparingTo("7500");
    }

    @Test
    void analyticsMonthCountsTheShare() {
        sharedRent(month, month);
        assertThat(analyticsService.totalExpense(userId, month)).isEqualByComparingTo("7500");
    }

    @Test
    void insightsMonthCountsTheShare() {
        sharedRent(month, month);
        var insights = insightsService.build(userId, month.toString());
        assertThat(insights.biggestCategory().name()).isEqualTo("Rent");
        assertThat(insights.biggestCategory().amount()).isEqualByComparingTo("7500");
    }

    @Test
    void budgetRentSpentIsTheShare() {
        sharedRent(month, month);
        budgetService.upsert(userId, new BudgetDto.UpsertRequest("Rent", BigDecimal.valueOf(10000)));
        BudgetDto budget = budgetService.listForUser(userId).stream()
                .filter(b -> "Rent".equals(b.categoryName())).findFirst().orElseThrow();
        assertThat(budget.spentThisMonth()).isEqualByComparingTo("7500");
    }

    @Test
    void shareCreditIsNotUnresolvedInflow() {
        sharedRent(month, month);
        var report = reportService.forRange(userId, month.atDay(1), month.atEndOfMonth());
        assertThat(report.unresolvedInflowCount()).isZero();
        assertThat(report.income()).isEqualByComparingTo("0");
    }

    /** Paid regularly and Coming up read the repeating payments: a split rent shows the user's
     *  share and how many people share it, beside the full amount older apps still read. */
    @Test
    void repeatingPaymentShowsYourShareAndHowManyShareIt() {
        for (int back = 3; back >= 0; back--) {
            YearMonth m = month.minusMonths(back);
            Transaction bill = txn(Transaction.Type.EXPENSE, 30000, m.atDay(1), "vpa:owner.b", rent.getId());
            bill.setMerchant("Owner");
            transactionRepository.save(bill);
            for (String who : new String[]{"vpa:flatmate.a", "vpa:flatmate.c", "vpa:flatmate.d"}) {
                Transaction share = txn(Transaction.Type.INCOME, 7500, m.atDay(1), who, rent.getId());
                shareLinkRepository.save(new ShareLink(userId, share.getId(), bill.getId(), BigDecimal.valueOf(7500)));
            }
        }
        var rentItem = recurringService.detectForUser(userId).stream()
                .filter(r -> "Owner".equals(r.merchant())).findFirst().orElseThrow();
        assertThat(rentItem.averageAmount()).isEqualByComparingTo("30000");
        assertThat(rentItem.yourShare()).isEqualByComparingTo("7500");
        assertThat(rentItem.sharedWith()).isEqualTo(3);
    }

    @Test
    void repeatingPaymentNobodySharesHasNoShareFields() {
        for (int back = 3; back >= 0; back--) {
            Transaction bill = txn(Transaction.Type.EXPENSE, 499, month.minusMonths(back).atDay(5), "vpa:stream.x", null);
            bill.setMerchant("Stream");
            transactionRepository.save(bill);
        }
        var item = recurringService.detectForUser(userId).stream()
                .filter(r -> "Stream".equals(r.merchant())).findFirst().orElseThrow();
        assertThat(item.yourShare()).isNull();
        assertThat(item.sharedWith()).isNull();
    }

    @Test
    void shareInNextMonthLowersThisMonth() {
        YearMonth previous = month.minusMonths(1);
        sharedRent(previous, month);
        assertThat(reportService.forMonth(userId, previous.toString()).expense()).isEqualByComparingTo("7500");
    }
}
