package com.finora.imports.convert;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.BalanceChainValidator;
import com.finora.imports.ColumnAmbiguityValidator;
import com.finora.imports.CreditCardFlowReconciliationValidator;
import com.finora.imports.CreditCardStatementTotalsValidator;
import com.finora.imports.DescriptionCorruptionValidator;
import com.finora.imports.ImportVerifier;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.StatementTotalsValidator;
import com.finora.imports.SummaryTotalsValidator;
import com.finora.imports.pdf.PdfMetadataExtractor;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import com.finora.imports.product.ProductAttributeExtractor;
import com.finora.imports.product.ProductDiscovery;
import com.finora.uploads.UploadScanGate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The converter's service, built with its real parser (native text, no account) and stand-ins for
 * everything outside it: Turnstile, the Redis-backed limits, the counter and the virus scanner.
 * The Spring wiring, the real limits and the "writes nothing" proof are in StatementConversionIT.
 */
class StatementConversionServiceTest {

    private static final String IP = "203.0.113.9";

    private ConverterLimits limits;
    private ConverterStats stats;
    private UploadScanGate scanGate;
    private final AtomicInteger turnstileCalls = new AtomicInteger();
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        limits = mock(ConverterLimits.class);
        when(limits.take(anyString())).thenReturn(new ConverterLimits.Slots(IP, "h", "d", "a"));
        stats = mock(ConverterStats.class);
        scanGate = mock(UploadScanGate.class);
        serviceLogger = (Logger) LoggerFactory.getLogger(StatementConversionService.class);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
    }

    static ImportVerifier verifier() {
        return new ImportVerifier(new BalanceChainValidator(), new StatementTotalsValidator(),
                new SummaryTotalsValidator(), new ColumnAmbiguityValidator(), new RowAccountingValidator(),
                new CreditCardStatementTotalsValidator(), new CreditCardFlowReconciliationValidator(),
                new DescriptionCorruptionValidator());
    }

    private StatementConversionService service() {
        return service(true, 5L * 1024 * 1024, 2, 60);
    }

    private StatementConversionService service(boolean enabled, long maxBytes, int maxConcurrent, long deadlineSeconds) {
        TurnstileVerifier turnstile = token -> {
            turnstileCalls.incrementAndGet();
            return "ok".equals(token);
        };
        return new StatementConversionService(enabled, maxBytes, 20, maxConcurrent, deadlineSeconds, turnstile,
                limits, stats, scanGate, new PdfTextExtractor(), new PdfTableLocator(), new PdfMetadataExtractor(),
                ProductDiscovery.standard(), new ProductAttributeExtractor(), verifier());
    }

    static ConvertDto.Request request(byte[] pdf, String password) {
        return new ConvertDto.Request(Base64.getEncoder().encodeToString(pdf), password, "ok", true);
    }

    @FunctionalInterface
    private interface Conversion {
        void run() throws Exception;
    }

    private static ErrorCode refusal(Conversion conversion) {
        try {
            conversion.run();
        } catch (ApiException e) {
            return e.getCode();
        } catch (Exception e) {
            throw new AssertionError("expected a refusal, got " + e, e);
        }
        throw new AssertionError("expected a refusal");
    }

    // ---- converts, and only confirmed statements ------------------------------------------------

    @Test
    void aSavingsStatementWhoseBalancesAgreeConverts_debitsAndCreditsApart_noCategoryOrDuplicate() throws Exception {
        ConvertDto.Result result = service().convert(
                request(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null), IP);

        assertThat(result.sections()).hasSize(1);
        ConvertDto.Section section = result.sections().get(0);
        assertThat(section.rows()).hasSize(6);
        assertThat(section.checks()).contains(BalanceChainValidator.RULE, StatementTotalsValidator.RULE);
        for (ConvertDto.Row row : section.rows()) {
            assertThat(row.date()).isNotNull();
            assertThat(row.debit() == null ^ row.credit() == null)
                    .as("exactly one of debit and credit on every row").isTrue();
            assertThat(row.balance()).isNotNull();
        }
        verify(stats).count(eq("CONVERTED"), any(), eq(6));
        verify(scanGate).requireCleanAnonymous(any(), eq("statement-convert"));
    }

    @Test
    void aCardStatementWhoseRowsMatchItsPrintedTotalsConverts_purchasesAsDebits() throws Exception {
        ConvertDto.Result result = service().convert(
                request(PdfFixtureBuilder.buildCreditCardStatementWhoseRowsReconcileSample(), null), IP);

        ConvertDto.Section section = result.sections().get(0);
        assertThat(section.checks()).containsExactly(CreditCardFlowReconciliationValidator.RULE);
        assertThat(section.rows()).anySatisfy(row -> assertThat(row.debit()).isNotNull());
    }

    @Test
    void aCompositeStatementConvertsEveryAccountSection() throws Exception {
        ConvertDto.Result result = service().convert(
                request(PdfFixtureBuilder.buildDepositScheduleBetweenAccountsSample(), null), IP);

        assertThat(result.sections()).hasSize(2);
        assertThat(result.sections()).allSatisfy(section -> assertThat(section.checks()).isNotEmpty());
    }

    @Test
    void aStatementWhoseAmountsDoNotReconcileIsRefused_neverConvertedWithAWarning() throws Exception {
        ErrorCode code = refusal(() -> service().convert(request(uncheck(
                PdfFixtureBuilder::buildAmountOnLastLineSampleEveryAmountReadOneAmountMisread), null), IP));

        assertThat(code).isEqualTo(ErrorCode.CONVERT_NOT_CONFIRMED);
        verify(stats).count(eq("CONVERT_NOT_CONFIRMED"), any(), anyInt());
        // The allowance is used: a refusal must not be a free probe.
        verify(limits, never()).giveBack(any());
    }

    // ---- the app's own refusals, unchanged -------------------------------------------------------

    @Test
    void aScannedStatementIsRefused_noOcrInTheConverter() throws Exception {
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample());

        assertThat(refusal(() -> service().convert(request(scanned, null), IP)))
                .isEqualTo(ErrorCode.IMPORT_SCANNED_OCR_REQUIRED);
    }

    @Test
    void aPaymentAppHistoryIsRefused() throws Exception {
        assertThat(refusal(() -> service().convert(request(uncheck(
                PdfFixtureBuilder::buildUpiAppTransactionHistory), null), IP)))
                .isEqualTo(ErrorCode.IMPORT_PAYMENT_APP_HISTORY);
    }

    @Test
    void aLockedStatementAsksForItsPassword_withoutUsingTheAllowance_thenOpensWithIt() throws Exception {
        byte[] locked = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), "s3cret");
        StatementConversionService service = service();

        assertThat(refusal(() -> service.convert(request(locked, null), IP)))
                .isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        verify(limits, times(1)).giveBack(any());

        assertThat(refusal(() -> service.convert(request(locked, "wrong"), IP)))
                .isEqualTo(ErrorCode.IMPORT_PDF_PASSWORD_INVALID);
        // A wrong guess uses the allowance, so the converter is no free password tester.
        verify(limits, times(1)).giveBack(any());

        ConvertDto.Result result = service.convert(request(locked, "s3cret"), IP);
        assertThat(result.sections().get(0).rows()).hasSize(6);
        // The password reaches no log line.
        assertThat(allLogText()).doesNotContain("s3cret");
    }

    // ---- the gates before any reading -----------------------------------------------------------

    @Test
    void switchedOff_refusesWithoutCountingOrReading() {
        assertThat(refusal(() -> service(false, 5L * 1024 * 1024, 2, 60).convert(request(new byte[]{1}, null), IP)))
                .isEqualTo(ErrorCode.CONVERT_UNAVAILABLE);
        verify(stats, never()).count(anyString(), any(), anyInt());
        assertThat(turnstileCalls).hasValue(0);
    }

    @Test
    void withoutConsentNothingElseHappens() {
        ConvertDto.Request noConsent = new ConvertDto.Request("JVBERi0=", null, "ok", false);
        ConvertDto.Request missing = new ConvertDto.Request("JVBERi0=", null, "ok", null);

        assertThat(refusal(() -> service().convert(noConsent, IP))).isEqualTo(ErrorCode.CONVERT_CONSENT_REQUIRED);
        assertThat(refusal(() -> service().convert(missing, IP))).isEqualTo(ErrorCode.CONVERT_CONSENT_REQUIRED);
        assertThat(turnstileCalls).hasValue(0);
        verify(limits, never()).take(anyString());
    }

    @Test
    void aFailedTurnstileCheckUsesNoAllowance() {
        ConvertDto.Request bot = new ConvertDto.Request("JVBERi0=", null, "forged", true);

        assertThat(refusal(() -> service().convert(bot, IP))).isEqualTo(ErrorCode.CONVERT_CHALLENGE_FAILED);
        verify(limits, never()).take(anyString());
        verify(scanGate, never()).requireCleanAnonymous(any(), anyString());
    }

    @Test
    void overTheLimitNothingIsRead() throws Exception {
        when(limits.take(IP)).thenReturn(null);

        assertThat(refusal(() -> service().convert(request(
                PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null), IP)))
                .isEqualTo(ErrorCode.CONVERT_LIMIT_REACHED);
        verify(scanGate, never()).requireCleanAnonymous(any(), anyString());
    }

    @Test
    void theFileIsCheckedForSizeTypeAndShapeBeforeTheScanner() throws Exception {
        byte[] statement = PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample();

        assertThat(refusal(() -> service(true, statement.length - 1, 2, 60).convert(request(statement, null), IP)))
                .isEqualTo(ErrorCode.CONVERT_TOO_LARGE);
        assertThat(refusal(() -> service().convert(request("date,amount\n".getBytes(StandardCharsets.UTF_8), null), IP)))
                .isEqualTo(ErrorCode.IMPORT_WRONG_FILE_TYPE);
        assertThat(refusal(() -> service().convert(new ConvertDto.Request("", null, "ok", true), IP)))
                .isEqualTo(ErrorCode.IMPORT_EMPTY_FILE);
        assertThat(refusal(() -> service().convert(new ConvertDto.Request("not base64 !!", null, "ok", true), IP)))
                .isEqualTo(ErrorCode.CONVERT_BAD_REQUEST);
        verify(scanGate, never()).requireCleanAnonymous(any(), anyString());
    }

    @Test
    void moreThanTwentyPagesIsRefused_twentyIsNot() throws Exception {
        assertThat(refusal(() -> service().convert(request(blankPdf(21), null), IP)))
                .isEqualTo(ErrorCode.CONVERT_TOO_MANY_PAGES);
        // Exactly twenty passes the page gate and reaches the parser, which finds nothing to read.
        assertThat(refusal(() -> service().convert(request(blankPdf(20), null), IP)))
                .isNotEqualTo(ErrorCode.CONVERT_TOO_MANY_PAGES);
    }

    @Test
    void aVirusRejectionStopsTheConversion() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.UPLOAD_MALWARE_DETECTED))
                .when(scanGate).requireCleanAnonymous(any(), anyString());

        assertThat(refusal(() -> service().convert(request(
                PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null), IP)))
                .isEqualTo(ErrorCode.UPLOAD_MALWARE_DETECTED);
    }

    // ---- its own pool, its own deadline ---------------------------------------------------------

    @Test
    void whenThePoolIsFullTheNextConversionIsRefusedAtOnce_andGetsItsAllowanceBack() throws Exception {
        StatementConversionService service = service(true, 5L * 1024 * 1024, 1, 60);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service.parseStep = (pdf, password, outcome) -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new ConvertDto.Result(1, List.of());
        };
        byte[] statement = PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample();
        CompletableFuture<ConvertDto.Result> first =
                CompletableFuture.supplyAsync(() -> service.convert(request(statement, null), IP));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(refusal(() -> service.convert(request(statement, null), IP))).isEqualTo(ErrorCode.CONVERT_BUSY);
        verify(limits, times(1)).giveBack(any());

        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).pageCount()).isEqualTo(1);
    }

    @Test
    void aParseThatOverrunsTheDeadlineIsAbandoned_andKeepsItsPlaceUntilItReallyEnds() throws Exception {
        StatementConversionService service = service(true, 5L * 1024 * 1024, 1, 1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        service.parseStep = (pdf, password, outcome) -> {
            try {
                // Ignores the interrupt on purpose: a parser stuck in a loop does not stop when asked.
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // keep going, as a stuck parser would
                    }
                }
                return new ConvertDto.Result(1, List.of());
            } finally {
                ended.countDown();
            }
        };
        byte[] statement = PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample();

        assertThat(refusal(() -> service.convert(request(statement, null), IP))).isEqualTo(ErrorCode.CONVERT_FAILED);
        // Still running, so still holding the only place.
        assertThat(refusal(() -> service.convert(request(statement, null), IP))).isEqualTo(ErrorCode.CONVERT_BUSY);

        release.countDown();
        assertThat(ended.await(10, TimeUnit.SECONDS)).isTrue();
        service.parseStep = (pdf, password, outcome) -> new ConvertDto.Result(1, List.of());
        // The place is free again once the stuck parse has really ended.
        Thread.sleep(100);
        assertThat(service.convert(request(statement, null), IP).pageCount()).isEqualTo(1);
    }

    // ---- nothing from the statement in the logs -------------------------------------------------

    @Test
    void aParserCrashIsLoggedByTypeAndFramesOnly_neverItsMessage() throws Exception {
        StatementConversionService service = service();
        service.parseStep = (pdf, password, outcome) -> {
            throw new IllegalStateException("Unparseable amount 'SENTINEL-NARRATION 12,345.67'",
                    new RuntimeException("cause quoting SENTINEL-CAUSE"));
        };

        assertThat(refusal(() -> service.convert(request(
                PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null), IP)))
                .isEqualTo(ErrorCode.CONVERT_FAILED);

        String text = allLogText();
        assertThat(text).contains("java.lang.IllegalStateException");
        assertThat(text).doesNotContain("SENTINEL").doesNotContain("12,345.67");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getThrowableProxy()).isNotNull());
    }

    @Test
    void aSuccessfulConversionLogsOneLineWithNothingReadFromTheStatement() throws Exception {
        ConvertDto.Result result = service().convert(
                request(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null), IP);

        String text = allLogText();
        for (ConvertDto.Row row : result.sections().get(0).rows()) {
            assertThat(text).doesNotContain(row.description());
            assertThat(text).doesNotContain(row.balance().toPlainString());
        }
        assertThat(text).doesNotContain(IP);
        assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("Statement conversion: outcome=")))
                .hasSize(1);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                text.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static byte[] blankPdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    private interface PdfSupplier {
        byte[] get() throws Exception;
    }

    private static byte[] uncheck(PdfSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
