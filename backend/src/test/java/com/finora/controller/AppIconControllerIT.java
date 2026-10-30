package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AppIconSchedule;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AppIconScheduleRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AppIconWindowSource;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The festival app icon schedule end to end: real HTTP through the security chain, real Postgres
 * (V299's exclusion constraint) and real Redis (the public endpoint's cache).
 *
 * <p>app_icon_schedule is one global table that only this class writes, so every test starts and
 * ends with it empty and the cache evicted -- a row left behind would change the public answer for
 * whichever test runs next.
 */
class AppIconControllerIT extends AbstractIntegrationTest {

    private static final String PUBLIC = "/api/v1/app/icon";
    private static final String SCHEDULE = "/api/v1/admin/app-icon/schedule";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AppIconScheduleRepository scheduleRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppIconWindowSource windowSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void emptySchedule() {
        jdbcTemplate.update("DELETE FROM app_icon_schedule");
        windowSource.evict();
    }

    private User createUser(String role, boolean phoneVerified) {
        User user = new User();
        user.setEmail("app-icon-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("App Icon IT User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(phoneVerified);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String body(String icon, String label, Instant start, Instant end, Long expectedVersion) {
        return "{\"icon\":\"" + icon + "\",\"label\":\"" + label + "\",\"startsAt\":\"" + start
                + "\",\"endsAt\":\"" + end + "\""
                + (expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion) + "}";
    }

    private ResponseEntity<String> anonymousIcon() {
        return restTemplate.getForEntity(PUBLIC, String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    private ResponseEntity<String> create(HttpHeaders admin, String json) {
        return restTemplate.exchange(SCHEDULE, HttpMethod.POST, new HttpEntity<>(json, admin), String.class);
    }

    // --- public endpoint ---

    @Test
    void anonymousCaller_getsTheDefaultIcon_withNoUntil_andACacheHeader() throws Exception {
        ResponseEntity<String> response = anonymousIcon();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = data(response);
        assertThat(data.get("icon").asText()).isEqualTo("default");
        assertThat(data.has("until")).as("the default icon has no end, so `until` is left out").isFalse();
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=60").contains("public");
    }

    @Test
    void anInvalidBearerToken_doesNotBlockThePublicAnswer() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not-a-real-token");
        ResponseEntity<String> response = restTemplate.exchange(PUBLIC, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response).get("icon").asText()).isEqualTo("default");
    }

    @Test
    void aSignedInUserWithNoVerifiedPhone_stillGetsTheAnswer() throws Exception {
        User unverified = createUser("USER", false);
        ResponseEntity<String> response = restTemplate.exchange(PUBLIC, HttpMethod.GET,
                new HttpEntity<>(bearerFor(unverified)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response).get("icon").asText()).isEqualTo("default");
    }

    @Test
    void otherMethodsOnThePublicPath_stayClosedToAnonymousCallers() {
        ResponseEntity<String> response = restTemplate.exchange(PUBLIC, HttpMethod.POST,
                new HttpEntity<>("{}", new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- admin access ---

    @Test
    void anonymousCaller_isUnauthorizedOnTheAdminSchedule() {
        ResponseEntity<String> response = restTemplate.getForEntity(SCHEDULE, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void plainUser_isForbiddenFromEveryAdminEndpoint() {
        HttpHeaders user = bearerFor(createUser("USER", true));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        String json = body("diwali", "Diwali", start, start.plus(1, ChronoUnit.DAYS), null);

        assertThat(restTemplate.exchange("/api/v1/admin/app-icon/icons", HttpMethod.GET, new HttpEntity<>(user), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/v1/admin/app-icon/current", HttpMethod.GET, new HttpEntity<>(user), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(SCHEDULE, HttpMethod.GET, new HttpEntity<>(user), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(create(user, json).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(SCHEDULE + "/" + UUID.randomUUID(), HttpMethod.PUT, new HttpEntity<>(json, user), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange(SCHEDULE + "/" + UUID.randomUUID(), HttpMethod.DELETE, new HttpEntity<>(user), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(scheduleRepository.count()).isZero();
    }

    @Test
    void admin_seesOnlySchedulableIcons() throws Exception {
        HttpHeaders admin = bearerFor(createUser("ADMIN", true));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/app-icon/icons", HttpMethod.GET,
                new HttpEntity<>(admin), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = data(response);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("key").asText()).isEqualTo("diwali");
        assertThat(data.get(0).get("label").asText()).isEqualTo("Diwali");
    }

    // --- the full lifecycle, through the cache ---

    @Test
    void createUpdateDelete_changeThePublicAnswerImmediately_andAreAudited() throws Exception {
        User adminUser = createUser("ADMIN", true);
        HttpHeaders admin = bearerFor(adminUser);
        // Warm the cache with "default" first: the create below must evict it, or the public
        // answer would stay "default" for up to a minute.
        assertThat(data(anonymousIcon()).get("icon").asText()).isEqualTo("default");

        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(2, ChronoUnit.HOURS);
        ResponseEntity<String> created = create(admin, body("diwali", "Diwali 2026", start, end, null));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode row = data(created);
        assertThat(row.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("createdByEmail").asText()).isEqualTo(adminUser.getEmail());
        String id = row.get("id").asText();
        long version = row.get("version").asLong();

        JsonNode now = data(anonymousIcon());
        assertThat(now.get("icon").asText()).isEqualTo("diwali");
        assertThat(Instant.parse(now.get("until").asText())).isEqualTo(end);
        ResponseEntity<String> adminView = restTemplate.exchange("/api/v1/admin/app-icon/current", HttpMethod.GET,
                new HttpEntity<>(admin), String.class);
        assertThat(data(adminView).get("icon").asText()).isEqualTo("diwali");
        assertThat(adminView.getHeaders().getCacheControl())
                .as("the admin view must never be reused from a browser cache").doesNotContain("max-age=60");

        Instant laterEnd = end.plus(1, ChronoUnit.HOURS);
        ResponseEntity<String> updated = restTemplate.exchange(SCHEDULE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(body("diwali", "Diwali 2026 (longer)", start, laterEnd, version), admin), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(updated).get("label").asText()).isEqualTo("Diwali 2026 (longer)");
        assertThat(Instant.parse(data(anonymousIcon()).get("until").asText())).isEqualTo(laterEnd);

        // Updating with the old version is refused: someone else's save would otherwise be lost.
        ResponseEntity<String> stale = restTemplate.exchange(SCHEDULE + "/" + id, HttpMethod.PUT,
                new HttpEntity<>(body("diwali", "Stale", start, laterEnd, version), admin), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> deleted = restTemplate.exchange(SCHEDULE + "/" + id, HttpMethod.DELETE,
                new HttpEntity<>(admin), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode afterDelete = data(anonymousIcon());
        assertThat(afterDelete.get("icon").asText()).isEqualTo("default");
        assertThat(afterDelete.has("until")).isFalse();

        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(UUID.fromString(id));
        assertThat(audit).extracting(AuditLog::getAction)
                .containsExactly("APP_ICON_SCHEDULE_CREATED", "APP_ICON_SCHEDULE_UPDATED", "APP_ICON_SCHEDULE_DELETED");
        assertThat(audit).allSatisfy(a -> assertThat(a.getUserId()).isEqualTo(adminUser.getId()));
        assertThat(audit.get(1).getMetadata()).containsEntry("label", "Diwali 2026 (longer)");
        assertThat(audit.get(1).getMetadata().get("before").toString()).contains("Diwali 2026");
        assertThat(audit.get(2).getMetadata()).containsEntry("icon", "diwali");
    }

    @Test
    void anUpcomingWindow_isListed_butTheAppStillGetsDefaultUntilItStarts() throws Exception {
        HttpHeaders admin = bearerFor(createUser("ADMIN", true));
        Instant start = Instant.now().plus(3, ChronoUnit.DAYS);
        assertThat(create(admin, body("diwali", "Diwali 2026", start, start.plus(2, ChronoUnit.DAYS), null))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(data(anonymousIcon()).get("icon").asText()).isEqualTo("default");
        JsonNode list = data(restTemplate.exchange(SCHEDULE, HttpMethod.GET, new HttpEntity<>(admin), String.class));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("state").asText()).isEqualTo("UPCOMING");
    }

    @Test
    void theAnswerIsServedFromTheCache_untilAnAdminChangeEvictsIt() throws Exception {
        assertThat(data(anonymousIcon()).get("icon").asText()).isEqualTo("default");

        // A row written behind the service's back, so nothing evicts: the cached list must still answer.
        Instant start = Instant.now().minus(1, ChronoUnit.MINUTES);
        insertDirectly(start, start.plus(1, ChronoUnit.HOURS));
        assertThat(data(anonymousIcon()).get("icon").asText())
                .as("a cached read must not query the table again").isEqualTo("default");

        windowSource.evict();
        assertThat(data(anonymousIcon()).get("icon").asText()).isEqualTo("diwali");
    }

    // --- validation ---

    @Test
    void overlappingWindows_areRefused_butBackToBackWindowsAreFine() throws Exception {
        HttpHeaders admin = bearerFor(createUser("ADMIN", true));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Instant end = start.plus(2, ChronoUnit.DAYS);
        assertThat(create(admin, body("diwali", "Diwali 2026", start, end, null)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> overlap = create(admin, body("diwali", "Overlap", end.minus(1, ChronoUnit.MINUTES),
                end.plus(1, ChronoUnit.DAYS), null));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(overlap.getBody()).get("message").asText()).contains("Diwali 2026").contains("IST");

        ResponseEntity<String> backToBack = create(admin, body("diwali", "After", end, end.plus(1, ChronoUnit.DAYS), null));
        assertThat(backToBack.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(scheduleRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidRequests_areRefusedWith400_andNothingIsSaved() {
        HttpHeaders admin = bearerFor(createUser("ADMIN", true));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(1, ChronoUnit.DAYS);

        assertThat(create(admin, body("holi", "Holi", start, end, null)).getStatusCode())
                .as("an icon the app does not bundle").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, body("default", "Default", start, end, null)).getStatusCode())
                .as("the default icon is not scheduled").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, body("diwali", "Backwards", end, start, null)).getStatusCode())
                .as("end before start").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, body("diwali", "Zero", start, start, null)).getStatusCode())
                .as("end equal to start").isEqualTo(HttpStatus.BAD_REQUEST);
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
        assertThat(create(admin, body("diwali", "Past", past, past.plus(1, ChronoUnit.DAYS), null)).getStatusCode())
                .as("already over").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, body("diwali", "   ", start, end, null)).getStatusCode())
                .as("blank label").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, body("diwali", "x".repeat(81), start, end, null)).getStatusCode())
                .as("label too long").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(admin, "{\"icon\":\"diwali\",\"label\":\"No dates\"}").getStatusCode())
                .as("missing dates").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(scheduleRepository.count()).isZero();
    }

    @Test
    void updateAndDelete_ofAMissingEntry_are404() {
        HttpHeaders admin = bearerFor(createUser("ADMIN", true));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        String json = body("diwali", "Diwali", start, start.plus(1, ChronoUnit.DAYS), 0L);
        String missing = SCHEDULE + "/" + UUID.randomUUID();

        assertThat(restTemplate.exchange(missing, HttpMethod.PUT, new HttpEntity<>(json, admin), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange(missing, HttpMethod.DELETE, new HttpEntity<>(admin), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theDatabaseItselfRefusesOverlappingRows() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        insertDirectly(start, start.plus(1, ChronoUnit.DAYS));

        assertThatThrownBy(() -> insertDirectly(start.plus(1, ChronoUnit.HOURS), start.plus(2, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("app_icon_schedule_no_overlap");
        assertThatThrownBy(() -> insertDirectly(start.plus(3, ChronoUnit.DAYS), start.plus(3, ChronoUnit.DAYS)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("app_icon_schedule_ends_after_start");
        assertThat(scheduleRepository.findAll()).extracting(AppIconSchedule::getLabel).containsExactly("Direct");
    }

    private void insertDirectly(Instant start, Instant end) {
        UUID admin = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO app_icon_schedule (id, icon_key, label, starts_at, ends_at, created_by, created_at,
                                               updated_by, updated_at, version)
                VALUES (?, 'diwali', 'Direct', ?, ?, ?, now(), ?, now(), 0)
                """, UUID.randomUUID(), Timestamp.from(start), Timestamp.from(end), admin, admin);
    }
}
