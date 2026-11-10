package com.finora.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.security.CurrentUser;
import com.finora.service.StatementImportService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * The statement download keeps a non-ASCII file name (support break-test, 2026-11-10). On the old
 * code, a statement named in Hindi downloaded with a 200 and no Content-Disposition at all -- Tomcat
 * drops a header it cannot encode -- so the browser showed it in the page under no name.
 * SupportLimitsAndDownloadNamesIT proves the same header survives the real server; this pins that
 * the statement route builds it the same way.
 */
class StatementImportControllerDownloadTest {

    private final StatementImportService service = mock(StatementImportService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final StatementImportController controller = new StatementImportController(service, currentUser);
    private final UUID userId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();

    private String dispositionFor(String fileName) {
        when(currentUser.id()).thenReturn(userId);
        when(service.getFile(userId, statementId)).thenReturn(new StatementImportService.FileDownload(
                fileName, "Date,Description,Amount".getBytes(StandardCharsets.UTF_8), "text/csv"));
        ResponseEntity<byte[]> response = controller.downloadFile(statementId);
        return response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
    }

    @Test
    void aStatementNamedOutsideAscii_downloadsUnderItsRealName() {
        String header = dispositionFor("बैंक स्टेटमेंट.csv");

        assertThat(header).startsWith("attachment; filename=\"").contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo("बैंक स्टेटमेंट.csv");
        // Nothing outside ASCII reaches the header, which is what Tomcat could not send.
        assertThat(header.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)).isTrue();
    }

    @Test
    void anAsciiStatementName_keepsTheHeaderItAlwaysHad() {
        assertThat(dispositionFor("hdfc-oct.csv")).isEqualTo("attachment; filename=\"hdfc-oct.csv\"");
    }
}
