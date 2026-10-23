package com.finora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.ClientPlatform;
import com.finora.entity.SupportTicket;
import com.finora.exception.ApiException;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.AuditLogRepository;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Marking a ticket as a duplicate, through real HTTP, a real database and the real notification
 * outbox and template table -- what {@code SupportTicketDuplicateServiceTest}'s mocks cannot show:
 * that V288's columns and template row exist and work, that the closure is what the customer then
 * reads back from their own ticket, and that the email rendered is the right one.
 */
class SupportTicketDuplicateIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private SupportTicketRepository ticketRepository;
    @Autowired private SupportTicketDuplicateService duplicateService;
    @Autowired private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("support-dup-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support Duplicate IT User");
        user.setRole(role);
        user.setAccountScope("ADMIN".equals(role) ? User.SCOPE_ADMIN : User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpHeaders jsonBearerFor(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode createTicket(User customer, String subject, String description) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "TECHNICAL_ISSUE");
        body.add("subject", subject);
        body.add("description", description);
        HttpHeaders headers = bearerFor(customer);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private ResponseEntity<String> markDuplicate(User admin, String ticketId, String referenceNumber) {
        String payload = "{\"referenceTicketNumber\":\"" + referenceNumber + "\"}";
        return restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/duplicate", HttpMethod.POST,
                new HttpEntity<>(payload, jsonBearerFor(admin)), String.class);
    }

    private JsonNode readOwn(User customer, String ticketId) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(bearerFor(customer)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private long ticketsInDatabaseOfStatus(User customer, com.finora.entity.SupportTicket.Status status) {
        return ticketRepository.findByUserIdOrderByCreatedAtDescIdDesc(customer.getId(),
                org.springframework.data.domain.PageRequest.of(0, 100)).stream()
                .filter(t -> t.getStatus() == status).count();
    }

    @Test
    void anAdminMarksADuplicate_theCustomerSeesItClosed_andIsEmailedOnce() throws Exception {
        User customer = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode original = createTicket(customer, "Import failing", "The first, original report.");
        String secret = "Words-only-the-customer-wrote-" + UUID.randomUUID();
        JsonNode again = createTicket(customer, secret, secret);
        String originalNumber = original.get("ticketNumber").asText();
        String againNumber = again.get("ticketNumber").asText();
        String againId = again.get("id").asText();

        ResponseEntity<String> marked = markDuplicate(admin, againId, originalNumber);

        assertThat(marked.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode summary = mapper.readTree(marked.getBody()).get("data");
        assertThat(summary.get("status").asText()).isEqualTo("CLOSED");
        assertThat(summary.get("duplicateOfTicketNumber").asText()).isEqualTo(originalNumber);

        // What the customer reads back from their own ticket, in the apps.
        JsonNode seenByCustomer = readOwn(customer, againId);
        assertThat(seenByCustomer.get("status").asText()).isEqualTo("CLOSED");
        assertThat(seenByCustomer.get("closedAt").isNull()).isFalse();
        assertThat(seenByCustomer.get("duplicateOfTicketNumber").asText()).isEqualTo(originalNumber);
        assertThat(seenByCustomer.get("duplicateOfTicketId").asText()).isEqualTo(original.get("id").asText());
        // The reference is untouched.
        JsonNode reference = readOwn(customer, original.get("id").asText());
        assertThat(reference.get("status").asText()).isEqualTo("OPEN");
        assertThat(reference.get("duplicateOfTicketNumber").isNull()).isTrue();

        // The audit trail names the admin and both tickets.
        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(UUID.fromString(againId)).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_MARKED_DUPLICATE")).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getUserId()).isEqualTo(customer.getId());
        assertThat(audit.get(0).getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
        assertThat(audit.get(0).getMetadata().get("duplicateOfTicketNumber")).isEqualTo(originalNumber);

        // Exactly one email, to the customer, rendered from the real template row.
        Notification queued = notificationRepository
                .findByNotificationKey("SUPPORT_TICKET_DUPLICATE_" + againId + ":EMAIL").orElseThrow();
        assertThat(queued.getUserId()).isEqualTo(customer.getId());
        assertThat(queued.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(queued.getType()).isEqualTo(NotificationType.SUPPORT_TICKET_DUPLICATE);
        assertThat(queued.getTitle()).contains(againNumber).doesNotContain("{{");
        assertThat(queued.getMessage()).contains(againNumber).contains(originalNumber)
                .doesNotContain("{{").doesNotContain(secret);
    }

    @Test
    void markingTheSameTicketAgain_isRefused_andNeverQueuesASecondEmail() throws Exception {
        User customer = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode original = createTicket(customer, "One", "d");
        JsonNode again = createTicket(customer, "Two", "d");
        String againId = again.get("id").asText();
        assertThat(markDuplicate(admin, againId, original.get("ticketNumber").asText()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = markDuplicate(admin, againId, original.get("ticketNumber").asText());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(second.getBody()).get("message").asText()).contains("already closed");
        assertThat(notificationRepository
                .findByNotificationKey("SUPPORT_TICKET_DUPLICATE_" + againId + ":EMAIL")).isPresent();
    }

    @Test
    void aReferenceBelongingToAnotherCustomer_isRefused_andNothingChanges() throws Exception {
        User customer = createCustomer();
        User someoneElse = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode mine = createTicket(customer, "Mine", "d");
        JsonNode theirs = createTicket(someoneElse, "Theirs", "d");

        ResponseEntity<String> response = markDuplicate(admin, mine.get("id").asText(), theirs.get("ticketNumber").asText());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readOwn(customer, mine.get("id").asText()).get("status").asText()).isEqualTo("OPEN");
        assertThat(notificationRepository
                .findByNotificationKey("SUPPORT_TICKET_DUPLICATE_" + mine.get("id").asText() + ":EMAIL")).isEmpty();
    }

    @Test
    void aReferenceThatIsItselfADuplicate_isRefused_pointingAtTheRealOne() throws Exception {
        User customer = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode first = createTicket(customer, "First", "d");
        JsonNode second = createTicket(customer, "Second", "d");
        JsonNode third = createTicket(customer, "Third", "d");
        assertThat(markDuplicate(admin, second.get("id").asText(), first.get("ticketNumber").asText())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = markDuplicate(admin, third.get("id").asText(), second.get("ticketNumber").asText());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).get("message").asText())
                .contains(first.get("ticketNumber").asText()).contains("instead");
        assertThat(ticketsInDatabaseOfStatus(customer, com.finora.entity.SupportTicket.Status.OPEN)).isEqualTo(2);
    }

    @Test
    void anUnknownReferenceNumber_isA404() throws Exception {
        User customer = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode mine = createTicket(customer, "Mine", "d");

        ResponseEntity<String> response = markDuplicate(admin, mine.get("id").asText(), "SUP-999999");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCustomerCannotMarkTheirOwnTicketAsADuplicate_andNeitherCanAnonymousCallers() throws Exception {
        User customer = createCustomer();
        JsonNode one = createTicket(customer, "One", "d");
        JsonNode two = createTicket(customer, "Two", "d");

        ResponseEntity<String> asCustomer = markDuplicate(customer, two.get("id").asText(), one.get("ticketNumber").asText());
        ResponseEntity<String> anonymous = restTemplate.exchange(
                "/api/v1/admin/support/tickets/" + two.get("id").asText() + "/duplicate", HttpMethod.POST,
                new HttpEntity<>("{\"referenceTicketNumber\":\"" + one.get("ticketNumber").asText() + "\"}",
                        jsonHeaders()), String.class);

        assertThat(asCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(anonymous.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(readOwn(customer, two.get("id").asText()).get("status").asText()).isEqualTo("OPEN");
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private User createCustomer() {
        return createUser("USER");
    }

    // --- two admins at once -----------------------------------------------------------------------

    private SupportTicket saveTicket(User customer, String number) {
        SupportTicket t = new SupportTicket();
        t.setTicketNumber(number);
        t.setUserId(customer.getId());
        t.setCategory(SupportTicket.Category.OTHER);
        t.setSource(ClientPlatform.WEB);
        t.setSubject("Race " + number);
        t.setDescription("Race description");
        return ticketRepository.save(t);
    }

    /** Starts every call at the same instant on its own thread and returns what each one did. */
    private List<Object> runTogether(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CyclicBarrier startTogether = new CyclicBarrier(calls.size());
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    try {
                        return call.call();
                    } catch (ApiException e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get());
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long successes(List<Object> outcomes) {
        return outcomes.stream().filter(o -> !(o instanceof ApiException)).count();
    }

    @Test
    void twoAdminsMarkingEachOthersTicketAtTheSameInstant_neverLeaveTwoTicketsPointingAtEachOther() throws Exception {
        User customer = createCustomer();
        UUID admin = UUID.randomUUID();
        // Many rounds: without the row locks the two checks interleave and both succeed in most of them.
        for (int round = 0; round < 15; round++) {
            String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            SupportTicket a = saveTicket(customer, "RA-" + suffix);
            SupportTicket b = saveTicket(customer, "RB-" + suffix);

            List<Object> outcomes = runTogether(List.of(
                    () -> duplicateService.markDuplicate(admin, a.getId(), b.getTicketNumber()),
                    () -> duplicateService.markDuplicate(admin, b.getId(), a.getTicketNumber())));

            assertThat(successes(outcomes)).as("round %d: exactly one of the two wins", round).isEqualTo(1);
            ApiException loser = (ApiException) outcomes.stream().filter(o -> o instanceof ApiException).findFirst().orElseThrow();
            assertThat(loser.getStatus()).as("the loser is told, not errored").isEqualTo(HttpStatus.CONFLICT);

            SupportTicket afterA = ticketRepository.findById(a.getId()).orElseThrow();
            SupportTicket afterB = ticketRepository.findById(b.getId()).orElseThrow();
            long duplicates = List.of(afterA, afterB).stream().filter(t -> t.getDuplicateOfTicketId() != null).count();
            assertThat(duplicates).as("round %d: one duplicate, one live reference -- no loop", round).isEqualTo(1);
            long emails = List.of(a, b).stream().filter(t -> notificationRepository
                    .findByNotificationKey("SUPPORT_TICKET_DUPLICATE_" + t.getId() + ":EMAIL").isPresent()).count();
            assertThat(emails).as("round %d: the customer is emailed once, not twice", round).isEqualTo(1);
        }
    }

    @Test
    void twoAdminsMarkingTheSameTicketAtTheSameInstant_closeItOnce_andEmailTheCustomerOnce() throws Exception {
        User customer = createCustomer();
        UUID admin = UUID.randomUUID();
        for (int round = 0; round < 10; round++) {
            String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            SupportTicket original = saveTicket(customer, "RO-" + suffix);
            SupportTicket again = saveTicket(customer, "RD-" + suffix);

            List<Object> outcomes = runTogether(List.of(
                    () -> duplicateService.markDuplicate(admin, again.getId(), original.getTicketNumber()),
                    () -> duplicateService.markDuplicate(admin, again.getId(), original.getTicketNumber())));

            assertThat(successes(outcomes)).as("round %d", round).isEqualTo(1);
            assertThat(notificationRepository
                    .findByNotificationKey("SUPPORT_TICKET_DUPLICATE_" + again.getId() + ":EMAIL")).isPresent();
            assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(again.getId()).stream()
                    .filter(row -> row.getAction().equals("SUPPORT_TICKET_MARKED_DUPLICATE")).count())
                    .as("round %d: audited once", round).isEqualTo(1);
        }
    }

    // --- account deletion -------------------------------------------------------------------------

    @Test
    void deletingACustomersTickets_asAccountDeletionDoes_worksWhenOneIsADuplicateOfAnother() throws Exception {
        User customer = createCustomer();
        User admin = createUser("ADMIN");
        JsonNode original = createTicket(customer, "One", "d");
        JsonNode again = createTicket(customer, "Two", "d");
        assertThat(markDuplicate(admin, again.get("id").asText(), original.get("ticketNumber").asText())
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer deleted = transactionTemplate.execute(status -> ticketRepository.deleteByUserId(customer.getId()));

        assertThat(deleted).isEqualTo(2);
        assertThat(ticketRepository.findById(UUID.fromString(again.get("id").asText()))).isEmpty();
        assertThat(ticketRepository.findById(UUID.fromString(original.get("id").asText()))).isEmpty();
    }
}
