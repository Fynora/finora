package com.finora.integrations.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin side of the trusted sender registry over real HTTP -- what the admin portal's Trusted
 * Senders page actually calls. {@link AdminTrustedSenderEndpointIT} proves an ordinary user is
 * refused; nothing proved the other half, that an admin holding SYSTEM_SETTINGS gets the response
 * the page is written against: the {@code success}/{@code data} envelope the portal's client
 * unwraps, the exact field names its types read, the server's own wording on a refused add, and
 * that disabling or enabling a domain changes the trust gate on the very next message.
 *
 * <p>Every domain added here is unique to the test ({@code UUID}-suffixed): this suite shares one
 * Postgres with no per-test rollback, and the registry has no hard delete, so a fixed domain would
 * leave state behind for a later test.
 */
class AdminTrustedSenderAdminFlowIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/admin/trusted-senders";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private TrustedSenderDomainRepository domains;
    @Autowired private SenderAuthenticationService authentication;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpHeaders admin;

    @BeforeEach
    void signInAsAdmin() {
        User user = new User();
        user.setEmail("trusted-sender-admin-it-" + UUID.randomUUID() + "@example.test");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Trusted Sender Admin");
        user.setRole("ADMIN");
        // Since V52 the scope decides whether a role's permissions are granted at all, so an
        // admin fixture must be an admin-portal account (see AdminFeatureFlagControllerIT).
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        admin = new HttpHeaders();
        admin.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        admin.setContentType(MediaType.APPLICATION_JSON);
    }

    private static String freshDomain() {
        return "trust-it-" + UUID.randomUUID() + ".example";
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String body) {
        return restTemplate.exchange(BASE + path, method, new HttpEntity<>(body, admin), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    private static String verdictFor(String domain) {
        return "mx.google.com; dmarc=pass (p=NONE) header.from=" + domain;
    }

    @Test
    @DisplayName("listing returns the envelope and field names the portal reads, with V217's rows in it")
    void listingHasTheShapeThePageReads() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, "", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(response);
        assertThat(body.get("success").asBoolean()).isTrue();
        JsonNode rows = body.get("data");
        assertThat(rows.isArray()).isTrue();

        JsonNode swiggy = null;
        for (JsonNode row : rows) {
            if ("swiggy.in".equals(row.get("domain").asText())) {
                swiggy = row;
            }
        }
        assertThat(swiggy).as("V217's swiggy.in row is in the listing").isNotNull();
        assertThat(swiggy.get("id").asText()).isNotBlank();
        assertThat(swiggy.get("merchantName").asText()).isEqualTo("Swiggy");
        assertThat(swiggy.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(swiggy.hasNonNull("createdAt")).isTrue();
        assertThat(swiggy.hasNonNull("updatedAt")).isTrue();
    }

    @Test
    @DisplayName("adding stores the domain normalized and trusts it on the very next message")
    void addingNormalizesAndTrusts() throws Exception {
        String domain = freshDomain();

        ResponseEntity<String> response = call(HttpMethod.POST, "",
                "{\"domain\":\"  " + domain.toUpperCase() + ".  \",\"merchantName\":\"  Fresh Merchant \"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = json(response).get("data");
        assertThat(created.get("domain").asText()).isEqualTo(domain);
        assertThat(created.get("merchantName").asText()).isEqualTo("Fresh Merchant");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(authentication.evaluate(verdictFor(domain)).verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.TRUSTED);
    }

    @Test
    @DisplayName("adding a domain already in the registry is refused with a reason the page can show")
    void addingADuplicateIsRefusedWithAReason() throws Exception {
        String domain = freshDomain();
        call(HttpMethod.POST, "", "{\"domain\":\"" + domain + "\",\"merchantName\":\"First\"}");

        ResponseEntity<String> response = call(HttpMethod.POST, "",
                "{\"domain\":\"" + domain + "\",\"merchantName\":\"Second\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).get("message").asText()).contains("already in the registry");
        assertThat(domains.findByDomain(domain).orElseThrow().getMerchantName()).isEqualTo("First");
    }

    @Test
    @DisplayName("a URL, an address and a wildcard are refused and nothing is written")
    void badInputIsRefusedAndNothingIsWritten() throws Exception {
        for (String bad : new String[] {"https://" + freshDomain(), "someone@" + freshDomain(), "*." + freshDomain()}) {
            ResponseEntity<String> response = call(HttpMethod.POST, "",
                    "{\"domain\":\"" + bad + "\",\"merchantName\":\"Bad\"}");

            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(json(response).get("message").asText()).as(bad).isNotBlank();
            assertThat(domains.findByDomain(bad)).as(bad).isEmpty();
        }
    }

    @Test
    @DisplayName("disabling stops trusting the domain at once, keeps the row, and enabling restores it")
    void disableAndEnableChangeTheGateImmediately() throws Exception {
        String domain = freshDomain();
        String id = json(call(HttpMethod.POST, "",
                "{\"domain\":\"" + domain + "\",\"merchantName\":\"Toggle\"}")).get("data").get("id").asText();
        // Warm the cache with the trusted answer, so the disable below has a real entry to evict.
        assertThat(authentication.evaluate(verdictFor(domain)).verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.TRUSTED);

        ResponseEntity<String> disabled = call(HttpMethod.DELETE, "/" + id, null);

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(disabled).get("data").get("status").asText()).isEqualTo("DISABLED");
        assertThat(domains.findByDomain(domain)).as("disabling never deletes").isPresent();
        assertThat(authentication.evaluate(verdictFor(domain)).verdict())
                .as("the next message is refused, not after a stale cache entry expires")
                .isEqualTo(SenderAuthenticationService.Verdict.DOMAIN_NOT_TRUSTED);

        ResponseEntity<String> enabled = call(HttpMethod.POST, "/" + id + "/enable", null);

        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(enabled).get("data").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(authentication.evaluate(verdictFor(domain)).verdict())
                .isEqualTo(SenderAuthenticationService.Verdict.TRUSTED);
    }

    @Test
    @DisplayName("renaming changes the merchant label and never the domain")
    void renamingChangesOnlyTheLabel() throws Exception {
        String domain = freshDomain();
        String id = json(call(HttpMethod.POST, "",
                "{\"domain\":\"" + domain + "\",\"merchantName\":\"Before\"}")).get("data").get("id").asText();

        ResponseEntity<String> response = call(HttpMethod.PUT, "/" + id, "{\"merchantName\":\"After\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = json(response).get("data");
        assertThat(updated.get("merchantName").asText()).isEqualTo("After");
        assertThat(updated.get("domain").asText()).isEqualTo(domain);
    }
}
