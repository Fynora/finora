package com.finora.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.SupportTicket;
import com.finora.entity.SuspensionReason;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SupportTicketRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AdminUserService;
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
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Privacy workstream 10 through the real security chain, over real HTTP with real rows: a suspended
 * customer who signs in is refused, but handed a token that reaches only the suspended screen's
 * routes (the reason, appeals and deletion requests, their data download) and nothing else.
 */
class SuspendedAccountIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Synthetic-Pass-9-Words";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private SupportTicketRepository ticketRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID adminId = UUID.randomUUID();

    private User customer(String scope) {
        User user = new User();
        user.setEmail("suspended-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setFullName("Suspension Fixture");
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ResponseEntity<String> call(String token, HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> signIn(User user, String scope) {
        return call(null, HttpMethod.POST, "/api/v1/auth/login",
                "{\"identifier\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\",\"scope\":\"" + scope + "\"}");
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    @Test
    void aSuspendedCustomer_getsATokenThatReachesOnlyTheSuspendedScreen() throws Exception {
        User user = customer(User.SCOPE_USER);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.FRAUD, "Chargebacks on synthetic cards.");

        ResponseEntity<String> refused = signIn(user, "USER");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode refusal = json(refused);
        assertThat(refusal.get("errorCode").asText()).isEqualTo("AUTH_018");
        assertThat(refusal.get("message").asText()).isEqualTo("This account has been suspended. Contact support for assistance.");
        assertThat(refusal.at("/data/token").isMissingNode() || refusal.at("/data/token").isNull()).isTrue();
        String token = refusal.at("/details/suspendedAccessToken").asText();
        assertThat(token).isNotBlank();

        // The suspended screen's own data.
        ResponseEntity<String> status = call(token, HttpMethod.GET, "/api/v1/users/me/suspension", null);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(status).get("data");
        assertThat(data.get("reason").asText()).isEqualTo("FRAUD");
        assertThat(data.get("reasonText").asText()).isEqualTo(SuspensionReason.FRAUD.customerText());
        assertThat(data.get("message").asText()).isEqualTo("Chargebacks on synthetic cards.");
        assertThat(data.get("suspendedAt").isNull()).isFalse();
        assertThat(data.get("signInMethod").asText()).isEqualTo("PASSWORD");
        assertThat(data.get("requests")).isEmpty();

        // Everything else is refused with the suspended code, including the self-service deletion
        // and password-change flows, and the ordinary profile.
        for (String path : new String[]{"/api/v1/users/me", "/api/v1/accounts", "/api/v1/transactions"}) {
            ResponseEntity<String> other = call(token, HttpMethod.GET, path, null);
            assertThat(other.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(json(other).get("errorCode").asText()).as(path).isEqualTo("AUTH_018");
        }
        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/password-change/start",
                "{\"currentPassword\":\"" + PASSWORD + "\"}").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Their data download still works.
        ResponseEntity<String> export = call(token, HttpMethod.POST, "/api/v1/users/me/data-export",
                "{\"currentPassword\":\"" + PASSWORD + "\"}");
        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);

        // An appeal, then the same again (refused while open), then a deletion request.
        ResponseEntity<String> appeal = call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"APPEAL\",\"text\":\"Those were my own cards.\"}");
        assertThat(appeal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(appeal).at("/data/requests/0/type").asText()).isEqualTo("APPEAL");
        assertThat(json(appeal).at("/data/requests/0/status").asText()).isEqualTo("OPEN");
        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"APPEAL\",\"text\":\"Again.\"}").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"DELETION\"}").getStatusCode()).isEqualTo(HttpStatus.OK);

        var tickets = ticketRepository.findByUserIdAndCategoryInOrderByCreatedAtDesc(user.getId(),
                java.util.EnumSet.of(SupportTicket.Category.SUSPENSION_APPEAL, SupportTicket.Category.SUSPENSION_DELETION_REQUEST));
        assertThat(tickets).extracting(SupportTicket::getCategory).containsExactlyInAnyOrder(
                SupportTicket.Category.SUSPENSION_APPEAL, SupportTicket.Category.SUSPENSION_DELETION_REQUEST);
        assertThat(tickets).filteredOn(t -> t.getCategory() == SupportTicket.Category.SUSPENSION_APPEAL)
                .first().satisfies(t -> assertThat(t.getDescription()).contains("Those were my own cards.", "FRAUD"));
    }

    /** The phone-verification gate refuses an unverified account almost everywhere. A suspended
     *  one must still reach its suspended screen, and is confined there instead. */
    @Test
    void aSuspendedCustomerWithAnUnverifiedPhone_stillReachesTheSuspendedScreen() throws Exception {
        User user = customer(User.SCOPE_USER);
        user.setPhoneVerified(false);
        userRepository.save(user);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.TERMS_VIOLATION, null);
        String token = json(signIn(user, "USER")).at("/details/suspendedAccessToken").asText();

        assertThat(call(token, HttpMethod.GET, "/api/v1/users/me/suspension", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> elsewhere = call(token, HttpMethod.GET, "/api/v1/accounts", null);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(elsewhere).get("errorCode").asText()).isEqualTo("AUTH_018");
    }

    @Test
    void anAppealNeedsText() {
        User user = customer(User.SCOPE_USER);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.TERMS_VIOLATION, null);
        String token = TestSessions.accessTokenFor(jwtService, refreshTokens, user);

        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"APPEAL\",\"text\":\"   \"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"SOMETHING\"}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** The suspension revokes every session, so a token issued before it stops working at once. */
    @Test
    void aSessionOpenBeforeTheSuspension_stopsWorking() {
        User user = customer(User.SCOPE_USER);
        String earlier = TestSessions.accessTokenFor(jwtService, refreshTokens, user);
        assertThat(call(earlier, HttpMethod.GET, "/api/v1/users/me", null).getStatusCode()).isEqualTo(HttpStatus.OK);

        adminUserService.suspend(user.getId(), adminId, SuspensionReason.SECURITY_RISK, null);

        assertThat(call(earlier, HttpMethod.GET, "/api/v1/users/me", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Confinement does not depend on how the token was issued: any live session of a suspended
     *  account is confined, because the principal is marked from the user row on every request. */
    @Test
    void anyLiveSessionOfASuspendedAccount_isConfined() throws Exception {
        User user = customer(User.SCOPE_USER);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.SECURITY_RISK, null);
        String token = TestSessions.accessTokenFor(jwtService, refreshTokens, user);

        ResponseEntity<String> profile = call(token, HttpMethod.GET, "/api/v1/users/me", null);
        assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(profile).get("errorCode").asText()).isEqualTo("AUTH_018");
        assertThat(call(token, HttpMethod.GET, "/api/v1/users/me/suspension", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anActiveCustomer_hasNoSuspension_andCannotRaiseSuspensionTickets() {
        User user = customer(User.SCOPE_USER);
        String token = TestSessions.accessTokenFor(jwtService, refreshTokens, user);

        assertThat(call(token, HttpMethod.GET, "/api/v1/users/me/suspension", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(token, HttpMethod.POST, "/api/v1/users/me/suspension/requests",
                "{\"type\":\"APPEAL\",\"text\":\"x\"}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void liftingTheSuspension_restoresSignIn_andEndsTheSuspendedScreen() {
        User user = customer(User.SCOPE_USER);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.TERMS_VIOLATION, null);
        adminUserService.reactivate(user.getId(), adminId, null);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getSuspensionReason()).isNull();
        assertThat(reloaded.getSuspendedAt()).isNull();
        ResponseEntity<String> signedIn = signIn(user, "USER");
        assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A suspended admin account gets the refusal with no token: the suspended screen is for customers. */
    @Test
    void aSuspendedAdmin_getsNoToken() throws Exception {
        User admin = customer(User.SCOPE_ADMIN);
        admin.setRole("ADMIN");
        userRepository.save(admin);
        adminUserService.suspend(admin.getId(), adminId, SuspensionReason.SECURITY_RISK, null);

        ResponseEntity<String> refused = signIn(admin, "ADMIN");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(refused).get("errorCode").asText()).isEqualTo("AUTH_018");
        assertThat(json(refused).at("/details/suspendedAccessToken").isMissingNode()).isTrue();
    }

    @Test
    void theSuspendedSignIn_isAudited() {
        User user = customer(User.SCOPE_USER);
        adminUserService.suspend(user.getId(), adminId, SuspensionReason.FRAUD, null);

        signIn(user, "USER");

        assertThat(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(user.getId()))
                .extracting(com.finora.entity.AuditLog::getAction)
                .contains("ACCOUNT_SUSPENDED", "SUSPENDED_ACCOUNT_SIGN_IN");
    }
}
