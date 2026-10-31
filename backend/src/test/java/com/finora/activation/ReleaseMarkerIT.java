package com.finora.activation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V303: release markers on the activation page. Read with the report by anyone who can see it;
 * added and removed only with SYSTEM_SETTINGS too, and every change audited. The IT suite shares one
 * database, so each test finds its own marker by a label no other test uses.
 */
class ReleaseMarkerIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/analytics/activation";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReportingDay reportingDay;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void anAdminAddsAMarker_andTheReportShowsItWithEqualWindowsEitherSide() throws Exception {
        String label = "IT marker " + UUID.randomUUID().toString().substring(0, 8);
        LocalDate today = reportingDay.today();
        LocalDate date = today.minusDays(9);
        User admin = staff("ADMIN");

        ResponseEntity<String> created = send(admin, HttpMethod.POST, BASE + "/markers",
                Map.of("date", date.toString(), "label", "  " + label + "  "));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode saved = mapper.readTree(created.getBody()).get("data");
        assertThat(saved.get("label").asText()).as("trimmed").isEqualTo(label);
        assertThat(saved.get("date").asText()).isEqualTo(date.toString());

        JsonNode marker = marker(report(admin), label);
        assertThat(marker).isNotNull();
        assertThat(marker.get("id").asLong()).isEqualTo(saved.get("id").asLong());
        assertThat(marker.get("after").get("from").asText()).isEqualTo(date.toString());
        assertThat(marker.get("after").get("to").asText()).isEqualTo(today.toString());
        assertThat(marker.get("before").get("from").asText()).isEqualTo(date.minusDays(10).toString());
        assertThat(marker.get("before").get("to").asText()).isEqualTo(date.minusDays(1).toString());
        for (ReachedStep step : ReachedStep.values()) {
            assertThat(marker.get("after").get("reached").has(step.name())).as(step.name()).isTrue();
        }

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT user_id, entity_type, metadata::text AS metadata FROM audit_logs "
                        + "WHERE action = 'ACTIVATION_RELEASE_MARKER_CREATED' AND metadata->>'label' = ?", label);
        assertThat(audit.get("user_id")).isEqualTo(admin.getId());
        assertThat(audit.get("entity_type")).isEqualTo("ActivationReleaseMarker");
        assertThat((String) audit.get("metadata")).contains(date.toString());
    }

    @Test
    void anAdminRemovesAMarker_audited_andASecondRemoveIsNotFound() throws Exception {
        String label = "IT remove " + UUID.randomUUID().toString().substring(0, 8);
        User admin = staff("ADMIN");
        long id = mapper.readTree(send(admin, HttpMethod.POST, BASE + "/markers",
                Map.of("date", "2026-10-25", "label", label)).getBody()).get("data").get("id").asLong();

        assertThat(send(admin, HttpMethod.DELETE, BASE + "/markers/" + id, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(marker(report(admin), label)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ACTIVATION_RELEASE_MARKER_DELETED' "
                + "AND metadata->>'label' = ?", Long.class, label)).isEqualTo(1);
        assertThat(send(admin, HttpMethod.DELETE, BASE + "/markers/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FINANCE can read the activation page (and its markers) but holds no SYSTEM_SETTINGS (V31). */
    @Test
    void addingOrRemovingNeedsSystemSettingsToo_whileReadingNeedsOnlyTheAnalyticsView() throws Exception {
        String label = "IT perms " + UUID.randomUUID().toString().substring(0, 8);
        User finance = staff("FINANCE");
        Map<String, String> body = Map.of("date", "2026-10-25", "label", label);

        assertThat(send(finance, HttpMethod.POST, BASE + "/markers", body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(staff("SUPPORT"), HttpMethod.POST, BASE + "/markers", body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(customer(), HttpMethod.POST, BASE + "/markers", body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        long id = mapper.readTree(send(staff("ADMIN"), HttpMethod.POST, BASE + "/markers", body).getBody())
                .get("data").get("id").asLong();
        assertThat(marker(report(finance), label)).as("FINANCE sees the marker").isNotNull();
        assertThat(send(finance, HttpMethod.DELETE, BASE + "/markers/" + id, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(marker(report(finance), label)).as("still there").isNotNull();
    }

    @Test
    void refusesAMissingDate_aBlankOrLongOrMultiLineLabel_andADateMoreThanAYearAhead() {
        User admin = staff("ADMIN");
        LocalDate today = reportingDay.today();

        assertThat(send(admin, HttpMethod.POST, BASE + "/markers", Map.of("label", "No date")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(admin, HttpMethod.POST, BASE + "/markers", Map.of("date", "2026-10-25", "label", "   ")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(admin, HttpMethod.POST, BASE + "/markers", Map.of("date", "2026-10-25", "label", "x".repeat(81))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(admin, HttpMethod.POST, BASE + "/markers", Map.of("date", "2026-10-25", "label", "two\nlines")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(admin, HttpMethod.POST, BASE + "/markers",
                Map.of("date", today.plusDays(367).toString(), "label", "Too far")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // 80 characters and a year ahead are allowed; a marker still to come has no windows.
        String edge = ("IT edge " + UUID.randomUUID()).substring(0, 20) + "y".repeat(60);
        ResponseEntity<String> ok = send(admin, HttpMethod.POST, BASE + "/markers",
                Map.of("date", today.plusDays(366).toString(), "label", edge));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aMarkerStillToComeIsListedWithNoWindows() throws Exception {
        String label = "IT future " + UUID.randomUUID().toString().substring(0, 8);
        User admin = staff("ADMIN");
        send(admin, HttpMethod.POST, BASE + "/markers", Map.of("date", reportingDay.today().plusDays(3).toString(), "label", label));

        JsonNode marker = marker(report(admin), label);

        assertThat(marker.get("before").isNull()).isTrue();
        assertThat(marker.get("after").isNull()).isTrue();
    }

    private static JsonNode marker(JsonNode report, String label) {
        for (JsonNode m : report.get("releaseMarkers")) {
            if (label.equals(m.get("label").asText())) return m;
        }
        return null;
    }

    private JsonNode report(User caller) throws Exception {
        ResponseEntity<String> response = send(caller, HttpMethod.GET, BASE, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody()).get("data");
    }

    private ResponseEntity<String> send(User caller, HttpMethod method, String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, caller));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private User staff(String role) {
        User user = new User();
        user.setEmail("release-marker-it-staff-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Release Marker Staff");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private User customer() {
        User user = new User();
        user.setEmail("release-marker-it-user-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Release Marker User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }
}
