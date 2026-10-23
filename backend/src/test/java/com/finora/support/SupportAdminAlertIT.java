package com.finora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.RoleRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin alert through the real path, which {@code SupportAdminAlertServiceTest}'s mocks cannot
 * prove: an HTTP request commits a ticket or feedback entry, the after-commit hook fires, the alert
 * runs on its own executor, and the recipients come from the real ADMIN and SUPER_ADMIN role rows
 * (so this also proves V149's grant of {@code SUPPORT_MANAGE} reaches both).
 *
 * <p>Assertions are per address, never on a total: integration tests in this suite share one
 * Postgres and admins created by other classes also hold the permission.
 */
class SupportAdminAlertIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @MockitoBean private EmailProvider emailProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void emailsSucceed() {
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
    }

    private User createUser(String role, String status) {
        User user = new User();
        user.setEmail("support-alert-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support Alert IT " + UUID.randomUUID());
        user.setRole(role);
        user.setStatus(status);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        if (!"USER".equals(role)) {
            // The real role row, so the recipient query walks the real grant (V149).
            user.getRoles().add(roleRepository.findByName(role).orElseThrow());
        }
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private ResponseEntity<String> postTicket(User customer, String subject, String description) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "TECHNICAL_ISSUE");
        body.add("subject", subject);
        body.add("description", description);
        HttpHeaders headers = bearerFor(customer);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postFeedback(User customer, String message) {
        HttpHeaders headers = bearerFor(customer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = "{\"type\":\"BUG\",\"context\":\"IMPORT_FLOW\",\"message\":\"" + message + "\"}";
        return restTemplate.exchange("/api/v1/feedback", HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
    }

    private static org.mockito.ArgumentMatcher<EmailMessage> to(User user) {
        return message -> message.to().equals(user.getEmail());
    }

    /** The one email this user received, once it arrives. */
    private EmailMessage emailTo(User user) {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, timeout(10_000).atLeastOnce()).send(argThat(to(user)));
        verify(emailProvider, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> m.to().equals(user.getEmail())).findFirst().orElseThrow();
    }

    @Test
    void aRaisedTicket_emailsBothAnAdminAndASuperAdmin_notASuspendedAdmin_andLeaksNoneOfTheTicketText() throws Exception {
        User admin = createUser("ADMIN", User.STATUS_ACTIVE);
        User superAdmin = createUser("SUPER_ADMIN", User.STATUS_ACTIVE);
        User suspended = createUser("ADMIN", User.STATUS_SUSPENDED);
        User customer = createUser("USER", User.STATUS_ACTIVE);
        String secretSubject = "Subject-" + UUID.randomUUID();
        String secretDescription = "Description-" + UUID.randomUUID();

        ResponseEntity<String> response = postTicket(customer, secretSubject, secretDescription);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        String ticketNumber = data.get("ticketNumber").asText();
        String ticketId = data.get("id").asText();
        for (User recipient : List.of(admin, superAdmin)) {
            EmailMessage email = emailTo(recipient);
            assertThat(email.subject()).isEqualTo("New support ticket — " + ticketNumber);
            assertThat(email.html()).contains(ticketNumber).contains("/support-tickets/" + ticketId);
            // A pointer, not a copy: nothing the customer wrote, and nothing that identifies them.
            assertThat(email.html())
                    .doesNotContain(secretSubject)
                    .doesNotContain(secretDescription)
                    .doesNotContain(customer.getEmail())
                    .doesNotContain(customer.getFullName());
        }
        verify(emailProvider, after(1500).never()).send(argThat(to(suspended)));
    }

    @Test
    void sentFeedback_emailsAnAdmin_withoutTheMessageOrTheSender() throws Exception {
        User admin = createUser("ADMIN", User.STATUS_ACTIVE);
        User customer = createUser("USER", User.STATUS_ACTIVE);
        String secretMessage = "Message-" + UUID.randomUUID();

        ResponseEntity<String> response = postFeedback(customer, secretMessage);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        EmailMessage email = emailTo(admin);
        assertThat(email.subject()).isEqualTo("New feedback — Bug");
        assertThat(email.html()).contains("Import flow").contains("/feedback");
        assertThat(email.html())
                .doesNotContain(secretMessage)
                .doesNotContain(customer.getEmail())
                .doesNotContain(customer.getFullName());
    }

    @Test
    void aTicketTheServerRejects_tellsNoOne() {
        User admin = createUser("ADMIN", User.STATUS_ACTIVE);
        User customer = createUser("USER", User.STATUS_ACTIVE);

        ResponseEntity<String> response = postTicket(customer, "   ", "A description");

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        verify(emailProvider, after(1500).never()).send(argThat(to(admin)));
    }

    /** The customer's request must return at once even when the email provider takes seconds. */
    @Test
    void aSlowEmailProvider_doesNotHoldTheCustomersRequest() {
        User admin = createUser("ADMIN", User.STATUS_ACTIVE);
        User customer = createUser("USER", User.STATUS_ACTIVE);
        // Slow only for this test's admin: admins other classes left in the shared database also
        // get the alert, on the same single thread, and must not push this one past the wait below.
        when(emailProvider.send(any())).thenAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            if (message.to().equals(admin.getEmail())) Thread.sleep(5000);
            return EmailResult.success(ProviderType.RESEND, "slow");
        });

        long started = System.nanoTime();
        ResponseEntity<String> response = postFeedback(customer, "Slow provider check");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsedMs).isLessThan(4000);
        verify(emailProvider, timeout(20_000).atLeastOnce()).send(argThat(to(admin)));
    }
}
