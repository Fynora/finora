package com.finora.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.repository.NotificationRepository;
import com.finora.notification.worker.NotificationDispatcher;
import com.finora.repository.RefreshTokenRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The acknowledgement email all the way out: a ticket raised over HTTP, the outbox row, the real
 * dispatcher draining it, and the message handed to the email provider -- the recipient, the sender,
 * and what the customer would actually read. {@code SupportTicketApiIT} stops at the queued row; this
 * is the half that proves the email is delivered to the right person and says the right thing. Only
 * the provider at the very edge is replaced, so nothing is sent for real.
 */
class SupportTicketAcknowledgementDeliveryIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationDispatcher dispatcher;
    @MockitoBean private EmailProvider emailProvider;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void emailProviderIsConfiguredAndSucceeds() {
        when(emailProvider.isConfigured()).thenReturn(true);
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "id-1"));
    }

    private User createUser() {
        User user = new User();
        user.setEmail("ack-delivery-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Ack Delivery IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private UUID createTicket(User user, String subject) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "TECHNICAL_ISSUE");
        body.add("subject", subject);
        body.add("description", "A private description the email must never repeat.");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return UUID.fromString(mapper.readTree(response.getBody()).get("data").get("id").asText());
    }

    private String ticketNumberOf(User user, UUID ticketId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return mapper.readTree(response.getBody()).get("data").get("ticketNumber").asText();
    }

    private Notification acknowledgementFor(UUID ticketId) {
        return notificationRepository.findByNotificationKey("SUPPORT_TICKET_RECEIVED_" + ticketId + ":EMAIL")
                .orElse(null);
    }

    @Test
    void theCustomerIsEmailedFromSupport_namingTheirTicket_andNothingTheyWrote() throws Exception {
        User user = createUser();
        UUID ticketId = createTicket(user, "Distinctive subject line");
        String ticketNumber = ticketNumberOf(user, ticketId);

        dispatcher.drainOnce();

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, atLeastOnce()).send(sent.capture());
        EmailMessage message = sent.getAllValues().stream()
                .filter(m -> user.getEmail().equals(m.to())).findFirst()
                .orElseThrow(() -> new AssertionError("no email was sent to the customer"));
        assertThat(message.sender()).isEqualTo(EmailMessage.Sender.SUPPORT);
        assertThat(message.subject()).contains(ticketNumber);
        assertThat(message.text()).contains(ticketNumber).contains("get back to you");
        assertThat(message.html()).contains(ticketNumber).contains("reply to this email");
        assertThat(message.text() + message.html())
                .doesNotContain("Distinctive subject line")
                .doesNotContain("private description")
                .doesNotContain("{{");
        assertThat(acknowledgementFor(ticketId).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    /** Ticket creation is not rate limited, and an account can be registered with someone else's
     *  address, so the email must not follow a loop of tickets. The tickets themselves all exist. */
    @Test
    void onlyTheFirstThreeTicketsInADayAreAcknowledged() throws Exception {
        User user = createUser();
        int acknowledged = 0;
        for (int i = 1; i <= 5; i++) {
            UUID ticketId = createTicket(user, "Ticket " + i);
            if (acknowledgementFor(ticketId) != null) acknowledged++;
        }

        assertThat(acknowledged).isEqualTo(SupportTicketService.MAX_ACKNOWLEDGEMENTS_PER_DAY);
    }
}
