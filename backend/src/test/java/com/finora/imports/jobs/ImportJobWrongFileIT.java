package com.finora.imports.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wrong file, the damaged file: what the REAL worker does with them, over real Postgres.
 *
 * <p>The owner's rule (2026-09-20): a damaged file, a scanned PDF, a too-large PDF and the wrong file
 * get their specific message straight away and are NOT held; only a genuine gap on our side -- a real
 * statement in a layout the engine cannot read -- reaches an admin. Measured the day this was written:
 * every wrong-file PDF (invoice, bill, salary slip, T&C, résumé) and every junk CSV had "recovered
 * lines", and were all being held, because that count is non-zero for any document with text.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-wrong-file-it",
        "app.import.queue.enabled=false",
        "app.rate-limit.import-stage.max=10000"
})
class ImportJobWrongFileIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private ImportJobRepository jobs;
    @Autowired private ImportJobWorker worker;
    @Autowired private JwtService jwt;
    @Autowired private RefreshTokenRepository refresh;
    private final ObjectMapper om = new ObjectMapper();

    private ImportJob run(String fileName, byte[] bytes) throws Exception {
        User u = new User();
        u.setEmail("wrong-file-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Wrong File IT");
        u.setPhoneVerified(true);
        u = users.save(u);
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestSessions.accessTokenFor(jwt, refresh, u));
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) { @Override public String getFilename() { return fileName; } });
        var accepted = rest.exchange("/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        UUID id = UUID.fromString(om.readTree(accepted.getBody()).get("data").get("jobId").asText());
        // Bounded: a retried job needs a few passes; anything still queued after that is the finding.
        for (int i = 0; i < 6; i++) {
            worker.drainOnce();
            if (jobs.findById(id).orElseThrow().getStatus() != ImportJob.Status.QUEUED) break;
        }
        return jobs.findById(id).orElseThrow();
    }

    private static byte[] csv(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage();
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.setLeading(14f);
                cs.newLineAtOffset(40, 780);
                for (String l : lines) { cs.showText(l); cs.newLine(); }
                cs.endText();
            }
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            d.save(o);
            return o.toByteArray();
        }
    }

    private void assertFailsStraightAway(ImportJob job, String code) {
        assertThat(job.getStatus()).as("status").isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFailureCode()).as("failure code").isEqualTo(code);
        assertThat(job.getAttemptCount()).as("a permanent failure is not retried").isEqualTo(1);
        assertThat(job.wasHeldForReview()).as("held").isFalse();
    }

    // ------------------------------------------------------------------ wrong PDFs

    @Test
    void anInvoiceFailsStraightAwayAndIsNotHeld() throws Exception {
        assertFailsStraightAway(run("invoice.pdf", pdf("TAX INVOICE", "Invoice No: INV-2026-0042",
                "Invoice Date: 12/03/2026", "Due Date: 26/03/2026", "Item Qty Rate Amount",
                "Web hosting 1 4,500.00 4,500.00", "GST 18% 810.00", "Total Due 5,310.00")),
                "IMPORT_NO_HEADER_DETECTED");
    }

    @Test
    void anElectricityBillFailsStraightAwayAndIsNotHeld() throws Exception {
        assertFailsStraightAway(run("bill.pdf", pdf("STATE ELECTRICITY BOARD", "Bill Date 05/08/2026 Due 20/08/2026",
                "Units consumed 184", "Energy charges 1,104.00", "Fixed charges 150.00", "Total payable 1,254.00")),
                "IMPORT_NO_HEADER_DETECTED");
    }

    @Test
    void aSalarySlipFailsStraightAwayAndIsNotHeld() throws Exception {
        assertFailsStraightAway(run("slip.pdf", pdf("SALARY SLIP - JULY 2026", "Basic 40,000.00", "HRA 16,000.00",
                "PF 4,800.00", "Net pay 51,200.00", "Paid on 31/07/2026")), "IMPORT_NO_HEADER_DETECTED");
    }

    @Test
    void aTermsPageAndAResumeFailStraightAwayAndAreNotHeld() throws Exception {
        assertFailsStraightAway(run("terms.pdf", pdf("Terms and Conditions", "1. The bank may change these terms.",
                "Effective from 01/04/2026.")), "IMPORT_NO_HEADER_DETECTED");
        assertFailsStraightAway(run("resume.pdf", pdf("Sanjay Tiwari", "Software Engineer, 2015 - 2026",
                "Skills: Java, Postgres")), "IMPORT_NO_HEADER_DETECTED");
    }

    @Test
    void aDamagedPdfFailsStraightAwayAndIsNotHeld() throws Exception {
        assertFailsStraightAway(run("damaged.pdf", "%PDF-1.4\nthis is not a real document\n".getBytes(StandardCharsets.UTF_8)),
                "IMPORT_CORRUPT_PDF");
    }

    @Test
    void aPdfWithTooManyPagesFailsStraightAwayAndIsNotHeld() throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 501; i++) d.addPage(new PDPage());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            d.save(o);
            assertFailsStraightAway(run("huge.pdf", o.toByteArray()), "IMPORT_PDF_TOO_LARGE");
        }
    }

    @Test
    void anImageOnlyPdfFailsStraightAwayAndIsNotHeld() throws Exception {
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage());
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            d.save(o);
            assertFailsStraightAway(run("scan.pdf", o.toByteArray()), "IMPORT_SCANNED_OCR_REQUIRED");
        }
    }

    // ------------------------------------------------------------------ the gap on OUR side

    /** The one PDF that MUST reach an admin: transaction rows the engine could not anchor into a table. */
    @Test
    void aStatementInALayoutWeCannotReadIsHeldForAnAdmin() throws Exception {
        ImportJob job = run("new-layout.pdf", pdf("01/07/2026 NEFT credit 40,000.00 46,098.10",
                "06/07/2026 ACH debit 1,000.00 45,098.10", "07/07/2026 Mandate 1,000.00 44,098.10"));

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.getFailureCode()).isEqualTo("IMPORT_NO_HEADER_DETECTED");
        assertThat(job.wasHeldForReview()).isTrue();
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void aValidCsvStillCompletes() throws Exception {
        assertThat(run("ok.csv", csv("Date,Description,Amount,Type\n2026-07-10,SWIGGY,486.00,DEBIT\n")).getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
    }

    @Test
    void aMalformedCsvFailsStraightAwayWithItsOwnCodeAndIsNotHeld() throws Exception {
        assertFailsStraightAway(run("bad.csv",
                csv("Date,Description,Amount\n2026-07-10,\"SWIGGY,486.00\n2026-07-11,X,1\n")), "IMPORT_MALFORMED_CSV");
    }

    @Test
    void wrongCsvFilesFailStraightAwayAndAreNotHeld() throws Exception {
        assertFailsStraightAway(run("a.csv", csv("foo,bar\n1,2\n3,4\n")), "IMPORT_NO_HEADER_DETECTED");
        assertFailsStraightAway(run("b.csv", csv("Dear customer, this is a letter about your account.\nRegards\n")),
                "IMPORT_NO_HEADER_DETECTED");
        assertFailsStraightAway(run("c.csv", new java.util.Random(3).ints(4000, 0, 256)
                .collect(ByteArrayOutputStream::new, (o, i) -> o.write(i), (a, c) -> { }).toByteArray()),
                "IMPORT_NO_HEADER_DETECTED");
        assertFailsStraightAway(run("d.csv", csv("Date,Description,Amount,Type\n")), "IMPORT_NO_TRANSACTIONS_FOUND");
        assertFailsStraightAway(run("e.csv", csv("Date,Description,Amount,Type\nnot-a-date,X,12,DEBIT\nalso-bad,Y,13,DEBIT\n")),
                "IMPORT_NO_TRANSACTIONS_FOUND");
    }

    /** A real bank CSV in a layout whose headers we do not recognise is OUR gap: real dates and amounts, held. */
    @Test
    void aBankCsvWithHeadersWeDoNotRecogniseIsHeldForAnAdmin() throws Exception {
        ImportJob job = run("unknown-bank.csv", csv("Posted,Details,Money\n10/07/2026,SWIGGY ORDER,486.00\n11/07/2026,BLINKIT,1240.50\n"));

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.wasHeldForReview()).isTrue();
    }
}
