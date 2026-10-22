package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirm time (spec 2026-10-22, sections 5 and 6): every account a statement import creates leaves
 * one count-only row saying where its bank came from and what it became, and an identity key built
 * for one bank never lands on an account the user gave another bank. Every value is synthetic.
 */
class ImportBankChoiceIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private LayoutCurationService curationService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LayoutReviewAlertService alertService;

    private static byte[] hdfcStyleCsv(String extraColumn, String preamble) {
        String csv = preamble
                + "Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance," + extraColumn + "\n"
                + "01/04/25,UPI-SYNTHETIC SHOP-SHOP@OKAXIS-UTIB0XXXXXX-100000000001-UPI,0000100000000001,01/04/25,100.00,,1900.00,x\n"
                + "03/04/25,SYNTHETIC SALARY CREDIT,0000100000000002,03/04/25,,5000.00,6900.00,y\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private static String extraColumn() {
        return "Remark" + UUID.randomUUID().toString().substring(0, 6);
    }

    private User user(String role) {
        User user = new User();
        user.setEmail("import-bank-choice-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Bank Choice IT");
        user.setRole(role);
        if ("ADMIN".equals(role)) user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        if (!"ADMIN".equals(role)) subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private String fingerprintOf(UUID sessionId) {
        return jdbc.queryForObject("SELECT layout_fingerprint FROM import_sessions WHERE id = ?", String.class, sessionId);
    }

    private static List<ConfirmedRow> confirmed(List<StagedRow> staged) {
        return staged.stream().map(r -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                r.suggestedCategory(), true, r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(),
                r.balanceAfter())).toList();
    }

    private static NewAccountRequest newAccount(DetectedAccountInfo detected, String bankId) {
        return new NewAccountRequest("Synthetic Account", "SAVINGS", BigDecimal.ZERO, null, null, null,
                detected.accountNumberMasked(), bankId, null, null, null, detected.productIdentityHash(),
                null, null, null, null, null, null, null);
    }

    private static ConfirmRequest request(UUID sessionId, List<ConfirmedRow> rows, UUID existingAccountId,
                                          NewAccountRequest newAccount) {
        return new ConfirmRequest(sessionId, rows, existingAccountId, newAccount, null, null, null,
                null, null, null, null, true, null, null);
    }

    private List<Map<String, Object>> choicesFor(String fingerprint) {
        return jdbc.queryForList("SELECT detected_bank_id, bank_source, chosen_bank_id FROM import_bank_choice "
                + "WHERE layout_fingerprint = ?", fingerprint);
    }

    /** Stages {@code csv} for a new user and confirms it into a new account with {@code bankId}. */
    private Account stageAndConfirmNew(byte[] csv, String fileName, String bankId) throws Exception {
        User user = user("USER");
        var staged = importService.parseAndStageWithSession(user.getId(), fileName, csv);
        importService.confirmSession(user.getId(), request(staged.sessionId(), confirmed(staged.staging().rows()), null,
                newAccount(staged.staging().detectedAccount(), bankId)));
        List<Account> accounts = accountRepository.findByUserId(user.getId());
        assertThat(accounts).hasSize(1);
        return accounts.get(0);
    }

    @Test
    void newAccountFromAProfileFilledUploadRecordsProfileSource() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn(), "");
        User first = user("USER");
        String fp = fingerprintOf(importService.parseAndStageWithSession(first.getId(), "statement.csv", csv).sessionId());
        UUID admin = user("ADMIN").getId();
        var profile = curationService.createProfile(admin, "Test Choice Profile " + UUID.randomUUID(), "HDFC");
        curationService.linkToProfile(admin, fp, profile.id());

        stageAndConfirmNew(csv, "statement.csv", "HDFC");

        assertThat(choicesFor(fp)).containsExactly(
                Map.of("detected_bank_id", "HDFC", "bank_source", "PROFILE", "chosen_bank_id", "HDFC"));
    }

    @Test
    void userPickedBankIsRecordedAgainstNone() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn(), "");

        Account account = stageAndConfirmNew(csv, "statement.csv", "HDFC");

        String fp = jdbc.queryForObject("SELECT layout_fingerprint FROM import_sessions WHERE user_id = ?",
                String.class, account.getUserId());
        assertThat(choicesFor(fp)).containsExactly(
                Map.of("detected_bank_id", "OTHER", "bank_source", "NONE", "chosen_bank_id", "HDFC"));
    }

    @Test
    void unknownChosenBankRecordsWhatTheAccountGot() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn(), "");

        Account account = stageAndConfirmNew(csv, "statement.csv", "NOT_A_BANK");

        String fp = jdbc.queryForObject("SELECT layout_fingerprint FROM import_sessions WHERE user_id = ?",
                String.class, account.getUserId());
        assertThat(choicesFor(fp)).singleElement()
                .satisfies(row -> assertThat(row.get("chosen_bank_id")).isEqualTo(account.getBankId()));
    }

    @Test
    void existingAccountImportRecordsNothing() throws Exception {
        byte[] csv = hdfcStyleCsv(extraColumn(), "");
        User user = user("USER");
        Account existing = new Account();
        existing.setUserId(user.getId());
        existing.setName("Existing");
        existing.setAccountType(Account.Type.SAVINGS);
        existing.setBalance(BigDecimal.ZERO);
        existing = accountRepository.save(existing);
        var staged = importService.parseAndStageWithSession(user.getId(), "statement.csv", csv);

        importService.confirmSession(user.getId(), request(staged.sessionId(), confirmed(staged.staging().rows()),
                existing.getId(), null));

        assertThat(choicesFor(fingerprintOf(staged.sessionId()))).isEmpty();
    }

    @Test
    void changingTheBankDropsTheIdentityKey_keepingItKeepsIt() throws Exception {
        String preamble = "Account No.,100000000001\n\n";
        String extra = extraColumn();

        Account changed = stageAndConfirmNew(hdfcStyleCsv(extra, preamble), "HDFC_statement.csv", "ICICI");
        Account kept = stageAndConfirmNew(hdfcStyleCsv(extra, preamble), "HDFC_statement.csv", "HDFC");

        assertThat(kept.getBankId()).isEqualTo("HDFC");
        assertThat(kept.getProductIdentityHash()).as("the precondition: this file yields a key").isNotNull();
        assertThat(changed.getBankId()).isEqualTo("ICICI");
        assertThat(changed.getProductIdentityHash()).isNull();
    }
}
