package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fyn Phase 4's {@code POST /api/v1/fyn/chat} entitlement gate. Since V205 (the 2026-09-14 costing
 * decision), {@code FYN_CHAT} is seeded to every plan including Free -- Free is rationed by
 * {@code FynChatOrchestrationService.freeDailyQuestionLimitReached} instead of gated out entirely,
 * so there is no real-world "a normal Free user is denied chat outright" case left to test here;
 * that would need a user with no subscription row at all. Every plan that DOES pass the
 * entitlement check still fails closed on availability -- with 503, not 403 -- because no {@code
 * ANTHROPIC_API_KEY} is configured under test, proving the entitlement check ran and passed before
 * anything else was attempted. (The daily-count cap itself is covered at the unit level in
 * FynChatOrchestrationServiceTest -- exercising it here would need a real Anthropic call to
 * actually complete and persist, which this environment can't make.)
 */
class ChatControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("chat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Chat IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> postChat(User user, String message) {
        return restTemplate.exchange("/api/v1/fyn/chat", HttpMethod.POST,
                new HttpEntity<>(Map.of("message", message), bearerFor(user)), String.class);
    }

    /** A user who was never provisioned any subscription at all (not even Free) has no row for
     *  {@code EntitlementService.hasEntitlement} to find -- the one remaining way to fail
     *  ChatController's entitlement check now that every real plan grants FYN_CHAT (V205). Not a
     *  reachable state for a normal signed-up user (provisioning Free happens at signup), but the
     *  fail-closed contract still needs to hold for it. */
    @Test
    void aUserWithNoSubscriptionAtAll_isDeniedChat_withTheEntitlementErrorCode() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = postChat(user, "what's my balance?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_001");
    }

    @Test
    void aFreeUserNowPassesTheEntitlementGate_thenFailsClosedOnAvailability() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = postChat(user, "what's my balance?");

        // Not FORBIDDEN: V205 seeds FYN_CHAT to Free too, so the entitlement check passed. Not OK
        // either -- no ANTHROPIC_API_KEY is configured under test, so FynAvailabilityGuard refuses,
        // correctly, before any Anthropic call is attempted.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aPlusUserAlsoPassesTheEntitlementGate_thenFailsClosedOnAvailability() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        ResponseEntity<String> response = postChat(user, "what's my balance?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aBlankMessageIsRejectedByValidationBeforeAnyEntitlementOrAvailabilityCheck() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = postChat(user, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
