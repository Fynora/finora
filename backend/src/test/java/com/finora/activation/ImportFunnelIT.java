package com.finora.activation;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 2a spec §3: the import-funnel record, end to end through the real filter chain. One row per
 * user, event and reporting-zone day; the event comes from a fixed list; the user is always the
 * caller.
 */
class ImportFunnelIT extends AbstractIntegrationTest {

    private static final String ENDPOINT = "/api/v1/activation/funnel-events";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private ImportFunnelDayRepository importFunnelDayRepository;
    @Autowired private ReportingDay reportingDay;

    private User newUser() {
        User user = new User();
        user.setEmail("funnel-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Funnel IT");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        return jwtService.generateToken(user.getId(), user.getEmail(), issued.sessionId(), user.getAccountScope());
    }

    private int post(String token, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return restTemplate.exchange(ENDPOINT, HttpMethod.POST, new HttpEntity<>(json, headers), String.class)
                .getStatusCode().value();
    }

    @Test
    void recordsEachEventOncePerDay() {
        User user = newUser();
        String token = tokenFor(user);

        LocalDate before = reportingDay.today();
        assertThat(post(token, "{\"event\":\"FILE_CHOSEN\"}")).isEqualTo(200);
        assertThat(post(token, "{\"event\":\"FILE_CHOSEN\"}")).isEqualTo(200);
        assertThat(post(token, "{\"event\":\"IMPORT_SCREEN_OPENED\"}")).isEqualTo(200);
        LocalDate after = reportingDay.today();

        List<ImportFunnelDay> rows = importFunnelDayRepository.findByUserIdOrderByFunnelDateAscEventAsc(user.getId());
        assertThat(rows).extracting(ImportFunnelDay::getEvent)
                .containsExactlyInAnyOrder(FunnelEvent.FILE_CHOSEN, FunnelEvent.IMPORT_SCREEN_OPENED);
        // before/after differ only if the test straddles midnight in the reporting zone.
        assertThat(rows).allSatisfy(r -> assertThat(r.getFunnelDate()).isBetween(before, after));
    }

    @Test
    void unknownEventIsRefused() {
        User user = newUser();

        assertThat(post(tokenFor(user), "{\"event\":\"FILE_UPLOADED\"}")).isEqualTo(400);

        assertThat(importFunnelDayRepository.findByUserIdOrderByFunnelDateAscEventAsc(user.getId())).isEmpty();
    }

    @Test
    void missingEventIsRefused() {
        User user = newUser();

        assertThat(post(tokenFor(user), "{}")).isEqualTo(400);

        assertThat(importFunnelDayRepository.findByUserIdOrderByFunnelDateAscEventAsc(user.getId())).isEmpty();
    }

    @Test
    void userIsAlwaysTheCaller() {
        User caller = newUser();
        User other = newUser();

        assertThat(post(tokenFor(caller),
                "{\"event\":\"REVIEW_OPENED\",\"userId\":\"" + other.getId() + "\"}")).isEqualTo(200);

        assertThat(importFunnelDayRepository.findByUserIdOrderByFunnelDateAscEventAsc(caller.getId()))
                .extracting(ImportFunnelDay::getEvent).containsExactly(FunnelEvent.REVIEW_OPENED);
        assertThat(importFunnelDayRepository.findByUserIdOrderByFunnelDateAscEventAsc(other.getId())).isEmpty();
    }

    @Test
    void unauthenticatedIsRefused() {
        assertThat(post(null, "{\"event\":\"FILE_CHOSEN\"}")).isEqualTo(401);
    }

    @Test
    void existenceQueriesSeeTheRecordedRows() {
        User user = newUser();
        LocalDate day = LocalDate.of(2026, 10, 1);
        importFunnelDayRepository.record(user.getId(), FunnelEvent.IMPORT_SCREEN_OPENED.name(), day);

        assertThat(importFunnelDayRepository.existsByUserIdAndEvent(user.getId(), FunnelEvent.IMPORT_SCREEN_OPENED)).isTrue();
        assertThat(importFunnelDayRepository.existsByUserIdAndEvent(user.getId(), FunnelEvent.FILE_CHOSEN)).isFalse();
        assertThat(importFunnelDayRepository.existsByUserIdAndEventAndFunnelDateBefore(
                user.getId(), FunnelEvent.IMPORT_SCREEN_OPENED, day.plusDays(1))).isTrue();
        assertThat(importFunnelDayRepository.existsByUserIdAndEventAndFunnelDateBefore(
                user.getId(), FunnelEvent.IMPORT_SCREEN_OPENED, day)).isFalse();
    }
}
