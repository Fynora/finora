package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.PreviousImport;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-33 of the 2026-09-25 corpus audit, as a notice rather than a refusal: staging the exact bytes of
 * a file this user already imported says so ({@code previousImport}), and every row still stages and
 * can still be confirmed. The refusal was built once and reverted, because the product contract is
 * that a repeat upload is never refused (e2e smoke test 4; {@link StatementReimportMarksDuplicatesIT}).
 */
class PreviousImportNoticeIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementImportService statementImportService;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-01,UPI/CR/C000000000001/TEST PAYER/ptye/0000000000@ptyes/NA,10000.00,CREDIT
            2026-07-29,UPI/DR/D000000000003/TEST SHOP/apl/testshop@apl/UPI,16281.00,DEBIT
            """;

    private User user() {
        User user = new User();
        user.setEmail("previous-import-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Previous Import IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ConfirmRequest confirmAll(StagingSessionResponse staged, UUID existingAccountId) {
        DetectedAccountInfo d = staged.staging().detectedAccount();
        List<ConfirmedRow> rows = staged.staging().rows().stream()
                .map((StagedRow r) -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                        r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(),
                        r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition(),
                        r.international(), r.foreignCurrency(), r.foreignAmount()))
                .toList();
        NewAccountRequest account = existingAccountId != null ? null
                : new NewAccountRequest("Previous Import IT account", "SAVINGS", null, null, null,
                        null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null);
        return new ConfirmRequest(staged.sessionId(), rows, existingAccountId, account, null, null, null,
                d == null ? null : d.statementPeriodStart(), d == null ? null : d.statementPeriodEnd(),
                null, null, null, null);
    }

    private byte[] bytes() {
        return CSV.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aFirstUpload_hasNoNotice() throws Exception {
        StagingSessionResponse staged = importService.parseAndStageWithSession(user().getId(), "july.csv", bytes());
        assertThat(staged.previousImport()).isNull();
    }

    @Test
    void theSameBytesAfterAConfirm_nameThePreviousImport_andStillStageAndConfirm() throws Exception {
        User user = user();
        StagingSessionResponse first = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes());
        importService.confirmSession(user.getId(), confirmAll(first, null));
        Account account = accountRepository.findByUserId(user.getId()).stream().findFirst().orElseThrow();

        StagingSessionResponse second = importService.parseAndStageWithSession(user.getId(), "july-again.csv", bytes());

        PreviousImport notice = second.previousImport();
        assertThat(notice).isNotNull();
        assertThat(notice.importedAt()).isNotNull();
        assertThat(notice.accountId()).isEqualTo(account.getId());
        assertThat(notice.accountName()).isEqualTo("Previous Import IT account");
        assertThat(notice.transactionsImported()).isEqualTo(2);
        assertThat(second.staging().rows()).hasSize(2);

        ConfirmResponse again = importService.confirmSession(user.getId(), confirmAll(second, account.getId()));
        assertThat(again.imported()).isEqualTo(2);
    }

    @Test
    void theSameBytesAfterTheStatementWasDeleted_haveNoNotice() throws Exception {
        User user = user();
        StagingSessionResponse first = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes());
        importService.confirmSession(user.getId(), confirmAll(first, null));
        StatementImport imported = statementImportRepository.findAll().stream()
                .filter(s -> user.getId().equals(s.getUserId())).findFirst().orElseThrow();
        statementImportService.delete(user.getId(), imported.getId(), user.getId());

        StagingSessionResponse second = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes());

        assertThat(second.previousImport()).isNull();
    }

    @Test
    void theSameBytesUploadedByAnotherUser_haveNoNotice() throws Exception {
        User first = user();
        StagingSessionResponse staged = importService.parseAndStageWithSession(first.getId(), "july.csv", bytes());
        importService.confirmSession(first.getId(), confirmAll(staged, null));

        StagingSessionResponse other = importService.parseAndStageWithSession(user().getId(), "july.csv", bytes());

        assertThat(other.previousImport()).isNull();
    }
}
