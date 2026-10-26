package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.SectionConfirm;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshRun;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.imports.refresh.StatementRefreshService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "How much do you owe on this card today?" -- answered at import review when a card statement
 * gives no closing balance. The answer is the card's balance, stated by the user, and nothing that
 * happens to rows already on the card afterwards moves it. Every case asserts the exact balance.
 * Design: docs/superpowers/specs/2026-10-26-card-amount-owed-prompt-design.md.
 */
class AmountOwedAtConfirmIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementRefreshService refreshService;
    @Autowired private TransactionService transactionService;
    @Autowired private com.finora.service.StatementImportService statementImportService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** A card statement with no balance anywhere: a payment of 2,000 and spends of 1,500. Summed
     *  from zero, that is -500.00 -- "in credit", which it almost certainly is not. */
    private static final byte[] CARD_FILE = ("Date,Description,Amount,Type\n"
            + "2025-04-03,PAYMENT RECEIVED,2000.00,CREDIT\n"
            + "2025-04-06,SAMPLE GROCER,1000.00,DEBIT\n"
            + "2025-04-10,SAMPLE FUEL,500.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);

    private static final BigDecimal OWED = new BigDecimal("12345.67");

    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void enableRefresh() {
        ReflectionTestUtils.setField(refreshService, "enabled", true);
    }

    @AfterEach
    void cleanUp() {
        ReflectionTestUtils.setField(refreshService, "enabled", false);
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId())).toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("amount-owed-confirm-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Amount Owed Confirm IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        return user;
    }

    private UUID account(User user, Account.Type type) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("IT " + type);
        account.setAccountType(type);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account).getId();
    }

    private static NewAccountRequest newCard() {
        return new NewAccountRequest("ICICI Card", "CREDIT_CARD", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Stages {@code file} for real and confirms it through the production path. */
    private StatementImport confirm(byte[] file, User user, UUID accountId, NewAccountRequest newAccount,
                                    BigDecimal opening, BigDecimal closing, BigDecimal amountOwedToday) throws Exception {
        var response = confirmResponse(file, user, accountId, newAccount, opening, closing, amountOwedToday);
        return statementImportRepository.findById(response.statementImportId()).orElseThrow();
    }

    private com.finora.dto.ImportDto.ConfirmResponse confirmResponse(byte[] file, User user, UUID accountId,
                                    NewAccountRequest newAccount, BigDecimal opening, BigDecimal closing,
                                    BigDecimal amountOwedToday) throws Exception {
        StagingResponse staging = importService.parseAndStage(user.getId(), "card.csv", new ByteArrayInputStream(file));
        ImportSession session = importSessionService.createSession(user.getId(), "card.csv", file,
                staging.rows(), staging.detectedAccount());
        List<ConfirmedRow> rows = new ArrayList<>();
        for (StagedRow r : staging.rows()) {
            rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                    true, r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                    r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
        }
        return importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(), rows,
                accountId, newAccount, opening, closing, null, null, null, null, null, null, null, null,
                amountOwedToday));
    }

    /** The result screen shows the account from the confirm response: a skipped answer must come
     *  back as not known, so the app hides the summed figure there too. */
    @Test
    void theConfirmResponseAccount_saysWhetherTheAmountOwedIsKnown() throws Exception {
        User user = user();
        var skipped = confirmResponse(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null, null, null, null);
        assertThat(skipped.account().amountOwedKnown()).isFalse();
        assertThat(skipped.account().balanceStated()).isFalse();

        var answered = confirmResponse(CARD_FILE, user(), null, newCard(), null, null, OWED);
        assertThat(answered.account().amountOwedKnown()).isTrue();
        assertThat(answered.account().balanceStated()).isTrue();
        assertThat(answered.account().balance()).isEqualByComparingTo("12345.67");
    }

    private Account accountOf(StatementImport s) {
        return accountRepository.findById(s.getAccountId()).orElseThrow();
    }

    private Transaction row(StatementImport s, String description) {
        return transactionRepository.findByStatementImportId(s.getId()).stream()
                .filter(t -> description.equals(t.getDescription())).findFirst().orElseThrow();
    }

    private boolean known(User user, UUID accountId) {
        return accountService.listForUser(user.getId()).stream()
                .filter(a -> a.id().equals(accountId)).findFirst().orElseThrow().amountOwedKnown();
    }

    @Test
    void newCardWithNoClosingBalance_takesTheAmountOwedAsItsTypedBalance() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, null, newCard(), null, null, OWED);

        Account card = accountOf(s);
        assertThat(card.getAccountType()).isEqualTo(Account.Type.CREDIT_CARD);
        assertThat(card.getBalance()).isEqualByComparingTo("12345.67");
        assertThat(card.getBalanceTypedAt()).isNotNull();
        assertThat(card.getBalanceTypedAt()).isAfter(row(s, "SAMPLE GROCER").getCreatedAt());
        assertThat(card.getBalanceBaselineDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1));
        assertThat(card.getLastAbsoluteSetStatementId()).isNull();
        assertThat(known(user, card.getId())).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM audit_logs
                 WHERE entity_id = ? AND action = 'ACCOUNT_UPDATED'
                   AND metadata->>'source' = 'IMPORT_CONFIRM'
                   AND (metadata->>'newBalance')::numeric = 12345.67
                   AND (metadata->>'previousBalance')::numeric = -500.00
                """, Long.class, card.getId())).isEqualTo(1L);
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void theMissingPreviousBalanceWarning_isLoggedOnlyWhenNothingWasStated(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String warning = "may be missing a previous balance";
        confirm(CARD_FILE, user(), null, newCard(), null, null, OWED);
        assertThat(output.getAll()).as("the user stated the figure").doesNotContain(warning);

        confirm(CARD_FILE, user(), null, newCard(), null, null, null);
        assertThat(output.getAll()).contains(warning);
    }

    @Test
    void anAnswerOfZero_isAStatedZero() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null, null, null,
                new BigDecimal("0.00"));
        Account card = accountOf(s);
        assertThat(card.getBalance()).isEqualByComparingTo("0.00");
        assertThat(card.getBalanceTypedAt()).isNotNull();
        assertThat(known(user, card.getId())).isTrue();
    }

    @Test
    void withoutTheAmount_theCardIsSummedFromZeroAndNotKnown() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null, null, null, null);
        Account card = accountOf(s);
        assertThat(card.getBalance()).isEqualByComparingTo("-500.00");
        assertThat(card.getBalanceTypedAt()).isNull();
        assertThat(known(user, card.getId())).isFalse();
    }

    @Test
    void deletingOrEditingARowOfThatStatementLater_doesNotMoveTheStatedFigure() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null, null, null, OWED);

        transactionService.delete(user.getId(), row(s, "SAMPLE GROCER").getId(), user.getId());
        assertThat(accountOf(s).getBalance()).isEqualByComparingTo("12345.67");
    }

    @Test
    void deletingTheWholeStatementLater_doesNotMoveTheStatedFigure_andItStaysKnown() throws Exception {
        User user = user();
        UUID cardId = account(user, Account.Type.CREDIT_CARD);
        StatementImport s = confirm(CARD_FILE, user, cardId, null, null, null, OWED);

        statementImportService.delete(user.getId(), s.getId(), user.getId());

        assertThat(accountRepository.findById(cardId).orElseThrow().getBalance()).isEqualByComparingTo("12345.67");
        assertThat(known(user, cardId)).isTrue();
    }

    @Test
    void reUploadingTheSameStatementAfterwards_doesNotMoveTheStatedFigure() throws Exception {
        User user = user();
        UUID cardId = account(user, Account.Type.CREDIT_CARD);
        confirm(CARD_FILE, user, cardId, null, null, null, OWED);

        confirm(CARD_FILE, user, cardId, null, null, null, null);

        assertThat(accountRepository.findById(cardId).orElseThrow().getBalance()).isEqualByComparingTo("12345.67");
    }

    @Test
    void aStatementRefreshAfterwards_doesNotMoveTheStatedFigure() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null, null, null, OWED);
        // How an older parser would have left it: one amount misread, one row missed.
        jdbcTemplate.update("UPDATE transactions SET amount = 100.00 WHERE id = ?", row(s, "SAMPLE GROCER").getId());
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", row(s, "SAMPLE FUEL").getId());

        StatementRefreshOutcome outcome = refreshService.refresh(user.getId(), s.getId(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.APPLIED);
        assertThat(outcome.rowsChanged()).isEqualTo(1);
        assertThat(outcome.rowsAdded()).isEqualTo(1);
        assertThat(accountOf(s).getBalance()).isEqualByComparingTo("12345.67");
        assertThat(outcome.balanceChange()).as("what the refresh summary tells the user: no change (null)").isNull();
    }

    @Test
    void aLaterStatement_movesTheFigureOnlyByRowsAfterIt() throws Exception {
        User user = user();
        UUID cardId = account(user, Account.Type.CREDIT_CARD);
        confirm(CARD_FILE, user, cardId, null, null, null, OWED);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        byte[] later = ("Date,Description,Amount,Type\n"
                + today.minusDays(30) + ",SAMPLE BOOKSHOP,300.00,DEBIT\n"
                + today + ",SAMPLE CAFE,250.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);
        StatementImport second = confirm(later, user, cardId, null, null, null, null);

        assertThat(accountOf(second).getBalance())
                .as("the bookshop spend is inside the figure stated today; only today's cafe is new")
                .isEqualByComparingTo("12595.67");
    }

    @Test
    void aClosingBalanceWins_overAnAmountOwedSentWithIt() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.CREDIT_CARD), null,
                new BigDecimal("8500.00"), new BigDecimal("8000.00"), BigDecimal.ONE);
        Account card = accountOf(s);
        assertThat(card.getBalance()).isEqualByComparingTo("8000.00");
        assertThat(card.getLastAbsoluteSetStatementId()).isEqualTo(s.getId());
        assertThat(card.getBalanceTypedAt()).isNull();
    }

    @Test
    void aSavingsAccount_ignoresTheAmount() throws Exception {
        User user = user();
        StatementImport s = confirm(CARD_FILE, user, account(user, Account.Type.SAVINGS), null, null, null,
                new BigDecimal("777.00"));
        Account savings = accountOf(s);
        assertThat(savings.getBalance()).isEqualByComparingTo("500.00");
        assertThat(savings.getBalanceTypedAt()).isNull();
    }

    @Test
    void aMultiAccountUpload_carriesTheAmountPerSection() throws Exception {
        User user = user();
        UUID savings = account(user, Account.Type.SAVINGS);
        UUID card = account(user, Account.Type.CREDIT_CARD);
        LocalDate when = LocalDate.of(2025, 4, 6);
        StagedRow staged = new StagedRow(when, "SAMPLE GROCER", new BigDecimal("1000.00"), "EXPENSE", "Other", "rule",
                null, false, null, null);
        ConfirmedRow confirmed = new ConfirmedRow(when, "SAMPLE GROCER", new BigDecimal("1000.00"), "EXPENSE", "Other",
                true, "rule", null, false, null, null, false);
        ImportSession session = importSessionService.createMultiSection(user.getId(), "consolidated.pdf",
                "irrelevant".getBytes(StandardCharsets.UTF_8), List.of(
                        new StagedAccountSection(null, List.of(staged), 1, 0, List.of()),
                        new StagedAccountSection(null, List.of(staged), 1, 0, List.of())));

        importService.confirmMultiSection(user.getId(), new MultiAccountConfirmRequest(session.getId(), List.of(
                new SectionConfirm(List.of(confirmed), savings, null, null, null, null, null, null, null, null, null),
                new SectionConfirm(List.of(confirmed), card, null, null, null, null, null, null, null, null, OWED))));

        assertThat(accountRepository.findById(savings).orElseThrow().getBalance()).isEqualByComparingTo("-1000.00");
        assertThat(accountRepository.findById(card).orElseThrow().getBalance()).isEqualByComparingTo("12345.67");
    }
}
