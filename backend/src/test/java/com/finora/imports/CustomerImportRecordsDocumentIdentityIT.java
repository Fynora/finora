package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A customer's upload records which bank and statement type the engine read it as (V239), on the
 * same evidence row Layout Studio lists -- the customer path, not only the admin analysis tool.
 */
class CustomerImportRecordsDocumentIdentityIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;

    /** Wholly invented merchants and reference numbers -- see check-fixture-hygiene.sh. */
    private static final String CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            02/07/2026,UPI-QUILLWORTH STATIONERS-0000000002,340.50,,24539.50
            """;

    private User user() {
        User user = new User();
        user.setEmail("doc-identity-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Document Identity User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private List<StatementAnalysisSession> analysesFor(User user) {
        return analysisRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)).stream()
                .filter(a -> user.getId().equals(a.getUserId()))
                .toList();
    }

    @Test
    void aCustomerUploadRecordsTheBankTheEngineRecognised() throws Exception {
        User user = user();

        importService.parseAndStageWithSession(user.getId(), "hdfc-statement.csv",
                CSV.getBytes(StandardCharsets.UTF_8));

        List<StatementAnalysisSession> analyses = analysesFor(user);
        assertThat(analyses).hasSize(1);
        assertThat(analyses.get(0).getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.PARSED);
        assertThat(analyses.get(0).isIdentityChecked()).isTrue();
        assertThat(analyses.get(0).getBankName()).isEqualTo("HDFC Bank");
    }

    @Test
    void aFailureAfterDetectionStillRecordsTheBank() {
        // Measured, not assumed: this document fails with IMPORT_NO_HEADER_DETECTED, but only after
        // the CSV preview has already run bank detection (here from its file name, BankRegistry's
        // last signal). "An HDFC statement failed" is exactly what an admin needs from the row.
        User user = user();

        catchThrowable(() -> importService.parseAndStageWithSession(user.getId(), "hdfc-statement.csv",
                "not a statement at all\njust two lines\n".getBytes(StandardCharsets.UTF_8)));

        List<StatementAnalysisSession> analyses = analysesFor(user);
        assertThat(analyses).hasSize(1);
        assertThat(analyses.get(0).getOutcome()).isEqualTo(StatementAnalysisSession.Outcome.FAILED);
        assertThat(analyses.get(0).isIdentityChecked()).isTrue();
        assertThat(analyses.get(0).getBankName()).isEqualTo("HDFC Bank");
    }

    @Test
    void anEncryptedPdfWithNoPasswordSaysDetectionNeverRan() throws Exception {
        // The document never opened, so no bank was looked for -- a different answer from "looked
        // and recognised none", and the page must be able to tell them apart.
        User user = user();
        byte[] locked = com.finora.imports.pdf.fixtures.PdfFixtureBuilder.encrypt(
                com.finora.imports.pdf.fixtures.PdfFixtureBuilder.buildReferenceNumberAndBalanceSample(), "s3cret");

        catchThrowable(() -> importService.parseAndStagePdfWithSession(user.getId(), "hdfc-statement.pdf", locked, null));

        List<StatementAnalysisSession> analyses = analysesFor(user);
        assertThat(analyses).hasSize(1);
        assertThat(analyses.get(0).getFailureCode()).isEqualTo("IMPORT_PDF_PASSWORD_REQUIRED");
        assertThat(analyses.get(0).isIdentityChecked()).isFalse();
        assertThat(analyses.get(0).getBankName()).isNull();
    }
}
