package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V228, the one-off correction of Account.balance for duplicate marks written before the rule in
 * AccountBalanceConvention.netEffectIsInBalance reached every site.
 *
 * <p>Flyway has already applied V228 to the empty test database by the time this class runs, so
 * the migration's own file is executed here a second time, against fixtures built through the real
 * services -- which is the only way to get rows, marks and DUPLICATE edges with the timestamps
 * production carries. The runtime now reverses at mark time, so the "still in the balance" state
 * V228 exists to correct is re-created by hand: the reversed amount is added back onto the account,
 * exactly as it sat there before 2026-09-25.
 *
 * <p>One row per bucket, in one or two accounts, and the assertion is the balance the runtime
 * would have produced had the rule always existed -- plus the audit row that reports every bucket,
 * and the record V228 writes on each row of what its mark did (the runtime writes it at mark time
 * now; before the rule nothing did, so the columns are reset to their defaults first).
 */
class DuplicateMarkBalanceBackfillIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionService transactionService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    /** Same cleanup, same reason, as ImportAccountBalanceIT's own @AfterEach. */
    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("dup-backfill-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Duplicate Backfill IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Account account(User user, String name) {
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName(name);
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("1000.00"));
        return accountRepository.save(account);
    }

    private ConfirmedRow row(String description) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal("45.00"), "EXPENSE",
                "Other", true, "rule", null, false, null, null, false);
    }

    private void importRows(User user, Account account, String fileName, ConfirmedRow... rows) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/csv",
                fileName.getBytes(StandardCharsets.UTF_8));
        importService.confirm(user.getId(), file,
                new ConfirmRequest(null, List.of(rows), account.getId(), null, null, null, null));
    }

    private void manualFare(User user, Account account, String description) {
        transactionService.create(user.getId(), new TransactionDto.CreateRequest(account.getId(), "Transport",
                LocalDate.of(2026, 7, 10), description, new BigDecimal("45.00"), "EXPENSE", null, null));
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private List<Transaction> marked(User user, String description) {
        return transactionRepository.findByUserId(user.getId()).stream()
                .filter(t -> description.equals(t.getDescription()) && t.getIsDuplicateOf() != null)
                .toList();
    }

    /** Re-creates the pre-2026-09-25 state: the runtime took this row's 45.00 off when it marked
     *  it; before the rule existed nothing did, so the amount sat in the balance. */
    private void undoRuntimeReversal(Account account) {
        Account a = accountRepository.findById(account.getId()).orElseThrow();
        a.setBalance(a.getBalance().subtract(new BigDecimal("45.00")));
        accountRepository.save(a);
    }

    private void runV228() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V228__duplicate_mark_balance_backfill.sql")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditFor(Account account) {
        String json = jdbc.queryForObject(
                "SELECT metadata::text FROM audit_logs WHERE action = 'DUPLICATE_MARK_BALANCE_BACKFILL' AND entity_id = ?",
                String.class, account.getId());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reversesOnlyTheMarksStillInTheBalance_andReportsEveryBucket() throws Exception {
        User user = user();
        Account account = account(user, "Backfill IT main");

        // ANCHORED, on a second account: a marked manual fare that predates a live absolute SET.
        // Built first so every statement import below is imported after these rows were created.
        Account anchored = account(user, "Backfill IT anchored");
        manualFare(user, anchored, "OLD FARE");
        manualFare(user, anchored, "OLD FARE");
        assertThat(marked(user, "OLD FARE")).hasSize(1);
        undoRuntimeReversal(anchored);
        BigDecimal anchoredBefore = balanceOf(anchored);
        // REVERSE (manual): two manual fares, the second marked. Never reversed before the rule.
        manualFare(user, account, "MANUAL FARE");
        manualFare(user, account, "MANUAL FARE");
        assertThat(marked(user, "MANUAL FARE")).hasSize(1);

        // REVERSE (retroactive import mark): a second statement's row under a narration the
        // engine could not match at confirm; the user then corrects it, and the later run marks it.
        importRows(user, account, "a.csv", row("METRO FARE"));
        importRows(user, account, "b.csv", row("MTR FARE TYPO"));
        Transaction typo = transactionRepository.findByUserId(user.getId()).stream()
                .filter(t -> "MTR FARE TYPO".equals(t.getDescription())).findFirst().orElseThrow();
        transactionService.update(user.getId(), typo.getId(),
                new TransactionDto.UpdateRequest(null, "METRO FARE", null, null, null, null, null, null));
        assertThat(marked(user, "METRO FARE")).hasSize(1);
        // The edge was written milliseconds after the row here; in production a later run is
        // hours or days later. Age the edge so the classifier sees what it would see there.
        jdbc.update("UPDATE transaction_relationships SET created_at = created_at + INTERVAL '2 hours' "
                + "WHERE from_transaction_id = ? AND relationship_type = 'DUPLICATE'", typo.getId());

        // ASSUMED_REVERSED: the same file twice -- the second copy is marked by its own confirm,
        // edge written in the same transaction as the row.
        importRows(user, account, "c.csv", row("COFFEE"));
        importRows(user, account, "c-again.csv", row("COFFEE"));
        assertThat(marked(user, "COFFEE")).hasSize(1);

        // UNCLASSIFIED: a mark whose only edge carries no explanation, the shape V114 backfilled.
        importRows(user, account, "d.csv", row("LUNCH"));
        importRows(user, account, "d-again.csv", row("LUNCH"));
        Transaction lunchCopy = marked(user, "LUNCH").get(0);
        jdbc.update("UPDATE transaction_relationships SET explanation = NULL WHERE from_transaction_id = ?",
                lunchCopy.getId());

        // NEVER_IN_BALANCE: an aggregator row marked against the coffee -- nothing on that path
        // ever wrote the balance.
        Transaction aggregator = new Transaction();
        aggregator.setUserId(user.getId());
        aggregator.setAccountId(account.getId());
        aggregator.setTxnDate(LocalDate.of(2026, 7, 10));
        aggregator.setAmount(new BigDecimal("45.00"));
        aggregator.setTxnType(Transaction.Type.EXPENSE);
        aggregator.setDescription("COFFEE");
        aggregator.setSource(Transaction.Source.ACCOUNT_AGGREGATOR);
        aggregator.setIsDuplicateOf(lunchCopy.getIsDuplicateOf());
        aggregator.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        Transaction aggregatorRow = transactionRepository.save(aggregator);

        // What the balance is with the rule applied everywhere: the runtime reversed both REVERSE
        // rows at their marks, and the two confirm-time marks at theirs.
        BigDecimal correct = balanceOf(account);

        // The pre-rule state V228 meets in production: the two REVERSE rows still in the balance.
        undoRuntimeReversal(account);
        undoRuntimeReversal(account);
        assertThat(balanceOf(account)).isEqualByComparingTo(correct.subtract(new BigDecimal("90.00")));

        // Any live statement import imported after the fares serves as the anchor.
        UUID anchorImportId = statementImportRepository.findAll().stream()
                .filter(si -> user.getId().equals(si.getUserId()))
                .max(java.util.Comparator.comparing(si -> si.getImportedAt()))
                .orElseThrow().getId();
        Account a2 = accountRepository.findById(anchored.getId()).orElseThrow();
        a2.setLastAbsoluteSetStatementId(anchorImportId);
        accountRepository.save(a2);

        // Before 2026-09-25 no site recorded what a mark did.
        jdbc.update("UPDATE transactions SET duplicate_balance_reversed = false, duplicate_balance_anchor_id = NULL "
                + "WHERE user_id = ?", user.getId());

        runV228();

        assertThat(balanceOf(account)).isEqualByComparingTo(correct);
        assertThat(balanceOf(anchored)).isEqualByComparingTo(anchoredBefore);

        // The record, per bucket: REVERSE and ASSUMED_REVERSED were taken off (now, or at their
        // confirm); UNCLASSIFIED is recorded as reversed by the stated assumption; the aggregator
        // row never was; the anchored manual row is held by the SET that stood in the way.
        assertThat(marked(user, "MANUAL FARE").get(0).isDuplicateBalanceReversed()).isTrue();
        assertThat(marked(user, "METRO FARE").get(0).isDuplicateBalanceReversed()).isTrue();
        Transaction coffeeCopy = marked(user, "COFFEE").stream()
                .filter(t -> t.getSource() != Transaction.Source.ACCOUNT_AGGREGATOR).findFirst().orElseThrow();
        assertThat(coffeeCopy.isDuplicateBalanceReversed()).isTrue();
        assertThat(transactionRepository.findById(lunchCopy.getId()).orElseThrow().isDuplicateBalanceReversed()).isTrue();
        Transaction aggregatorAfter = transactionRepository.findById(aggregatorRow.getId()).orElseThrow();
        assertThat(aggregatorAfter.isDuplicateBalanceReversed()).isFalse();
        assertThat(aggregatorAfter.getDuplicateBalanceAnchorId()).isNull();
        Transaction oldFareCopy = marked(user, "OLD FARE").get(0);
        assertThat(oldFareCopy.isDuplicateBalanceReversed()).isFalse();
        assertThat(oldFareCopy.getDuplicateBalanceAnchorId()).isEqualTo(anchorImportId);

        Map<String, Object> main = auditFor(account);
        assertThat(main.get("migration")).isEqualTo("V228");
        assertThat(((Number) main.get("reversedRows")).intValue()).isEqualTo(2);
        assertThat(new BigDecimal(main.get("balanceMovedBy").toString())).isEqualByComparingTo("90.00");
        assertThat(((Number) main.get("assumedReversedRows")).intValue()).isEqualTo(1);
        assertThat(((Number) main.get("unclassifiedRows")).intValue()).isEqualTo(1);
        assertThat(((Number) main.get("anchoredRows")).intValue()).isEqualTo(0);
        assertThat(((Number) main.get("anchoredHeldRows")).intValue()).isEqualTo(0);
        assertThat(((Number) main.get("neverInBalanceRows")).intValue()).isEqualTo(1);

        Map<String, Object> second = auditFor(anchored);
        assertThat(((Number) second.get("reversedRows")).intValue()).isEqualTo(0);
        assertThat(((Number) second.get("anchoredRows")).intValue()).isEqualTo(1);
        assertThat(((Number) second.get("anchoredHeldRows")).intValue()).isEqualTo(1);
        assertThat(new BigDecimal(second.get("anchoredNet").toString())).isEqualByComparingTo("45.00");
    }
}
