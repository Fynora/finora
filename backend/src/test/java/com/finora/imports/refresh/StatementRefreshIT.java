package com.finora.imports.refresh;

import com.finora.AbstractIntegrationTest;
import com.finora.config.BuildVersionResolver;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshRun;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshRunRepository;
import com.finora.repository.TransactionRelationshipRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import com.finora.service.TransactionGraphService;
import com.finora.transactions.TransactionDto;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The refresh end to end against real Postgres. A real CSV statement is imported, then its rows
 * AND the account balance are put back the way an older parser would have left them. A refresh
 * must bring both to exactly what a clean import of the same file gives -- the same values, the
 * same balance, and the same transaction ids wherever a row survives.
 */
class StatementRefreshIT extends AbstractIntegrationTest {

    @Autowired private StatementRefreshService refreshService;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private TransactionService transactionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private TransactionGraphService graphService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementRefreshRunRepository runRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionRelationshipRepository relationshipRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private BuildVersionResolver buildVersionResolver;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StatementRefreshDryRunService dryRunService;
    @Autowired private StatementRefreshUserService userService;
    @Autowired private StatementRefreshNotifier notifier;

    /** Four purchases, 950.00 in all. */
    private static final byte[] FILE = ("Date,Description,Amount,Type\n"
            + "2026-07-01,SAMPLE GROCER,450.00,DEBIT\n"
            + "2026-07-02,SAMPLE CAFE,120.00,DEBIT\n"
            + "2026-07-03,SAMPLE BOOKSHOP,300.00,DEBIT\n"
            + "2026-07-04,SAMPLE PHARMACY,80.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void enable() {
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

    private record Imported(User user, StatementImport statement) {
        UUID userId() { return user.getId(); }
        UUID id() { return statement.getId(); }
    }

    private Imported importStatement(BigDecimal opening, BigDecimal closing, String... leftOut) throws Exception {
        return importFile(FILE, null, opening, closing, leftOut);
    }

    /** Imports {@code file} into {@code into}'s account, or for a new user and account when null. */
    private Imported importFile(byte[] file, Imported into, BigDecimal opening, BigDecimal closing,
                                String... leftOut) throws Exception {
        if (into != null) return confirmInto(file, into.user(), into.statement().getAccountId(), opening, closing, leftOut);
        User user = new User();
        user.setEmail("refresh-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Refresh IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);
        return confirmInto(file, user, account.getId(), opening, closing, leftOut);
    }

    private Imported confirmInto(byte[] file, User user, UUID accountId, BigDecimal opening, BigDecimal closing,
                                 String... leftOut) throws Exception {
        StagingResponse staging = importService.parseAndStage(user.getId(), "statement.csv", new ByteArrayInputStream(file));
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv", file,
                staging.rows(), staging.detectedAccount());
        List<String> excluded = List.of(leftOut);
        List<ConfirmedRow> rows = new ArrayList<>();
        for (StagedRow r : staging.rows()) {
            rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                    !excluded.contains(r.description()), r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                    r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
        }
        var response = importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(), rows, accountId,
                null, opening, closing, null));
        StatementImport statement = statementImportRepository.findById(response.statementImportId()).orElseThrow();
        return new Imported(user, statement);
    }

    private Imported importStatement(String... leftOut) throws Exception {
        return importStatement(null, null, leftOut);
    }

    private Transaction row(Imported i, String description) {
        return transactionRepository.findByStatementImportId(i.id()).stream()
                .filter(t -> description.equals(t.getDescription())).findFirst().orElseThrow();
    }

    private BigDecimal balance(Imported i) {
        return accountRepository.findById(i.statement().getAccountId()).orElseThrow().getBalance();
    }

    private void moveBalance(Imported i, String by) {
        jdbcTemplate.update("UPDATE accounts SET balance = balance + ? WHERE id = ?", new BigDecimal(by),
                i.statement().getAccountId());
    }

    private UUID insertPhantom(Imported i, String description, String amount, int ordinal) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions (id, user_id, account_id, statement_import_id, txn_date, description,
                                          amount, txn_type, created_at, updated_at, version, reconciliation_status,
                                          source, row_ordinal, source_row_position)
                SELECT ?, user_id, account_id, id, DATE '2026-07-05', ?, ?, 'EXPENSE', imported_at, now(), 0,
                       'OK', 'CSV_IMPORT', ?, ?
                  FROM statement_imports WHERE id = ?
                """, id, description, new BigDecimal(amount), ordinal, ordinal, i.id());
        return id;
    }

    private StatementRefreshRun lastRun(Imported i) {
        return runRepository.findByStatementImportIdOrderByCreatedAtDesc(i.id()).get(0);
    }

    @Test
    void anOlderParsersStatement_isBroughtToExactlyWhatACleanImportGives_keepingEveryIdThatSurvives() throws Exception {
        Imported i = importStatement();
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
        UUID grocer = row(i, "SAMPLE GROCER").getId();
        UUID cafe = row(i, "SAMPLE CAFE").getId();
        UUID bookshop = row(i, "SAMPLE BOOKSHOP").getId();

        // How an older parser left it: an amount misread, a narration cut short, a row missed and a
        // page-furniture line taken for a purchase -- and the balance each of those produced.
        jdbcTemplate.update("UPDATE transactions SET amount = 45.00 WHERE id = ?", grocer);
        moveBalance(i, "405.00");
        jdbcTemplate.update("UPDATE transactions SET description = 'SAMPLE CAF' WHERE id = ?", cafe);
        UUID pharmacy = row(i, "SAMPLE PHARMACY").getId();
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", pharmacy);
        moveBalance(i, "80.00");
        UUID phantom = insertPhantom(i, "PAGE 1 OF 2", "3.00", 9);
        moveBalance(i, "-3.00");

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.APPLIED);
        assertThat(outcome.rowsChanged()).isEqualTo(2);
        assertThat(outcome.rowsAdded()).isEqualTo(1);
        assertThat(outcome.rowsRemoved()).isEqualTo(1);
        assertThat(transactionRepository.findByStatementImportId(i.id()))
                .extracting(Transaction::getDescription, t -> t.getAmount().toPlainString(), Transaction::getTxnDate)
                .containsExactlyInAnyOrder(
                        tuple("SAMPLE GROCER", "450.00", java.time.LocalDate.of(2026, 7, 1)),
                        tuple("SAMPLE CAFE", "120.00", java.time.LocalDate.of(2026, 7, 2)),
                        tuple("SAMPLE BOOKSHOP", "300.00", java.time.LocalDate.of(2026, 7, 3)),
                        tuple("SAMPLE PHARMACY", "80.00", java.time.LocalDate.of(2026, 7, 4)));
        assertThat(balance(i)).as("the balance a clean import of the file gives").isEqualByComparingTo("-950.00");
        assertThat(outcome.balanceChange()).isEqualByComparingTo("-482.00");
        assertThat(row(i, "SAMPLE GROCER").getId()).isEqualTo(grocer);
        assertThat(row(i, "SAMPLE CAFE").getId()).isEqualTo(cafe);
        assertThat(row(i, "SAMPLE BOOKSHOP").getId()).isEqualTo(bookshop);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM transactions WHERE id = ?",
                Boolean.class, phantom)).as("removed, soft-deleted").isTrue();

        StatementImport statement = statementImportRepository.findById(i.id()).orElseThrow();
        assertThat(statement.getParserVersion()).isEqualTo(buildVersionResolver.currentCommit());
        assertThat(statement.getTransactionsImported()).isEqualTo(4);
        StatementRefreshRun run = lastRun(i);
        assertThat((List<Map<String, Object>>) run.getDetail().get("removed"))
                .extracting(m -> m.get("description"), m -> m.get("userEdited"))
                .containsExactly(tuple("PAGE 1 OF 2", false));
        assertThat((List<Map<String, Object>>) run.getDetail().get("added"))
                .extracting(m -> m.get("description")).containsExactly("SAMPLE PHARMACY");

        StatementRefreshOutcome again = refreshService.refresh(i.userId(), i.id(), null);
        assertThat(again.status()).as("a second refresh finds nothing left").isEqualTo(StatementRefreshRun.Status.NO_CHANGES);
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
    }

    @Test
    void aFieldTheUserEdited_keepsTheirValue_whileTheParsersOtherCorrectionsApply() throws Exception {
        Imported i = importStatement();
        Transaction cafe = row(i, "SAMPLE CAFE");
        transactionService.update(i.userId(), cafe.getId(), new TransactionDto.UpdateRequest(
                cafe.getTxnDate(), cafe.getDescription(), cafe.getMerchant(), new BigDecimal("999.00"),
                "EXPENSE", null, null, null));
        BigDecimal afterEdit = balance(i);
        jdbcTemplate.update("UPDATE transactions SET description = 'SAMPLE GROC' WHERE id = ?", row(i, "SAMPLE GROCER").getId());

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.APPLIED);
        assertThat(outcome.rowsChanged()).isEqualTo(1);
        assertThat(row(i, "SAMPLE CAFE").getAmount()).as("the user's amount").isEqualByComparingTo("999.00");
        assertThat(row(i, "SAMPLE GROCER").getAmount()).isEqualByComparingTo("450.00");
        assertThat(balance(i)).as("a narration fix moves no money").isEqualByComparingTo(afterEdit);
    }

    @Test
    void anEditedRowThatNoLongerPairs_isRemovedAndReadAgain_andTheSummarySaysTheUserHadEditedIt() throws Exception {
        Imported i = importStatement();
        Transaction grocer = row(i, "SAMPLE GROCER");
        transactionService.update(i.userId(), grocer.getId(), new TransactionDto.UpdateRequest(
                grocer.getTxnDate(), grocer.getDescription(), grocer.getMerchant(), new BigDecimal("500.00"),
                "EXPENSE", null, null, null));
        assertThat(balance(i)).isEqualByComparingTo("-1000.00");
        // The older parser also cut the narration short: two fields differ, so nothing pairs.
        jdbcTemplate.update("UPDATE transactions SET description = 'SAMPLE GROC' WHERE id = ?", grocer.getId());

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.rowsRemoved()).isEqualTo(1);
        assertThat(outcome.rowsAdded()).isEqualTo(1);
        assertThat(row(i, "SAMPLE GROCER").getAmount()).isEqualByComparingTo("450.00");
        assertThat(row(i, "SAMPLE GROCER").getId()).isNotEqualTo(grocer.getId());
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
        assertThat((List<Map<String, Object>>) lastRun(i).getDetail().get("removed"))
                .extracting(m -> m.get("description"), m -> m.get("userEdited"))
                .containsExactly(tuple("SAMPLE GROC", true));
    }

    @Test
    void rowsTheUserDeletedOrLeftOut_neverComeBack() throws Exception {
        Imported i = importStatement("SAMPLE BOOKSHOP");
        transactionService.delete(i.userId(), row(i, "SAMPLE CAFE").getId(), i.userId());
        BigDecimal before = balance(i);
        jdbcTemplate.update("UPDATE statement_imports SET parser_version = 'oldbuild' WHERE id = ?", i.id());

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.NO_CHANGES);
        assertThat(transactionRepository.findByStatementImportId(i.id())).extracting(Transaction::getDescription)
                .containsExactlyInAnyOrder("SAMPLE GROCER", "SAMPLE PHARMACY");
        assertThat(balance(i)).isEqualByComparingTo(before);
        assertThat(statementImportRepository.findById(i.id()).orElseThrow().getParserVersion())
                .as("recorded as read by this build").isEqualTo(buildVersionResolver.currentCommit());
    }

    @Test
    void aMissedRowOnAStatementWhoseClosingBalanceSetTheAccount_movesNoMoney() throws Exception {
        // Opening 1000.00, four purchases of 950.00, closing 50.00: the bank's figure sets the balance.
        Imported i = importStatement(new BigDecimal("1000.00"), new BigDecimal("50.00"));
        StatementImport statement = statementImportRepository.findById(i.id()).orElseThrow();
        assertThat(statement.getBalanceApplicationMode()).isEqualTo(StatementImport.BalanceApplicationMode.ABSOLUTE);
        assertThat(balance(i)).isEqualByComparingTo("50.00");
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", row(i, "SAMPLE PHARMACY").getId());

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.rowsAdded()).isEqualTo(1);
        assertThat(balance(i)).as("the closing balance already counted it").isEqualByComparingTo("50.00");
        assertThat(outcome.balanceChange()).isNull();
    }

    @Test
    void aReReadThatWouldRemoveTooMuch_changesNothing() throws Exception {
        Imported i = importStatement();
        for (int n = 0; n < 3; n++) insertPhantom(i, "SAMPLE PHANTOM " + n, "10.00", 10 + n);
        BigDecimal before = balance(i);
        String versionBefore = statementImportRepository.findById(i.id()).orElseThrow().getParserVersion();

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.NEEDS_REVIEW);
        assertThat(transactionRepository.findByStatementImportId(i.id())).hasSize(7);
        assertThat(balance(i)).isEqualByComparingTo(before);
        assertThat(statementImportRepository.findById(i.id()).orElseThrow().getParserVersion()).isEqualTo(versionBefore);
    }

    @Test
    void aStoredFileThatNowReadsAsNothing_changesNothing() throws Exception {
        Imported i = importStatement();
        jdbcTemplate.update("UPDATE statement_imports SET file_content = ?, object_key = NULL WHERE id = ?",
                "not,a,statement\n".getBytes(StandardCharsets.UTF_8), i.id());

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.FAILED);
        assertThat(outcome.reason()).isEqualTo("PARSED_NO_ROWS");
        assertThat(transactionRepository.findByStatementImportId(i.id())).hasSize(4);
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
    }

    @Test
    void aRemovedRowsLinks_areRejectedWithIt() throws Exception {
        Imported i = importStatement();
        UUID phantom = insertPhantom(i, "PAGE 1 OF 2", "3.00", 9);
        UUID grocer = row(i, "SAMPLE GROCER").getId();
        TransactionRelationship edge = graphService.linkAll(List.of(new TransactionGraphService.PendingEdge(
                i.userId(), phantom, grocer, TransactionRelationship.RelationshipType.REFUND, new BigDecimal("3.00"),
                90, 90, TransactionRelationship.Status.AUTO_CONFIRMED,
                TransactionRelationship.DetectionMethod.RULE_ENGINE, Map.of()))).get(0);

        refreshService.refresh(i.userId(), i.id(), null);

        assertThat(relationshipRepository.findById(edge.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionRelationship.Status.REJECTED);
    }

    @Test
    void onlyTheOwnerCanRefresh_notAReplacedStatement_andNothingWhileSwitchedOff() throws Exception {
        Imported i = importStatement();
        Imported other = importStatement();
        assertThatThrownBy(() -> refreshService.refresh(other.userId(), i.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));

        ReflectionTestUtils.setField(refreshService, "enabled", false);
        assertThatThrownBy(() -> refreshService.refresh(i.userId(), i.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        ReflectionTestUtils.setField(refreshService, "enabled", true);

        jdbcTemplate.update("UPDATE statement_imports SET superseded_by = ? WHERE id = ?", other.id(), i.id());
        assertThatThrownBy(() -> refreshService.refresh(i.userId(), i.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @Test
    void deletingTheStatement_deletesItsRefreshResults() throws Exception {
        Imported i = importStatement();
        jdbcTemplate.update("UPDATE transactions SET description = 'SAMPLE CAF' WHERE id = ?", row(i, "SAMPLE CAFE").getId());
        refreshService.refresh(i.userId(), i.id(), null);
        assertThat(runRepository.findByStatementImportIdOrderByCreatedAtDesc(i.id())).isNotEmpty();

        statementImportService.delete(i.userId(), i.id(), i.userId());

        assertThat(runRepository.findByStatementImportIdOrderByCreatedAtDesc(i.id())).isEmpty();
    }

    @Test
    void aMissedRowThatAnotherStatementAlreadyHas_isLeftOut_notCountedTwice() throws Exception {
        Imported first = importStatement();
        byte[] overlapping = ("Date,Description,Amount,Type\n"
                + "2026-07-04,SAMPLE PHARMACY,80.00,DEBIT\n"
                + "2026-07-05,SAMPLE BAKERY,60.00,DEBIT\n"
                + "2026-07-06,SAMPLE TAXI,90.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);
        Imported second = importFile(overlapping, first, null, null, "SAMPLE PHARMACY");
        // An older parser never read the overlapping row at all: no transaction, no left-out record.
        jdbcTemplate.update("DELETE FROM statement_import_excluded_rows WHERE statement_import_id = ?", second.id());
        BigDecimal before = balance(first);

        StatementRefreshOutcome outcome = refreshService.refresh(second.userId(), second.id(), null);

        assertThat(outcome.rowsAdded()).isZero();
        assertThat(transactionRepository.findByStatementImportId(second.id())).extracting(Transaction::getDescription)
                .containsExactlyInAnyOrder("SAMPLE BAKERY", "SAMPLE TAXI");
        assertThat(balance(first)).isEqualByComparingTo(before);
        assertThat((List<Map<String, Object>>) lastRun(second).getDetail().get("skippedAsDuplicate"))
                .extracting(m -> m.get("description")).containsExactly("SAMPLE PHARMACY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM statement_import_excluded_rows WHERE statement_import_id = ? AND likely_duplicate",
                Integer.class, second.id())).as("recognised next time").isEqualTo(1);
    }

    /** An interest credit is labelled "interest" on import, and again after a corrected narration or direction. */
    @Test
    void aCorrectedInterestCredit_isLabelledAsACleanImportLabelsIt() throws Exception {
        byte[] dailyInterest = ("Date,Description,Amount,Type\n"
                + "2026-07-01,Interest Cr. for 30-Jun-2026,12.34,CREDIT\n"
                + "2026-07-02,Interest Cr. for 01-Jul-2026,12.35,CREDIT\n"
                + "2026-07-03,SAMPLE CAFE,120.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);
        Imported i = importFile(dailyInterest, null, null, null);
        assertThat(transactionRepository.findByStatementImportId(i.id()))
                .extracting(Transaction::getDescription, Transaction::getMerchant)
                .containsExactlyInAnyOrder(
                        tuple("Interest Cr. for 30-Jun-2026", "interest"),
                        tuple("Interest Cr. for 01-Jul-2026", "interest"),
                        tuple("SAMPLE CAFE", "sample cafe"));

        // How an older parser left them: one narration cut short, the other credit read as a debit,
        // each carrying the label its reading gave.
        UUID cutShort = row(i, "Interest Cr. for 30-Jun-2026").getId();
        jdbcTemplate.update("UPDATE transactions SET description = 'Interest Cr. for 30-Jun', merchant = 'interest cr for 30' WHERE id = ?",
                cutShort);
        UUID misread = row(i, "Interest Cr. for 01-Jul-2026").getId();
        jdbcTemplate.update("UPDATE transactions SET txn_type = 'EXPENSE', merchant = 'interest cr for 01' WHERE id = ?", misread);
        moveBalance(i, "-24.70");

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.APPLIED);
        // A corrected narration is a change to the row; a corrected direction is never matched to
        // the old row (every matching pass keys on the direction), so it is read again as a new one.
        assertThat(outcome.rowsChanged()).isEqualTo(1);
        assertThat(outcome.rowsRemoved()).isEqualTo(1);
        assertThat(outcome.rowsAdded()).isEqualTo(1);
        Transaction narrationFixed = transactionRepository.findById(cutShort).orElseThrow();
        assertThat(narrationFixed.getDescription()).isEqualTo("Interest Cr. for 30-Jun-2026");
        assertThat(narrationFixed.getMerchant()).isEqualTo("interest");
        Transaction directionFixed = row(i, "Interest Cr. for 01-Jul-2026");
        assertThat(directionFixed.getId()).isNotEqualTo(misread);
        assertThat(directionFixed.getTxnType()).isEqualTo(Transaction.Type.INCOME);
        assertThat(directionFixed.getMerchant()).isEqualTo("interest");
        assertThat(balance(i)).isEqualByComparingTo("-95.31");
    }

    @Test
    void aSecondIdenticalRowOnTheSameStatement_isAdded_notMistakenForADuplicate() throws Exception {
        byte[] twoFares = ("Date,Description,Amount,Type\n"
                + "2026-07-01,SAMPLE METRO FARE,45.00,DEBIT\n"
                + "2026-07-01,SAMPLE METRO FARE,45.00,DEBIT\n"
                + "2026-07-02,SAMPLE CAFE,120.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);
        Imported i = importFile(twoFares, null, null, null);
        assertThat(transactionRepository.findByStatementImportId(i.id())).hasSize(3);
        assertThat(balance(i)).isEqualByComparingTo("-210.00");
        UUID oneFare = transactionRepository.findByStatementImportId(i.id()).stream()
                .filter(t -> "SAMPLE METRO FARE".equals(t.getDescription())).findFirst().orElseThrow().getId();
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", oneFare);
        moveBalance(i, "45.00");

        StatementRefreshOutcome outcome = refreshService.refresh(i.userId(), i.id(), null);

        assertThat(outcome.rowsAdded()).isEqualTo(1);
        assertThat(transactionRepository.findByStatementImportId(i.id())).extracting(Transaction::getDescription)
                .containsExactlyInAnyOrder("SAMPLE METRO FARE", "SAMPLE METRO FARE", "SAMPLE CAFE");
        assertThat(balance(i)).isEqualByComparingTo("-210.00");
    }

    @Test
    void aStatementOnADeletedAccount_isNotRefreshed() throws Exception {
        Imported i = importStatement();
        jdbcTemplate.update("UPDATE accounts SET deleted_at = now() WHERE id = ?", i.statement().getAccountId());

        assertThatThrownBy(() -> refreshService.refresh(i.userId(), i.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    /** A second account with one credit of {@code amount} on the GROCER row's date, paired with that row as a transfer. */
    private UUID pairWithCreditElsewhere(Imported i, UUID row, String amount, TransactionRelationship.Status edgeStatus) {
        Account other = new Account();
        other.setUserId(i.userId());
        other.setName("Wallet");
        other.setAccountType(Account.Type.SAVINGS);
        other.setBalance(BigDecimal.ZERO);
        other = accountRepository.save(other);
        UUID credit = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, description, amount, txn_type, created_at,
                                          updated_at, version, reconciliation_status, source, is_transfer, transfer_pair_id)
                VALUES (?, ?, ?, DATE '2026-07-01', 'SAMPLE WALLET TOP UP', ?, 'INCOME', now(), now(), 0, 'TRANSFER',
                        'MANUAL', true, ?)
                """, credit, i.userId(), other.getId(), new BigDecimal(amount), row);
        jdbcTemplate.update("UPDATE transactions SET is_transfer = true, transfer_pair_id = ?, reconciliation_status = 'TRANSFER' WHERE id = ?",
                credit, row);
        graphService.linkAll(List.of(new TransactionGraphService.PendingEdge(i.userId(), row, credit,
                TransactionRelationship.RelationshipType.TRANSFER, new BigDecimal(amount), 90, 90, edgeStatus,
                edgeStatus == TransactionRelationship.Status.USER_CONFIRMED
                        ? TransactionRelationship.DetectionMethod.MANUAL : TransactionRelationship.DetectionMethod.RULE_ENGINE,
                Map.of())));
        return credit;
    }

    @Test
    void aTransferMatchedOnAMisreadAmount_isUndoneWhenTheAmountIsCorrected() throws Exception {
        Imported i = importStatement();
        UUID grocer = row(i, "SAMPLE GROCER").getId();
        // The older parser read 450.00 as 45.00, and reconciliation paired it with a 45.00 credit.
        jdbcTemplate.update("UPDATE transactions SET amount = 45.00 WHERE id = ?", grocer);
        moveBalance(i, "405.00");
        UUID credit = pairWithCreditElsewhere(i, grocer, "45.00", TransactionRelationship.Status.AUTO_CONFIRMED);

        refreshService.refresh(i.userId(), i.id(), null);

        Transaction corrected = transactionRepository.findById(grocer).orElseThrow();
        Transaction other = transactionRepository.findById(credit).orElseThrow();
        assertThat(corrected.getAmount()).isEqualByComparingTo("450.00");
        assertThat(corrected.isTransfer()).isFalse();
        assertThat(other.isTransfer()).isFalse();
        assertThat(other.getTransferPairId()).isNull();
        assertThat(relationshipRepository.findByEitherSideIn(List.of(grocer)))
                .as("the machine's edge is gone, not rejected: the pair stays free to match again").isEmpty();
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
    }

    @Test
    void aTransferTheUserMarked_survivesTheCorrection() throws Exception {
        Imported i = importStatement();
        UUID grocer = row(i, "SAMPLE GROCER").getId();
        jdbcTemplate.update("UPDATE transactions SET amount = 45.00 WHERE id = ?", grocer);
        moveBalance(i, "405.00");
        UUID credit = pairWithCreditElsewhere(i, grocer, "45.00", TransactionRelationship.Status.USER_CONFIRMED);

        refreshService.refresh(i.userId(), i.id(), null);

        assertThat(transactionRepository.findById(grocer).orElseThrow().getTransferPairId()).isEqualTo(credit);
        assertThat(transactionRepository.findById(credit).orElseThrow().getTransferPairId()).isEqualTo(grocer);
        assertThat(relationshipRepository.findByEitherSideIn(List.of(grocer)))
                .extracting(TransactionRelationship::getStatus).containsOnly(TransactionRelationship.Status.USER_CONFIRMED);
    }

    @Test
    void aRemovedRowTheUserHadAnnotated_isFlaggedInTheSummary() throws Exception {
        Imported i = importStatement();
        UUID phantom = insertPhantom(i, "PAGE 1 OF 2", "3.00", 9);
        moveBalance(i, "-3.00");
        jdbcTemplate.update("UPDATE transactions SET notes = 'SAMPLE NOTE' WHERE id = ?", phantom);

        refreshService.refresh(i.userId(), i.id(), null);

        assertThat((List<Map<String, Object>>) lastRun(i).getDetail().get("removed"))
                .extracting(m -> m.get("description"), m -> m.get("userEdited"))
                .containsExactly(tuple("PAGE 1 OF 2", true));
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
    }

    @Test
    void anEmailReceipt_isNeitherRefreshedNorCheckedByTheDryRun() throws Exception {
        Imported i = importStatement();
        // What a Gmail receipt import leaves behind: its row carries the receipt's source.
        jdbcTemplate.update("UPDATE transactions SET source = 'GMAIL_IMPORT' WHERE statement_import_id = ?", i.id());
        jdbcTemplate.update("UPDATE statement_imports SET parser_version = 'oldbuild' WHERE id = ?", i.id());

        assertThatThrownBy(() -> refreshService.refresh(i.userId(), i.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        assertThat(statementImportRepository.findIdsAwaitingRefreshCheck(buildVersionResolver.currentCommit(), 100_000))
                .doesNotContain(i.id());
    }

    // ---- step 5: the banner, "update all" and the summary --------------------------------------

    /** Leaves {@code i} as an older parser would have: one amount misread. */
    private void misreadGrocer(Imported i) {
        jdbcTemplate.update("UPDATE transactions SET amount = 45.00 WHERE id = ?", row(i, "SAMPLE GROCER").getId());
        moveBalance(i, "405.00");
    }

    private int refreshNotifications(Imported i) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'STATEMENT_REFRESH_AVAILABLE'",
                Integer.class, i.userId());
    }

    @Test
    void theBannerOffersAStatementTheCheckFoundChangesFor_andUpdateAllAppliesItAndSaysWhatChanged() throws Exception {
        Imported i = importStatement();
        misreadGrocer(i);
        dryRunService.check(i.id(), buildVersionResolver.currentCommit());

        var overview = userService.overview(i.userId());
        assertThat(overview.enabled()).isTrue();
        assertThat(overview.updatable()).singleElement().satisfies(p -> {
            assertThat(p.statementImportId()).isEqualTo(i.id());
            assertThat(p.status()).isEqualTo("CHANGES");
            assertThat(p.rowsChanged()).isEqualTo(1);
        });
        assertThat(overview.needsPassword()).isEmpty();

        var result = userService.refreshAll(i.userId());

        assertThat(result.remaining()).isZero();
        assertThat(result.results()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("APPLIED");
            assertThat(r.fileName()).isEqualTo("statement.csv");
            assertThat(r.changed()).singleElement().satisfies(c -> {
                assertThat(c.description()).isEqualTo("SAMPLE GROCER");
                assertThat(c.changes()).extracting(f -> f.field(), f -> f.before(), f -> f.after())
                        .contains(tuple("AMOUNT", "45", "450"));
            });
            assertThat(r.balanceChange()).isEqualByComparingTo("-405.00");
        });
        assertThat(balance(i)).isEqualByComparingTo("-950.00");
        assertThat(userService.overview(i.userId()).updatable()).as("refreshed, so no longer offered").isEmpty();

        UUID runId = result.results().get(0).runId();
        assertThat(userService.run(i.userId(), runId).changed()).hasSize(1);
        User other = userRepository.save(otherUser());
        createdUserIds.add(other.getId());
        assertThatThrownBy(() -> userService.run(other.getId(), runId))
                .as("another user's run is not found").isInstanceOf(ApiException.class);
    }

    @Test
    void aStatementWhoseUpdateCouldNotBeApplied_isNotOfferedAgainUntilTheNextCheck() throws Exception {
        Imported i = importStatement();
        misreadGrocer(i);
        dryRunService.check(i.id(), buildVersionResolver.currentCommit());
        // The refresh records FAILED: the stored file no longer reads as a statement.
        jdbcTemplate.update("UPDATE statement_imports SET object_key = NULL, file_content = ? WHERE id = ?",
                "not a statement".getBytes(StandardCharsets.UTF_8), i.id());

        var first = userService.refreshAll(i.userId());

        assertThat(first.results()).singleElement().satisfies(r -> assertThat(r.status()).isEqualTo("FAILED"));
        assertThat(first.remaining()).as("otherwise the client would call again forever").isZero();
        assertThat(userService.overview(i.userId()).updatable()).isEmpty();
        assertThat(userService.refreshAll(i.userId()).results()).as("and a second tap tries nothing").isEmpty();
    }

    @Test
    void whileRefreshingIsSwitchedOff_theBannerIsEmptyAndUpdateAllIsNotFound() throws Exception {
        Imported i = importStatement();
        misreadGrocer(i);
        dryRunService.check(i.id(), buildVersionResolver.currentCommit());
        ReflectionTestUtils.setField(refreshService, "enabled", false);

        var overview = userService.overview(i.userId());

        assertThat(overview.enabled()).isFalse();
        assertThat(overview.updatable()).isEmpty();
        assertThatThrownBy(() -> userService.refreshAll(i.userId())).isInstanceOf(ApiException.class);
    }

    @Test
    void theUserIsToldOnce_whenTheCheckFindsChanges_andNotAgainWithinAWeek() throws Exception {
        ReflectionTestUtils.setField(notifier, "enabled", true);
        try {
            Imported i = importStatement();
            misreadGrocer(i);

            dryRunService.check(i.id(), buildVersionResolver.currentCommit());
            assertThat(refreshNotifications(i)).as("push and email").isEqualTo(2);

            dryRunService.check(i.id(), "a-later-build");
            assertThat(refreshNotifications(i)).as("a later build inside the quiet week tells nobody again").isEqualTo(2);
        } finally {
            ReflectionTestUtils.setField(notifier, "enabled", false);
        }
    }

    @Test
    void nobodyIsToldWhenTheCheckFindsNothing_orWhileRefreshingIsOff() throws Exception {
        Imported i = importStatement();
        misreadGrocer(i);
        dryRunService.check(i.id(), buildVersionResolver.currentCommit());
        assertThat(refreshNotifications(i)).as("switched off").isZero();

        ReflectionTestUtils.setField(notifier, "enabled", true);
        try {
            Imported clean = importStatement();
            dryRunService.check(clean.id(), buildVersionResolver.currentCommit());
            assertThat(refreshNotifications(clean)).as("nothing would change").isZero();
        } finally {
            ReflectionTestUtils.setField(notifier, "enabled", false);
        }
    }

    private static User otherUser() {
        User user = new User();
        user.setEmail("refresh-it-other-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Refresh IT Other");
        user.setPhoneVerified(true);
        return user;
    }
}
