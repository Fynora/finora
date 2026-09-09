package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AuthService;
import com.finora.service.ReferralService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end -- proves REFERRAL_MANAGEMENT_VIEW/_MANAGE gate separately (V101), and the full
 *  lifecycle against a real database: a referral code redeemed at registration (REGISTERED), a
 *  real webhook-driven charge advancing it (SUBSCRIBED), and an admin credit finishing it
 *  (REWARDED, with the wallet balance actually moving). */
class AdminReferralControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ReferralService referralService;
    @Autowired private AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-referral-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Referral IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromListingReferrals() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromCreditingAReward() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals/" + UUID.randomUUID() + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "reason", "test"), bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creditingABareRegisteredReferral_isRejectedWithConflict() throws Exception {
        User referrer = createUser("USER");
        String code = referralService.myCode(referrer.getId());
        String referredEmail = "referred-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(referredEmail, "Password123", "Referred Person",
                "+919876500778" /* synthetic-ok */, code));
        User referred = userRepository.findByEmailIgnoreCaseAndAccountScope(referredEmail, User.SCOPE_USER)
                .orElseThrow();
        User admin = createUser("ADMIN");

        ResponseEntity<String> list = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        UUID referralId = UUID.fromString(findRow(list, referred.getId()).get("referralId").asText());

        ResponseEntity<String> creditResponse = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "too early"), bearerFor(admin)), String.class);

        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fullLifecycle_registrationToSubscriptionToCreditedReward_movesTheWalletBalance() throws Exception {
        User referrer = createUser("USER");
        String code = referralService.myCode(referrer.getId());

        String referredEmail = "referred-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(referredEmail, "Password123", "Referred Person",
                "+919876500777" /* synthetic-ok */, code));
        User referred = userRepository.findByEmailIgnoreCaseAndAccountScope(referredEmail, User.SCOPE_USER)
                .orElseThrow();

        User admin = createUser("ADMIN");

        ResponseEntity<String> afterRegister = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        JsonNode row = findRow(afterRegister, referred.getId());
        assertThat(row.get("status").asText()).isEqualTo("REGISTERED");
        UUID referralId = UUID.fromString(row.get("referralId").asText());

        // The real automatic trigger (Task 3), not a direct service call -- proves the actual
        // webhook wiring, not just ReferralService in isolation.
        referralService.onPlanChanged(referred.getId(), "PLUS");

        ResponseEntity<String> afterSubscribe = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(findRow(afterSubscribe, referred.getId()).get("status").asText()).isEqualTo("SUBSCRIBED");

        ResponseEntity<String> creditResponse = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "successful referral"), bearerFor(admin)), String.class);
        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> mineResponse = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(referrer)), String.class);
        JsonNode mine = mapper.readTree(mineResponse.getBody()).get("data");
        assertThat(mine.get("walletBalance").asDouble()).isEqualTo(250.0);
        assertThat(mine.get("referrals").get(0).get("status").asText()).isEqualTo("REWARDED");

        ResponseEntity<String> secondCredit = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "duplicate attempt"), bearerFor(admin)), String.class);
        assertThat(secondCredit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private JsonNode findRow(ResponseEntity<String> response, UUID referredUserId) throws Exception {
        JsonNode data = mapper.readTree(response.getBody()).get("data").get("content");
        for (JsonNode row : data) {
            if (row.get("referredUserId").asText().equals(referredUserId.toString())) return row;
        }
        throw new AssertionError("No referral row found for referredUserId=" + referredUserId);
    }
}
