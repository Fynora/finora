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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fyn Phase 3's {@code /api/v1/insights/narration} entitlement gate -- same pattern as {@code
 * AnalyticsControllerIT}, against real Plan/Subscription/FeatureEntitlement rows, not mocks.
 *
 * <p>{@code FYN_INSIGHTS} has no seed migration yet (plan §7 item 1 -- pricing undecided), so
 * unlike {@code ADVANCED_REPORTS}, no real plan grants it today. The "entitled" case here inserts
 * the grant row directly rather than upgrading to a real plan, proving the gate opens correctly
 * once granted without waiting on a pricing decision this test has no business making.
 *
 * <p>Once granted, the endpoint still fails -- with 503, not 403 -- because no {@code
 * ANTHROPIC_API_KEY} is configured under test. That is the correct, expected failure mode
 * ({@code FynAvailabilityGuard} failing closed), and distinguishing 403 (denied by entitlement)
 * from 503 (denied by availability) is exactly the proof that the entitlement check ran and
 * passed before anything else was attempted.
 */
class InsightsControllerIT extends AbstractIntegrationTest {

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
        user.setEmail("insights-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Insights IT Test User");
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

    private ResponseEntity<String> get(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void aFreeUser_isDeniedNarration_withTheEntitlementErrorCode() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/insights/narration", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_001");
    }

    @Test
    void grantingFynInsightsClearsTheEntitlementGate_thenFailsClosedOnAvailability() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        Plan plan = planRepository.findByCode("PLUS").orElseThrow();
        FeatureEntitlement grant = new FeatureEntitlement();
        grant.setPlanId(plan.getId());
        grant.setFeatureKey(FeatureEntitlement.FYN_INSIGHTS);
        grant.setEnabled(true);
        featureEntitlementRepository.save(grant);
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        ResponseEntity<String> response = get("/api/v1/insights/narration", user);

        // Not FORBIDDEN: the entitlement check passed. Not OK either -- no ANTHROPIC_API_KEY is
        // configured under test, so FynAvailabilityGuard refuses, correctly, before any Anthropic
        // call is attempted.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void theNumericInsightsEndpointIsUnaffectedByTheNarrationGate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/insights", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
