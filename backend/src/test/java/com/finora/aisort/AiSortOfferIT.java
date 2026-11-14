package com.finora.aisort;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCostGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Who sees the AI sorting card, and with what numbers (spec 2026-11-14-ai-sort-after-import, "Who sees the card"). */
class AiSortOfferIT extends AbstractIntegrationTest {

    @Autowired private AiSortService aiSort;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private CategoryRepository categories;
    @Autowired private StatementImportRepository statementImports;
    @Autowired private TransactionRepository transactions;
    @Autowired private UserMerchantCategoryResolutionRepository resolutions;
    @Autowired private TransactionTemplate tx;

    @MockitoBean private FynAvailabilityGuard guard;
    @MockitoBean private FynCostGovernanceService costs;

    private UUID userId;
    private UUID accountId;
    private UUID otherId;
    private UUID importId;
    private LocalDate nextDate = LocalDate.of(2026, 11, 10);

    @BeforeEach
    void setUp() {
        when(guard.categorizationOffered()).thenReturn(true);
        when(costs.userDailyCapReached(any())).thenReturn(false);
        userId = newUser(Boolean.TRUE);
        accountId = newAccount(userId);
        otherId = category(userId, "Other");
        importId = newImport(userId, accountId);
    }

    @Test
    void offersSortToACustomerWhoSaidYes() {
        String shopA = shop();
        row(importId, shopA, 500);
        row(importId, shopA, 300);
        row(importId, shop(), 200);

        AiSortDto.Offer offer = aiSort.offer(userId, List.of(importId));

        assertThat(offer.show()).isTrue();
        assertThat(offer.mode()).isEqualTo(AiSortDto.Mode.SORT);
        assertThat(offer.payments()).isEqualTo(3);
        assertThat(offer.amount()).isEqualByComparingTo("1000");
        assertThat(offer.payees()).isEqualTo(2);
    }

    @Test
    void ordersPayeesByMoney() {
        String small = shop();
        String big = shop();
        row(importId, small, 100);
        row(importId, big, 900);

        assertThat(aiSort.sortablePayees(userId, List.of(importId)))
                .extracting(AiSortService.SortablePayee::counterpartyKey)
                .containsExactly(keyOf(big), keyOf(small));
    }

    @Test
    void asksACustomerWhoSaidNoOnce() {
        UUID no = newUser(Boolean.FALSE);
        UUID account = newAccount(no);
        category(no, "Other");
        UUID imp = newImport(no, account);
        rowFor(no, account, imp, shop(), 400, Transaction.Type.EXPENSE, true);

        assertThat(aiSort.offer(no, List.of(imp)).mode()).isEqualTo(AiSortDto.Mode.ASK);

        User u = users.findById(no).orElseThrow();
        u.recordAiImportQuestion(Instant.now());
        users.save(u);
        assertThat(aiSort.offer(no, List.of(imp)).show()).isFalse();
    }

    @Test
    void noOfferForANeverAskedCustomer() {
        UUID never = newUser(null);
        UUID account = newAccount(never);
        category(never, "Other");
        UUID imp = newImport(never, account);
        rowFor(never, account, imp, shop(), 400, Transaction.Type.EXPENSE, true);

        assertThat(aiSort.offer(never, List.of(imp)).show()).isFalse();
    }

    @Test
    void leavesOutPaymentsToPeopleAndSavedPayees() {
        String person = "UPI-RAVI KUMAR-ravik" + suffix() + "@okaxis-REF1";
        assertThat(CategorizationService.mayGoToModel(person)).isFalse();
        row(importId, person, 700);
        String saved = shop();
        row(importId, saved, 600);
        tx.executeWithoutResult(s -> resolutions.insertIfAbsent(userId, keyOf(saved), "EXPENSE", otherId, Instant.now()));
        row(importId, shop(), 50);

        AiSortDto.Offer offer = aiSort.offer(userId, List.of(importId));

        assertThat(offer.payments()).isEqualTo(1);
        assertThat(offer.amount()).isEqualByComparingTo("50");
    }

    @Test
    void leavesOutMoneyInAndRowsNotWaiting() {
        rowFor(userId, accountId, importId, shop(), 400, Transaction.Type.INCOME, true);
        rowFor(userId, accountId, importId, shop(), 300, Transaction.Type.EXPENSE, false);

        assertThat(aiSort.offer(userId, List.of(importId)).show()).isFalse();
    }

    @Test
    void noOfferWhenAiIsOffGlobally() {
        row(importId, shop(), 400);
        when(guard.categorizationOffered()).thenReturn(false);

        assertThat(aiSort.offer(userId, List.of(importId)).show()).isFalse();
    }

    @Test
    void noOfferWhenTodaysCapIsReached() {
        row(importId, shop(), 400);
        when(costs.userDailyCapReached(userId)).thenReturn(true);

        assertThat(aiSort.offer(userId, List.of(importId)).show()).isFalse();
    }

    @Test
    void noOfferForAnotherCustomersImport() {
        row(importId, shop(), 400);
        UUID stranger = newUser(Boolean.TRUE);

        assertThat(aiSort.offer(stranger, List.of(importId)).show()).isFalse();
    }

    @Test
    void noOfferWhenNothingIsSortable() {
        assertThat(aiSort.offer(userId, List.of(importId)).show()).isFalse();
        assertThat(aiSort.offer(userId, List.of()).show()).isFalse();
    }

    // --- fixtures ---

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** A shop narration with its own payee key; merchant_understanding is shared by every IT. */
    private static String shop() {
        return "UPI-QZX HARDWARE STORE-qzxstore" + suffix() + "@ybl-REF881234";
    }

    private static String keyOf(String narration) {
        return com.finora.util.CounterpartyTyping.of(narration).key();
    }

    private void row(UUID statementImportId, String narration, double amount) {
        rowFor(userId, accountId, statementImportId, narration, amount, Transaction.Type.EXPENSE, true);
    }

    private void rowFor(UUID owner, UUID account, UUID statementImportId, String narration, double amount,
                        Transaction.Type type, boolean waiting) {
        Transaction t = new Transaction();
        t.setUserId(owner);
        t.setAccountId(account);
        t.setStatementImportId(statementImportId);
        t.setCategoryId(categories.findByUserId(owner).get(0).getId());
        t.setTxnDate(nextDate);
        nextDate = nextDate.minusDays(1);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTxnType(type);
        t.setDescription(narration);
        t.applyCounterpartyTyping(narration);
        t.setNeedsCategoryReview(waiting);
        transactions.save(t);
    }

    private UUID newUser(Boolean consent) {
        User u = new User();
        u.setEmail("ai-sort-offer-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Ai Sort Offer IT");
        u.setAccountScope(User.SCOPE_USER);
        if (consent != null) u.recordAiProcessingConsent(consent, Instant.now());
        return users.save(u).getId();
    }

    private UUID newAccount(UUID owner) {
        Account a = new Account();
        a.setUserId(owner);
        a.setName("Ai Sort Offer IT Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(1000));
        return accounts.save(a).getId();
    }

    private UUID category(UUID owner, String name) {
        Category c = new Category();
        c.setUserId(owner);
        c.setName(name);
        return categories.save(c).getId();
    }

    private UUID newImport(UUID owner, UUID account) {
        StatementImport s = new StatementImport();
        s.setUserId(owner);
        s.setAccountId(account);
        s.setFileName("sample.csv");
        s.setSourceFormat("CSV");
        s.setFileContent(new byte[]{1});
        s.setContentHash("ai-sort-offer-it-" + UUID.randomUUID());
        return statementImports.save(s).getId();
    }
}
