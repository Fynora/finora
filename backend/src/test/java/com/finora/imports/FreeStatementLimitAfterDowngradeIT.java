package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.entity.StatementImport;
import com.finora.entity.StatementRefreshRun;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.dto.StatementRefreshOutcome;
import com.finora.imports.refresh.StatementRefreshService;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The Free one-month statement limit applies to a NEW import only (owner's decision, 2026-10-06).
 * Re-import and statement refresh re-read a statement already imported -- possibly while on Plus --
 * and a downgrade to Free must not stop someone repairing their own records. These pin that as a
 * decision, so a later change to the limit cannot quietly start refusing them.
 */
class FreeStatementLimitAfterDowngradeIT extends AbstractIntegrationTest {

    /** Three months of activity, no printed period: over the Free limit by its transactions. */
    private static final byte[] THREE_MONTH_FILE = ("Date,Description,Amount,Type\n"
            + "2026-04-01,SAMPLE GROCER,450.00,DEBIT\n"
            + "2026-05-15,SAMPLE CAFE,120.00,DEBIT\n"
            + "2026-07-01,SAMPLE BOOKSHOP,300.00,DEBIT\n").getBytes(StandardCharsets.UTF_8);

    @Autowired private ImportService importService;
    @Autowired private ImportSessionService importSessionService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private StatementRefreshService refreshService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void enableRefresh() {
        ReflectionTestUtils.setField(refreshService, "enabled", true);
    }

    @AfterEach
    void disableRefresh() {
        ReflectionTestUtils.setField(refreshService, "enabled", false);
    }

    private record Imported(User user, Account account, StatementImport statement, List<ConfirmedRow> rows) {}

    /** Imports the three-month file on Plus, then downgrades the user to Free. */
    private Imported importOnPlusThenDowngrade() throws Exception {
        User user = new User();
        user.setEmail("free-limit-downgrade-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Downgrade IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        Account account = new Account();
        account.setUserId(user.getId());
        account.setName("Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account = accountRepository.save(account);

        StagingResponse staging = importService.parseAndStage(user.getId(), "statement.csv",
                new ByteArrayInputStream(THREE_MONTH_FILE));
        List<ConfirmedRow> rows = staging.rows().stream().map(FreeStatementLimitAfterDowngradeIT::confirmed).toList();
        ImportSession session = importSessionService.createSession(user.getId(), "statement.csv", THREE_MONTH_FILE,
                staging.rows(), staging.detectedAccount());
        var response = importService.confirmSession(user.getId(),
                new ConfirmRequest(session.getId(), rows, account.getId(), null, null, null, null));
        StatementImport statement = statementImportRepository.findById(response.statementImportId()).orElseThrow();

        subscriptionService.changePlan(user.getId(), "FREE", "test-downgrade", user.getId());
        return new Imported(user, account, statement, rows);
    }

    private static ConfirmedRow confirmed(StagedRow r) {
        return new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(), r.suggestedCategory(), true,
                r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(), r.balanceAfter(), false,
                r.categoryConfidence(), r.rowPosition());
    }

    @Test
    void theSameFile_uploadedFreshOnFree_isRefused() throws Exception {
        // The control: without it, the two tests below would also pass if the file simply fit.
        Imported i = importOnPlusThenDowngrade();
        StagingResponse staging = importService.parseAndStage(i.user().getId(), "statement.csv",
                new ByteArrayInputStream(THREE_MONTH_FILE));
        ImportSession session = importSessionService.createSession(i.user().getId(), "again.csv", THREE_MONTH_FILE,
                staging.rows(), staging.detectedAccount());

        Throwable refused = catchThrowable(() -> importService.confirmSession(i.user().getId(),
                new ConfirmRequest(session.getId(), i.rows(), i.account().getId(), null, null, null, null)));

        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).getCode()).isEqualTo(ErrorCode.STATEMENT_PERIOD_TOO_LONG);
    }

    @Test
    void reimportingAStatementImportedOnPlus_stillWorksAfterADowngrade() throws Exception {
        Imported i = importOnPlusThenDowngrade();

        var result = statementImportService.confirmReimport(i.user().getId(), i.statement().getId(),
                new ConfirmRequest(null, i.rows(), null, null, null, null, null));

        assertThat(result.imported()).isEqualTo(3);
    }

    @Test
    void refreshingAStatementImportedOnPlus_stillWorksAfterADowngrade() throws Exception {
        Imported i = importOnPlusThenDowngrade();
        // How an older parser might have left it: one row missed.
        UUID cafe = transactionRepository.findByStatementImportId(i.statement().getId()).stream()
                .filter(t -> "SAMPLE CAFE".equals(t.getDescription())).findFirst().orElseThrow().getId();
        jdbcTemplate.update("DELETE FROM transactions WHERE id = ?", cafe);

        StatementRefreshOutcome outcome = refreshService.refresh(i.user().getId(), i.statement().getId(), null);

        assertThat(outcome.status()).isEqualTo(StatementRefreshRun.Status.APPLIED);
        assertThat(outcome.rowsAdded()).isEqualTo(1);
    }
}
