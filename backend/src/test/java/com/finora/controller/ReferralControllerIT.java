package com.finora.controller;

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
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End to end -- a real user's own code, generated lazily against a real (empty) referral_codes
 *  table, and their own (empty) referrals list and zero wallet balance. */
class ReferralControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("referral-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Referral IT Test User");
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

    @Test
    void myCode_generatesARealCode_andReturnsTheSameOneOnASecondCall() throws Exception {
        User user = createUser();

        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/referrals/my-code", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/referrals/my-code", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstCode = mapper.readTree(first.getBody()).get("data").get("code").asText();
        String secondCode = mapper.readTree(second.getBody()).get("data").get("code").asText();
        assertThat(firstCode).isNotBlank();
        assertThat(secondCode).isEqualTo(firstCode);
    }

    @Test
    void mine_returnsTheCodeAnEmptyListAndZeroBalance_forAUserWhoHasReferredNoOne() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        // Precise pattern, not just isNotBlank(): a JSON `null` code (the exact regression this
        // guards against -- mine() previously read the code repository directly instead of
        // lazily creating one) still passes isNotBlank() because Jackson renders it as the
        // string "null", not an empty string.
        assertThat(data.get("code").asText()).matches("[0-9A-F]{8}");
        assertThat(data.get("referrals").size()).isZero();
        assertThat(data.get("walletBalance").asDouble()).isEqualTo(0.0);
        // referralCount: real end-to-end serialization check, not just the unit-level one in
        // ReferralServiceTest -- see MyReferralsDto's own doc comment for why this field exists.
        assertThat(data.get("referralCount").asInt()).isZero();
    }

    @Test
    void redeem_belowThresholdReturnsConflict() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/referrals/redeem", HttpMethod.POST, new HttpEntity<>("{\"tier\":\"PLUS\"}", bearerFor(user)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void applyCode_linksAFriendOnce_andRefusesASecondCodeWith409() throws Exception {
        User friendA = createUser();
        User friendB = createUser();
        User joiner = createUser();
        String codeA = mapper.readTree(restTemplate.exchange("/api/v1/referrals/my-code", HttpMethod.GET,
                new HttpEntity<>(bearerFor(friendA)), String.class).getBody()).get("data").get("code").asText();
        String codeB = mapper.readTree(restTemplate.exchange("/api/v1/referrals/my-code", HttpMethod.GET,
                new HttpEntity<>(bearerFor(friendB)), String.class).getBody()).get("data").get("code").asText();

        JsonNode before = mapper.readTree(restTemplate.exchange("/api/v1/referrals/mine", HttpMethod.GET,
                new HttpEntity<>(bearerFor(joiner)), String.class).getBody()).get("data");
        assertThat(before.get("canApplyCode").asBoolean()).isTrue();

        ResponseEntity<String> first = restTemplate.exchange("/api/v1/referrals/apply-code", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"" + codeA.toLowerCase() + "\"}", bearerFor(joiner)), String.class);
        ResponseEntity<String> second = restTemplate.exchange("/api/v1/referrals/apply-code", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"" + codeB + "\"}", bearerFor(joiner)), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already used a referral code");
        JsonNode after = mapper.readTree(restTemplate.exchange("/api/v1/referrals/mine", HttpMethod.GET,
                new HttpEntity<>(bearerFor(joiner)), String.class).getBody()).get("data");
        assertThat(after.get("canApplyCode").asBoolean()).isFalse();
    }

    @Test
    void applyCode_unknownAndOwnCodesAre400_andABlankBodyIsRejected() throws Exception {
        User user = createUser();
        String own = mapper.readTree(restTemplate.exchange("/api/v1/referrals/my-code", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class).getBody()).get("data").get("code").asText();

        ResponseEntity<String> unknown = restTemplate.exchange("/api/v1/referrals/apply-code", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"ZZZZ9999\"}", bearerFor(user)), String.class);
        ResponseEntity<String> self = restTemplate.exchange("/api/v1/referrals/apply-code", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"" + own + "\"}", bearerFor(user)), String.class);
        ResponseEntity<String> blank = restTemplate.exchange("/api/v1/referrals/apply-code", HttpMethod.POST,
                new HttpEntity<>("{\"code\":\"\"}", bearerFor(user)), String.class);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getBody()).contains("isn't valid");
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(self.getBody()).contains("your own referral code");
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
