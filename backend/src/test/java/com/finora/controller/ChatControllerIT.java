package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.ChatConversation;
import com.finora.entity.ChatMessage;
import com.finora.entity.User;
import com.finora.repository.ChatConversationRepository;
import com.finora.repository.ChatMessageRepository;
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
    @Autowired private ChatConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository messageRepository;
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

    /** Probe: what actually happens when the "image" multipart part is omitted entirely (a
     *  malformed/broken client request, not something the real FynWidget UI can produce, but
     *  network middleboxes and hand-rolled API clients aren't bound by that). Not guessed at --
     *  GlobalExceptionHandler.handleBindingFailure only maps MissingServletRequestParameterException,
     *  and Spring throws the different MissingServletRequestPartException for an absent
     *  MultipartFile @RequestParam, so this needed checking, not assuming. */
    @Test
    void screenshotEndpoint_aRequestWithNoImagePart_isRejectedCleanly() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("message", "what's this?");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/fyn/chat/screenshot", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> getHistory(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return restTemplate.exchange("/api/v1/fyn/chat/history", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }

    /** {@code updatedAt} is a plain Java-side {@code Instant.now()} default (see the entity), not
     *  DB-generated -- two conversations created back-to-back within one test method could land on
     *  the same millisecond, making findByUserIdOrderByUpdatedAtDesc's ordering non-deterministic.
     *  Explicit, well-separated timestamps make "most recent" unambiguous, same reasoning as
     *  AiAuditLogRepositoryIT/ChatMessageRepositoryIT's own explicit-timestamp fixtures. */
    private ChatConversation newConversation(UUID userId, java.time.Instant updatedAt) {
        ChatConversation conversation = new ChatConversation();
        conversation.setUserId(userId);
        conversation.setUpdatedAt(updatedAt);
        return conversationRepository.save(conversation);
    }

    private void saveMessage(UUID conversationId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageRepository.save(message);
    }

    /** GET /chat/history needs entitlement like every other Fyn endpoint, but -- unlike POST
     *  /chat -- no ANTHROPIC_API_KEY, so this is real evidence the endpoint doesn't call through
     *  to FynAvailabilityGuard at all: it returns 200 with real data even in an environment where
     *  every other Fyn endpoint fails closed with 503. */
    @Test
    void historyEndpoint_returnsTheMostRecentConversationInFull_withNoAvailabilityCheck() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        java.time.Instant now = java.time.Instant.now();
        ChatConversation older = newConversation(user.getId(), now.minusSeconds(3600));
        saveMessage(older.getId(), ChatMessage.ROLE_USER, "old question");
        saveMessage(older.getId(), ChatMessage.ROLE_ASSISTANT, "old answer");
        ChatConversation newer = newConversation(user.getId(), now);
        saveMessage(newer.getId(), ChatMessage.ROLE_USER, "what's my balance?");
        saveMessage(newer.getId(), ChatMessage.ROLE_ASSISTANT, "Your balance is ₹50,000.");

        ResponseEntity<String> response = getHistory(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("conversationId").asText()).isEqualTo(newer.getId().toString());
        JsonNode turns = data.get("turns");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).get("role").asText()).isEqualTo("user");
        assertThat(turns.get(0).get("content").asText()).isEqualTo("what's my balance?");
        assertThat(turns.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(turns.get(1).get("content").asText()).isEqualTo("Your balance is ₹50,000.");
    }

    @Test
    void historyEndpoint_returnsANullConversationIdAndNoTurns_forAUserWhoHasNeverChatted() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = getHistory(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("conversationId").isNull()).isTrue();
        assertThat(data.get("turns")).isEmpty();
    }

    @Test
    void historyEndpoint_deniesAUserWithNoSubscriptionAtAll_withTheEntitlementErrorCode() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = getHistory(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("ENTITLEMENT_001");
    }

    private ResponseEntity<String> patchFeedback(User user, UUID messageId, String feedback) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/fyn/chat/messages/" + messageId + "/feedback", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("feedback", feedback), headers), String.class);
    }

    @Test
    void feedbackEndpoint_savesHelpfulOnTheCallersOwnAssistantMessage() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        ChatConversation conversation = newConversation(user.getId(), java.time.Instant.now());
        saveMessage(conversation.getId(), ChatMessage.ROLE_USER, "hi");
        saveMessage(conversation.getId(), ChatMessage.ROLE_ASSISTANT, "hello");
        UUID assistantMessageId = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .get(1).getId();

        ResponseEntity<String> response = patchFeedback(user, assistantMessageId, "HELPFUL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(messageRepository.findById(assistantMessageId).orElseThrow().getFeedback()).isEqualTo("HELPFUL");
    }

    @Test
    void feedbackEndpoint_deniesFeedbackOnAnotherUsersMessage_withNotFound() throws Exception {
        User owner = createUser();
        subscriptionService.provisionFreeSubscription(owner.getId());
        User attacker = createUser();
        subscriptionService.provisionFreeSubscription(attacker.getId());
        ChatConversation conversation = newConversation(owner.getId(), java.time.Instant.now());
        saveMessage(conversation.getId(), ChatMessage.ROLE_ASSISTANT, "hello");
        UUID assistantMessageId = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .get(0).getId();

        ResponseEntity<String> response = patchFeedback(attacker, assistantMessageId, "HELPFUL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(messageRepository.findById(assistantMessageId).orElseThrow().getFeedback()).isNull();
    }

    @Test
    void feedbackEndpoint_rejectsAnUnrecognizedValue() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        ChatConversation conversation = newConversation(user.getId(), java.time.Instant.now());
        saveMessage(conversation.getId(), ChatMessage.ROLE_ASSISTANT, "hello");
        UUID assistantMessageId = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .get(0).getId();

        ResponseEntity<String> response = patchFeedback(user, assistantMessageId, "LOVE_IT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
