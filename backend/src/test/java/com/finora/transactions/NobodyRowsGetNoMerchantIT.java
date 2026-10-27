package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.PreviewGenerator;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AnalyticsService;
import com.finora.service.TransactionGroupingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A row whose narration names nobody -- a bare UPI reference, a cash withdrawal -- is stored with no
 * merchant, still gets a category, teaches nothing, and stays out of Top Merchants and the merchant
 * review groups. Through the real write paths (manual create, import preview and confirm) against
 * real Postgres. Every narration is invented.
 */
class NobodyRowsGetNoMerchantIT extends AbstractIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private TransactionGroupingService groupingService;
    @Autowired private PreviewGenerator previewGenerator;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportService importService;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private com.finora.repository.CategoryRuleRepository ruleRepository;
    @Autowired private JdbcTemplate jdbc;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setEmail("nobody-rows-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        user.setPhoneVerified(true);
        userId = userRepository.save(user).getId();
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(100000));
        accountId = accountRepository.save(account).getId();
    }

    @AfterEach
    void cleanUp() {
        // merchant_learning_events is shared by every IT; leave none of this user's behind.
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> userId.equals(e.getUserId())).toList());
        // category_rules is shared by every IT too.
        ruleRepository.deleteAll(ruleRepository.findAll().stream().filter(r -> userId.equals(r.getUserId())).toList());
    }

    private TransactionDto create(String narration, String categoryName) {
        return transactionService.create(userId, new TransactionDto.CreateRequest(
                accountId, categoryName, LocalDate.of(2026, 9, 15), narration,
                new BigDecimal("500.00"), "EXPENSE", null));
    }

    private Transaction stored(TransactionDto dto) {
        return transactionRepository.findById(dto.id()).orElseThrow();
    }

    @Test
    void aCashWithdrawalIsStoredWithNoMerchantAndStillFiledAsCash() {
        Transaction t = stored(create("ATM WDL 100000000001 SAMPLETOWN", null));

        assertThat(t.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(t.getCategoryId()).orElseThrow().getName()).isEqualTo("Cash Withdrawal");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchants WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    void aCategoryAPersonGivesARowThatNamesNobodyStaysOnThatRow() {
        Transaction t = stored(create("UPI/RRN 100000000001/UPI", "Shopping"));

        assertThat(t.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(t.getCategoryId()).orElseThrow().getName()).isEqualTo("Shopping");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_category_learning WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_learning_events WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    void aRowThatNamesAPayeeStillGetsItsMerchant_andOnlyItReachesTopMerchants() {
        Transaction named = stored(create("UPI/DR/100000000002/SAMPLEPA/HDFC/samplepayee21@okhdfc", null));
        stored(create("UPI/RRN 100000000003/UPI", null));
        stored(create("NWD-512345XXXXXX1234-SAMPLETOWN", null));

        assertThat(named.getMerchantId()).isNotNull();
        assertThat(analyticsService.topMerchants(userId, null))
                .extracting(top -> top.merchantId())
                .containsExactly(named.getMerchantId());
    }

    @Test
    void rowsThatNameNobodyNeverFormAMerchantReviewGroup() {
        // Two withdrawals at one place: before this change they shared a merchant named after the
        // place, so they formed a review group as if the ATM were a shop.
        Transaction first = stored(create("ATM WDL 100000000006 SAMPLETOWN", null));
        Transaction second = stored(create("ATM WDL 100000000007 SAMPLETOWN", null));
        // Both are left for review, so they are candidates for the review groups at all.
        jdbc.update("UPDATE transactions SET needs_category_review = true WHERE id IN (?, ?)", first.getId(), second.getId());

        assertThat(groupingService.groupNeedsReviewByMerchant(userId)).isEmpty();
    }

    @Test
    void aRuleOnTheMerchantNameStillMatchesARowThatNoLongerHasThatMerchant() {
        com.finora.entity.CategoryRule rule = new com.finora.entity.CategoryRule();
        rule.setUserId(userId);
        rule.setScope(com.finora.entity.CategoryRule.Scope.USER);
        rule.setField(com.finora.entity.CategoryRule.Field.MERCHANT);
        rule.setOperator(com.finora.entity.CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("Atm Wdl Sampletown");
        rule.setActionType(com.finora.entity.CategoryRule.ActionType.ASSIGN_CATEGORY);
        rule.setActionValue("Pocket Money");
        rule.setPriority(100);
        rule.setEnabled(true);
        ruleRepository.save(rule);

        Transaction t = stored(create("ATM WDL 100000000008 SAMPLETOWN", null));

        assertThat(t.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(t.getCategoryId()).orElseThrow().getName()).isEqualTo("Pocket Money");
    }

    @Test
    void aMandateThatRepeatsIsFiledAsThePersonFiledItLastTime() {
        stored(create("NACH CHQ 004471", "Insurance"));

        Transaction nextMonth = stored(create("NACH CHQ 004471", null));

        assertThat(nextMonth.getMerchantId()).isNull();
        assertThat(categoryRepository.findById(nextMonth.getCategoryId()).orElseThrow().getName()).isEqualTo("Insurance");
    }

    @Test
    void aChoiceForOneBareUpiReferenceIsNotCarriedToAnother() {
        stored(create("UPI/RRN 100000000011/UPI", "Gifts"));

        Transaction another = stored(create("UPI/RRN 100000000012/UPI", null));

        assertThat(categoryRepository.findById(another.getCategoryId()).orElseThrow().getName()).isNotEqualTo("Gifts");
    }

    @Test
    void aChoiceMadeInImportReviewIsRememberedForTheNextImport() throws Exception {
        String first = "Date,Description,Amount,Type\n2026-07-05,NACH CHQ 004481,500.00,DEBIT\n";
        List<StagedRow> rows = previewGenerator.generate(userId, "statement.csv",
                new ByteArrayInputStream(first.getBytes(StandardCharsets.UTF_8))).rows();
        StagedRow shown = rows.get(0).withRowPosition(0);
        ImportSession session = importSessionService.createSession(userId, "statement.csv",
                first.getBytes(StandardCharsets.UTF_8), List.of(shown), null);
        ConfirmedRow chosen = new ConfirmedRow(shown.date(), shown.description(), shown.amount(), shown.type(), "Insurance", true,
                shown.categorySource(), shown.ruleId(), false, null, null, false, shown.categoryConfidence(), shown.rowPosition());
        importService.confirmSession(userId, new ConfirmRequest(session.getId(), List.of(chosen), accountId, null, null, null, null));

        String next = "Date,Description,Amount,Type\n2026-08-05,NACH CHQ 004481,500.00,DEBIT\n";
        StagedRow nextMonth = previewGenerator.generate(userId, "statement.csv",
                new ByteArrayInputStream(next.getBytes(StandardCharsets.UTF_8))).rows().get(0);

        assertThat(nextMonth.suggestedCategory()).isEqualTo("Insurance");
        assertThat(nextMonth.merchant()).isNull();
    }

    private ConfirmedRow confirmed(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(), true,
                r.categorySource(), r.ruleId(), false, null, null, false, r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void anImportShowsAndStoresARowThatNamesNobodyWithNoMerchant() throws Exception {
        String csv = "Date,Description,Amount,Type\n"
                + "2026-07-01,UPI/RRN 100000000004/UPI,500.00,DEBIT\n"
                + "2026-07-02,UPI/DR/100000000005/SAMPLEPA/HDFC/samplepayee21@okhdfc,500.00,DEBIT\n";
        List<StagedRow> rows = previewGenerator.generate(userId, "statement.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).rows();
        StagedRow nobodyRow = rows.stream().filter(r -> r.description().startsWith("UPI/RRN")).findFirst().orElseThrow();
        assertThat(nobodyRow.merchant()).isNull();

        List<StagedRow> positioned = List.of(rows.get(0).withRowPosition(0), rows.get(1).withRowPosition(1));
        ImportSession session = importSessionService.createSession(userId, "statement.csv",
                csv.getBytes(StandardCharsets.UTF_8), positioned, null);
        importService.confirmSession(userId, new ConfirmRequest(session.getId(),
                positioned.stream().map(this::confirmed).toList(), accountId, null, null, null, null));

        List<Transaction> stored = transactionRepository.findByUserId(userId);
        assertThat(stored).filteredOn(t -> t.getDescription().startsWith("UPI/RRN")).singleElement()
                .extracting(Transaction::getMerchantId).isNull();
        assertThat(stored).filteredOn(t -> t.getDescription().startsWith("UPI/DR")).singleElement()
                .extracting(Transaction::getMerchantId).isNotNull();
    }
}
