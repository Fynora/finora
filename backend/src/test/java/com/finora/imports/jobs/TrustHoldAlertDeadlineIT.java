package com.finora.imports.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The trust-hold alert's "answer by" line, through the real worker (2026-11-12). The alert is sent
 * when the review record commits, which is before the worker starts the job's own hold clock, so a
 * deadline read from the job came out missing (a fresh job) or stale (one held before). Only an
 * end-to-end hold shows that ordering; a unit test with the clock set by hand cannot.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-it-statement-storage",
        "app.import.queue.enabled=false"
})
class TrustHoldAlertDeadlineIT extends AbstractIntegrationTest {

    /** A statement period in the future trips the trust check (same document as HeldStatementReuploadIT). */
    private static final byte[] FUTURE_PERIOD_CSV = ("Date,Description,Amount,Balance,Statement Period\n"
            + "01/01/2026,Opening balance,,1000.00,01/01/2030 to 31/01/2030\n"
            + "05/01/2026,Coffee shop,-150.00,850.00,\n").getBytes(StandardCharsets.UTF_8);
    private static final DateTimeFormatter IST =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobWorker worker;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @MockitoBean private EmailProvider emailProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    private User user(String role) {
        User user = new User();
        user.setEmail("trust-hold-alert-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Trust Hold Alert IT");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        // Alert recipients come from assigned roles (UserRepository.findByPermissionNameAndAccountScope).
        if (!"USER".equals(role)) user.getRoles().add(roleRepository.findByName(role).orElseThrow());
        return userRepository.save(user);
    }

    private UUID upload(User owner) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, owner));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(FUTURE_PERIOD_CSV) {
            @Override public String getFilename() { return "statement.csv"; }
        });
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/v1/import/jobs", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(mapper.readTree(accepted.getBody()).get("data").get("jobId").asText());
    }

    @Test
    void theTrustHoldEmailSaysWhenTheCustomerWasPromisedAnAnswer() throws Exception {
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id"));
        user("ADMIN"); // holds TRUST_REVIEW_MANAGE, so the alert has at least one recipient
        UUID jobId = upload(user("USER"));

        worker.drainOnce();

        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
        HeldStatement held = heldStatementRepository.findByImportJobId(jobId).orElseThrow();
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, atLeastOnce()).send(sent.capture());
        List<EmailMessage> alerts = sent.getAllValues().stream()
                .filter(m -> m.subject().equals("Statement held for trust review — " + held.getHeldId()))
                .toList();
        assertThat(alerts).isNotEmpty();
        String expected = IST.format(held.getCreatedAt().plus(ImportJob.HOLD_PROMISE));
        assertThat(alerts.get(0).html())
                .contains("<strong>Customer told to expect an answer by:</strong> " + expected);
    }
}
