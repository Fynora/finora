package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.fixtures.PdfTrace;
import com.finora.imports.pdf.fixtures.TraceQualityReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the validator fires on both failure modes it exists for.
 *
 * A check that cannot be shown to fail is not a control -- and "the capturer will read the file
 * first" was exactly that: a control everyone believed in, which let a customer's account number
 * into the repository and separately let three traces lose their deposit vocabulary without a
 * single test going red.
 */
class TraceValidatorTest {

    private String traceWith(TraceMetadata metadata, String... lines) {
        List<PositionedText> runs = new java.util.ArrayList<>();
        float y = 700f;
        // A minimal but real table: a header row and a data row, so a well-formed trace parses into
        // one section and the parseability check is exercised rather than incidentally satisfied.
        runs.add(new PositionedText("Date", 50f, y, 0));
        runs.add(new PositionedText("Narration", 150f, y, 0));
        runs.add(new PositionedText("Amount", 300f, y, 0));
        y -= 12f;
        runs.add(new PositionedText("01/06/2026", 50f, y, 0));
        runs.add(new PositionedText("Salary", 150f, y, 0));
        runs.add(new PositionedText("1000.00", 300f, y, 0));

        for (String line : lines) {
            y -= 12f;
            runs.add(new PositionedText(line, 50f, y, 0));
        }
        return PdfTrace.format(runs, metadata);
    }

    private TraceMetadata metadata(List<String> requiredHeaders) {
        return new TraceMetadata(TraceMetadata.CURRENT_TRACE_VERSION, PdfTraceRedactor.REDACTOR_VERSION,
                PdfTraceRedactor.allowlistFingerprint(), "2026-08-03", "synthetic",
                List.of(), List.of(), "validator self-test", requiredHeaders);
    }

    @Test
    void blocksAnUnmaskedEmailAddress() {
        String trace = traceWith(metadata(List.of()), "priya.nair@gmail.com"); // synthetic-ok: invented, and PII-shaped ON PURPOSE -- the assertion is that this is caught

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("email"));
    }

    @Test
    void blocksAnUnmaskedIndianMobileNumber() {
        String trace = traceWith(metadata(List.of()), "Contact 9812345678"); // synthetic-ok: invented number, PII-shaped so the check can be shown to fire

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("phone"));
    }

    @Test
    void blocksAnUnmaskedIfscBranchCode() {
        String trace = traceWith(metadata(List.of()), "IFSC HDFC0004521"); // synthetic-ok: invented branch code, needed unmasked to prove it is rejected

        var result = TraceValidator.validate("probe", trace);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.blockers()).anyMatch(f -> f.detail().contains("IFSC"));
    }

    @Test
    void allowsACorrectlyRedactedIfscWhoseBankPrefixSurvives() {
        // The 4-letter prefix is deliberately preserved -- it is what lets a trace regression-test
        // bank detection. Blocking it would make every correctly redacted trace unpublishable.
        String trace = traceWith(metadata(List.of()), "IFSC HDFC0XXXXXX");

        assertThat(TraceValidator.validate("probe", trace).isCommittable()).isTrue();
    }

    @Test
    void blocksATraceThatLostTheEvidenceItWasCapturedToPreserve() {
        // THE incident, as an executable check. A trace declaring it must retain "Maturity Date"
        // but whose redaction masked it away is no longer evidence of anything -- and previously
        // this was undetectable, because every test kept passing.
        String damaged = traceWith(metadata(List.of("Maturity Date")), "Xxxxxxxx Date");

        var result = TraceValidator.validate("probe", damaged);

        assertThat(result.isCommittable()).isFalse();
        assertThat(result.missingHeaders()).containsExactly("Maturity Date");
        assertThat(result.blockers())
                .anyMatch(f -> f.detail().contains("no longer contains the evidence"));
    }

    @Test
    void passesWhenTheRequiredEvidenceSurvived() {
        String intact = traceWith(metadata(List.of("Maturity Date")), "Maturity Date");

        var result = TraceValidator.validate("probe", intact);

        assertThat(result.isCommittable()).isTrue();
        assertThat(result.preservedHeaders()).containsExactly("Maturity Date");
        assertThat(result.missingHeaders()).isEmpty();
    }

    @Test
    void flagsATraceWithNoProvenanceForReviewWithoutBlockingIt() {
        // v1 traces are legitimately un-establishable, not corrupt -- they predate metadata. They
        // must be visible without making the corpus unbuildable.
        String legacy = PdfTrace.format(List.of(
                new PositionedText("Date", 50f, 700f, 0),
                new PositionedText("Narration", 150f, 700f, 0),
                new PositionedText("Amount", 300f, 700f, 0),
                new PositionedText("01/06/2026", 50f, 688f, 0),
                new PositionedText("Salary", 150f, 688f, 0),
                new PositionedText("1000.00", 300f, 688f, 0)));

        var result = TraceValidator.validate("probe", legacy);

        assertThat(result.metadata().isLegacy()).isTrue();
        assertThat(result.isCommittable()).as("legacy is a review item, not a blocker").isTrue();
        assertThat(result.findings()).anyMatch(f -> f.check().equals("provenance"));
    }

    @Test
    void aChangedAllowlistMakesEveryExistingTraceReportStale() {
        // The automatic half: a trace records the fingerprint it was captured under, so an
        // allowlist edit invalidates it without anyone having to connect the two.
        TraceMetadata capturedUnderOldAllowlist = new TraceMetadata(2, 1, "0000DEAD", "2026-01-01",
                "synthetic", List.of(), List.of(), "", List.of());

        assertThat(capturedUnderOldAllowlist.isStaleAgainst(PdfTraceRedactor.allowlistFingerprint()))
                .isTrue();
        assertThat(metadata(List.of()).isStaleAgainst(PdfTraceRedactor.allowlistFingerprint()))
                .as("a trace captured under the current allowlist is not stale")
                .isFalse();
    }

    @Test
    void theAllowlistFingerprintIsStableAcrossCallsAndSensitiveToContent() {
        assertThat(PdfTraceRedactor.allowlistFingerprint())
                .isEqualTo(PdfTraceRedactor.allowlistFingerprint());
        assertThat(PdfTraceRedactor.allowlistFingerprint()).hasSize(8);
    }

    @Test
    void theQualityReportStatesTheVerdictAndTheEvidence() {
        var result = TraceValidator.validate("probe",
                traceWith(metadata(List.of("Maturity Date")), "Maturity Date"));

        String report = TraceQualityReport.render(result);

        assertThat(report).contains("Trace Summary — probe");
        assertThat(report).contains("Allowlist fingerprint:");
        assertThat(report).contains("[ok]      Maturity Date");
        assertThat(report).contains("VERDICT: safe to commit");
    }

    @Test
    void theQualityReportRefusesLoudlyWhenEvidenceIsMissing() {
        var result = TraceValidator.validate("probe",
                traceWith(metadata(List.of("Maturity Date")), "Xxxxxxxx Date"));

        assertThat(TraceQualityReport.render(result))
                .contains("[MISSING] Maturity Date")
                .contains("VERDICT: DO NOT COMMIT");
    }

    @Test
    void privacyKindsNamesTheKindNeverTheValue() {
        // Not "example.org": the validator's own placeholder rule exempts it, which would make this
        // assertion about a value the scan is designed to let through.
        List<PositionedText> runs = List.of(
                new PositionedText("someone@mail.org", 0f, 0f, 0), // synthetic-ok
                new PositionedText("9876543210", 0f, 10f, 0), // synthetic-ok
                new PositionedText("HDFC0001234", 0f, 20f, 0)); // synthetic-ok
        List<String> kinds = TraceValidator.privacyKinds(runs);
        assertThat(kinds).containsExactlyInAnyOrder("email address", "phone number", "IFSC branch code");
        assertThat(String.join(" ", kinds)).doesNotContain("someone", "98765", "0001234");
    }

    /**
     * The fail-closed backstop: every word the redactor keeps is plain ASCII, so a letter, digit or
     * mark outside ASCII that is still in a run was not masked, whatever script it is in.
     */
    @Test
    void privacyKindsRefusesUnmaskedTextInAnotherScript() {
        for (String text : List.of(
                "\u0905\u092E\u093F\u0924 \u0936\u0930\u094D\u092E\u093E",   // synthetic-ok: Devanagari name
                "\u0BB5\u0BA3\u0B95\u0BCD\u0B95\u0BAE\u0BCD",   // synthetic-ok: Tamil word
                "\u0969\u0968\u096D\u096E\u096F\u0966\u0967\u0968\u0969\u096A",   // synthetic-ok: Devanagari digits
                "\uFF21\uFF33\uFF28\uFF21",   // synthetic-ok: full-width Latin
                "\u00C0\u00C9\u00CE",   // synthetic-ok: accented Latin
                "Xxxx \u093F")) {   // a mark left behind by itself
            List<String> kinds = TraceValidator.privacyKinds(List.of(new PositionedText(text, 0f, 0f, 0)));
            assertThat(kinds).as("code points %s", text.codePoints().boxed().toList())
                    .containsExactly("unmasked text in another script");
        }
    }

    @Test
    void privacyKindsAllowsTheRedactorsOwnOutputForAnotherScript() {
        String masked = PdfTraceRedactor.redactLine("UPI \u0905\u092E\u093F\u0924 \uFF21\uFF33 \u0969\u0968 \u20B9 1,234.00");   // synthetic-ok
        assertThat(TraceValidator.privacyKinds(List.of(new PositionedText(masked, 0f, 0f, 0)))).isEmpty();
        // Symbols and punctuation outside ASCII are not text.
        assertThat(TraceValidator.privacyKinds(List.of(new PositionedText("\u20B9 \u2013 \u2019 \u00A0", 0f, 0f, 0))))
                .isEmpty();
    }

    /**
     * A short email masks to runs of x too short to make a placeholder run. Every letter
     * and digit in it is already the redactor's X, x or 9, so it is not an email any more, and the
     * scan must not refuse the copy for it -- in the server's check or in a committed trace's.
     */
    @Test
    void anAlreadyMaskedShortEmailIsNotRefused() {
        String masked = PdfTraceRedactor.redactLine("raj@tcs.in"); // synthetic-ok: invented, short on purpose
        assertThat(masked).isEqualTo("xxx@xxx.xx"); // synthetic-ok: the redactor's own mask, nothing real
        assertThat(TraceValidator.privacyKinds(List.of(new PositionedText(masked, 0f, 0f, 0)))).isEmpty();
        assertThat(TraceValidator.validate("probe", traceWith(metadata(List.of()), masked)).blockers())
                .noneMatch(f -> f.detail().contains("email"));
        // A masked phone number stays exempt (its nine 9s are already a placeholder run).
        assertThat(TraceValidator.privacyKinds(List.of(new PositionedText("+91 9999999999", 0f, 0f, 0)))).isEmpty();
    }

    /** The exemption is for masked text only: one unmasked letter or digit and it is still an email. */
    @Test
    void aPartlyMaskedEmailIsStillRefused() {
        for (String text : List.of("statement@x.xx", "xxx@xxx.in", "xx9@xx1.xx")) { // synthetic-ok: invented
            assertThat(TraceValidator.privacyKinds(List.of(new PositionedText(text, 0f, 0f, 0))))
                    .as(text).containsExactly("email address");
        }
    }

    @Test
    void privacyKindsIsEmptyForATraceWithNoTable() {
        List<PositionedText> runs = List.of(new PositionedText("Xxxxx xxxx", 0f, 0f, 0));
        assertThat(TraceValidator.privacyKinds(runs)).isEmpty();
    }
}
