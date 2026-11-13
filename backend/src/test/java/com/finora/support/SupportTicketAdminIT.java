package com.finora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.SupportTicket;
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
 * The admin side of support tickets (2026-11-12): who raised a ticket and which admin holds it,
 * named; the "mine / unclaimed" queue filter; the admin detail view; and telling the customer when
 * their ticket is resolved or closed.
 */
class SupportTicketAdminIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private SupportTicketRepository ticketRepository;
    @Autowired private SupportTicketService ticketService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role, String fullName, boolean emailVerified) {
        User user = new User();
        user.setEmail("support-admin-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(fullName);
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user.setEmailVerified(emailVerified);
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

    private JsonNode createTicket(User customer, String subject) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "TECHNICAL_ISSUE");
        body.add("subject", subject);
        body.add("description", "Something went wrong for " + subject);
        HttpHeaders headers = bearerFor(customer);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private ResponseEntity<String> get(User caller, String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(caller)), String.class);
    }

    private ResponseEntity<String> changeStatus(User admin, String ticketId, String json) {
        return restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>(json, jsonBearerFor(admin)), String.class);
    }

    private void claim(User admin, String ticketId) {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private List<AuditLog> statusAudit(String ticketId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(UUID.fromString(ticketId)).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_STATUS_CHANGED")).toList();
    }

    // --- names and the queue filter --------------------------------------------------------------

    @Test
    void theQueueNamesTheCustomerAndTheClaimingAdmin() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String subject = "names-" + UUID.randomUUID();
        JsonNode ticket = createTicket(customer, subject);
        claim(admin, ticket.get("id").asText());

        JsonNode row = data(get(admin, "/api/v1/admin/support/tickets?q=" + subject)).get("content").get(0);

        assertThat(row.get("customerName").asText()).isEqualTo("Asha Rao");
        assertThat(row.get("customerEmail").asText()).isEqualTo(customer.getEmail());
        assertThat(row.get("claimedByAdminName").asText()).isEqualTo("Ops Admin");
    }

    @Test
    void mineAndUnclaimed_narrowTheQueueToWhatTheCallerHolds_andToNobodysYet() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User me = createUser("ADMIN", "Me Admin", false);
        User colleague = createUser("ADMIN", "Colleague Admin", false);
        String subject = "claims-" + UUID.randomUUID();
        String mine = createTicket(customer, subject + "-mine").get("id").asText();
        String theirs = createTicket(customer, subject + "-theirs").get("id").asText();
        String nobodys = createTicket(customer, subject + "-nobody").get("id").asText();
        claim(me, mine);
        claim(colleague, theirs);

        JsonNode minePage = data(get(me, "/api/v1/admin/support/tickets?q=" + subject + "&claim=MINE")).get("content");
        JsonNode unclaimedPage = data(get(me, "/api/v1/admin/support/tickets?q=" + subject + "&claim=UNCLAIMED")).get("content");
        JsonNode everything = data(get(me, "/api/v1/admin/support/tickets?q=" + subject)).get("content");

        assertThat(minePage).hasSize(1);
        assertThat(minePage.get(0).get("id").asText()).isEqualTo(mine);
        assertThat(minePage.get(0).get("claimedByYou").asBoolean()).isTrue();
        assertThat(everything).anySatisfy(row -> {
            assertThat(row.get("id").asText()).isEqualTo(theirs);
            assertThat(row.get("claimedByYou").asBoolean()).isFalse();
        });
        assertThat(unclaimedPage).hasSize(1);
        assertThat(unclaimedPage.get(0).get("id").asText()).isEqualTo(nobodys);
        assertThat(everything).hasSize(3);
    }

    /** A role holding SUPPORT_MANAGE without USER_VIEW could never look a customer up; naming them
     *  in the queue must not change that. Staff are always named. */
    @Test
    void neverNamesTheCustomer_toACallerWhoCannotViewUsers() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String subject = "privacy-" + UUID.randomUUID();
        String id = createTicket(customer, subject).get("id").asText();
        claim(admin, id);

        var page = ticketService.adminList(null, null, subject, null, admin.getId(), false, 0, 25);
        var detail = ticketService.adminDetail(admin.getId(), UUID.fromString(id), false);

        assertThat(page.content().get(0).customerName()).isNull();
        assertThat(page.content().get(0).customerEmail()).isNull();
        assertThat(page.content().get(0).claimedByAdminName()).isEqualTo("Ops Admin");
        assertThat(detail.customerName()).isNull();
        assertThat(detail.customerEmail()).isNull();
    }

    @Test
    void theAdminDetailCarriesTheCustomer_theClaimingAdmin_andNamedNotes() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "detail-" + UUID.randomUUID()).get("id").asText();
        claim(admin, id);
        restTemplate.exchange("/api/v1/admin/support/tickets/" + id + "/notes", HttpMethod.POST,
                new HttpEntity<>("{\"note\":\"Looked at the import\"}", jsonBearerFor(admin)), String.class);

        JsonNode detail = data(get(admin, "/api/v1/admin/support/tickets/" + id));
        JsonNode notes = data(get(admin, "/api/v1/admin/support/tickets/" + id + "/notes"));

        assertThat(detail.get("customerName").asText()).isEqualTo("Asha Rao");
        assertThat(detail.get("customerEmail").asText()).isEqualTo(customer.getEmail());
        assertThat(detail.get("claimedByAdminName").asText()).isEqualTo("Ops Admin");
        assertThat(detail.get("claimedByYou").asBoolean()).isTrue();
        assertThat(detail.get("description").asText()).startsWith("Something went wrong");
        assertThat(detail.has("source")).isTrue();
        assertThat(notes.get(0).get("adminName").asText()).isEqualTo("Ops Admin");
        // Opening the ticket is recorded with the admin as actor, as it was through the old route.
        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(UUID.fromString(id)).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_VIEWED"))
                .anyMatch(row -> admin.getId().toString().equals(row.getMetadata().get("actorId")))).isTrue();
    }

    @Test
    void theCustomersOwnViewOfATicket_namesNobody() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "own-" + UUID.randomUUID()).get("id").asText();
        claim(admin, id);

        JsonNode own = data(get(customer, "/api/v1/support/tickets/" + id));

        assertThat(own.has("claimedByAdminName")).isFalse();
        assertThat(own.has("customerEmail")).isFalse();
        // Not the staff account's id either (break-test 2026-11-13): it reached the customer on the
        // detail and the "My Tickets" list, though the admin who held the ticket is not theirs to know.
        assertThat(own.has("claimedByAdminId")).isFalse();
        assertThat(own.toString()).doesNotContain(admin.getId().toString());
        JsonNode list = data(get(customer, "/api/v1/support/tickets"));
        assertThat(list.get("content")).isNotEmpty().allSatisfy(row -> {
            assertThat(row.has("claimedByAdminId")).isFalse();
            assertThat(row.toString()).doesNotContain(admin.getId().toString());
        });
        // The admin side still says who holds it.
        assertThat(data(get(admin, "/api/v1/admin/support/tickets/" + id)).get("claimedByAdminId").asText())
                .isEqualTo(admin.getId().toString());
    }

    @Test
    void theAdminDetailIsForAdminsOnly() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        String id = createTicket(customer, "gate-" + UUID.randomUUID()).get("id").asText();

        assertThat(get(customer, "/api/v1/admin/support/tickets/" + id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- telling the customer -----------------------------------------------------------------------

    @Test
    void resolving_withANote_emailsAVerifiedCustomerOnce_andRecordsWhatWasSent() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        JsonNode ticket = createTicket(customer, "resolve-" + UUID.randomUUID());
        String id = ticket.get("id").asText();

        JsonNode result = data(changeStatus(admin, id,
                "{\"status\":\"RESOLVED\",\"notifyCustomer\":true,\"message\":\"We re-read your statement.\\nIt is fixed.\"}"));

        assertThat(result.get("ticket").get("status").asText()).isEqualTo("RESOLVED");
        assertThat(result.get("customerEmailed").asBoolean()).isTrue();
        Notification queued = notificationRepository
                .findByNotificationKey("SUPPORT_TICKET_RESOLVED_" + id + ":EMAIL").orElseThrow();
        assertThat(queued.getUserId()).isEqualTo(customer.getId());
        assertThat(queued.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(queued.getType()).isEqualTo(NotificationType.SUPPORT_TICKET_RESOLVED);
        assertThat(queued.getTitle()).contains(ticket.get("ticketNumber").asText()).doesNotContain("{{");
        assertThat(queued.getParams()).containsEntry("message", "We re-read your statement.\nIt is fixed.");
        AuditLog audit = statusAudit(id).get(0);
        assertThat(audit.getMetadata()).containsEntry("customerEmailed", true)
                .containsEntry("messageToCustomer", "We re-read your statement.\nIt is fixed.");
    }

    @Test
    void closing_emailsWithTheClosedWording_andWithoutANoteToo() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "close-" + UUID.randomUUID()).get("id").asText();

        JsonNode result = data(changeStatus(admin, id, "{\"status\":\"CLOSED\",\"notifyCustomer\":true}"));

        assertThat(result.get("customerEmailed").asBoolean()).isTrue();
        Notification queued = notificationRepository
                .findByNotificationKey("SUPPORT_TICKET_CLOSED_" + id + ":EMAIL").orElseThrow();
        assertThat(queued.getType()).isEqualTo(NotificationType.SUPPORT_TICKET_CLOSED);
        assertThat(queued.getParams()).doesNotContainKey("message");
    }

    /** Same rule as the acknowledgement: an address nobody proved they own may belong to a
     *  stranger. The ticket still changes; the admin is told the email was not sent and why. */
    @Test
    void anUnverifiedCustomerIsNotEmailed_andTheAdminIsToldWhy() throws Exception {
        User customer = createUser("USER", "Asha Rao", false);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "unverified-" + UUID.randomUUID()).get("id").asText();

        JsonNode result = data(changeStatus(admin, id, "{\"status\":\"RESOLVED\",\"notifyCustomer\":true}"));

        assertThat(result.get("ticket").get("status").asText()).isEqualTo("RESOLVED");
        assertThat(result.get("customerEmailed").asBoolean()).isFalse();
        assertThat(result.get("notEmailedReason").asText()).contains("not verified");
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_RESOLVED_" + id + ":EMAIL")).isEmpty();
        assertThat(statusAudit(id).get(0).getMetadata()).containsEntry("customerEmailed", false);
    }

    @Test
    void choosingNotToTellTheCustomer_sendsNothing() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "silent-" + UUID.randomUUID()).get("id").asText();

        JsonNode result = data(changeStatus(admin, id, "{\"status\":\"CLOSED\",\"notifyCustomer\":false}"));

        assertThat(result.get("customerEmailed").asBoolean()).isFalse();
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_CLOSED_" + id + ":EMAIL")).isEmpty();
    }

    @Test
    void aStatusOnlyRequest_fromAnOlderClient_stillWorks_andSendsNothing() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "legacy-" + UUID.randomUUID()).get("id").asText();

        JsonNode result = data(changeStatus(admin, id, "{\"status\":\"RESOLVED\"}"));

        assertThat(result.get("ticket").get("status").asText()).isEqualTo("RESOLVED");
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_RESOLVED_" + id + ":EMAIL")).isEmpty();
    }

    @Test
    void aMessage_isOnlyForResolvingOrClosing_andHasALimit() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "limits-" + UUID.randomUUID()).get("id").asText();

        ResponseEntity<String> inProgress = changeStatus(admin, id,
                "{\"status\":\"IN_PROGRESS\",\"notifyCustomer\":true,\"message\":\"Hello\"}");
        ResponseEntity<String> tooLong = changeStatus(admin, id,
                "{\"status\":\"RESOLVED\",\"notifyCustomer\":true,\"message\":\"" + "x".repeat(2001) + "\"}");
        ResponseEntity<String> atTheLimit = changeStatus(admin, id,
                "{\"status\":\"RESOLVED\",\"notifyCustomer\":true,\"message\":\"" + "x".repeat(2000) + "\"}");

        assertThat(inProgress.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(atTheLimit.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The refused requests changed nothing.
        assertThat(statusAudit(id)).hasSize(1);
    }

    /** Two admins resolving the same ticket at once: one wins, the other is told it already moved
     *  (RESOLVED to RESOLVED is not a move), and the customer gets one email, not one per admin. */
    @Test
    void twoAdminsResolvingTheSameTicketAtOnce_oneWins_andTheCustomerIsEmailedOnce() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User first = createUser("ADMIN", "First Admin", false);
        User second = createUser("ADMIN", "Second Admin", false);
        String id = createTicket(customer, "race-" + UUID.randomUUID()).get("id").asText();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<String>>> results = new ArrayList<>();
        for (var call : List.<Callable<ResponseEntity<String>>>of(
                () -> { barrier.await(); return changeStatus(first, id, "{\"status\":\"RESOLVED\",\"notifyCustomer\":true}"); },
                () -> { barrier.await(); return changeStatus(second, id, "{\"status\":\"RESOLVED\",\"notifyCustomer\":true}"); })) {
            results.add(pool.submit(call));
        }
        List<HttpStatus> statuses = new ArrayList<>();
        for (var f : results) statuses.add(HttpStatus.valueOf(f.get().getStatusCode().value()));
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_RESOLVED_" + id + ":EMAIL")).isPresent();
        assertThat(statusAudit(id)).hasSize(1);
        assertThat(ticketRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
                .isEqualTo(SupportTicket.Status.RESOLVED);
    }

    /** RESOLVED may still move to CLOSED (SupportTicket.Status). Closing a resolved ticket tells the
     *  customer again only if the admin asks; by default the portal does not. */
    @Test
    void closingAResolvedTicket_emailsAgainOnlyWhenAsked() throws Exception {
        User customer = createUser("USER", "Asha Rao", true);
        User admin = createUser("ADMIN", "Ops Admin", false);
        String id = createTicket(customer, "resolve-then-close-" + UUID.randomUUID()).get("id").asText();
        data(changeStatus(admin, id, "{\"status\":\"RESOLVED\",\"notifyCustomer\":true}"));

        JsonNode closed = data(changeStatus(admin, id, "{\"status\":\"CLOSED\",\"notifyCustomer\":false}"));

        assertThat(closed.get("ticket").get("status").asText()).isEqualTo("CLOSED");
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_RESOLVED_" + id + ":EMAIL")).isPresent();
        assertThat(notificationRepository.findByNotificationKey("SUPPORT_TICKET_CLOSED_" + id + ":EMAIL")).isEmpty();
    }
}
