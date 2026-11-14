package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AiAuditLog;
import com.finora.entity.Category;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.UserMerchantCategoryResolutionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.CategorizationService;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V319: saved payee categories learn who chose them, and transactions already filed from the
 * user's own choice lose the AI label -- only where the audit log proves no model answered. Flyway
 * has already applied the file to the empty test database, so it runs here again over rows shaped
 * the way the database held them before it (every saved row marked the model's, every transaction
 * from one labelled AI_FALLBACK at 30). Every value is synthetic.
 */
class V319ChosenByUserBackfillIT extends AbstractIntegrationTest {

    private static final String KEY = "vpa:samplev319backfill";

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionService transactionService;
    @Autowired private CategorizationService categorizationService;
    @Autowired private UserMerchantCategoryResolutionRepository resolutionRepository;
    @Autowired private AiAuditLogRepository aiAuditLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private final List<UUID> auditRowsToRemove = new ArrayList<>();

    @AfterEach
    void removeGlobalAuditRows() {
        // A SUGGEST_CATEGORY row turns the transaction relabel off for everyone: never leave one.
        auditRowsToRemove.forEach(aiAuditLogRepository::deleteById);
        auditRowsToRemove.clear();
    }

    private record Owner(UUID userId, UUID accountId) {}

    private Owner owner() {
        User user = new User();
        user.setEmail("v319-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V319 IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return new Owner(user.getId(), accountRepository.save(account).getId());
    }

    private void auditCall(UUID userId, String toolName) {
        AiAuditLog row = new AiAuditLog();
        row.setUserId(userId);
        row.setModel("synthetic-model");
        row.setPromptVersion("v319-it");
        row.setToolName(toolName);
        row.setTokensIn(1);
        row.setTokensOut(1);
        row.setCost(BigDecimal.ZERO);
        row.setLatencyMs(1);
        auditRowsToRemove.add(aiAuditLogRepository.save(row).getId());
    }

    /** A saved row as the database held it before V319: no writer recorded, so the column
     *  default. Called after the transaction is made, whose own category choice pins the row. */
    private void savedRowBeforeV319(Owner o) {
        Category pets = categorizationService.resolveOrCreateCategory(o.userId(), "Pets");
        resolutionRepository.insertIfAbsent(o.userId(), KEY, "EXPENSE", pets.getId(), Instant.now());
        jdbc.update("UPDATE user_merchant_category_resolution SET chosen_by_user = FALSE WHERE user_id = ?", o.userId());
    }

    /** A transaction filed from that row before V319: AI_FALLBACK at 30, flagged for review. */
    private UUID aiLabelledTransaction(Owner o) {
        TransactionDto created = transactionService.create(o.userId(), new TransactionDto.CreateRequest(
                o.accountId(), "Pets", LocalDate.of(2026, 7, 1), "UPI/DR/555555555555/SAMPLE/HDFC/samplev319backfill@okhdfc/UPI",
                new BigDecimal("250.00"), "EXPENSE", null, null));
        jdbc.update("""
                UPDATE transactions SET decision_source = 'AI_FALLBACK', decision_confidence = 30,
                       needs_category_review = TRUE, category_manually_set = FALSE WHERE id = ?
                """, created.id());
        return created.id();
    }

    private UUID letGoKeepingAiLabel(Owner o, UUID txnId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO loan_entries (id, user_id, person_key, txn_id, direction, amount,
                                          before_decision_source, before_decision_confidence)
                VALUES (?, ?, 'name:sample', ?, 'LET_GO', 250.00, 'AI_FALLBACK', 30)
                """, id, o.userId(), txnId);
        return id;
    }

    private boolean chosenByUser(Owner o) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT chosen_by_user FROM user_merchant_category_resolution
                 WHERE user_id = ? AND counterparty_key = ? AND direction = 'EXPENSE'
                """, Boolean.class, o.userId(), KEY));
    }

    private Map<String, Object> decision(UUID txnId) {
        return jdbc.queryForMap("""
                SELECT decision_source, decision_confidence, needs_category_review
                  FROM transactions WHERE id = ?
                """, txnId);
    }

    private Map<String, Object> keptDecision(UUID loanEntryId) {
        return jdbc.queryForMap("""
                SELECT before_decision_source, before_decision_confidence FROM loan_entries WHERE id = ?
                """, loanEntryId);
    }

    private void runV319() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(
                    "db/migration/V319__resolution_chosen_by_user.sql")));
        }
    }

    /** No model call on the account: every saved row is the user's, and so is every AI label. */
    @Test
    void aUserTheModelNeverAnswered_getsTheirChoicesBack() throws Exception {
        Owner o = owner();
        UUID txn = aiLabelledTransaction(o);
        savedRowBeforeV319(o);
        UUID letGo = letGoKeepingAiLabel(o, txn);
        // Another tool's calls are not a category answer.
        auditCall(o.userId(), "INSIGHTS_NARRATION");
        assertThat(chosenByUser(o)).as("precondition").isFalse();

        runV319();

        assertThat(chosenByUser(o)).isTrue();
        Map<String, Object> d = decision(txn);
        assertThat(d.get("decision_source")).isEqualTo("LEARNED_PATTERN");
        assertThat(d.get("decision_confidence")).isEqualTo(100);
        // Left for the user to look at, not silently taken off their list.
        assertThat(d.get("needs_category_review")).isEqualTo(true);
        Map<String, Object> kept = keptDecision(letGo);
        assertThat(kept.get("before_decision_source")).isEqualTo("LEARNED_PATTERN");
        assertThat(kept.get("before_decision_confidence")).isEqualTo(100);
    }

    /** One RESOLVE_CATEGORY call and the user's rows cannot be told apart: all keep the AI label. */
    @Test
    void aUserTheModelAnswered_keepsTheAiLabel() throws Exception {
        Owner o = owner();
        UUID txn = aiLabelledTransaction(o);
        savedRowBeforeV319(o);
        UUID letGo = letGoKeepingAiLabel(o, txn);
        auditCall(o.userId(), "RESOLVE_CATEGORY");

        runV319();

        assertThat(chosenByUser(o)).isFalse();
        assertThat(decision(txn).get("decision_source")).isEqualTo("AI_FALLBACK");
        assertThat(decision(txn).get("decision_confidence")).isEqualTo(30);
        assertThat(keptDecision(letGo).get("before_decision_source")).isEqualTo("AI_FALLBACK");
    }

    /**
     * The fallback before V214 shared one model answer per payee across users, so where it ever ran
     * an AI label cannot be shown to be the user's own -- even for a user who made no call. Their
     * saved rows still become theirs: that table only ever held per-user answers.
     */
    @Test
    void whereThePreV214SharedFallbackRan_noTransactionIsRelabelled() throws Exception {
        Owner o = owner();
        UUID txn = aiLabelledTransaction(o);
        savedRowBeforeV319(o);
        UUID letGo = letGoKeepingAiLabel(o, txn);
        auditCall(owner().userId(), "SUGGEST_CATEGORY");

        runV319();

        assertThat(chosenByUser(o)).isTrue();
        assertThat(decision(txn).get("decision_source")).isEqualTo("AI_FALLBACK");
        assertThat(keptDecision(letGo).get("before_decision_source")).isEqualTo("AI_FALLBACK");
    }

    /** Other labels and a category set by hand are never touched. */
    @Test
    void otherLabels_areUntouched() throws Exception {
        Owner o = owner();
        UUID txn = aiLabelledTransaction(o);
        jdbc.update("UPDATE transactions SET decision_source = 'KEYWORD_MATCH', decision_confidence = 70 WHERE id = ?", txn);

        runV319();

        assertThat(decision(txn).get("decision_source")).isEqualTo("KEYWORD_MATCH");
        assertThat(decision(txn).get("decision_confidence")).isEqualTo(70);
    }

    @Test
    void runningItTwice_changesNothingMore() throws Exception {
        Owner o = owner();
        UUID txn = aiLabelledTransaction(o);
        savedRowBeforeV319(o);
        runV319();
        Map<String, Object> once = decision(txn);

        runV319();

        assertThat(decision(txn)).isEqualTo(once);
        assertThat(chosenByUser(o)).isTrue();
    }
}
