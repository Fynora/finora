package com.finora.transactions;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V254, which gives interest credits stored before CategoryRules.extractMerchantLabel(description,
 * direction) the label new ones get. Flyway has already applied it to the empty test database, so
 * the file is run here a second time over rows written as an older release left them. Every
 * description is synthetic.
 */
class InterestLabelBackfillIT extends AbstractIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private Account account() {
        User user = new User();
        user.setEmail("interest-label-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Interest Label IT User");
        user = userRepository.save(user);
        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    private UUID insert(Account account, String description, String type, String merchant, String counterpartyType,
                        String... userEdited) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, description, merchant, amount, txn_type,
                                          created_at, updated_at, version, reconciliation_status, source,
                                          counterparty_type, user_edited_fields)
                VALUES (?, ?, ?, DATE '2026-07-01', ?, ?, 12.34, ?, now(), now(), 0, 'OK', 'CSV_IMPORT', ?, ?::varchar[])
                """, id, account.getUserId(), account.getId(), description, merchant, type, counterpartyType,
                "{" + String.join(",", userEdited) + "}");
        return id;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT merchant, version FROM transactions WHERE id = ?", id);
    }

    private void runV254() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ClassPathResource("db/migration/V254__interest_credit_label.sql")));
        }
    }

    @Test
    void storedInterestCredits_getTheLabelNewOnesGet_andTheirVersionMoves() throws Exception {
        Account account = account();
        UUID daily = insert(account, "Interest Cr. for 03-Jan-2026", "INCOME", "interest cr for 03", "FINANCIAL_INSTITUTION");
        UUID quarterly = insert(account, "INTEREST PAID TILL 31-MAR-2026", "INCOME", "interest paid till 31", "FINANCIAL_INSTITUTION");
        UUID cutShort = insert(account, "SAVING A/C CREDIT INTEREST", "INCOME", "saving credit", "FINANCIAL_INSTITUTION");
        UUID unlabelled = insert(account, "Int.Pd:01-01-2026 to 31-03-2026", "INCOME", null, "UNKNOWN");

        runV254();

        for (UUID id : new UUID[]{daily, quarterly, cutShort, unlabelled}) {
            assertThat(row(id)).containsEntry("merchant", "interest").containsEntry("version", 1L);
        }
        runV254();
        assertThat(row(daily)).as("a second run changes nothing").containsEntry("version", 1L);
    }

    @Test
    void everyOtherRow_isLeftExactlyAsItWas() throws Exception {
        Account account = account();
        UUID debit = insert(account, "INTEREST PAID TILL 31-MAR-2026", "EXPENSE", "interest paid till 31", "FINANCIAL_INSTITUTION");
        UUID charged = insert(account, "INTEREST ON EMI", "EXPENSE", "interest on emi", "FINANCIAL_INSTITUTION");
        UUID instalment = insert(account, "SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", "INCOME",
                "sample store 2nd of", "BUSINESS");
        UUID cashback = insert(account, "CASHBACK EARNED", "INCOME", "cashback earned", "FINANCIAL_INSTITUTION");
        UUID person = insert(account, "UPI-AMIT KUMAR-amitkumar@okaxis-HDFC0000000-000000000000-INTEREST PAID", "INCOME",
                "amit kumar", "PERSON");
        UUID notAWord = insert(account, "WINTER CRAFTS", "INCOME", "winter crafts", "BUSINESS");
        UUID edited = insert(account, "Interest Cr. for 03-Jan-2026", "INCOME", "my bank", "FINANCIAL_INSTITUTION",
                Transaction.EditableField.MERCHANT.name());
        UUID handTyped = insert(account, "Interest Cr. for 04-Jan-2026", "INCOME", "Savings Interest", "FINANCIAL_INSTITUTION");

        runV254();

        assertThat(row(debit)).containsEntry("merchant", "interest paid till 31").containsEntry("version", 0L);
        assertThat(row(charged)).containsEntry("merchant", "interest on emi").containsEntry("version", 0L);
        assertThat(row(instalment)).containsEntry("merchant", "sample store 2nd of").containsEntry("version", 0L);
        assertThat(row(cashback)).containsEntry("merchant", "cashback earned").containsEntry("version", 0L);
        assertThat(row(person)).containsEntry("merchant", "amit kumar").containsEntry("version", 0L);
        assertThat(row(notAWord)).containsEntry("merchant", "winter crafts").containsEntry("version", 0L);
        assertThat(row(edited)).as("a label the user set").containsEntry("merchant", "my bank").containsEntry("version", 0L);
        assertThat(row(handTyped)).as("typed before edits were recorded").containsEntry("merchant", "Savings Interest")
                .containsEntry("version", 0L);
    }

    /**
     * V254 is the runtime rule as it stood when it ran, written in SQL; it is frozen, and the rule has
     * moved on since (V255 rechecks the difference). These rows pin what V254 itself decides.
     */
    @Test
    void theMigrationLabelsExactlyTheSpellingsOfItsRelease() throws Exception {
        Account account = account();
        Map<String, Boolean> expected = new java.util.LinkedHashMap<>();
        for (String d : new String[]{"Interest Cr. for 03-Jan-2026", "INTEREST PAID TILL 31-MAR-2026", "CREDIT INTEREST",
                "SB INT CREDIT", "INTCR 01-2026", "SAVINGS INTEREST Q1", "Int.Cr-Jan"}) {
            expected.put(d, true);
        }
        for (String d : new String[]{"interest credited", "POINTERESTCR", "WINT PD ABC",
                "SAMPLE STORE 2ND OF 3 INSTALLMENTS INTEREST", "CASHBACK EARNED", "INTEREST ON EMI"}) {
            expected.put(d, false);
        }
        Map<UUID, String> ids = new java.util.LinkedHashMap<>();
        expected.keySet().forEach(d ->
                ids.put(insert(account, d, "INCOME", "old label", com.finora.util.CounterpartyTyping.of(d).type().name()), d));

        runV254();

        ids.forEach((id, d) -> assertThat("interest".equals(row(id).get("merchant"))).as(d).isEqualTo(expected.get(d)));
    }
}
