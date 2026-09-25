package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingSessionResponse;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-33 of the 2026-09-25 corpus audit: confirming a session whose bytes already belong to a
 * confirmed statement_import must be refused, and must leave the ledger untouched. Byte-identical
 * content can never be a legitimate replacement (the supersede flow handles a re-upload of the
 * same period with different content), so a second confirm would only re-insert every row.
 */
class StatementReimportRefusedIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private UserRepository userRepository;

    private static final String CSV = """
            Date,Description,Amount,Type,Balance
            2026-07-01,UPI/CR/C000000000001/TEST PAYER/ptye/0000000000@ptyes/NA,10000.00,CREDIT,20728.84
            2026-07-12,UPI/CR/C000000000002/TEST PAYER/ptye/0000000000@ptyes/NA,15000.00,CREDIT,35728.84
            2026-07-29,UPI/DR/D000000000003/TEST SHOP/apl/testshop@apl/UPI,16281.00,DEBIT,19447.84
            """;

    private User user() {
        User user = new User();
        user.setEmail("reimport-refused-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reimport Refused IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ConfirmRequest confirmAll(StagingSessionResponse staged) {
        DetectedAccountInfo d = staged.staging().detectedAccount();
        List<ConfirmedRow> rows = staged.staging().rows().stream()
                .map((StagedRow r) -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                        r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(),
                        r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition(),
                        r.international(), r.foreignCurrency(), r.foreignAmount()))
                .toList();
        NewAccountRequest account = new NewAccountRequest("Reimport IT account", "SAVINGS", null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        return new ConfirmRequest(staged.sessionId(), rows, null, account, null, null, null,
                d == null ? null : d.statementPeriodStart(), d == null ? null : d.statementPeriodEnd(),
                null, null, null, null);
    }

    @Test
    void confirmingTheSameFileASecondTime_isRefused_andWritesNothing() throws Exception {
        User user = user();
        byte[] bytes = CSV.getBytes(StandardCharsets.UTF_8);

        StagingSessionResponse first = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        importService.confirmSession(user.getId(), confirmAll(first));
        int transactionsAfterFirst = transactionRepository.findByUserId(user.getId()).size();
        List<StatementImport> importsAfterFirst = statementImportRepository.findAll().stream()
                .filter(si -> user.getId().equals(si.getUserId())).toList();
        assertThat(transactionsAfterFirst).isEqualTo(3);
        assertThat(importsAfterFirst).hasSize(1);
        assertThat(importsAfterFirst.get(0).getContentHash()).isNotNull();

        StagingSessionResponse second = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());

        assertThatThrownBy(() -> importService.confirmSession(user.getId(), confirmAll(second)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMPORT_STATEMENT_ALREADY_IMPORTED));

        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(transactionsAfterFirst);
        assertThat(statementImportRepository.findAll().stream().filter(si -> user.getId().equals(si.getUserId())).count())
                .isEqualTo(1);

        // The refused session stays STAGED, so a third upload of the same bytes is handed that same
        // session by findLiveSessionByContentHash (the live-session unique index would otherwise
        // reject a second STAGED row for the same content), and confirming it is refused again.
        StagingSessionResponse third = importService.parseAndStageWithSession(user.getId(), "july.csv", bytes);
        assertThat(third.sessionId()).isEqualTo(second.sessionId());
        assertThatThrownBy(() -> importService.confirmSession(user.getId(), confirmAll(third)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.IMPORT_STATEMENT_ALREADY_IMPORTED));
        assertThat(transactionRepository.findByUserId(user.getId())).hasSize(transactionsAfterFirst);
    }
}
