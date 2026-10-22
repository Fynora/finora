package com.finora.imports.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.UnparseableRowSummary;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * V282, which removes the statement cells that unparseable summaries written before PR #2299 carry
 * in their reason keys. Flyway has already applied it to the empty test database, so the file is run
 * here a second time over rows shaped the way the old release wrote them. Every cell is synthetic.
 */
class V282RedactUnparseableSummaryCellsIT extends AbstractIntegrationTest {

    private static final String DATE_MARKER = "Date value \"(redacted)\" didn't match any known date format";
    private static final String AMOUNT_MARKER = "Amount value \"(redacted)\" didn't match any known numeric format";
    private static final String NO_DATE = "No column recognized as a date";

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Account account() {
        User user = new User();
        user.setEmail("v282-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V282 IT User");
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private UUID statementImport(Account account, String summaryJson) {
        StatementImport s = new StatementImport();
        s.setUserId(account.getUserId());
        s.setAccountId(account.getId());
        s.setFileName("statement.csv");
        s.setSourceFormat("CSV");
        s.setFileContent(new byte[]{1});
        s.setContentHash("v282-it-" + UUID.randomUUID());
        s.setUnparseableSummaryJson(summaryJson);
        return statementImportRepository.saveAndFlush(s).getId();
    }

    private UUID importSession(Account account, String summaryJson) {
        ImportSession session = new ImportSession();
        session.setUserId(account.getUserId());
        session.setFileName("statement.csv");
        session.setFileContent("Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));
        session.setStagedRowsJson("[]");
        session.setExpiresAt(Instant.now().plus(2, ChronoUnit.DAYS));
        session.setUnparseableSummaryJson(summaryJson);
        return importSessionRepository.saveAndFlush(session).getId();
    }

    private String statementSummary(UUID id) {
        return jdbc.queryForObject("SELECT unparseable_summary_json FROM statement_imports WHERE id = ?", String.class, id);
    }

    private String sessionSummary(UUID id) {
        return jdbc.queryForObject("SELECT unparseable_summary_json FROM import_sessions WHERE id = ?", String.class, id);
    }

    private void runV282() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V282__redact_cells_in_unparseable_summaries.sql")));
        }
    }

    /** As the old release wrote it: Jackson output of the record, reasons keyed by the review-screen text. */
    private String legacy(String... reasonCountPairs) throws Exception {
        var reasons = new java.util.LinkedHashMap<String, Integer>();
        int total = 0;
        for (int i = 0; i < reasonCountPairs.length; i += 2) {
            int n = Integer.parseInt(reasonCountPairs[i + 1]);
            reasons.put(reasonCountPairs[i], n);
            total += n;
        }
        return objectMapper.writeValueAsString(new UnparseableRowSummary(reasons,
                java.util.Map.of("Date|Description|Amount", total), total));
    }

    private static String dateReason(String cell) {
        return "Date value \"" + cell + "\" didn't match any known date format";
    }

    private static String amountReason(String cell) {
        return "Amount value \"" + cell + "\" didn't match any known numeric format";
    }

    @Test
    void cellsInOldKeysAreReplacedByAMarkerAndTheirCountsMerged() throws Exception {
        Account account = account();
        UUID id = statementImport(account, legacy(
                dateReason("SAMPLE NOTICE TEXT"), "1",
                dateReason("31-Foo-2026 \"quoted\"\nsecond line"), "2",
                amountReason("1,2O0.00"), "1",
                NO_DATE, "3"));

        runV282();

        String after = statementSummary(id);
        assertThat(after).doesNotContain("SAMPLE").doesNotContain("Foo").doesNotContain("quoted")
                .doesNotContain("second line").doesNotContain("1,2O0.00");
        UnparseableRowSummary summary = objectMapper.readValue(after, UnparseableRowSummary.class);
        assertThat(summary.reasons()).containsOnly(entry(DATE_MARKER, 3), entry(AMOUNT_MARKER, 1), entry(NO_DATE, 3));
        assertThat(summary.columnSignatures()).containsOnly(entry("Date|Description|Amount", 7));
        assertThat(summary.total()).isEqualTo(7);
        assertThat(summary.reasons().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(summary.total());
    }

    @Test
    void stagedSessionsAreRedactedToo() throws Exception {
        UUID id = importSession(account(), legacy(dateReason("SAMPLE NOTICE TEXT"), "2"));

        runV282();

        assertThat(sessionSummary(id)).doesNotContain("SAMPLE");
        assertThat(objectMapper.readValue(sessionSummary(id), UnparseableRowSummary.class).reasons())
                .containsOnly(entry(DATE_MARKER, 2));
    }

    @Test
    void keysThatAreAlreadyShapesAndRowsWithoutCellsAreLeftExactlyAsTheyWere() throws Exception {
        Account account = account();
        // Written after #2299: the quoted part is a shape, which carries no value.
        String shaped = legacy(dateReason("99-XXX-9999 99:99"), "2", amountReason("9,999/-"), "1", NO_DATE, "1");
        String fixedOnly = legacy(NO_DATE, "4");
        UUID shapedId = statementImport(account, shaped);
        UUID fixedId = statementImport(account, fixedOnly);
        UUID noneId = statementImport(account, null);

        runV282();

        assertThat(statementSummary(shapedId)).isEqualTo(shaped);
        assertThat(statementSummary(fixedId)).isEqualTo(fixedOnly);
        assertThat(statementSummary(noneId)).isNull();
    }

    @Test
    void aMalformedSummaryIsSkippedRatherThanFailingTheMigration() throws Exception {
        Account account = account();
        String notJson = "{\"reasons\":{\"Date value \\\"SAMPLE";
        String textCount = "{\"reasons\":{\"" + dateReason("SAMPLE").replace("\"", "\\\"") + "\":\"two\"},"
                + "\"columnSignatures\":{},\"total\":2}";
        String reasonsNotAnObject = "{\"reasons\":[\"Date value \\\"SAMPLE\\\"\"],\"columnSignatures\":{},\"total\":1}";
        UUID notJsonId = statementImport(account, notJson);
        UUID textCountId = statementImport(account, textCount);
        UUID arrayId = statementImport(account, reasonsNotAnObject);

        runV282();

        assertThat(statementSummary(notJsonId)).isEqualTo(notJson);
        assertThat(statementSummary(textCountId)).isEqualTo(textCount);
        assertThat(statementSummary(arrayId)).isEqualTo(reasonsNotAnObject);
    }

    @Test
    void aSecondRunChangesNothing() throws Exception {
        UUID id = statementImport(account(), legacy(dateReason("SAMPLE NOTICE TEXT"), "1", NO_DATE, "1"));

        runV282();
        String once = statementSummary(id);
        runV282();

        assertThat(statementSummary(id)).isEqualTo(once);
    }
}
