package com.finora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-account daily limit through the real path: property binding, the real HTTP request, the
 * real count query on real rows, and the 429 body an app shows inline. The unit tests mock the
 * count; this proves the count is of the right rows (this account's, from the last day) and that a
 * refused request really saves nothing.
 */
@TestPropertySource(properties = {
        "app.support.max-tickets-per-day=2",
        "app.support.max-feedback-per-day=2"
})
class SupportSubmissionLimitIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SupportTicketRepository tickets;
    @Autowired private FeedbackEntryRepository feedback;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createCustomer() {
        User user = new User();
        user.setEmail("support-limit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support Limit IT");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(contentType);
        return headers;
    }

    private ResponseEntity<String> postTicket(User customer, String subject) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "OTHER");
        body.add("subject", subject);
        body.add("description", "A description");
        return restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, bearerFor(customer, MediaType.MULTIPART_FORM_DATA)), String.class);
    }

    private ResponseEntity<String> postFeedback(User customer, String message) {
        String payload = "{\"type\":\"GENERAL\",\"context\":\"OTHER\",\"message\":\"" + message + "\"}";
        return restTemplate.exchange("/api/v1/feedback", HttpMethod.POST,
                new HttpEntity<>(payload, bearerFor(customer, MediaType.APPLICATION_JSON)), String.class);
    }

    private long ticketsSince(User customer) {
        return tickets.countByUserIdAndCreatedAtAfter(customer.getId(), Instant.now().minusSeconds(3600));
    }

    private long feedbackSince(User customer) {
        return feedback.countByUserIdAndCreatedAtAfter(customer.getId(), Instant.now().minusSeconds(3600));
    }

    @Test
    void theThirdTicketInADayIsRefused_withAMessageTheAppShows_andNothingIsSaved() throws Exception {
        User customer = createCustomer();

        assertThat(postTicket(customer, "First").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postTicket(customer, "Second").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> third = postTicket(customer, "Third");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        JsonNode body = mapper.readTree(third.getBody());
        assertThat(body.get("message").asText()).contains("limit of 2 support tickets in 24 hours");
        assertThat(ticketsSince(customer)).isEqualTo(2);
    }

    @Test
    void theThirdFeedbackInADayIsRefused_andNothingIsSaved() throws Exception {
        User customer = createCustomer();

        assertThat(postFeedback(customer, "one").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postFeedback(customer, "two").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> third = postFeedback(customer, "three");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mapper.readTree(third.getBody()).get("message").asText())
                .contains("limit of 2 pieces of feedback in 24 hours");
        assertThat(feedbackSince(customer)).isEqualTo(2);
    }

    @Test
    void theLimitIsPerAccount_anotherCustomerIsUnaffected_andTicketsDoNotUseUpFeedback() {
        User flooder = createCustomer();
        User other = createCustomer();
        postTicket(flooder, "One");
        postTicket(flooder, "Two");
        assertThat(postTicket(flooder, "Three").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(postTicket(other, "Mine").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postFeedback(flooder, "still allowed").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMalformedTicketStillAnswers400_notTheLimitMessage() {
        User customer = createCustomer();
        postTicket(customer, "One");
        postTicket(customer, "Two");

        // Over the limit AND malformed: the caller is told what is wrong with the request first.
        ResponseEntity<String> blankSubject = postTicket(customer, "   ");

        assertThat(blankSubject.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
