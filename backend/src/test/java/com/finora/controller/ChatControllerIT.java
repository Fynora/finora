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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
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

    /** {@code contentType} is set per-part (wrapped in its own {@code HttpEntity} -- {@code
     *  LinkedMultiValueMap} has no per-part header API otherwise), since that's what {@code
     *  MultipartFile.getContentType()} reads server-side, and validating it is the whole point of
     *  several tests below. */
    private ResponseEntity<String> postScreenshot(User user, byte[] imageBytes, String contentType, String message) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpHeaders imagePartHeaders = new HttpHeaders();
        if (contentType != null) {
            imagePartHeaders.setContentType(MediaType.parseMediaType(contentType));
        }
        ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
            @Override public String getFilename() { return "screenshot.png"; }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new HttpEntity<>(imageResource, imagePartHeaders));
        if (message != null) {
            body.add("message", message);
        }
        return restTemplate.exchange("/api/v1/fyn/chat/screenshot", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
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

    /** Same entitlement gate as {@code /chat} -- a user with no subscription row at all is denied
     *  before the multipart body is even looked at. */
    @Test
    void screenshotEndpoint_deniesAUserWithNoSubscriptionAtAll_withTheEntitlementErrorCode() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = postScreenshot(user, new byte[]{1, 2, 3}, "image/png", "what's this?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_001");
    }

    /** A wrong file type always comes back as "fix your upload," even in this test environment
     *  which has no tesseract binary -- ChatController validates the upload shape before checking
     *  whether OCR itself is available, precisely so these two failure reasons don't collapse into
     *  the same response (see ChatController's own comment on this ordering). */
    @Test
    void screenshotEndpoint_rejectsAWrongFileType_regardlessOfOcrAvailability() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = postScreenshot(user, "not an image".getBytes(StandardCharsets.UTF_8),
                "text/plain", "what's this?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A validly-shaped upload passes entitlement and file-type validation, then fails closed on
     *  availability -- either because this test environment has no tesseract binary (CI) or,
     *  where it does, because no ANTHROPIC_API_KEY is configured (same as every other Fyn IT).
     *  Both reasons map to the same 503, which is the actual contract this asserts: a well-formed
     *  screenshot request never succeeds in an environment where Fyn itself can't actually run. */
    @Test
    void screenshotEndpoint_aWellFormedUpload_failsClosedOnAvailability() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = postScreenshot(user, new byte[]{(byte) 0x89, 'P', 'N', 'G'},
                "image/png", "what's this?");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
