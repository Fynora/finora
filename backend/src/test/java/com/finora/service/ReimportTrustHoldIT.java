package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.imports.trust.TrustPredicate;
import com.finora.repository.AccountRepository;
import com.finora.repository.ReimportConfirmationClaimRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A re-import that the upload path's trust check would hold is refused, at staging and at confirm.
 *
 * <p>The hole: {@code reimport} and {@code confirmReimport} re-parse the stored file and write a new
 * statement without {@link TrustPredicate}, which {@code StagingTrustGate} and {@code ImportJobWorker}
 * run on every upload. Before the fix, re-importing {@link #CHAIN_BROKEN_FILE} (whose re-read the
 * predicate holds) wrote its four rows again: the account went from 4 rows to 8.
 */
class ReimportTrustHoldIT extends AbstractIntegrationTest {

    /** Four purchases with a running balance the rows contradict in two of three pairs. */
    private static final byte[] CHAIN_BROKEN_FILE = ("Date,Description,Amount,Type,Balance\n"
            + "2026-07-01,SAMPLE GROCER,450.00,DEBIT,550.00\n"
            + "2026-07-02,SAMPLE CAFE,120.00,DEBIT,330.00\n"
            + "2026-07-03,SAMPLE BOOKSHOP,300.00,DEBIT,30.00\n"
            + "2026-07-04,SAMPLE PHARMACY,80.00,DEBIT,-150.00\n").getBytes(StandardCharsets.UTF_8);

    /** The same four purchases with a running balance every pair agrees with. */
    private static final byte[] CLEAN_FILE = ("Date,Description,Amount,Type,Balance\n"
            + "2026-07-01,SAMPLE GROCER,450.00,DEBIT,550.00\n"
            + "2026-07-02,SAMPLE CAFE,120.00,DEBIT,430.00\n"
            + "2026-07-03,SAMPLE BOOKSHOP,300.00,DEBIT,130.00\n"
            + "2026-07-04,SAMPLE PHARMACY,80.00,DEBIT,50.00\n").getBytes(StandardCharsets.UTF_8);

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReimportConfirmationClaimRepository reimportClaimRepository;

    @Test
    void theFixtureIsOneTheUploadPathHolds() throws Exception {
        StagingResponse asUploaded = importService.parseAndStage(UUID.randomUUID(), "statement.csv",
                new ByteArrayInputStream(CHAIN_BROKEN_FILE));

        assertThat(TrustPredicate.evaluate(List.of(asUploaded.verification()), List.of(),
                LocalDate.now(ZoneOffset.UTC)).categories())
                .contains(TrustPredicate.Category.BALANCE_CHAIN_DISCREPANCY);
    }

    @Test
    void reImportingAStatementTheUploadPathWouldHold_isRefusedAtStaging() throws Exception {
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account, CHAIN_BROKEN_FILE);

        Throwable refused = catchThrowable(() ->
                statementImportService.reimport(user.getId(), original.getId(), null));

        assertRefusedAsUntrusted(refused);
        assertThat(rowsFor(user, account)).isEqualTo(4);
    }

    @Test
    void confirmingAReImportTheUploadPathWouldHold_isRefused_andWritesNothing() throws Exception {
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account, CHAIN_BROKEN_FILE);
        BigDecimal balanceBefore = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        // The rows a client could send without the staging step refusing them first: an older
        // client, or a review screen opened before this check existed.
        StagingResponse rows = importService.parseAndStage(user.getId(), "statement.csv",
                new ByteArrayInputStream(CHAIN_BROKEN_FILE));
        String attemptKey = UUID.randomUUID().toString();

        Throwable refused = catchThrowable(() -> statementImportService.confirmReimport(
                user.getId(), original.getId(), reimportOf(rows, attemptKey)));

        assertRefusedAsUntrusted(refused);
        assertThat(rowsFor(user, account)).isEqualTo(4);
        assertThat(statementsFor(user)).isEqualTo(1);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
        assertThat(reimportClaimRepository.findByUserIdAndIdempotencyKey(user.getId(), attemptKey))
                .as("the refused attempt's claim rolls back with it").isEmpty();
    }

    @Test
    void aStatementWhoseBalancesChain_stillReImports() throws Exception {
        User user = user();
        Account account = account(user);
        StatementImport original = importOnce(user, account, CLEAN_FILE);

        var reimport = statementImportService.reimport(user.getId(), original.getId(), null);
        var response = statementImportService.confirmReimport(user.getId(), original.getId(),
                reimportOf(reimport.staging(), UUID.randomUUID().toString()));

        assertThat(response.imported()).isEqualTo(4);
        assertThat(rowsFor(user, account)).isEqualTo(8);
        assertThat(statementsFor(user)).isEqualTo(2);
    }

    private static void assertRefusedAsUntrusted(Throwable refused) {
        assertThat(refused).isInstanceOf(ApiException.class);
        ApiException api = (ApiException) refused;
        assertThat(api.getCode()).isEqualTo(ErrorCode.IMPORT_REIMPORT_UNTRUSTED);
        assertThat(api.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(api.getMessage()).contains("Your existing transactions are unchanged.");
        assertThat(api.getDetails().get("holdCategories")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("BALANCE_CHAIN_DISCREPANCY");
    }

    // --- fixtures ------------------------------------------------------------------------------

    private ConfirmRequest reimportOf(StagingResponse staged, String idempotencyKey) {
        return new ConfirmRequest(null, staged.rows().stream().map(ReimportTrustHoldIT::confirmed).toList(),
                null, null, null, null, null, null, null, null, null, null, idempotencyKey);
    }

    private static ConfirmedRow confirmed(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(), true,
                r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(), r.balanceAfter(),
                true, r.categoryConfidence(), r.rowPosition());
    }

    /**
     * A first-time import that skips the upload path's trust gate, as one imported before the gate
     * existed (or approved by a reviewer) did, so there is a stored file to replay.
     */
    private StatementImport importOnce(User user, Account account, byte[] file) throws Exception {
        StagingResponse staged = importService.parseAndStage(user.getId(), "statement.csv",
                new ByteArrayInputStream(file));
        ImportSession session = importSessionService.createSession(
                user.getId(), "statement.csv", file, staged.rows(), null);
        importService.confirmSession(user.getId(), new ConfirmRequest(
                session.getId(), staged.rows().stream().map(ReimportTrustHoldIT::confirmed).toList(),
                account.getId(), null, null, null, null));
        return statementImportRepository.findAll().stream()
                .filter(si -> user.getId().equals(si.getUserId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the first import created no StatementImport row"));
    }

    private long rowsFor(User user, Account account) {
        return transactionRepository.findAll().stream()
                .filter(t -> user.getId().equals(t.getUserId()) && account.getId().equals(t.getAccountId()))
                .count();
    }

    private long statementsFor(User user) {
        return statementImportRepository.findAll().stream().filter(si -> user.getId().equals(si.getUserId())).count();
    }

    private User user() {
        User user = new User();
        user.setEmail("reimport-trust-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reimport Trust User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(new BigDecimal("10000.00"));
        return accountRepository.save(account);
    }
}
