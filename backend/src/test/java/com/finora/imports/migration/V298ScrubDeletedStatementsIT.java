package com.finora.imports.migration;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.storage.ContentAddress;
import com.finora.repository.AccountRepository;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V298, which clears the file name, detected holder name, inline file bytes and layout text from
 * statements deleted before deleting did that itself. Flyway has already applied it to the empty
 * test database, so the file runs here again over rows shaped the way the old release left them:
 * soft-deleted with everything still on them. Every value is synthetic.
 */
class V298ScrubDeletedStatementsIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private Account account() {
        User user = new User();
        user.setEmail("v298-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("V298 IT User");
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private UUID statement(Account account, byte[] inlineBytes, String contentHash) {
        StatementImport s = new StatementImport();
        s.setUserId(account.getUserId());
        s.setAccountId(account.getId());
        s.setFileName("synthetic-holder-statement-july.csv");
        s.setSourceFormat("CSV");
        s.setFileContent(inlineBytes);
        s.setContentHash(contentHash);
        s.setExtractedHolderName("SYNTHETIC HOLDER NAME");
        s.setLayoutMetadataJson("{\"sourceFormat\":\"CSV\",\"headers\":[\"Date\",\"SYNTHETIC HEADER TEXT\"]}");
        return statementImportRepository.saveAndFlush(s).getId();
    }

    /** How the old release deleted: the deleted_at stamp alone, nothing else touched. */
    private void oldReleaseSoftDelete(UUID id) {
        jdbc.update("UPDATE statement_imports SET deleted_at = now() WHERE id = ?", id);
    }

    private Map<String, Object> rawRow(UUID id) {
        return jdbc.queryForMap("""
                SELECT file_name, extracted_holder_name, file_content, layout_metadata_json, content_hash, deleted_at
                  FROM statement_imports WHERE id = ?
                """, id);
    }

    private void runV298() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V298__scrub_deleted_statements.sql")));
        }
    }

    @Test
    void aStatementDeletedByTheOldRelease_isScrubbed() throws Exception {
        UUID id = statement(account(), new byte[]{1, 2, 3}, "v298-it-" + UUID.randomUUID());
        oldReleaseSoftDelete(id);

        runV298();

        Map<String, Object> row = rawRow(id);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("file_name")).isEqualTo(StatementImport.DELETED_FILE_NAME);
        assertThat(row.get("extracted_holder_name")).isNull();
        assertThat(row.get("file_content")).isNull();
        assertThat(row.get("layout_metadata_json")).isNull();
    }

    @Test
    void aLegacyDeletedRowWithoutAHash_getsTheHashOfItsBytesBeforeTheyGo() throws Exception {
        byte[] bytes = "Date,Description,Amount\n2026-07-01,SYNTHETIC ROW,1.00\n".getBytes(StandardCharsets.UTF_8);
        UUID id = statement(account(), bytes, null);
        oldReleaseSoftDelete(id);

        runV298();

        assertThat(rawRow(id).get("content_hash")).isEqualTo(ContentAddress.hashOf(bytes));
    }

    @Test
    void anExistingHashIsKept() throws Exception {
        String hash = "v298-it-" + UUID.randomUUID();
        UUID id = statement(account(), new byte[]{9}, hash);
        oldReleaseSoftDelete(id);

        runV298();

        assertThat(rawRow(id).get("content_hash")).isEqualTo(hash);
    }

    @Test
    void aLiveStatementIsLeftAlone() throws Exception {
        UUID id = statement(account(), new byte[]{1, 2, 3}, "v298-it-" + UUID.randomUUID());

        runV298();

        Map<String, Object> row = rawRow(id);
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("file_name")).isEqualTo("synthetic-holder-statement-july.csv");
        assertThat(row.get("extracted_holder_name")).isEqualTo("SYNTHETIC HOLDER NAME");
        assertThat((byte[]) row.get("file_content")).containsExactly(1, 2, 3);
        assertThat(row.get("layout_metadata_json")).isNotNull();
    }

    /** V76's check still holds for a live row: it must keep its file somewhere. */
    @Test
    void aLiveRowWithNeitherBytesNorAnObjectKey_isStillRefused() {
        UUID id = statement(account(), new byte[]{1}, "v298-it-" + UUID.randomUUID());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE statement_imports SET file_content = NULL WHERE id = ?", id))
                .hasMessageContaining("statement_imports_file_content_or_object_key");
    }

    /** ...and a deleted one may hold neither, which is what lets a legacy row lose its bytes. */
    @Test
    void aDeletedRowMayHoldNeitherBytesNorAnObjectKey() {
        UUID id = statement(account(), new byte[]{1}, "v298-it-" + UUID.randomUUID());
        oldReleaseSoftDelete(id);

        jdbc.update("UPDATE statement_imports SET file_content = NULL WHERE id = ?", id);

        assertThat(rawRow(id).get("file_content")).isNull();
    }

    @Test
    void runningItTwiceChangesNothingTheSecondTime() throws Exception {
        byte[] bytes = "synthetic".getBytes(StandardCharsets.UTF_8);
        UUID id = statement(account(), bytes, null);
        oldReleaseSoftDelete(id);

        runV298();
        Map<String, Object> first = rawRow(id);
        runV298();
        Map<String, Object> second = rawRow(id);

        assertThat(second.get("file_name")).isEqualTo(first.get("file_name"));
        assertThat(second.get("content_hash")).isEqualTo(first.get("content_hash"));
        assertThat(second.get("content_hash")).isEqualTo(ContentAddress.hashOf(bytes));
    }
}
