package com.finora.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.api.DeviceTokenService;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The admin push campaign API over HTTP: gated by its own {@code PUSH_CAMPAIGN_MANAGE} through real
 * method security (a plain user and an admin role that lacks the permission both get 403), request
 * validation answers 400 instead of a 500, and the whole lifecycle works end to end.
 */
class AdminPushCampaignControllerIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/push-campaigns";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private DeviceTokenService deviceTokenService;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        clean();
        jdbc.update("UPDATE device_tokens SET revoked_at = now() WHERE revoked_at IS NULL");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM notification_logs WHERE notification_id IN "
                + "(SELECT id FROM notifications WHERE type = 'CUSTOM_PUSH')");
        jdbc.update("DELETE FROM notifications WHERE type = 'CUSTOM_PUSH'");
        jdbc.update("DELETE FROM custom_push_daily_cap");
        jdbc.update("DELETE FROM push_campaign_runs");
        jdbc.update("DELETE FROM push_campaigns");
    }

    private User createUser(String role, String scope) {
        User user = new User();
        user.setEmail("push-campaign-http-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Push Campaign HTTP IT User");
        user.setRole(role);
        user.setAccountScope(scope);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User admin() {
        return createUser("ADMIN", User.SCOPE_ADMIN);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> call(User as, HttpMethod method, String path, String body) {
        return restTemplate.exchange(BASE + path, method, new HttpEntity<>(body, bearerFor(as)), String.class);
    }

    private static String nowOnlyJson(String title) {
        return """
                {"name":"HTTP IT","title":"%s","message":"Upload your statement.",
                 "audienceType":"ALL_WITH_DEVICE","scheduleKind":"NOW_ONLY"}
                """.formatted(title);
    }

    private static String dailyJson(String time) {
        return """
                {"name":"HTTP daily","title":"Daily","message":"Upload your statement.",
                 "audienceType":"NO_STATEMENT_UPLOADED","scheduleKind":"DAILY_AT","sendTimeIst":"%s"}
                """.formatted(time);
    }

    private String createCampaign(User as, String json) throws Exception {
        ResponseEntity<String> created = call(as, HttpMethod.POST, "", json);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(created.getBody()).path("data").path("id").asText();
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void aPlainUserIsForbiddenFromEveryEndpoint() {
        User user = createUser("USER", User.SCOPE_USER);
        UUID id = UUID.randomUUID();
        for (String[] endpoint : new String[][] {
                {"GET", ""}, {"GET", "/" + id}, {"GET", "/audience-count?audienceType=ALL_WITH_DEVICE"},
                {"POST", ""}, {"PUT", "/" + id}, {"POST", "/" + id + "/clone"},
                {"POST", "/" + id + "/send-test"}, {"POST", "/" + id + "/send-now"},
                {"POST", "/" + id + "/start"}, {"POST", "/" + id + "/pause"},
                {"POST", "/" + id + "/resume"}, {"POST", "/" + id + "/stop"},
                {"POST", "/" + id + "/cancel-sending"}}) {
            // A VALID body: Spring validates @Valid arguments before method security runs, so an
            // invalid body would be answered 400 before authorization is reached (true of every
            // admin controller here, and no data is exposed by it).
            String body = endpoint[1].endsWith("/send-test") ? "{\"userId\":\"" + id + "\"}" : nowOnlyJson("t");
            ResponseEntity<String> response = call(user, HttpMethod.valueOf(endpoint[0]), endpoint[1], body);
            assertThat(response.getStatusCode()).as(endpoint[0] + " " + endpoint[1])
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void anAdminRoleWithoutThePermissionIsForbidden() {
        // SUPPORT is a real admin-portal role that was never granted PUSH_CAMPAIGN_MANAGE.
        User support = createUser("SUPPORT", User.SCOPE_ADMIN);
        assertThat(call(support, HttpMethod.GET, "", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(support, HttpMethod.POST, "", nowOnlyJson("t")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(support, HttpMethod.GET, "/audience-count?audienceType=ALL_WITH_DEVICE", null)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bothAdminAndSuperAdminHoldThePermission() {
        assertThat(call(createUser("ADMIN", User.SCOPE_ADMIN), HttpMethod.GET, "", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(createUser("SUPER_ADMIN", User.SCOPE_ADMIN), HttpMethod.GET, "", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAnonymousCallerIsRejected() {
        ResponseEntity<String> response = restTemplate.getForEntity(BASE, String.class);
        assertThat(response.getStatusCode().value()).isIn(401, 403);
    }

    // ------------------------------------------------------------------ validation answers 400

    @Test
    void invalidBodiesAreRejectedWithA400NotA500() {
        User admin = admin();
        // blank title, over-long title, missing audience, unknown enum, malformed JSON
        assertThat(call(admin, HttpMethod.POST, "", nowOnlyJson("")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "", nowOnlyJson("x".repeat(81))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "", """
                {"name":"n","title":"t","message":"m","scheduleKind":"NOW_ONLY"}""").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "", """
                {"name":"n","title":"t","message":"m","audienceType":"EVERYONE","scheduleKind":"NOW_ONLY"}""")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(admin, HttpMethod.POST, "", "{not json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Quiet hours are a service rule, still a 400.
        assertThat(call(admin, HttpMethod.POST, "", dailyJson("03:00")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Unknown campaign is a 404; a test with no target is a 400.
        assertThat(call(admin, HttpMethod.GET, "/" + UUID.randomUUID(), null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAudienceCountForAnUnknownAudienceIsA400() {
        assertThat(call(admin(), HttpMethod.GET, "/audience-count?audienceType=NOPE", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ the lifecycle over HTTP

    @Test
    void createListGetStartPauseResumeStopCloneOverHttp() throws Exception {
        User admin = admin();
        String id = createCampaign(admin, dailyJson("19:00"));

        JsonNode list = mapper.readTree(call(admin, HttpMethod.GET, "", null).getBody()).path("data");
        assertThat(list.findValuesAsText("id")).contains(id);

        JsonNode detail = mapper.readTree(call(admin, HttpMethod.GET, "/" + id, null).getBody()).path("data");
        assertThat(detail.path("campaign").path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.path("campaign").path("sendTimeIst").asText()).startsWith("19:00");
        assertThat(detail.path("runs").size()).isZero();

        assertThat(status(admin, "/" + id + "/start")).isEqualTo("ACTIVE");
        assertThat(status(admin, "/" + id + "/pause")).isEqualTo("PAUSED");
        assertThat(status(admin, "/" + id + "/resume")).isEqualTo("ACTIVE");
        assertThat(status(admin, "/" + id + "/stop")).isEqualTo("STOPPED");

        // An illegal move is a 409, not a 500.
        assertThat(call(admin, HttpMethod.POST, "/" + id + "/pause", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> clone = call(admin, HttpMethod.POST, "/" + id + "/clone", null);
        assertThat(clone.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mapper.readTree(clone.getBody()).path("data").path("status").asText()).isEqualTo("DRAFT");
    }

    private String status(User admin, String path) throws Exception {
        ResponseEntity<String> response = call(admin, HttpMethod.POST, path, null);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).path("data").path("status").asText();
    }

    @Test
    void audienceCountSendTestAndSendNowOverHttp() throws Exception {
        User admin = admin();
        User phoneUser = createUser("USER", User.SCOPE_USER);
        deviceTokenService.register(phoneUser.getId(), "IOS", "http-it-token-" + UUID.randomUUID());
        String id = createCampaign(admin, nowOnlyJson("Fynora is waiting"));

        JsonNode count = mapper.readTree(
                call(admin, HttpMethod.GET, "/audience-count?audienceType=ALL_WITH_DEVICE", null).getBody())
                .path("data");
        assertThat(count.path("count").asLong()).isEqualTo(1);
        assertThat(count.path("rolloutLimit").asLong()).isPositive();

        ResponseEntity<String> test = call(admin, HttpMethod.POST, "/" + id + "/send-test",
                "{\"userId\":\"" + phoneUser.getId() + "\"}");
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(test.getBody()).path("data").path("queued").asBoolean()).isTrue();

        // A test body naming nobody is a 400.
        assertThat(call(admin, HttpMethod.POST, "/" + id + "/send-test", "{}").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> sendNow = call(admin, HttpMethod.POST, "/" + id + "/send-now", null);
        assertThat(sendNow.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode run = mapper.readTree(sendNow.getBody()).path("data");
        assertThat(run.path("status").asText()).isEqualTo("RUNNING");
        assertThat(run.path("titleSnapshot").asText()).isEqualTo("Fynora is waiting");

        // The campaign is finished after a one-off send now, so a second one is a 409.
        assertThat(call(admin, HttpMethod.POST, "/" + id + "/send-now", null).getStatusCode())
                .isIn(HttpStatus.CONFLICT);
    }

    @Test
    void cancelSendingOverHttpWithdrawsQueuedPushesAndReportsHowMany() throws Exception {
        User admin = admin();
        User phoneUser = createUser("USER", User.SCOPE_USER);
        deviceTokenService.register(phoneUser.getId(), "ANDROID", "http-cancel-token-" + UUID.randomUUID());
        String id = createCampaign(admin, nowOnlyJson("Oops"));
        ResponseEntity<String> sendNow = call(admin, HttpMethod.POST, "/" + id + "/send-now", null);
        assertThat(sendNow.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        waitForQueuedRow(phoneUser.getId());

        ResponseEntity<String> cancel = call(admin, HttpMethod.POST, "/" + id + "/cancel-sending", null);

        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(cancel.getBody()).path("data");
        assertThat(data.path("cancelledPushes").asInt()).isEqualTo(1);
        assertThat(data.path("releasedSlots").asInt()).isEqualTo(1);
        // A second call is harmless, and an unknown campaign is a 404.
        assertThat(mapper.readTree(call(admin, HttpMethod.POST, "/" + id + "/cancel-sending", null).getBody())
                .path("data").path("cancelledPushes").asInt()).isZero();
        assertThat(call(admin, HttpMethod.POST, "/" + UUID.randomUUID() + "/cancel-sending", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** send-now returns before the run has queued anyone; wait for its row to appear. */
    private void waitForQueuedRow(UUID userId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'CUSTOM_PUSH' "
                    + "AND user_id = ? AND status = 'QUEUED'", Integer.class, userId);
            if (n != null && n > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the run never queued the push");
    }

    @Test
    void theResponseCarriesNoDeviceTokenOrEmail() throws Exception {
        User admin = admin();
        User phoneUser = createUser("USER", User.SCOPE_USER);
        String rawToken = "http-it-secret-token-" + UUID.randomUUID();
        deviceTokenService.register(phoneUser.getId(), "ANDROID", rawToken);
        String id = createCampaign(admin, nowOnlyJson("Hello"));
        call(admin, HttpMethod.POST, "/" + id + "/send-test", "{\"userId\":\"" + phoneUser.getId() + "\"}");

        String body = call(admin, HttpMethod.GET, "/" + id, null).getBody();
        assertThat(body).doesNotContain(rawToken).doesNotContain(phoneUser.getEmail());
    }
}
