package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.BillingOffer;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin billing offers API end to end: real HTTP through the security chain and real Postgres.
 * The lifecycle test also proves an offer created here reaches the public pricing endpoint and is
 * what checkout charges (Razorpay mocked, as in BillingControllerIT, so both classes share one
 * Spring context).
 *
 * <p>billing_offers is one global table, and V297 seeds the Diwali 2026 rows on Plus that other
 * classes assert on. So this class works on Premium only, gives every code it creates the
 * {@link #PREFIX} prefix, and deletes exactly those rows afterwards; it also puts back Premium's
 * Razorpay plan ids.
 */
class AdminBillingOfferControllerIT extends AbstractIntegrationTest {

    private static final String OFFERS = "/api/v1/admin/billing-offers";
    private static final String PREFIX = "AOIT";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private BillingOfferRepository billingOfferRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private final ObjectMapper mapper = new ObjectMapper();
    private BillingPrice premiumMonthly;
    private String savedMonthlyRazorpayPlanId;
    private BillingPrice premiumYearly;
    private String savedYearlyRazorpayPlanId;

    @BeforeEach
    void premiumIsReadyForCheckout() {
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        premiumMonthly = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        premiumYearly = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "YEARLY").orElseThrow();
        savedMonthlyRazorpayPlanId = premiumMonthly.getRazorpayPlanId();
        savedYearlyRazorpayPlanId = premiumYearly.getRazorpayPlanId();
        premiumMonthly.setRazorpayPlanId("plan_prem_m_" + UUID.randomUUID());
        premiumMonthly = billingPriceRepository.save(premiumMonthly);
        deleteOwnOffers();
    }

    @AfterEach
    void cleanUp() {
        deleteOwnOffers();
        premiumMonthly.setRazorpayPlanId(savedMonthlyRazorpayPlanId);
        billingPriceRepository.save(premiumMonthly);
        BillingPrice yearly = billingPriceRepository.findById(premiumYearly.getId()).orElseThrow();
        yearly.setRazorpayPlanId(savedYearlyRazorpayPlanId);
        billingPriceRepository.save(yearly);
    }

    private void deleteOwnOffers() {
        jdbcTemplate.update("DELETE FROM billing_offers WHERE code LIKE ?", PREFIX + "%");
    }

    private static String newCode() {
        return PREFIX + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private User createUser(String role, String scope) {
        User user = new User();
        user.setEmail("billing-offer-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Billing Offer IT User");
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

    private static String body(String code, String cycle, String price, Instant start, Instant end) {
        return "{\"code\":\"" + code + "\",\"planCode\":\"PREMIUM\",\"billingCycle\":\"" + cycle
                + "\",\"firstPeriodPrice\":" + price + ",\"label\":\"Test offer\",\"startsAt\":\"" + start
                + "\",\"endsAt\":\"" + end + "\"}";
    }

    private ResponseEntity<String> create(HttpHeaders headers, String json) {
        return restTemplate.exchange(OFFERS, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> endNow(HttpHeaders headers, String id) {
        return restTemplate.exchange(OFFERS + "/" + id + "/end", HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody()).get("data");
    }

    private String halfOf(BigDecimal price) {
        return price.divide(BigDecimal.valueOf(2), 2, RoundingMode.DOWN).toPlainString();
    }

    private long ownOfferCount() {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM billing_offers WHERE code LIKE ?", Long.class, PREFIX + "%");
        return n == null ? 0 : n;
    }

    // --- access ---

    @Test
    void anonymousCaller_isUnauthorized() {
        assertThat(restTemplate.getForEntity(OFFERS, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aPlainUser_andAnAdminWithoutSubscriptionPermissions_areForbiddenFromEveryEndpoint() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        String json = body(newCode(), "MONTHLY", "10", start, start.plus(1, ChronoUnit.DAYS));
        BillingOffer someOffer = billingOfferRepository.findAll().get(0);

        for (User caller : List.of(createUser("USER", User.SCOPE_USER), createUser("SUPPORT", User.SCOPE_ADMIN))) {
            HttpHeaders headers = bearerFor(caller);
            assertThat(restTemplate.exchange(OFFERS, HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode()).as(caller.getRole() + " list").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(restTemplate.exchange(OFFERS + "/plans", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                    .getStatusCode()).as(caller.getRole() + " plans").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(create(headers, json).getStatusCode()).as(caller.getRole() + " create").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(endNow(headers, someOffer.getId().toString()).getStatusCode())
                    .as(caller.getRole() + " end").isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(ownOfferCount()).isZero();
        BillingOffer untouched = billingOfferRepository.findById(someOffer.getId()).orElseThrow();
        assertThat(untouched.getEndsAt()).isEqualTo(someOffer.getEndsAt());
    }

    // --- the full lifecycle, through the pricing card and checkout ---

    @Test
    void anOfferCreatedHere_showsOnThePricingCard_isChargedAtCheckout_andEndNowStopsIt_allAudited() throws Exception {
        User adminUser = admin();
        HttpHeaders adminHeaders = bearerFor(adminUser);
        String code = newCode();
        String price = halfOf(premiumMonthly.getPrice());
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant end = start.plus(3, ChronoUnit.DAYS);

        ResponseEntity<String> created = create(adminHeaders, body(code.toLowerCase(), "monthly", price, start, end));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode row = data(created);
        String id = row.get("id").asText();
        assertThat(row.get("code").asText()).isEqualTo(code);
        assertThat(row.get("state").asText()).isEqualTo("LIVE");
        assertThat(row.get("appliedAtCheckout").asBoolean()).isTrue();
        assertThat(new BigDecimal(row.get("firstPeriodPrice").asText())).isEqualByComparingTo(price);

        // The public pricing card reads it straight away.
        ResponseEntity<String> publicOffers = restTemplate.getForEntity("/api/v1/billing/offers", String.class);
        JsonNode card = null;
        for (JsonNode o : data(publicOffers)) {
            if (code.equals(o.get("code").asText())) card = o;
        }
        assertThat(card).as("the new offer is on the public pricing endpoint").isNotNull();
        assertThat(card.get("planCode").asText()).isEqualTo("PREMIUM");
        assertThat(card.get("billingCycle").asText()).isEqualTo("MONTHLY");
        assertThat(new BigDecimal(card.get("firstPeriodPrice").asText())).isEqualByComparingTo(price);
        assertThat(Instant.parse(card.get("endsAt").asText())).isEqualTo(end);

        // Checkout charges the offer price up front, payable until the offer's end.
        User customer = createUser("USER", User.SCOPE_USER);
        subscriptionService.provisionFreeSubscription(customer.getId());
        String subId = "sub_aoit_" + UUID.randomUUID().toString().substring(0, 12);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscriptionWithFirstPeriod(eq(premiumMonthly.getRazorpayPlanId()), eq("MONTHLY"), anyMap(), any()))
                .thenReturn(new RazorpaySubscriptionDto(subId, "created"));
        ResponseEntity<String> checkout = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(customer)), String.class);
        assertThat(checkout.getStatusCode()).as(checkout.getBody()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<RazorpaySubscriptionGateway.FirstPeriod> firstPeriod =
                ArgumentCaptor.forClass(RazorpaySubscriptionGateway.FirstPeriod.class);
        verify(gateway).createSubscriptionWithFirstPeriod(eq(premiumMonthly.getRazorpayPlanId()), eq("MONTHLY"),
                anyMap(), firstPeriod.capture());
        assertThat(firstPeriod.getValue().amountPaise()).isEqualTo(new BigDecimal(price).movePointRight(2).longValueExact());
        assertThat(firstPeriod.getValue().payBy()).isEqualTo(end);
        assertThat(subscriptionOrderRepository.findByRazorpaySubscriptionId(subId).orElseThrow().getOfferCode()).isEqualTo(code);

        // The admin list shows it.
        ResponseEntity<String> list = restTemplate.exchange(OFFERS, HttpMethod.GET, new HttpEntity<>(adminHeaders), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains("\"code\":\"" + code + "\"").contains("\"code\":\"DIWALI2026\"");

        // End now: the customer's checkout, still unpaid on Razorpay, is cancelled there and
        // abandoned here, so it can no longer be paid at the offer price.
        when(gateway.fetchSubscription(subId)).thenReturn(new RazorpaySubscriptionDto(subId, "created"));
        when(gateway.findAuthorisationPayment(subId)).thenReturn(java.util.Optional.empty());
        ResponseEntity<String> ended = endNow(adminHeaders, id);
        assertThat(ended.getStatusCode()).as(ended.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(data(ended).get("offer").get("state").asText()).isEqualTo("ENDED");
        assertThat(data(ended).get("checkoutsCancelled").asInt()).isEqualTo(1);
        assertThat(data(ended).get("checkoutsNotCancelled").asInt()).isZero();
        verify(gateway).cancelSubscription(subId, false);
        assertThat(subscriptionOrderRepository.findByRazorpaySubscriptionId(subId).orElseThrow().getStatus())
                .isEqualTo(com.finora.entity.SubscriptionOrder.STATUS_ABANDONED);

        // Off the pricing card, never deleted, and a second end is refused.
        assertThat(restTemplate.getForEntity("/api/v1/billing/offers", String.class).getBody()).doesNotContain(code);
        BillingOffer stored = billingOfferRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getStartsAt()).isEqualTo(start);
        assertThat(stored.getEndsAt()).isBefore(end).isAfter(start);
        assertThat(endNow(adminHeaders, id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        List<AuditLog> audit = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(UUID.fromString(id));
        assertThat(audit).extracting(AuditLog::getAction)
                .containsExactly("BILLING_OFFER_CREATED", "BILLING_OFFER_ENDED", "BILLING_OFFER_CHECKOUTS_CANCELLED");
        assertThat(audit.get(2).getMetadata()).containsEntry("cancelled", 1).containsEntry("openCheckouts", 1);
        assertThat(audit).allSatisfy(a -> assertThat(a.getUserId()).isEqualTo(adminUser.getId()));
        assertThat(audit.get(0).getMetadata()).containsEntry("code", code).containsEntry("planCode", "PREMIUM");
        assertThat(audit.get(1).getMetadata().get("before").toString()).contains(end.toString());
    }

    @Test
    void endingAnUpcomingOffer_neverMakesItLive_andTheRowStays() throws Exception {
        HttpHeaders adminHeaders = bearerFor(admin());
        Instant start = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        ResponseEntity<String> created = create(adminHeaders,
                body(newCode(), "MONTHLY", halfOf(premiumMonthly.getPrice()), start, start.plus(2, ChronoUnit.DAYS)));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode row = data(created);
        assertThat(row.get("state").asText()).isEqualTo("UPCOMING");
        assertThat(row.get("appliedAtCheckout").asBoolean()).isFalse();

        Instant before = Instant.now();
        ResponseEntity<String> ended = endNow(adminHeaders, row.get("id").asText());
        assertThat(ended.getStatusCode()).as(ended.getBody()).isEqualTo(HttpStatus.OK);
        BillingOffer stored = billingOfferRepository.findById(UUID.fromString(row.get("id").asText())).orElseThrow();
        assertThat(stored.getEndsAt()).isAfter(stored.getStartsAt());
        assertThat(stored.getStartsAt()).isAfter(before.minusSeconds(1));
        assertThat(stored.isLiveAt(Instant.now())).isFalse();
    }

    @Test
    void endingAMissingOffer_is404() {
        assertThat(endNow(bearerFor(admin()), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- validation ---

    @Test
    void invalidOffers_areRefusedWith400_andNothingIsSaved() {
        HttpHeaders adminHeaders = bearerFor(admin());
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = start.plus(2, ChronoUnit.DAYS);
        String regular = premiumMonthly.getPrice().toPlainString();
        String half = halfOf(premiumMonthly.getPrice());

        List<String> refused = List.of(
                body(newCode(), "MONTHLY", "0", start, end),
                body(newCode(), "MONTHLY", "-5", start, end),
                body(newCode(), "MONTHLY", regular, start, end),
                body(newCode(), "MONTHLY", premiumMonthly.getPrice().add(BigDecimal.ONE).toPlainString(), start, end),
                body(newCode(), "MONTHLY", "10.555", start, end),
                body(newCode(), "MONTHLY", half, end, start),
                body(newCode(), "MONTHLY", half, start, start),
                body(newCode(), "MONTHLY", half, Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS)),
                body(newCode(), "MONTHLY", half, Instant.now().minus(1, ChronoUnit.DAYS), end),
                body(newCode(), "WEEKLY", half, start, end),
                body("X", "MONTHLY", half, start, end),
                "{\"code\":\"" + newCode() + "\",\"planCode\":\"GOLD\",\"billingCycle\":\"MONTHLY\",\"firstPeriodPrice\":10,"
                        + "\"label\":\"Test offer\",\"startsAt\":\"" + start + "\",\"endsAt\":\"" + end + "\"}",
                "{\"code\":\"" + newCode() + "\",\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}");
        for (String json : refused) {
            assertThat(create(adminHeaders, json).getStatusCode()).as(json).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(ownOfferCount()).isZero();
    }

    @Test
    void aPlanAndCycleWithNoRazorpayPlanId_isRefused_andShownAsNotReady() throws Exception {
        HttpHeaders adminHeaders = bearerFor(admin());
        BillingPrice yearly = billingPriceRepository.findById(premiumYearly.getId()).orElseThrow();
        yearly.setRazorpayPlanId(null);
        billingPriceRepository.save(yearly);
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);

        ResponseEntity<String> response = create(adminHeaders,
                body(newCode(), "YEARLY", halfOf(yearly.getPrice()), start, start.plus(1, ChronoUnit.DAYS)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Razorpay plan id");
        assertThat(ownOfferCount()).isZero();

        JsonNode options = data(restTemplate.exchange(OFFERS + "/plans", HttpMethod.GET, new HttpEntity<>(adminHeaders), String.class));
        boolean sawYearly = false;
        for (JsonNode o : options) {
            assertThat(o.get("planCode").asText()).isNotEqualTo("FREE");
            if ("PREMIUM".equals(o.get("planCode").asText()) && "YEARLY".equals(o.get("billingCycle").asText())) {
                sawYearly = true;
                assertThat(o.get("checkoutReady").asBoolean()).isFalse();
            }
            if ("PREMIUM".equals(o.get("planCode").asText()) && "MONTHLY".equals(o.get("billingCycle").asText())) {
                assertThat(o.get("checkoutReady").asBoolean()).isTrue();
            }
        }
        assertThat(sawYearly).isTrue();
    }

    @Test
    void overlappingOffers_areRefused_backToBackOnesAreFine_andACodeIsNotReusedOnThePlanAndCycle() {
        HttpHeaders adminHeaders = bearerFor(admin());
        String half = halfOf(premiumMonthly.getPrice());
        Instant a = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant b = a.plus(2, ChronoUnit.DAYS);
        Instant c = b.plus(2, ChronoUnit.DAYS);
        String firstCode = newCode();

        assertThat(create(adminHeaders, body(firstCode, "MONTHLY", half, a, b)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> overlap = create(adminHeaders, body(newCode(), "MONTHLY", half, a.plus(1, ChronoUnit.DAYS), c));
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overlap.getBody()).contains(firstCode);
        assertThat(create(adminHeaders, body(newCode(), "MONTHLY", half, b, c)).getStatusCode())
                .as("one offer may start at the exact instant another ends").isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> reused = create(adminHeaders, body(firstCode, "MONTHLY", half, c, c.plus(1, ChronoUnit.DAYS)));
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody()).contains("already used");
        assertThat(ownOfferCount()).isEqualTo(2);
    }
}
