package com.finora.uploads;

import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.uploads.MalwareScanner.ScanResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UploadScanGateTest {

    private final UUID userId = UUID.randomUUID();
    private final AuditService auditService = mock(AuditService.class);
    private final MockMultipartFile file = new MockMultipartFile(
            "file", "jan-statement.pdf", "application/pdf", "%PDF-1.4 not really".getBytes());

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MalwareScanner> providing(MalwareScanner scanner) {
        ObjectProvider<MalwareScanner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scanner);
        return provider;
    }

    private static MalwareScanner answering(ScanResult result) {
        MalwareScanner scanner = mock(MalwareScanner.class);
        when(scanner.scan(any())).thenReturn(result);
        when(scanner.describe()).thenReturn("fake scanner");
        return scanner;
    }

    /** No retry window: one attempt, the behaviour every test above the retry section pins. */
    private UploadScanGate gate(ObjectProvider<MalwareScanner> provider, String policy) {
        return new UploadScanGate(provider, auditService, policy, 0);
    }

    /** A clock the test owns: sleeping advances it, and so can a scan attempt (a connect timeout). */
    private static final class FakeTime {
        long nanos;
        final List<Long> sleptMs = new ArrayList<>();

        UploadScanGate.Sleeper sleeper() {
            return ms -> {
                sleptMs.add(ms);
                nanos += TimeUnit.MILLISECONDS.toNanos(ms);
            };
        }

        void advanceMs(long ms) {
            nanos += TimeUnit.MILLISECONDS.toNanos(ms);
        }

        long elapsedMs() {
            return TimeUnit.NANOSECONDS.toMillis(nanos);
        }
    }

    private UploadScanGate retrying(MalwareScanner scanner, String policy, long windowMs, FakeTime time) {
        return new UploadScanGate(providing(scanner), auditService, policy, windowMs, () -> time.nanos, time.sleeper());
    }

    @Test
    void noScannerConfigured_passesEveryUploadThroughAndReportsItIsNotScanning() {
        UploadScanGate gate = gate(providing(null), "reject");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
        assertThat(gate.isScanning()).isFalse();
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aCleanVerdictPasses() {
        UploadScanGate gate = gate(providing(answering(ScanResult.clean())), "reject");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
        assertThat(gate.isScanning()).isTrue();
    }

    @Test
    void anInfectedVerdictIsA400_andWritesAnAuditRowNamingTheSignature() {
        UploadScanGate gate = gate(providing(answering(ScanResult.infected("Eicar-Test-Signature"))), "allow");

        assertThatThrownBy(() -> gate.requireClean(file, userId, "support-attachment"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("rejected by the malware scanner");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq(UploadScanGate.AUDIT_ACTION_REJECTED), eq("Upload"),
                eq(userId), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("signature", "Eicar-Test-Signature")
                .containsEntry("context", "support-attachment")
                .containsEntry("fileName", "jan-statement.pdf")
                .containsEntry("sizeBytes", (long) file.getSize());
    }

    @Test
    void anUnavailableScannerIsA503UnderTheDefaultRejectPolicy() {
        UploadScanGate gate = gate(providing(answering(ScanResult.unavailable("connection refused"))), "reject");

        assertThatThrownBy(() -> gate.requireClean(file, userId, "statement-import"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("try again");
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUnavailableScannerPassesTheUploadUnderTheAllowPolicy() {
        UploadScanGate gate = gate(providing(answering(ScanResult.unavailable("connection refused"))), "allow");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
    }

    @Test
    void thePolicyStringIsRejectUnlessItIsExactlyAllow() {
        MalwareScanner down = answering(ScanResult.unavailable("down"));
        for (String policy : new String[]{"reject", "REJECT", "", "yes", "true"}) {
            UploadScanGate gate = gate(providing(down), policy);
            assertThatThrownBy(() -> gate.requireClean(file, userId, "x"))
                    .as("policy '" + policy + "' must fail closed").isInstanceOf(ApiException.class);
        }
        assertThatCode(() -> gate(providing(down), "ALLOW").requireClean(file, userId, "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsentOrEmptyFileIsNotScanned() {
        MalwareScanner scanner = answering(ScanResult.infected("would-have-fired"));
        UploadScanGate gate = gate(providing(scanner), "reject");

        assertThatCode(() -> gate.requireClean(null, userId, "support-attachment")).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireClean(new MockMultipartFile("file", new byte[0]), userId, "x"))
                .doesNotThrowAnyException();
        verify(scanner, never()).scan(any());
    }

    @Test
    void aFileNameIsSanitisedBeforeItReachesTheAuditRow() {
        UploadScanGate gate = gate(providing(answering(ScanResult.infected("Sig"))), "reject");
        MockMultipartFile hostile = new MockMultipartFile(
                "file", "../../evil\r\nforged log line.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> gate.requireClean(hostile, userId, "x")).isInstanceOf(ApiException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(any(), any(), any(), any(), metadata.capture());
        assertThat(metadata.getValue().get("fileName")).isEqualTo("evilforged log line.pdf");
    }

    /** The scanner's reply text is not something the uploader controls, but it is external text
     *  reaching a log line, so a line break in it must not be able to forge a second entry.
     *  Code-scanning #204-#207 (java/log-injection). */
    @Test
    void aLineBreakInTheScannersReplyCannotForgeASecondLogLine() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UploadScanGate.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            String forged = "Eicar\r\n[ERROR] forged entry";
            UploadScanGate infected = gate(providing(answering(ScanResult.infected(forged))), "reject");
            UploadScanGate down = gate(providing(answering(ScanResult.unavailable(forged))), "reject");
            UploadScanGate allowed = gate(providing(answering(ScanResult.unavailable(forged))), "allow");

            assertThatThrownBy(() -> infected.requireClean(file, userId, "x")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> down.requireClean(file, userId, "x")).isInstanceOf(ApiException.class);
            allowed.requireClean(file, userId, "x");

            assertThat(appender.list).hasSize(3);
            for (var event : appender.list) {
                String line = event.getFormattedMessage();
                assertThat(line).doesNotContain("\r").doesNotContain("\n");
                assertThat(line).contains("Eicar??[ERROR] forged entry");
            }
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ---- Waiting for a scanner that is waking up ----------------------------------------------
    //
    // The scanner can be put to sleep when idle (Railway's App Sleeping) and woken by the first
    // connection. Until it is up, an attempt answers UNAVAILABLE; the gate keeps asking for a
    // bounded window before applying the on-unavailable policy.

    @Test
    void aScannerThatWakesInsideTheWindow_isAskedAgainAndItsCleanVerdictPasses() {
        FakeTime time = new FakeTime();
        MalwareScanner waking = mock(MalwareScanner.class);
        when(waking.describe()).thenReturn("fake scanner");
        when(waking.scan(any()))
                .thenReturn(ScanResult.unavailable("connection refused"))
                .thenReturn(ScanResult.unavailable("connection refused"))
                .thenReturn(ScanResult.clean());

        assertThatCode(() -> retrying(waking, "reject", 25_000, time).requireClean(file, userId, "statement-import"))
                .doesNotThrowAnyException();
        verify(waking, times(3)).scan(any());
        assertThat(time.sleptMs).as("backs off between attempts, starting small").containsExactly(500L, 1_000L);
    }

    @Test
    void aScannerThatNeverAnswers_isAskedUntilTheWindowEnds_thenTheUploadIsA503() {
        FakeTime time = new FakeTime();
        MalwareScanner down = answering(ScanResult.unavailable("connection refused"));

        assertThatThrownBy(() -> retrying(down, "reject", 25_000, time).requireClean(file, userId, "statement-import"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(time.elapsedMs()).as("gave the scanner the whole window").isEqualTo(25_000);
        assertThat(time.sleptMs).as("the backoff is capped, and never sleeps past the window")
                .allSatisfy(ms -> assertThat(ms).isBetween(1L, 4_000L))
                .containsExactly(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L, 4_000L, 4_000L, 1_500L);
        verify(down, times(10)).scan(any());
    }

    @Test
    void slowAttemptsCountAgainstTheWindow_soTheWaitStaysBounded() {
        FakeTime time = new FakeTime();
        MalwareScanner timingOut = mock(MalwareScanner.class);
        when(timingOut.describe()).thenReturn("fake scanner");
        // Each attempt burns a 2 s connect timeout before answering UNAVAILABLE.
        when(timingOut.scan(any())).thenAnswer(inv -> {
            time.advanceMs(2_000);
            return ScanResult.unavailable("connect timed out");
        });

        assertThatThrownBy(() -> retrying(timingOut, "reject", 10_000, time).requireClean(file, userId, "x"))
                .isInstanceOf(ApiException.class);
        // Attempt (2 s), wait 0.5 s, attempt, wait 1 s, attempt, wait 2 s: the last attempt starts
        // at 9.5 s, inside the window, and ends at 11.5 s. No attempt starts after the window.
        assertThat(time.elapsedMs()).isEqualTo(11_500);
        verify(timingOut, times(4)).scan(any());
        assertThat(time.sleptMs).allSatisfy(ms -> assertThat(ms).isPositive());
    }

    @Test
    void anInfectedVerdictIsFinal_andIsNeverRetried() {
        FakeTime time = new FakeTime();
        MalwareScanner infected = answering(ScanResult.infected("Eicar-Test-Signature"));

        assertThatThrownBy(() -> retrying(infected, "reject", 25_000, time).requireClean(file, userId, "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(infected, times(1)).scan(any());
        assertThat(time.sleptMs).isEmpty();
    }

    @Test
    void anInfectedVerdictAfterTheScannerWakes_isStillA400WithItsAuditRow() {
        FakeTime time = new FakeTime();
        MalwareScanner waking = mock(MalwareScanner.class);
        when(waking.describe()).thenReturn("fake scanner");
        when(waking.scan(any()))
                .thenReturn(ScanResult.unavailable("connection refused"))
                .thenReturn(ScanResult.infected("Eicar-Test-Signature"));

        assertThatThrownBy(() -> retrying(waking, "reject", 25_000, time).requireClean(file, userId, "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(auditService).record(eq(userId), eq(UploadScanGate.AUDIT_ACTION_REJECTED), eq("Upload"),
                eq(userId), any());
    }

    @Test
    void everyAttemptScansTheWholeFileFromTheStart() {
        FakeTime time = new FakeTime();
        List<byte[]> seen = new ArrayList<>();
        MalwareScanner reading = mock(MalwareScanner.class);
        when(reading.describe()).thenReturn("fake scanner");
        when(reading.scan(any())).thenAnswer(inv -> {
            try (InputStream in = inv.getArgument(0)) {
                seen.add(in.readAllBytes());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return seen.size() < 3 ? ScanResult.unavailable("waking") : ScanResult.clean();
        });

        retrying(reading, "reject", 25_000, time).requireClean(file, userId, "x");

        assertThat(seen).hasSize(3).allSatisfy(bytes -> assertThat(bytes).isEqualTo(file.getBytes()));
    }

    @Test
    void theAllowPolicyAlsoWaitsForTheScanner_beforeLettingAnUploadThroughUnscanned() {
        FakeTime time = new FakeTime();
        MalwareScanner waking = mock(MalwareScanner.class);
        when(waking.describe()).thenReturn("fake scanner");
        when(waking.scan(any()))
                .thenReturn(ScanResult.unavailable("connection refused"))
                .thenReturn(ScanResult.infected("Eicar-Test-Signature"));

        assertThatThrownBy(() -> retrying(waking, "allow", 25_000, time).requireClean(file, userId, "x"))
                .as("waiting found the virus that letting it through on the first refusal would have missed")
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anInterruptWhileWaiting_stopsAtOnce_refusesTheUpload_andKeepsTheInterruptFlag() {
        MalwareScanner down = answering(ScanResult.unavailable("connection refused"));
        UploadScanGate gate = new UploadScanGate(providing(down), auditService, "reject", 25_000,
                System::nanoTime, ms -> { throw new InterruptedException(); });
        try {
            assertThatThrownBy(() -> gate.requireClean(file, userId, "x"))
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(down, times(1)).scan(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anUploadThatCannotBeRead_isRefusedAtOnce_withoutWaiting() {
        FakeTime time = new FakeTime();
        MalwareScanner scanner = answering(ScanResult.clean());
        MockMultipartFile broken = new MockMultipartFile("file", "jan.pdf", "application/pdf", new byte[]{1}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("temp file gone");
            }
        };

        assertThatThrownBy(() -> retrying(scanner, "reject", 25_000, time).requireClean(broken, userId, "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(time.sleptMs).as("the scanner is not what failed, so there is nothing to wait for").isEmpty();
        verify(scanner, never()).scan(any());
    }

    @Test
    void aNegativeWindowIsTreatedAsNoWindow() {
        FakeTime time = new FakeTime();
        MalwareScanner down = answering(ScanResult.unavailable("connection refused"));

        assertThatThrownBy(() -> retrying(down, "reject", -1, time).requireClean(file, userId, "x"))
                .isInstanceOf(ApiException.class);
        verify(down, times(1)).scan(any());
        assertThat(time.sleptMs).isEmpty();
    }

    @Test
    void aScannerThatAnswersAfterWaking_isLoggedOnce_withHowLongItTook() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UploadScanGate.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            FakeTime time = new FakeTime();
            MalwareScanner waking = mock(MalwareScanner.class);
            when(waking.describe()).thenReturn("fake scanner");
            when(waking.scan(any()))
                    .thenReturn(ScanResult.unavailable("connection refused"))
                    .thenReturn(ScanResult.clean());

            retrying(waking, "reject", 25_000, time).requireClean(file, userId, "x");

            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage())
                            .contains("answered on attempt 2").contains("after 500 ms"));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
