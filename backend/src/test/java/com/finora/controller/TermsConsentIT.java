package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.entity.UserTermsAcceptance;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserTermsAcceptanceRepository;
import com.finora.security.JwtService;
import com.finora.service.LegalTerms;
import com.finora.support.ClientIdentity;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Privacy workstreams 7 and 8 end to end against the real schema (V302): the consent endpoint, the
 * sign-up boxes, the profile fields the apps gate on, withdrawal, and the migration's backfill.
 * docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md. Every value is synthetic.
 */
class TermsConsentIT extends AbstractIntegrationTest {

    private static final AtomicInteger CLIENT_IP = new AtomicInteger(10);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private UserTermsAcceptanceRepository acceptances;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.finora.service.SubscriptionService subscriptionService;

    private User user;

    @BeforeEach
    void setUp() {
        user = newUser();
    }

    private User newUser() {
        User u = new User();
        u.setEmail("terms-consent-it-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Terms Consent IT User");
        u.setPhoneVerified(true);
        return userRepository.save(u);
    }

    private HttpHeaders headers(User u, String platform) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, u));
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (platform != null) headers.set(ClientIdentity.PLATFORM_HEADER, platform);
        return headers;
    }

    private ResponseEntity<String> consent(User u, String platform, String body) {
        return restTemplate.exchange("/api/v1/users/me/consent", HttpMethod.POST,
                new HttpEntity<>(body, headers(u, platform)), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    private JsonNode me(User u) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(headers(u, null)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return data(response);
    }

    private List<String> auditActions(UUID userId) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(AuditLog::getAction).toList();
    }

    // ---------------------------------------------------------------- the profile fields

    @Test
    void aNewAccountWithNoAnswersMustSeeTheConsentStep() throws Exception {
        JsonNode me = me(user);

        assertThat(me.get("termsAcceptanceRequired").asBoolean()).isTrue();
        assertThat(me.get("currentTermsVersion").asText()).isEqualTo(LegalTerms.CURRENT_VERSION);
        assertThat(me.get("aiProcessingConsent").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- accepting

    @Test
    void acceptingRecordsACheckboxRowWithTheChannel_andClearsTheStep() throws Exception {
        ResponseEntity<String> response = consent(user, "MOBILE_IOS",
                "{\"acceptTermsVersion\": \"%s\", \"aiProcessing\": true}".formatted(LegalTerms.CURRENT_VERSION));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(response).get("termsAcceptanceRequired").asBoolean()).isFalse();
        assertThat(data(response).get("aiProcessingConsent").asBoolean()).isTrue();

        List<UserTermsAcceptance> rows = acceptances.findByUserIdOrderByAcceptedAtAsc(user.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTermsVersion()).isEqualTo(LegalTerms.CURRENT_VERSION);
        assertThat(rows.get(0).getChannel()).isEqualTo("MOBILE_IOS");
        assertThat(rows.get(0).getMethod()).isEqualTo(UserTermsAcceptance.METHOD_CHECKBOX);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getTermsVersion()).isEqualTo(LegalTerms.CURRENT_VERSION);
        assertThat(reloaded.getTermsAcceptedAt()).isEqualTo(rows.get(0).getAcceptedAt());
        assertThat(reloaded.getAiProcessingConsent()).isTrue();
        assertThat(auditActions(user.getId())).contains("TERMS_ACCEPTED", "AI_PROCESSING_CONSENT_CHANGED");
    }

    @Test
    void everyAcceptanceIsANewRow_neverAnUpdate() {
        String body = "{\"acceptTermsVersion\": \"%s\"}".formatted(LegalTerms.CURRENT_VERSION);
        assertThat(consent(user, "WEB", body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(consent(user, "MOBILE_ANDROID", body).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(acceptances.findByUserIdOrderByAcceptedAtAsc(user.getId()))
                .extracting(UserTermsAcceptance::getChannel).containsExactly("WEB", "MOBILE_ANDROID");
    }

    /** A missing or unknown header is recorded as WEB -- ClientIdentity's own documented rule. */
    @Test
    void noPlatformHeaderRecordsWeb() {
        consent(user, null, "{\"acceptTermsVersion\": \"%s\"}".formatted(LegalTerms.CURRENT_VERSION));

        assertThat(acceptances.findByUserIdOrderByAcceptedAtAsc(user.getId()))
                .extracting(UserTermsAcceptance::getChannel).containsExactly("WEB");
    }

    @Test
    void anOutdatedVersionIsRefusedAndRecordsNothing() throws Exception {
        ResponseEntity<String> response = consent(user, "WEB", "{\"acceptTermsVersion\": \"2020-01\", \"aiProcessing\": true}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("PRIVACY_002");
        assertThat(acceptances.findByUserIdOrderByAcceptedAtAsc(user.getId())).isEmpty();
        // The AI answer in the same request is not applied either: the whole request is refused.
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAiProcessingConsent()).isNull();
    }

    @Test
    void anEmptyRequestIsRefused() {
        assertThat(consent(user, "WEB", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Unticked AI box on the consent step: recorded as no, so the step does not come back. */
    @Test
    void answeringNoIsAnAnswer() throws Exception {
        consent(user, "WEB", "{\"acceptTermsVersion\": \"%s\", \"aiProcessing\": false}".formatted(LegalTerms.CURRENT_VERSION));

        JsonNode me = me(user);
        assertThat(me.get("termsAcceptanceRequired").asBoolean()).isFalse();
        assertThat(me.get("aiProcessingConsent").asBoolean()).isFalse();
        assertThat(userRepository.existsByIdAndAiProcessingConsentTrue(user.getId())).isFalse();
    }

    // ---------------------------------------------------------------- withdrawal

    private void storedAiOutput(UUID userId) {
        jdbc.update("""
                INSERT INTO ai_insight_texts (id, user_id, kind, subject, month, facts_hash, prompt_version, model,
                                              policy_version, status)
                VALUES (gen_random_uuid(), ?, 'STORY', 'month', '2026-09', 'hash', 'v1', 'model', 'policy-v2', 'READY')
                """, userId);
        jdbc.update("""
                INSERT INTO ai_audit_log (user_id, model, prompt_version, tool_name, tool_inputs, tool_outputs,
                                          tokens_in, tokens_out, cost, latency_ms)
                VALUES (?, 'model', 'v1', 'spending_summary', '{"month": "2026-09"}'::jsonb,
                        '{"total": "1234.00"}'::jsonb, 10, 5, 0.001, 100)
                """, userId);
        UUID conversation = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_conversations (id, user_id, title) VALUES (?, ?, 'Synthetic chat')", conversation, userId);
    }

    @Test
    void turningAiOff_erasesThatUsersAiOutput_andNobodyElses() throws Exception {
        User other = newUser();
        consent(user, "WEB", "{\"aiProcessing\": true}");
        storedAiOutput(user.getId());
        storedAiOutput(other.getId());

        ResponseEntity<String> response = consent(user, "WEB", "{\"aiProcessing\": false}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("SELECT count(*) FROM ai_insight_texts WHERE user_id = ?", user.getId())).isZero();
        assertThat(count("SELECT count(*) FROM ai_insight_texts WHERE user_id = ?", other.getId())).isEqualTo(1);
        // The audit row stays (cost is read from it); what was sent and returned is cleared.
        Map<String, Object> mine = jdbc.queryForMap(
                "SELECT tool_inputs, tool_outputs, cost FROM ai_audit_log WHERE user_id = ?", user.getId());
        assertThat(mine.get("tool_inputs")).isNull();
        assertThat(mine.get("tool_outputs")).isNull();
        assertThat(mine.get("cost")).isNotNull();
        Map<String, Object> theirs = jdbc.queryForMap(
                "SELECT tool_inputs FROM ai_audit_log WHERE user_id = ?", other.getId());
        assertThat(theirs.get("tool_inputs")).isNotNull();
        // Ask Fyn conversations stay (see the spec's withdrawal section).
        assertThat(count("SELECT count(*) FROM chat_conversations WHERE user_id = ?", user.getId())).isEqualTo(1);

        assertThat(auditActions(user.getId())).contains("AI_OUTPUT_ERASED");
        assertThat(userRepository.findById(user.getId()).orElseThrow().getAiProcessingConsent()).isFalse();
    }

    /** A first answer of no is not a withdrawal: there is nothing produced under a consent to erase. */
    @Test
    void aFirstNoErasesNothing() {
        storedAiOutput(user.getId()); // written before V302, while nobody had been asked

        consent(user, "WEB", "{\"aiProcessing\": false}");

        assertThat(count("SELECT count(*) FROM ai_insight_texts WHERE user_id = ?", user.getId())).isEqualTo(1);
        assertThat(auditActions(user.getId())).doesNotContain("AI_OUTPUT_ERASED");
    }

    @Test
    void askFynSaysAiIsOffWithItsOwnCode() throws Exception {
        subscriptionService.provisionFreeSubscription(user.getId()); // Free has FYN_CHAT (V205)
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/fyn/chat", HttpMethod.POST,
                new HttpEntity<>("{\"message\": \"hello\"}", headers(user, "WEB")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("PRIVACY_001");
    }

    private long count(String sql, UUID userId) {
        Long n = jdbc.queryForObject(sql, Long.class, userId);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- sign-up

    private ResponseEntity<String> register(String extraFields) {
        String email = "terms-consent-signup-" + UUID.randomUUID() + "@example.com";
        String phone = "+9197655" + (10000 + new java.util.Random().nextInt(90000)); // synthetic-ok: random test number
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "192.0.2." + CLIENT_IP.getAndIncrement() + ", 100.64.0.1");
        headers.set(ClientIdentity.PLATFORM_HEADER, "MOBILE_ANDROID");
        return restTemplate.postForEntity("/api/v1/auth/register", new HttpEntity<>("""
                {"email": "%s", "password": "correct horse battery staple", "fullName": "Consent Signup Test",
                 "phoneNumber": "%s"%s}
                """.formatted(email, phone, extraFields), headers), String.class);
    }

    private User registered(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String email = mapper.readTree(response.getBody()).get("data").get("email").asText();
        return userRepository.findByEmailIgnoreCaseAndAccountScope(email, User.SCOPE_USER).orElseThrow();
    }

    @Test
    void signUpWithTheBoxTicked_recordsACheckboxAcceptanceAndTheAiAnswer() throws Exception {
        User created = registered(register(", \"acceptedTerms\": true, \"aiProcessingConsent\": true"));

        assertThat(acceptances.findByUserIdOrderByAcceptedAtAsc(created.getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getChannel()).isEqualTo("MOBILE_ANDROID");
                    assertThat(row.getMethod()).isEqualTo(UserTermsAcceptance.METHOD_CHECKBOX);
                });
        assertThat(created.getTermsVersion()).isEqualTo(LegalTerms.CURRENT_VERSION);
        assertThat(created.getAiProcessingConsent()).isTrue();
    }

    /** The shape a 1.1.x app sends: no acceptance is recorded, so the consent step asks later. */
    @Test
    void signUpFromAnOlderApp_recordsNoAcceptance() throws Exception {
        User created = registered(register(""));

        assertThat(acceptances.findByUserIdOrderByAcceptedAtAsc(created.getId())).isEmpty();
        assertThat(created.getTermsVersion()).isNull();
        assertThat(created.getAiProcessingConsent()).isNull();
    }

    @Test
    void signUpWithTheBoxUnticked_isRefused() throws Exception {
        ResponseEntity<String> response = register(", \"acceptedTerms\": false");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(response.getBody()).get("errorCode").asText()).isEqualTo("PRIVACY_003");
    }

    // ---------------------------------------------------------------- V302 itself

    @Test
    void theAiColumnsMoveTogether() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE users SET ai_processing_consent = true, ai_processing_consent_at = NULL WHERE id = ?", user.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theAcceptanceRowsOnlyTakeKnownChannelsAndMethods() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_terms_acceptances (user_id, terms_version, accepted_at, channel, method)
                VALUES (?, '2026-10', now(), 'FAX', 'CHECKBOX')
                """, user.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_terms_acceptances (user_id, terms_version, accepted_at, channel, method)
                VALUES (?, '2026-10', now(), 'WEB', 'IMPLIED')
                """, user.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Flyway has already run V302 on the empty test database, so its backfill statement runs here
     * again over accounts shaped the way V223 left them, inside a transaction that is rolled back:
     * the statement covers every user, and the suite's tables are shared.
     */
    @Test
    void theBackfillGivesEachRecordedAcceptanceOneNoticeRow_andInventsNone() throws Exception {
        User recorded = newUser();
        Instant at = Instant.parse("2026-10-12T09:30:00Z");
        jdbc.update("UPDATE users SET terms_version = '2026-10', terms_accepted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(at), recorded.getId());
        User neverRecorded = newUser();

        String migration = new String(new ClassPathResource("db/migration/V302__ai_consent_and_terms_acceptances.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String backfill = migration.substring(migration.indexOf("INSERT INTO user_terms_acceptances")).trim();
        assertThat(backfill).endsWith(";");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update(backfill.substring(0, backfill.length() - 1));
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT terms_version, accepted_at, channel, method FROM user_terms_acceptances WHERE user_id = ?",
                    recorded.getId());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("terms_version")).isEqualTo("2026-10");
            assertThat(((java.sql.Timestamp) rows.get(0).get("accepted_at")).toInstant()).isEqualTo(at);
            assertThat(rows.get(0).get("channel")).isEqualTo("UNKNOWN");
            assertThat(rows.get(0).get("method")).isEqualTo("NOTICE");
            assertThat(count("SELECT count(*) FROM user_terms_acceptances WHERE user_id = ?", neverRecorded.getId()))
                    .isZero();
            status.setRollbackOnly();
        });
    }
}
