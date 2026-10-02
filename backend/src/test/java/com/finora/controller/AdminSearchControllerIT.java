package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Bank;
import com.finora.entity.CategoryRule;
import com.finora.entity.User;
import com.finora.repository.BankRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Global Search (AdminSearchController) -- proves the endpoint rejects requests with no token at
 *  all, rejects a plain (non-admin) user with 403 -- see the controller's own bug-fix comment for
 *  why this gate exists now -- and that an authorized (USER_VIEW) caller still gets correct
 *  fanned-out results for a real created User and a real created custom Bank. */
class AdminSearchControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private BankRepository bankRepository;
    @Autowired private CategoryRuleRepository categoryRuleRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role, String fullName) {
        User user = new User();
        user.setEmail("admin-search-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName(fullName);
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true); // see AdminRbacIT for why this must be set
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Bug fix: this built the URL with UriComponentsBuilder...toUriString(), which leaves the
     *  query value UNENCODED. A search term containing spaces ("Zephyr Global Search Target
     *  &lt;uuid&gt;") produced a malformed request line and the server saw a blank q, so the
     *  endpoint correctly returned [] and the test failed claiming the user could not be found.
     *  The single-word bank search in this same class passed throughout, which is what made it
     *  look like a search bug rather than a URL-building one. Passing the value as a URI template
     *  variable lets RestTemplate encode it, which is what it is for. */
    private ResponseEntity<String> search(String q, HttpHeaders headers) {
        return restTemplate.exchange("/api/v1/admin/search?q={q}", HttpMethod.GET,
                new HttpEntity<>(headers), String.class, q);
    }

    @Test
    void search_withNoTokenAtAll_isUnauthorized() {
        ResponseEntity<String> response = search("anything", new HttpHeaders());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void search_plainUserWithNoUserViewAuthority_isForbidden() {
        User plainUser = createUser("USER", "Plain User");

        ResponseEntity<String> response = search("anything", bearerFor(plainUser));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void search_anAuthorizedAdmin_findsARealUserByFullName() throws Exception {
        String uniqueName = "Zephyr Global Search Target " + UUID.randomUUID();
        User admin = createUser("ADMIN", "Search Admin");
        User target = createUser("USER", uniqueName);

        ResponseEntity<String> response = search(uniqueName, bearerFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        boolean found = false;
        for (JsonNode row : data) {
            if (row.get("type").asText().equals("user") && row.get("id").asText().equals(target.getId().toString())) {
                found = true;
                assertThat(row.get("title").asText()).isEqualTo(uniqueName);
                assertThat(row.get("link").asText()).isEqualTo("/users/" + target.getId());
            }
        }
        assertThat(found).as("expected user result for " + uniqueName).isTrue();
    }

    @Test
    void search_findsARealCustomBankByShortName() throws Exception {
        User admin = createUser("ADMIN", "Search Admin");
        String uniqueShortName = "ZBANK" + UUID.randomUUID().toString().substring(0, 8);

        Bank bank = new Bank();
        // banks.id is VARCHAR(30) -- it is the bank's own short code ("IOB"), not a generated
        // UUID; see V26__custom_banks.sql. "zbank-" + a full UUID is 42 characters and was
        // rejected with "value too long for type character varying(30)", failing this test in
        // setup before it reached its assertion. Never caught because this class had never run:
        // *IT did not match surefire's default includes (see pom.xml).
        bank.setId("zbank-" + UUID.randomUUID().toString().substring(0, 8));
        bank.setOfficialName("Zephyr Test Bank Ltd");
        bank.setShortName(uniqueShortName);
        bankRepository.save(bank);

        ResponseEntity<String> response = search(uniqueShortName, bearerFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        boolean found = false;
        for (JsonNode row : data) {
            if (row.get("type").asText().equals("bank") && row.get("id").asText().equals(bank.getId())) {
                found = true;
                assertThat(row.get("title").asText()).isEqualTo("Zephyr Test Bank Ltd");
                assertThat(row.get("link").asText()).isEqualTo("/banks");
            }
        }
        assertThat(found).as("expected bank result for " + uniqueShortName).isTrue();
    }

    @Test
    void search_blankQuery_returnsEmptyList() throws Exception {
        User admin = createUser("ADMIN", "Search Admin Two");
        ResponseEntity<String> response = search("", bearerFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }

    /** One search term carrying a literal underscore, run against real Postgres. The LIKE-backed
     *  sub-searches must treat _ literally (the decoy bank, whose name differs from the target
     *  only where the _ sits, must not match), and the in-memory Global Rules filter must still
     *  find the rule whose comparison value contains the term -- it used to receive the
     *  LIKE-escaped "zrule\_..." and match nothing. */
    @Test
    void search_termWithUnderscore_matchesLiterallyInBanksAndStillFindsGlobalRule() throws Exception {
        User admin = createUser("ADMIN", "Search Admin Three");
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Bank target = new Bank();
        target.setId("zb_" + suffix);
        target.setOfficialName("Zephyr Underscore Bank");
        target.setShortName("ZB_" + suffix);
        Bank decoy = new Bank();
        decoy.setId("zbx" + suffix);
        decoy.setOfficialName("Zephyr Decoy Bank");
        decoy.setShortName("ZBX" + suffix);
        bankRepository.save(target);
        bankRepository.save(decoy);

        // Disabled so the rule engine never evaluates it for any other test sharing this database;
        // admin search lists disabled global rules too (findByScopeOrderByPriorityAsc).
        CategoryRule rule = new CategoryRule();
        rule.setScope(CategoryRule.Scope.GLOBAL);
        rule.setField(CategoryRule.Field.DESCRIPTION);
        rule.setOperator(CategoryRule.Operator.CONTAINS);
        rule.setComparisonValue("ZRULE_" + suffix + " transfer");
        rule.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        rule.setActionValue("Transfers");
        rule.setEnabled(false);
        rule = categoryRuleRepository.save(rule);

        try {
            ResponseEntity<String> bankResponse = search("zb_" + suffix, bearerFor(admin));
            assertThat(bankResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(idsOfType(bankResponse, "bank")).containsExactly(target.getId());

            ResponseEntity<String> ruleResponse = search("zrule_" + suffix, bearerFor(admin));
            assertThat(ruleResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(idsOfType(ruleResponse, "rule")).containsExactly(rule.getId().toString());
        } finally {
            categoryRuleRepository.delete(rule);
            bankRepository.delete(target);
            bankRepository.delete(decoy);
        }
    }

    private List<String> idsOfType(ResponseEntity<String> response, String type) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : mapper.readTree(response.getBody()).get("data")) {
            if (row.get("type").asText().equals(type)) ids.add(row.get("id").asText());
        }
        return ids;
    }
}
