package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.DashboardSummaryDto;
import com.finora.dto.InsightsDto;
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
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.util.CounterpartyType;
import com.finora.util.UserZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI insights foundation §4.3: every month-sheet and row-sheet value equals the figure the existing
 * services already show, on a user with a shared bill (shares linked), money lent, a user-created
 * category, a category in its first month and a three-month baseline. Every name and amount is invented.
 */
class AiInsightMonthSheetParityIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ShareLinkRepository shareLinkRepository;
    @Autowired private LoanEntryRepository loanEntryRepository;
    @Autowired private InsightsService insightsService;
    @Autowired private DashboardService dashboardService;
    @Autowired private AiInsightMonthSheetBuilder monthSheetBuilder;

    private UUID userId;
    private UUID accountId;
    private Category food;
    private Category friendRent;
    private final YearMonth month = YearMonth.now(UserZone.DEFAULT);

    @BeforeEach
    void setUp() {
        User u = new User();
        u.setEmail("ai-sheet-parity-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("not-used");
        u.setFullName("Sample User");
        userId = userRepository.save(u).getId();
        Account a = new Account();
        a.setUserId(userId);
        a.setName("Sample Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(a).getId();

        food = new Category();
        food.setUserId(userId);
        food.setName("Food");
        food.setSystem(true);
        food = categoryRepository.save(food);
        friendRent = new Category();
        friendRent.setUserId(userId);
        friendRent.setName("Friend K rent");
        friendRent = categoryRepository.save(friendRent);

        // Food: 1,000 in each of the three months before, 3,000 this month.
        for (int back = 3; back >= 1; back--) {
            txn(Transaction.Type.EXPENSE, 1000, month.minusMonths(back).atDay(5), "vpa:cafe.sample", food.getId());
        }
        txn(Transaction.Type.EXPENSE, 3000, month.atDay(1), "vpa:cafe.sample", food.getId());
        // A shared bill in a user category, first seen this month: 30,000 paid, three shares of 7,500 linked.
        Transaction bill = txn(Transaction.Type.EXPENSE, 30000, month.atDay(1), "vpa:owner.b", friendRent.getId());
        for (String who : new String[]{"vpa:flatmate.a", "vpa:flatmate.c", "vpa:flatmate.d"}) {
            Transaction share = txn(Transaction.Type.INCOME, 7500, month.atDay(1), who, friendRent.getId());
            shareLinkRepository.save(new ShareLink(userId, share.getId(), bill.getId(), BigDecimal.valueOf(7500)));
        }
        // Money lent is not spending.
        Transaction lent = txn(Transaction.Type.EXPENSE, 2000, month.atDay(1), "vpa:friend.k", null);
        loanEntryRepository.save(new LoanEntry(userId, "vpa:friend.k", lent.getId(), LoanEntry.Direction.LENT, BigDecimal.valueOf(2000)));
        txn(Transaction.Type.INCOME, 50000, month.atDay(1), "vpa:employer.e", null);
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

    private static BigDecimal firstRupeeFigure(String sentence) {
        Matcher m = Pattern.compile("₹([0-9,]+)").matcher(sentence);
        assertThat(m.find()).as("a rupee figure in: " + sentence).isTrue();
        return new BigDecimal(m.group(1).replace(",", ""));
    }

    @Test
    void monthSheetEqualsTheFiguresTheServicesShow() {
        InsightsDto insights = insightsService.build(userId, month.toString());
        DashboardSummaryDto dashboard = dashboardService.summarize(userId);

        FactSheet sheet = monthSheetBuilder.build(userId, month.toString()).orElseThrow();

        // Food 3,000 + the user's own share of the bill (7,500); the 2,000 lent is not spending.
        assertThat(sheet.fact("month.total").value()).isEqualByComparingTo("10500");
        assertThat(sheet.fact("month.total").value()).isEqualByComparingTo(firstRupeeFigure(insights.sentences().get(0)));

        for (InsightsDto.CategoryMover m : insights.movers()) {
            String subject = sheet.subjects().stream().filter(s -> s.displayName().equals(m.category()))
                    .findFirst().orElseThrow().id();
            assertThat(sheet.fact(subject + ".this_month").value()).as(m.category())
                    .isEqualByComparingTo(m.current());
            if (sheet.fact(subject + ".usual") != null) {
                assertThat(sheet.fact(subject + ".usual").value()).isEqualByComparingTo(m.priorAverage());
                assertThat(sheet.fact(subject + ".change_pct").value().doubleValue()).isEqualTo(m.pctChange());
            }
        }
        assertThat(sheet.fact("food.usual").value()).isEqualByComparingTo("1000");
        // First month for the user category: no usual month to compare with.
        assertThat(sheet.fact("category_1.this_month").value()).isEqualByComparingTo("7500");
        assertThat(sheet.fact("category_1.usual")).isNull();

        assertThat(dashboard.reportingMonth()).isEqualTo(month.toString());
        assertThat(sheet.fact("month.money_in").value()).isEqualByComparingTo(dashboard.monthlyIncome());
        assertThat(sheet.fact("month.money_out").value()).isEqualByComparingTo(dashboard.monthlyExpense());

        assertThat(sheet.modelJson()).doesNotContain("Friend K").doesNotContain("vpa:");
    }
}
