package com.finora.uploads;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import com.finora.uploads.MalwareScanner.ScanResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * The scan gate over real HTTP, on the endpoint a customer actually uses. The scanner itself is a
 * mock (the protocol has its own test); what this proves is the seam: a verdict from the scanner
 * turns into the right status for the client, the right audit row, and no parse.
 */
class UploadScanGateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogRepository;

    /** Registered as a bean here even though the test profile has provider=none: the gate finds
     *  whatever MalwareScanner bean exists, which is exactly what a configured deployment does. */
    @MockitoBean private MalwareScanner scanner;

    private static final String READABLE_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            """;

    private User createUser() {
        User user = new User();
        user.setEmail("upload-scan-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Upload Scan IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpEntity<MultiValueMap<String, Object>> upload(User user, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(READABLE_CSV.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return fileName; }
        });
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> stage(User user, String fileName) {
        return restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST, upload(user, fileName), String.class);
    }

    @Test
    void anInfectedUploadIsRefusedWith400_andTheRejectionIsAudited() {
        when(scanner.scan(any(), anyLong())).thenReturn(ScanResult.infected("Eicar-Test-Signature"));
        when(scanner.describe()).thenReturn("mock scanner");
        User user = createUser();

        ResponseEntity<String> response = stage(user, "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("rejected by the malware scanner");
        assertThat(auditLogRepository.findAll())
                .filteredOn(row -> UploadScanGate.AUDIT_ACTION_REJECTED.equals(row.getAction())
                        && user.getId().equals(row.getUserId()))
                .as("the rejection is an audit event: who, which file, which signature")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getMetadata())
                        .containsEntry("signature", "Eicar-Test-Signature")
                        .containsEntry("fileName", "july.csv")
                        .containsEntry("context", "statement-import"));
    }

    @Test
    void aCleanUploadProceedsToParse() {
        when(scanner.scan(any(), anyLong())).thenReturn(ScanResult.clean());
        when(scanner.describe()).thenReturn("mock scanner");

        ResponseEntity<String> response = stage(createUser(), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnreachableScannerRefusesTheUploadWith503_underTheDefaultPolicy() {
        when(scanner.scan(any(), anyLong())).thenReturn(ScanResult.unavailable("connection refused"));
        when(scanner.describe()).thenReturn("mock scanner");

        ResponseEntity<String> response = stage(createUser(), "july.csv");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("malware scanner is unreachable");
    }
}
