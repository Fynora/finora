package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.imports.storage.ContentAddress;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting a statement removes what identifies its file and its holder from the row it leaves
 * behind. The row itself stays (soft delete): the storage sweep, the balance anchor chain and the
 * activation funnel still read it. Every check reads the raw row with SQL, because the entity hides
 * deleted rows and an API answer would prove nothing about what is stored. All values are synthetic.
 */
class DeletedStatementScrubIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementImportService statementImportService;
    @Autowired private JdbcTemplate jdbc;

    private Account account() {
        User user = new User();
        user.setEmail("scrub-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Scrub IT User");
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private StatementImport statement(Account account, byte[] inlineBytes, String contentHash, String objectKey) {
        StatementImport s = new StatementImport();
        s.setUserId(account.getUserId());
        s.setAccountId(account.getId());
        s.setFileName("synthetic-holder-statement-july.pdf");
        s.setSourceFormat("PDF");
        s.setFileContent(inlineBytes);
        s.setContentHash(contentHash);
        s.setObjectKey(objectKey);
        s.setExtractedHolderName("SYNTHETIC HOLDER NAME");
        s.setLayoutMetadataJson("{\"sourceFormat\":\"PDF\",\"headers\":[\"Date\",\"SYNTHETIC HEADER TEXT\"]}");
        return statementImportRepository.saveAndFlush(s);
    }

    private Map<String, Object> rawRow(UUID id) {
        return jdbc.queryForMap("""
                SELECT file_name, extracted_holder_name, file_content, layout_metadata_json,
                       content_hash, object_key, deleted_at
                  FROM statement_imports WHERE id = ?
                """, id);
    }

    @Test
    void deletingAStatement_clearsItsFileNameHolderNameBytesAndLayoutText() {
        Account account = account();
        StatementImport s = statement(account, null, "scrub-it-" + UUID.randomUUID(), "objects/scrub-it-" + UUID.randomUUID());

        statementImportService.delete(account.getUserId(), s.getId(), account.getUserId());

        Map<String, Object> row = rawRow(s.getId());
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("file_name")).isEqualTo(StatementImport.DELETED_FILE_NAME);
        assertThat(row.get("extracted_holder_name")).isNull();
        assertThat(row.get("file_content")).isNull();
        assertThat(row.get("layout_metadata_json")).isNull();
    }

    @Test
    void deletingAStatement_keepsWhatTheStorageSweepAndFunnelStillRead() {
        Account account = account();
        String hash = "scrub-it-" + UUID.randomUUID();
        String key = "objects/scrub-it-" + UUID.randomUUID();
        StatementImport s = statement(account, null, hash, key);

        statementImportService.delete(account.getUserId(), s.getId(), account.getUserId());

        Map<String, Object> row = rawRow(s.getId());
        assertThat(row.get("content_hash")).isEqualTo(hash);
        assertThat(row.get("object_key")).isEqualTo(key);
    }

    /** A row from before content_hash was filled: the funnel hashes its inline bytes to tell files
     *  apart. The scrub records that same hash before the bytes go, so the funnel's grouping of a
     *  deleted legacy row does not change. */
    @Test
    void deletingALegacyRow_withBytesButNoHash_recordsTheHashOfTheBytesBeforeRemovingThem() {
        Account account = account();
        byte[] bytes = "Date,Description,Amount\n2026-07-01,SYNTHETIC ROW,1.00\n".getBytes(StandardCharsets.UTF_8);
        StatementImport s = statement(account, bytes, null, null);

        statementImportService.delete(account.getUserId(), s.getId(), account.getUserId());

        Map<String, Object> row = rawRow(s.getId());
        assertThat(row.get("file_content")).isNull();
        assertThat(row.get("content_hash")).isEqualTo(ContentAddress.hashOf(bytes));
    }

    @Test
    void deletingARow_withNeitherBytesNorHash_leavesTheHashEmpty() {
        Account account = account();
        StatementImport s = statement(account, null, null, "objects/scrub-it-" + UUID.randomUUID());

        statementImportService.delete(account.getUserId(), s.getId(), account.getUserId());

        assertThat(rawRow(s.getId()).get("content_hash")).isNull();
    }

    @Test
    void deletingOneStatement_leavesAnotherLiveStatementOnTheSameAccountAlone() {
        Account account = account();
        StatementImport deleted = statement(account, null, "scrub-it-" + UUID.randomUUID(), "objects/scrub-it-" + UUID.randomUUID());
        StatementImport kept = statement(account, new byte[]{1, 2, 3}, "scrub-it-" + UUID.randomUUID(), null);

        statementImportService.delete(account.getUserId(), deleted.getId(), account.getUserId());

        Map<String, Object> row = rawRow(kept.getId());
        assertThat(row.get("deleted_at")).isNull();
        assertThat(row.get("file_name")).isEqualTo("synthetic-holder-statement-july.pdf");
        assertThat(row.get("extracted_holder_name")).isEqualTo("SYNTHETIC HOLDER NAME");
        assertThat((byte[]) row.get("file_content")).containsExactly(1, 2, 3);
        assertThat(row.get("layout_metadata_json")).isNotNull();
    }
}
