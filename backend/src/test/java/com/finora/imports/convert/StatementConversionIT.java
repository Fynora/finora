package com.finora.imports.convert;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The free statement converter through the real server: no Authorization header, the real limits in
 * Redis, the real database. The two promises the Privacy page will make are proven here:
 *
 * <ul>
 *   <li><b>Nothing is kept.</b> A conversion, a refusal and a not-confirmed statement change no row
 *       in any table except the daily counter, and the only Redis keys they add are rate-limit keys.</li>
 *   <li><b>Nothing from a statement is logged.</b> Every log line from every logger during a
 *       conversion is captured, and no narration, balance or planted sentinel from the statement
 *       appears in any of them -- nor does an unreadable body.</li>
 * </ul>
 *
 * Turnstile is stood in for (the real one calls Cloudflare); everything else is the real wiring.
 */
@TestPropertySource(properties = {
        "app.converter.enabled=true",
        "app.rate-limit.convert-request.max=10000"
})
class StatementConversionIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @MockitoBean private TurnstileVerifier turnstile;

    private final ObjectMapper mapper = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;
    private Logger root;

    @BeforeEach
    void setUp() {
        when(turnstile.verify(anyString())).thenAnswer(call -> "ok".equals(call.getArgument(0)));
        clearConverterLimits();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        clearConverterLimits();
    }

    private void clearConverterLimits() {
        Set<String> keys = redis.keys("ratelimit:convert*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private ResponseEntity<String> convert(byte[] pdf, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("file", Base64.getEncoder().encodeToString(pdf));
        if (password != null) body.put("password", password);
        body.put("turnstileToken", "ok");
        body.put("consent", true);
        try {
            return post(mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The public page's call: JSON, no Authorization header, no cookie. */
    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/convert", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    // ---- reachable without an account ------------------------------------------------------------

    @Test
    void availabilityIsPublic_andStatesTheLimits() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/convert/availability", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = read(response).get("data");
        assertThat(data.get("available").asBoolean()).isTrue();
        assertThat(data.get("maxPages").asInt()).isEqualTo(20);
        assertThat(data.get("maxBytes").asLong()).isEqualTo(5L * 1024 * 1024);
    }

    @Test
    void aStatementConvertsWithNoAccount() throws Exception {
        ResponseEntity<String> response = convert(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode section = read(response).get("data").get("sections").get(0);
        assertThat(section.get("rows")).hasSize(6);
        assertThat(section.get("checks").toString()).contains("BALANCE_CHAIN");
    }

    // ---- nothing is kept -----------------------------------------------------------------------------

    @Test
    void conversionsKeepNothing_onlyTheDailyCounterAndRateLimitKeysChange() throws Exception {
        Map<String, Long> before = rowCounts();
        Set<String> redisBefore = redisKeys();
        long counterBefore = counter();

        assertThat(convert(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(convert(PdfFixtureBuilder.buildAmountOnLastLineSampleEveryAmountReadOneAmountMisread(), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(convert(PdfFixtureBuilder.buildUpiAppTransactionHistory(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // Three an hour per address: start the window again for the fourth.
        clearConverterLimits();
        byte[] locked = PdfFixtureBuilder.encrypt(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), "s3cret");
        assertThat(convert(locked, "s3cret").getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Long> after = rowCounts();
        Map<String, String> changed = new LinkedHashMap<>();
        for (String table : after.keySet()) {
            if (!after.get(table).equals(before.get(table))) {
                changed.put(table, before.get(table) + " -> " + after.get(table));
            }
        }
        assertThat(changed).as("tables a conversion changed").containsOnlyKeys("converter_daily_stats");
        assertThat(counter()).isEqualTo(counterBefore + 4);

        Set<String> added = new HashSet<>(redisKeys());
        added.removeAll(redisBefore);
        assertThat(added).allSatisfy(key -> assertThat(key).startsWith("ratelimit:convert"));
    }

    // ---- nothing from a statement in any log ---------------------------------------------------------

    @Test
    void noLogLineCarriesAnythingFromTheStatement_orFromAnUnreadableBody() throws Exception {
        String sentinel = "ZQXSENTINEL PLANTED NOTICE 98765";
        ResponseEntity<String> planted = convert(PdfFixtureBuilder.buildNoticeSwallowedIntoADescriptionSample(sentinel), null);
        ResponseEntity<String> converted = convert(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), "ignored-password-PWSENTINEL");
        ResponseEntity<String> garbage = post("{\"file\": \"ZQXBODYSENTINEL\", \"consent\": tru");

        assertThat(planted.getStatusCode().is4xxClientError() || planted.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(converted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(garbage.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(garbage).toString()).contains("CONVERT_010");

        List<String> forbidden = new ArrayList<>(List.of("ZQXSENTINEL", "98765", "PWSENTINEL", "ZQXBODYSENTINEL"));
        for (JsonNode row : read(converted).get("data").get("sections").get(0).get("rows")) {
            forbidden.add(row.get("description").asText());
            forbidden.add(row.get("balance").decimalValue().toPlainString());
        }
        String text = allLogText();
        for (String value : forbidden) {
            assertThat(text).as("log text must not contain a value from the statement").doesNotContain(value);
        }
        assertThat(text).contains("Statement conversion: outcome=CONVERTED");
    }

    // ---- limits ----------------------------------------------------------------------------------

    @Test
    void theFourthConversionInAnHourFromOneAddressIsRefused() throws Exception {
        byte[] notAPdf = "Date,Amount\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            assertThat(read(convert(notAPdf, null)).toString()).contains("IMPORT_022");
        }

        ResponseEntity<String> fourth = convert(PdfFixtureBuilder.buildChainedOldestFirstRunningBalanceSample(), null);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(read(fourth).toString()).contains("CONVERT_004");
    }

    @Test
    void withoutATurnstileTokenNothingIsCounted() throws Exception {
        String json = "{\"file\":\"JVBERi0=\",\"turnstileToken\":\"forged\",\"consent\":true}";

        ResponseEntity<String> response = post(json);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Set<String> keys = redis.keys("ratelimit:convert-ip-*");
        assertThat(keys).isNullOrEmpty();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private Map<String, Long> rowCounts() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename", String.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class));
        }
        return counts;
    }

    private long counter() {
        Long sum = jdbc.queryForObject("SELECT coalesce(sum(conversions), 0) FROM converter_daily_stats", Long.class);
        return sum == null ? 0 : sum;
    }

    private Set<String> redisKeys() {
        Set<String> keys = redis.keys("*");
        return keys == null ? Set.of() : keys;
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                text.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return text.toString();
    }
}
