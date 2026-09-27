package com.finora.imports.refresh;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshPreview;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.config.BuildVersionResolver;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementRefreshPreviewRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refresh dry run end to end against real Postgres: a real CSV statement is staged and
 * confirmed, then re-read from its stored file. With the parser unchanged it must report nothing;
 * each simulated "older parser" difference must show up exactly once; rows the user deleted or left
 * out must never come back as added; and nothing it does may touch the transactions themselves.
 */
class StatementRefreshDryRunIT extends AbstractIntegrationTest {

    @Autowired private StatementRefreshDryRunService dryRun;
    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private TransactionService transactionService;
    @Autowired private com.finora.service.StatementImportService statementImportService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementRefreshPreviewRepository previewRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private BuildVersionResolver buildVersionResolver;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String NEW_BUILD = "newbuild";

    private static final byte[] FILE = ("Date,Description,Amount,Type\n"
            + "2026-07-01,SAMPLE GROCER,450.00,DEBIT\n"
            + "2026-07-02,SAMPLE CAFE,120.00,DEBIT\n"
            + "2026-07-03,SAMPLE BOOKSHOP,300.00,DEBIT\n"
            + "2026-07-04,SAMPLE PHARMACY,80.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId())).toList());
        createdUserIds.clear();
    }

    private record Imported(User user, StatementImport statement) {}

    /** Imports FILE the way the web client does, leaving out the rows whose description is given. */
    private Imported importStatement(String... leftOut) throws Exception {
        User user = new User();
        user.setEmail("dry-run-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Dry Run IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        createdUserIds.add(user.getId());
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        StagingResponse staging = importService.parseAndStage(user.getId(), "statement.csv", new ByteArrayInputStream(FILE));
        assertThat(staging.rows()).hasSize(4);
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv", FILE,
                staging.rows(), staging.detectedAccount());
        List<String> excluded = List.of(leftOut);
        List<ConfirmedRow> rows = new ArrayList<>();
        for (StagedRow r : staging.rows()) {
            rows.add(new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(),
                    !excluded.contains(r.description()), r.categorySource(), r.ruleId(), r.likelyDuplicate(),
                    r.referenceNumber(), r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition()));
        }
        importService.confirmSession(user.getId(), new ConfirmRequest(session.getId(), rows, account.getId(),
                null, null, null, null));
        StatementImport statement = statementImportRepository.findAll().stream()
                .filter(s -> s.getUserId().equals(createdUserIds.get(createdUserIds.size() - 1)))
                .findFirst().orElseThrow();
        return new Imported(user, statement);
    }

    private Transaction row(Imported i, String description) {
        return transactionRepository.findByStatementImportId(i.statement().getId()).stream()
                .filter(t -> description.equals(t.getDescription())).findFirst().orElseThrow();
    }

    private StatementRefreshPreview preview(Imported i, String version) {
        return previewRepository.findByStatementImportIdAndParserVersion(i.statement().getId(), version).orElseThrow();
    }

    @Test
    void theSameParserReadingTheSameFile_reportsNoChanges_andTouchesNoTransaction() throws Exception {
        Imported i = importStatement();
        List<Transaction> before = transactionRepository.findByStatementImportId(i.statement().getId());

        dryRun.check(i.statement().getId(), NEW_BUILD);

        StatementRefreshPreview p = preview(i, NEW_BUILD);
        assertThat(p.getStatus()).isEqualTo(StatementRefreshPreview.Status.NO_CHANGES);
        assertThat(p.getRowsUnchanged()).isEqualTo(4);
        assertThat(p.getFactsChanged()).isZero();
        assertThat(p.getDetail()).isNull();
        List<Transaction> after = transactionRepository.findByStatementImportId(i.statement().getId());
        assertThat(after).extracting(Transaction::getId, Transaction::getDescription, Transaction::getAmount)
                .containsExactlyInAnyOrderElementsOf(before.stream()
                        .map(t -> org.assertj.core.groups.Tuple.tuple(t.getId(), t.getDescription(), t.getAmount()))
                        .toList());
    }

    @Test
    void aDescriptionAnOlderParserReadDifferently_isExactlyOneChangedRow() throws Exception {
        Imported i = importStatement();
        Transaction cafe = row(i, "SAMPLE CAFE");
        jdbcTemplate.update("UPDATE transactions SET description = 'SAMPLE CAF' WHERE id = ?", cafe.getId());

        dryRun.check(i.statement().getId(), NEW_BUILD);

        StatementRefreshPreview p = preview(i, NEW_BUILD);
        assertThat(p.getStatus()).isEqualTo(StatementRefreshPreview.Status.CHANGES);
        assertThat(p.getRowsChanged()).isEqualTo(1);
        assertThat(p.getRowsAdded()).isZero();
        assertThat(p.getRowsRemoved()).isZero();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changed = (List<Map<String, Object>>) p.getDetail().get("changed");
        assertThat(changed).singleElement().satisfies(c -> assertThat(c.get("transactionId")).isEqualTo(cafe.getId().toString()));
        // The dry run never writes to the transaction itself.
        assertThat(row(i, "SAMPLE CAF").getId()).isEqualTo(cafe.getId());

        // Its detail quotes the statement, so it goes with the statement.
        statementImportService.delete(i.user().getId(), i.statement().getId(), i.user().getId());
        assertThat(previewRepository.findByStatementImportIdAndParserVersion(i.statement().getId(), NEW_BUILD)).isEmpty();
    }

    @Test
    void aRowTheUserDeleted_andARowTheyLeftOut_doNotComeBackAsAdded() throws Exception {
        Imported i = importStatement("SAMPLE BOOKSHOP");
        transactionService.delete(i.user().getId(), row(i, "SAMPLE PHARMACY").getId(), i.user().getId());

        dryRun.check(i.statement().getId(), NEW_BUILD);

        StatementRefreshPreview p = preview(i, NEW_BUILD);
        assertThat(p.getStatus()).isEqualTo(StatementRefreshPreview.Status.NO_CHANGES);
        assertThat(p.getRowsAdded()).isZero();
        assertThat(p.getRowsUnchanged()).isEqualTo(2);
    }

    @Test
    void aStoredFileThatNowParsesToNoRows_isFailed_neverAnOfferToDeleteEveryTransaction() throws Exception {
        Imported i = importStatement();
        jdbcTemplate.update("UPDATE statement_imports SET file_content = ?, object_key = NULL WHERE id = ?",
                "not,a,statement\n".getBytes(StandardCharsets.UTF_8), i.statement().getId());

        dryRun.check(i.statement().getId(), NEW_BUILD);

        StatementRefreshPreview p = preview(i, NEW_BUILD);
        assertThat(p.getStatus()).isEqualTo(StatementRefreshPreview.Status.FAILED);
        assertThat(p.getDetail()).containsExactly(Map.entry("reason", "PARSED_NO_ROWS"));
        assertThat(p.getRowsRemoved()).isZero();
    }

    @Test
    void aReReadThatWouldRemoveMostOfTheStatement_isHeldForReview_notOffered() throws Exception {
        Imported i = importStatement();
        // Three extra rows the file does not contain -- as if an older parser had invented them,
        // or, the other way round, a newer one stopped seeing most of the statement.
        for (int n = 0; n < 3; n++) {
            jdbcTemplate.update("""
                    INSERT INTO transactions (id, user_id, account_id, statement_import_id, txn_date, description,
                                              amount, txn_type, created_at, updated_at, version, reconciliation_status)
                    SELECT gen_random_uuid(), user_id, account_id, id, DATE '2026-07-10', 'SAMPLE PHANTOM ' || ?,
                           10.00, 'EXPENSE', now(), now(), 0, 'OK' FROM statement_imports WHERE id = ?
                    """, n, i.statement().getId());
        }

        dryRun.check(i.statement().getId(), NEW_BUILD);

        StatementRefreshPreview p = preview(i, NEW_BUILD);
        assertThat(p.getStatus()).isEqualTo(StatementRefreshPreview.Status.NEEDS_REVIEW);
        assertThat(p.getRowsRemoved()).isEqualTo(3);
    }

    @Test
    void aSupersededStatement_isNotPickedUp_andAPreviewOfADeletedOneIsCleanedUp() throws Exception {
        String current = buildVersionResolver.currentCommit();
        Imported replaced = importStatement();
        Imported deleted = importStatement();
        jdbcTemplate.update("UPDATE statement_imports SET parser_version = 'oldbuild' WHERE id IN (?, ?)",
                replaced.statement().getId(), deleted.statement().getId());
        // A preview for the statement about to be deleted, as if written in the moment around it.
        dryRun.check(deleted.statement().getId(), current);
        assertThat(previewRepository.findByStatementImportIdAndParserVersion(deleted.statement().getId(), current)).isPresent();
        jdbcTemplate.update("UPDATE statement_imports SET superseded_by = ? WHERE id = ?",
                deleted.statement().getId(), replaced.statement().getId());
        jdbcTemplate.update("UPDATE statement_imports SET deleted_at = now() WHERE id = ?", deleted.statement().getId());

        dryRun.runBatch(10_000);

        assertThat(previewRepository.findByStatementImportIdAndParserVersion(replaced.statement().getId(), current))
                .as("a replaced statement's rows no longer count: it is never offered a refresh").isEmpty();
        assertThat(previewRepository.findByStatementImportIdAndParserVersion(deleted.statement().getId(), current))
                .as("a deleted statement's preview is removed on the next batch").isEmpty();
    }

    @Test
    void theBatch_checksOnlyStatementsAnOlderBuildParsed_andKeepsOnlyTheLatestPreview() throws Exception {
        String current = buildVersionResolver.currentCommit();
        assertThat(current).as("the test build must carry a commit id for this test to mean anything").isNotBlank();
        Imported older = importStatement();
        Imported current_ = importStatement();
        jdbcTemplate.update("UPDATE statement_imports SET parser_version = 'oldbuild' WHERE id = ?", older.statement().getId());
        dryRun.check(older.statement().getId(), "evenolderbuild");

        dryRun.runBatch(10_000);

        assertThat(previewRepository.findByStatementImportIdAndParserVersion(older.statement().getId(), current)).isPresent();
        assertThat(previewRepository.findByStatementImportIdAndParserVersion(current_.statement().getId(), current))
                .as("parsed by the running build already: nothing to check").isEmpty();
        assertThat(previewRepository.findByStatementImportIdAndParserVersion(older.statement().getId(), "evenolderbuild"))
                .as("a statement keeps only its latest preview").isEmpty();

        var totals = previewRepository.totalsFor(current);
        assertThat(totals).anySatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo("NO_CHANGES");
            assertThat(t.getStatements()).isGreaterThanOrEqualTo(1);
        });
        assertThat(statementImportRepository.countAwaitingRefreshCheck(current)).isZero();
    }
}
