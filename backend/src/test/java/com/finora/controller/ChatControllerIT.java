package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
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
 * Fyn Phase 4's {@code POST /api/v1/fyn/chat} entitlement gate -- same pattern as {@code
 * InsightsControllerIT} (Phase 3): {@code FYN_CHAT} has no seed migration yet (plan §7 item 1),
 * so the "entitled" case inserts the grant row directly. Once granted, the endpoint still fails --
 * with 503, not 403 -- because no {@code ANTHROPIC_API_KEY} is configured under test, proving the
 * entitlement check ran and passed before anything else was attempted.
 */
class ChatControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private FeatureEntitlementRepository featureEntitlementRepository;
    @Autowired private PlanRepository planRepository;
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

    @Test
    void aFreeUser_isDeniedChat_withTheEntitlementErrorCode() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = postChat(user, "what's my balance?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_001");
    }

    @Test
    void grantingFynChatClearsTheEntitlementGate_thenFailsClosedOnAvailability() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plan = planRepository.findByCode("PLUS").orElseThrow();
        FeatureEntitlement grant = new FeatureEntitlement();
        grant.setPlanId(plan.getId());
        grant.setFeatureKey(FeatureEntitlement.FYN_CHAT);
        grant.setEnabled(true);
        featureEntitlementRepository.save(grant);
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        ResponseEntity<String> response = postChat(user, "what's my balance?");

        // Not FORBIDDEN: the entitlement check passed. Not OK either -- no ANTHROPIC_API_KEY is
        // configured under test, so FynAvailabilityGuard refuses, correctly, before any Anthropic
        // call is attempted.
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
