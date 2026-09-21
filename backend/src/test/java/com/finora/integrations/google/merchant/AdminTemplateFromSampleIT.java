package com.finora.integrations.google.merchant;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Create a template from a sample email", over real HTTP: the upload, the analysis, and the same
 * template then created, tested and activated through the ordinary endpoints, exactly as the admin
 * portal drives them. The email is invented; its layout copies a receipt that prints no date, which
 * is the case that needed the arrival-day date pattern.
 */
class AdminTemplateFromSampleIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/merchant-templates";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private MerchantTemplateRepository templates;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("sample-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Sample IT User");
        user.setRole(role);
        // An admin is an admin-portal account since V52 (see AdminMerchantTemplateEndpointIT).
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders headersFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String sampleEmail(String domain) {
        String html = "<html><body><p>Your order is delivered.</p><table>"
                + "<tr><td>Delivery Fee</td><td>&#8377;12</td></tr>"
                + "<tr><td>Total</td>\n<td>\n&#8377;1491.00</td></tr></table></body></html>";
        return ("From: Shop Orders <noreply@" + domain + ">\n"
                + "Authentication-Results: mx.example.test; dmarc=pass (p=NONE) header.from=" + domain + "\n"
                + "Date: Tue, 01 Sep 2026 20:30:00 +0000\n"
                + "Content-Type: text/html; charset=utf-8\n\n" + html).replace("\n", "\r\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ResponseEntity<Map> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @Test
    @DisplayName("upload, analyse, then create, test and activate a template for a receipt that prints no date")
    @SuppressWarnings("unchecked")
    void fromAnEmailToAnActiveTemplate() {
        HttpHeaders headers = headersFor(createUser("ADMIN"));
        String domain = "it-shop-" + UUID.randomUUID().toString().substring(0, 8) + ".example";

        // 1. Analyse the uploaded email.
        ResponseEntity<Map> analysed = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", sampleEmail(domain)), headers), Map.class);

        assertThat(analysed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(analysed.getBody().get("success")).isEqualTo(true);
        Map<String, Object> a = dataOf(analysed);
        assertThat(a.get("authenticatedDomain")).isEqualTo(domain);
        assertThat(a.get("domainIsTrusted")).as("not on the registry yet").isEqualTo(false);
        assertThat(a.get("senderVerdict")).isEqualTo("DOMAIN_NOT_TRUSTED");
        assertThat(a.get("handWrittenParserExists")).isEqualTo(false);
        assertThat(a.get("senderName")).isEqualTo("Shop Orders");
        // 20:30 UTC on 1 September is 02:00 IST on 2 September.
        assertThat(a.get("receivedOn")).isEqualTo("2026-09-02");
        assertThat(a.get("arrivalDatePattern")).isEqualTo("{received}");
        assertThat((List<?>) a.get("dates")).as("this email prints no date").isEmpty();
        assertThat((String) a.get("html")).contains("Delivery Fee");
        assertThat((String) a.get("text")).contains("1491.00");

        List<Map<String, Object>> amounts = (List<Map<String, Object>>) a.get("amounts");
        assertThat(amounts).isNotEmpty();
        Map<String, Object> total = amounts.get(0);
        assertThat(total.get("value")).isEqualTo("1491.00");
        assertThat(total.get("likelyTotal")).isEqualTo(true);
        assertThat(total.get("pattern")).isEqualTo("Total ₹{amount}");
        List<String> markers = (List<String>) a.get("receiptMarkerSuggestions");
        assertThat(markers).contains("Your order is delivered.");

        // 2. Create the template the analysis proposed. Always created disabled.
        ResponseEntity<Map> created = restTemplate.exchange(BASE, HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantDomain", domain, "merchantName", "Shop",
                        "receiptMarker", markers.get(0), "amountPattern", total.get("pattern"),
                        "datePattern", a.get("arrivalDatePattern")), headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(created).get("enabled")).isEqualTo(false);
        String id = (String) dataOf(created).get("id");

        // 3. Test it against the analysed HTML with the analysed arrival day.
        ResponseEntity<Map> tested = restTemplate.exchange(BASE + "/test", HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantDomain", domain, "receiptMarker", markers.get(0),
                        "amountPattern", total.get("pattern"), "datePattern", a.get("arrivalDatePattern"),
                        "sampleHtml", a.get("html"), "receivedOn", a.get("receivedOn")), headers), Map.class);
        assertThat(tested.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> result = dataOf(tested);
        assertThat(result.get("status")).as(String.valueOf(result.get("reason"))).isEqualTo("PARSED");
        assertThat(result.get("amount")).isEqualTo(1491.0);
        assertThat(result.get("transactionDate")).isEqualTo("2026-09-02");

        // 4. The same test without the arrival day cannot pass: the screen must not report a pass
        //    for a template it could not actually date.
        ResponseEntity<Map> untested = restTemplate.exchange(BASE + "/test", HttpMethod.POST,
                new HttpEntity<>(Map.of("merchantDomain", domain, "receiptMarker", markers.get(0),
                        "amountPattern", total.get("pattern"), "datePattern", a.get("arrivalDatePattern"),
                        "sampleHtml", a.get("html")), headers), Map.class);
        assertThat(dataOf(untested).get("status")).isEqualTo("MALFORMED");

        // 5. Activate.
        ResponseEntity<Map> activated = restTemplate.exchange(BASE + "/" + id + "/activate",
                HttpMethod.POST, new HttpEntity<>(headers), Map.class);
        assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(templates.findByMerchantDomain(domain)).get().matches(MerchantTemplate::isEnabled, "enabled");
    }

    @Test
    @DisplayName("an email from a domain a hand-written parser owns says so, before a template is attempted")
    void aDomainWithAHandWrittenParserIsFlagged() {
        HttpHeaders headers = headersFor(createUser("ADMIN"));

        ResponseEntity<Map> analysed = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", sampleEmail("amazon.in")), headers), Map.class);

        assertThat(dataOf(analysed).get("authenticatedDomain")).isEqualTo("amazon.in");
        assertThat(dataOf(analysed).get("handWrittenParserExists")).isEqualTo(true);
    }

    @Test
    @DisplayName("an ordinary authenticated user cannot analyse an email")
    void analysingIsRefusedToANonAdmin() {
        HttpHeaders headers = headersFor(createUser("USER"));

        ResponseEntity<String> response = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", sampleEmail("x.example")), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an unauthenticated request is refused")
    void analysingRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", sampleEmail("x.example"))), String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an empty file, a file with no body and an oversized file are each a clean 400 with a reason")
    void badInputIsARefusalNotAnError() {
        HttpHeaders headers = headersFor(createUser("ADMIN"));

        ResponseEntity<Map> empty = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", "  "), headers), Map.class);
        ResponseEntity<Map> noBody = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", "Subject: nothing\r\n\r\n"), headers), Map.class);
        ResponseEntity<Map> tooBig = restTemplate.exchange(BASE + "/analyze-sample", HttpMethod.POST,
                new HttpEntity<>(Map.of("rawEmail", "x".repeat(TemplateSampleAnalyzer.MAX_EMAIL_CHARS + 1)), headers),
                Map.class);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) noBody.getBody().get("message")).contains("Download original");
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
