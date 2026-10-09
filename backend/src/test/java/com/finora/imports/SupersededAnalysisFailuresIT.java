package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.ImportFailureSummaryDto;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.storage.ContentAddress;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import com.finora.service.StatementImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The customer's "Recent failed imports" list (GET /import/failures) leaves out a failure once the
 * same file was confirmed as a statement import -- and only then.
 *
 * <p>The case it was built for: a locked PDF uploaded without its password fails; the user uploads
 * the same file again with the password and imports it. The failure used to stay listed, saying
 * the file couldn't be read, over a statement already in their accounts. The first test also
 * proves the premise the whole rule rests on: the failure and the confirmed statement carry the
 * SAME hash, because both are taken over the file exactly as uploaded -- the still-locked bytes --
 * not over anything decrypted on the way.
 */
class SupersededAnalysisFailuresIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private StatementAnalysisRecorder recorder;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private StatementImportService statementImportService;
    @Autowired private UserRepository userRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private static final String PASSWORD = "AAAA1234";

    private static final String CSV = """
            Date,Description,Amount,Type
            2026-07-01,UPI/CR/C000000000001/TEST PAYER/ptye/0000000000@ptyes/NA,10000.00,CREDIT
            2026-07-29,UPI/DR/D000000000003/TEST SHOP/apl/testshop@apl/UPI,16281.00,DEBIT
            """;

    /** Not a statement at all: fails at parse, the way a corrupt or unsupported file does. */
    private static final byte[] UNREADABLE_CSV = "not,a,statement\n".getBytes(StandardCharsets.UTF_8);

    private User user() {
        User user = new User();
        user.setEmail("superseded-failures-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Superseded Failures IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private static byte[] lockedPdf() throws Exception {
        return PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildReverseChronologicalRunningBalanceSample(), PASSWORD);
    }

    private ConfirmRequest confirmAll(UUID sessionId, StagingResponse staging) {
        DetectedAccountInfo d = staging.detectedAccount();
        List<ConfirmedRow> rows = staging.rows().stream()
                .map((StagedRow r) -> new ConfirmedRow(r.date(), r.description(), r.amount(), r.type(),
                        r.suggestedCategory() == null ? "Other" : r.suggestedCategory(), true,
                        r.categorySource(), r.ruleId(), r.likelyDuplicate(), r.referenceNumber(),
                        r.balanceAfter(), false, r.categoryConfidence(), r.rowPosition(),
                        r.international(), r.foreignCurrency(), r.foreignAmount()))
                .toList();
        NewAccountRequest account = new NewAccountRequest("Superseded Failures IT account", "SAVINGS",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
        return new ConfirmRequest(sessionId, rows, null, account, null, null, null,
                d == null ? null : d.statementPeriodStart(), d == null ? null : d.statementPeriodEnd(),
                null, null, null, null);
    }

    private void importCsv(User user, byte[] bytes) throws Exception {
        var staged = importService.parseAndStageWithSession(user.getId(), "statement.csv", bytes);
        importService.confirmSession(user.getId(), confirmAll(staged.sessionId(), staged.staging()));
    }

    private void failToParse(User user, byte[] bytes) {
        assertThatThrownBy(() -> importService.parseAndStageWithSession(user.getId(), "statement.csv", bytes))
                .isInstanceOf(RuntimeException.class);
    }

    private List<String> customerList(User user) {
        return recorder.recentUnresolvedCustomerFailures(user.getId(), 20).stream()
                .map(ImportFailureSummaryDto::reference).toList();
    }

    private List<StatementAnalysisSession> failuresOf(User user) {
        return analysisRepository.findAll().stream()
                .filter(s -> user.getId().equals(s.getUserId()))
                .filter(s -> s.getOutcome() == StatementAnalysisSession.Outcome.FAILED)
                .toList();
    }

    @Test
    void aLockedPdfThatFailedWithoutItsPasswordIsHiddenOnceImportedWithIt() throws Exception {
        User user = user();
        byte[] locked = lockedPdf();

        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, null))
                .isInstanceOf(ApiException.class);
        StatementAnalysisSession failure = failuresOf(user).getFirst();
        assertThat(customerList(user)).containsExactly(failure.getReference());

        var staged = importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, PASSWORD);
        importService.confirmSession(user.getId(), confirmAll(staged.sessionId(), staged.staging()));

        StatementImport imported = statementImportRepository.findAll().stream()
                .filter(s -> user.getId().equals(s.getUserId())).findFirst().orElseThrow();
        assertThat(failure.getContentHash())
                .as("the premise: both hashes are of the file as uploaded -- the still-locked bytes")
                .isEqualTo(ContentAddress.hashOf(locked))
                .isEqualTo(imported.getContentHash());
        assertThat(customerList(user)).as("the statement is in their accounts now").isEmpty();
        assertThat(recorder.recentCustomerFailures(user.getId(), 20))
                .as("the admin view keeps the full history -- the failure is still evidence about the parser")
                .extracting(ImportFailureSummaryDto::reference).containsExactly(failure.getReference());
    }

    @Test
    void aFailureOfADifferentFileStays() throws Exception {
        User user = user();
        failToParse(user, UNREADABLE_CSV);

        importCsv(user, CSV.getBytes(StandardCharsets.UTF_8));

        assertThat(customerList(user)).hasSize(1);
    }

    @Test
    void aFailureWhoseRetryAlsoFailedStays() {
        User user = user();
        failToParse(user, UNREADABLE_CSV);
        failToParse(user, UNREADABLE_CSV);

        assertThat(customerList(user)).hasSize(2);
    }

    /** Merely staged is not imported: the user does not have the data yet. */
    @Test
    void aStagedButUnconfirmedRetryDoesNotHideIt() throws Exception {
        User user = user();
        byte[] locked = lockedPdf();
        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, null))
                .isInstanceOf(ApiException.class);

        importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, PASSWORD);

        assertThat(customerList(user)).hasSize(1);
    }

    @Test
    void anotherUsersImportOfTheSameFileNeverHidesYours() throws Exception {
        User mine = user();
        User theirs = user();
        byte[] locked = lockedPdf();
        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(mine.getId(), "statement.pdf", locked, null))
                .isInstanceOf(ApiException.class);

        var staged = importService.parseAndStagePdfWithSession(theirs.getId(), "statement.pdf", locked, PASSWORD);
        importService.confirmSession(theirs.getId(), confirmAll(staged.sessionId(), staged.staging()));

        assertThat(customerList(mine)).hasSize(1);
    }

    /** "Once the same statement imports" -- an import from before the failure does not count. */
    @Test
    void anImportFromBeforeTheFailureDoesNotHideIt() throws Exception {
        User user = user();
        byte[] locked = lockedPdf();
        var staged = importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, PASSWORD);
        importService.confirmSession(user.getId(), confirmAll(staged.sessionId(), staged.staging()));

        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, null))
                .isInstanceOf(ApiException.class);

        assertThat(customerList(user)).hasSize(1);
    }

    /** A statement the user deleted is no longer in their accounts, so its failure counts again. */
    @Test
    void deletingTheImportedStatementListsTheFailureAgain() throws Exception {
        User user = user();
        byte[] locked = lockedPdf();
        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, null))
                .isInstanceOf(ApiException.class);
        var staged = importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, PASSWORD);
        importService.confirmSession(user.getId(), confirmAll(staged.sessionId(), staged.staging()));
        assertThat(customerList(user)).isEmpty();

        StatementImport imported = statementImportRepository.findAll().stream()
                .filter(s -> user.getId().equals(s.getUserId())).findFirst().orElseThrow();
        statementImportService.delete(user.getId(), imported.getId(), user.getId());

        assertThat(customerList(user)).hasSize(1);
    }

    /**
     * Filtered in the query, so hidden rows do not eat into the page: it still fills to its size.
     * The hidden failures are the NEWEST ones, so a filter applied after the page was cut would
     * return them (and then drop them) instead of the older, still-unresolved two.
     */
    @Test
    void hiddenFailuresDoNotShortenThePage() throws Exception {
        User user = user();
        byte[] locked = lockedPdf();
        failToParse(user, UNREADABLE_CSV);
        failToParse(user, UNREADABLE_CSV);
        List<String> unresolved = failuresOf(user).stream().map(StatementAnalysisSession::getReference).toList();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, null))
                    .isInstanceOf(ApiException.class);
        }
        var staged = importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", locked, PASSWORD);
        importService.confirmSession(user.getId(), confirmAll(staged.sessionId(), staged.staging()));

        assertThat(recorder.recentUnresolvedCustomerFailures(user.getId(), 2))
                .extracting(ImportFailureSummaryDto::reference)
                .containsExactlyInAnyOrderElementsOf(unresolved);
    }

    @Test
    void deletingTheAccountForgetsWhichFileFailed() {
        User user = user();
        failToParse(user, UNREADABLE_CSV);
        assertThat(failuresOf(user).getFirst().getContentHash()).isNotNull();

        // The account-deletion purge runs this inside its own transaction; a bare call has none.
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> analysisRepository.anonymizeByUserId(user.getId()));

        assertThat(analysisRepository.findAll().stream()
                .filter(s -> s.getUserId() == null)
                .filter(s -> ContentAddress.hashOf(UNREADABLE_CSV).equals(s.getContentHash())))
                .isEmpty();
    }
}
