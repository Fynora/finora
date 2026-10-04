package com.finora.uploads;

import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.uploads.MalwareScanner.ScanResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void noScannerConfigured_passesEveryUploadThroughAndReportsItIsNotScanning() {
        UploadScanGate gate = new UploadScanGate(providing(null), auditService, "reject");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
        assertThat(gate.isScanning()).isFalse();
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void aCleanVerdictPasses() {
        UploadScanGate gate = new UploadScanGate(providing(answering(ScanResult.clean())), auditService, "reject");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
        assertThat(gate.isScanning()).isTrue();
    }

    @Test
    void anInfectedVerdictIsA400_andWritesAnAuditRowNamingTheSignature() {
        UploadScanGate gate = new UploadScanGate(
                providing(answering(ScanResult.infected("Eicar-Test-Signature"))), auditService, "allow");

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
        UploadScanGate gate = new UploadScanGate(
                providing(answering(ScanResult.unavailable("connection refused"))), auditService, "reject");

        assertThatThrownBy(() -> gate.requireClean(file, userId, "statement-import"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessageContaining("try again");
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void anUnavailableScannerPassesTheUploadUnderTheAllowPolicy() {
        UploadScanGate gate = new UploadScanGate(
                providing(answering(ScanResult.unavailable("connection refused"))), auditService, "allow");

        assertThatCode(() -> gate.requireClean(file, userId, "statement-import")).doesNotThrowAnyException();
    }

    @Test
    void thePolicyStringIsRejectUnlessItIsExactlyAllow() {
        MalwareScanner down = answering(ScanResult.unavailable("down"));
        for (String policy : new String[]{"reject", "REJECT", "", "yes", "true"}) {
            UploadScanGate gate = new UploadScanGate(providing(down), auditService, policy);
            assertThatThrownBy(() -> gate.requireClean(file, userId, "x"))
                    .as("policy '" + policy + "' must fail closed").isInstanceOf(ApiException.class);
        }
        assertThatCode(() -> new UploadScanGate(providing(down), auditService, "ALLOW").requireClean(file, userId, "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsentOrEmptyFileIsNotScanned() {
        MalwareScanner scanner = answering(ScanResult.infected("would-have-fired"));
        UploadScanGate gate = new UploadScanGate(providing(scanner), auditService, "reject");

        assertThatCode(() -> gate.requireClean(null, userId, "support-attachment")).doesNotThrowAnyException();
        assertThatCode(() -> gate.requireClean(new MockMultipartFile("file", new byte[0]), userId, "x"))
                .doesNotThrowAnyException();
        verify(scanner, never()).scan(any());
    }

    @Test
    void aFileNameIsSanitisedBeforeItReachesTheAuditRow() {
        UploadScanGate gate = new UploadScanGate(
                providing(answering(ScanResult.infected("Sig"))), auditService, "reject");
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
            UploadScanGate infected = new UploadScanGate(
                    providing(answering(ScanResult.infected(forged))), auditService, "reject");
            UploadScanGate down = new UploadScanGate(
                    providing(answering(ScanResult.unavailable(forged))), auditService, "reject");
            UploadScanGate allowed = new UploadScanGate(
                    providing(answering(ScanResult.unavailable(forged))), auditService, "allow");

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
}
