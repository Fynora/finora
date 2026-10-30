package com.finora.service;

import com.finora.dto.BillingOfferAdminDtos.BillingOfferAdminDto;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferCreateRequest;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferPlanOptionDto;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferState;
import com.finora.entity.BillingOffer;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.exception.ApiException;
import com.finora.notification.campaign.IstClock;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The save rules for billing offers at their exact edges, without a database. HTTP, permissions,
 * the table's constraints and the effect on the pricing card and checkout are covered by
 * AdminBillingOfferControllerIT.
 */
class BillingOfferAdminServiceTest {

    /** 10:00 IST on 1 Mar 2027. */
    private static final Instant NOW = IstClock.at(LocalDate.of(2027, 3, 1), LocalTime.of(10, 0));
    /** Holi 2027 window: from 00:00 IST 20 Mar to 00:00 IST 23 Mar (last day 22 Mar). */
    private static final Instant START = IstClock.at(LocalDate.of(2027, 3, 20), LocalTime.MIDNIGHT);
    private static final Instant END = IstClock.at(LocalDate.of(2027, 3, 23), LocalTime.MIDNIGHT);

    private final BillingOfferRepository offers = mock(BillingOfferRepository.class);
    private final BillingPriceRepository prices = mock(BillingPriceRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final BillingOfferService offerService = mock(BillingOfferService.class);
    private final AuditService audit = mock(AuditService.class);
    private final UUID admin = UUID.randomUUID();
    private final UUID plusId = UUID.randomUUID();
    private Plan plus;
    private BillingPrice plusMonthly;

    @BeforeEach
    void setUp() {
        plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusId);
        plus.setCode("PLUS");
        plus.setName("Plus");
        plusMonthly = price(plusId, "MONTHLY", "249.00", "plan_plus_m");
        when(plans.findByCode("PLUS")).thenReturn(Optional.of(plus));
        when(plans.findById(plusId)).thenReturn(Optional.of(plus));
        when(plans.findAll()).thenReturn(List.of(plus));
        when(prices.lockActive(plusId, "MONTHLY")).thenReturn(Optional.of(plusMonthly));
        when(prices.findByPlanIdAndBillingCycleAndActiveTrue(plusId, "MONTHLY")).thenReturn(Optional.of(plusMonthly));
        when(offers.findOverlapping(any(), anyString(), any(), any())).thenReturn(List.of());
        when(offers.saveAndFlush(any(BillingOffer.class))).thenAnswer(inv -> {
            BillingOffer o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
            return o;
        });
    }

    private static BillingPrice price(UUID planId, String cycle, String amount, String razorpayPlanId) {
        BillingPrice p = new BillingPrice();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setPlanId(planId);
        p.setBillingCycle(cycle);
        p.setPrice(new BigDecimal(amount));
        p.setRazorpayPlanId(razorpayPlanId);
        p.setActive(true);
        return p;
    }

    private BillingOfferAdminService serviceAt(Instant now) {
        return new BillingOfferAdminService(offers, prices, plans, offerService, audit, IstClock.fixedAt(now));
    }

    private static BillingOfferCreateRequest request(String price, Instant start, Instant end) {
        return new BillingOfferCreateRequest("holi2027", "PLUS", "MONTHLY", new BigDecimal(price), "Holi offer", start, end);
    }

    private ApiException refused(BillingOfferCreateRequest request) {
        try {
            serviceAt(NOW).create(admin, request);
        } catch (ApiException e) {
            verify(offers, never()).saveAndFlush(any());
            return e;
        }
        throw new AssertionError("expected the request to be refused");
    }

    private BillingOffer offer(Instant start, Instant end) {
        BillingOffer o = new BillingOffer();
        ReflectionTestUtils.setField(o, "id", UUID.randomUUID());
        o.setCode("HOLI2027");
        o.setPlanId(plusId);
        o.setBillingCycle("MONTHLY");
        o.setFirstPeriodPrice(new BigDecimal("99.00"));
        o.setLabel("Holi offer");
        o.setStartsAt(start);
        o.setEndsAt(end);
        return o;
    }

    // --- create ---

    @Test
    void aValidOffer_isSavedWithANormalisedCode_andAudited() {
        BillingOfferAdminDto dto = serviceAt(NOW).create(admin,
                new BillingOfferCreateRequest("  holi2027 ", "plus", "monthly", new BigDecimal("99"), "  Holi offer ", START, END));

        ArgumentCaptor<BillingOffer> saved = ArgumentCaptor.forClass(BillingOffer.class);
        verify(offers).saveAndFlush(saved.capture());
        BillingOffer row = saved.getValue();
        assertThat(row.getCode()).isEqualTo("HOLI2027");
        assertThat(row.getBillingCycle()).isEqualTo("MONTHLY");
        assertThat(row.getFirstPeriodPrice()).isEqualByComparingTo("99.00");
        assertThat(row.getFirstPeriodPrice().scale()).isEqualTo(2);
        assertThat(row.getLabel()).isEqualTo("Holi offer");
        assertThat(row.getStartsAt()).isEqualTo(START);
        assertThat(row.getEndsAt()).isEqualTo(END);

        assertThat(dto.state()).isEqualTo(BillingOfferState.UPCOMING);
        assertThat(dto.planCode()).isEqualTo("PLUS");
        assertThat(dto.regularPrice()).isEqualByComparingTo("249.00");
        assertThat(dto.appliedAtCheckout()).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(admin), eq("BILLING_OFFER_CREATED"), eq("BillingOffer"), eq(row.getId()), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("code", "HOLI2027").containsEntry("planCode", "PLUS")
                .containsEntry("firstPeriodPrice", "99.00").containsEntry("endsAt", END.toString());
    }

    @Test
    void anOfferStartingNow_isLive_andCountsAsApplied() {
        BillingOfferAdminDto dto = serviceAt(NOW).create(admin, request("99", NOW, END));
        assertThat(dto.state()).isEqualTo(BillingOfferState.LIVE);
        assertThat(dto.appliedAtCheckout()).isTrue();
    }

    @Test
    void thePrice_mustBeAtLeastOneRupee_andBelowTheRegularPrice() {
        assertThat(refused(request("0", START, END)).getMessage()).contains("at least Rs 1");
        assertThat(refused(request("-10", START, END)).getMessage()).contains("at least Rs 1");
        assertThat(refused(request("0.99", START, END)).getMessage()).contains("at least Rs 1");
        assertThat(refused(request("249.00", START, END)).getMessage()).contains("below the regular price of Rs 249.00");
        assertThat(refused(request("300", START, END)).getMessage()).contains("below the regular price");
        assertThat(refused(request("99.999", START, END)).getMessage()).contains("two decimal places");
    }

    @Test
    void thePriceEdges_oneRupee_andOnePaisaBelowRegular_areAccepted() {
        serviceAt(NOW).create(admin, request("1", START, END));
        serviceAt(NOW).create(admin, request("248.99", START, END));
        serviceAt(NOW).create(admin, request("99.990", START, END)); // a trailing zero is still whole paise
    }

    @Test
    void thePlanAndCycle_mustExist_andBeSetUpForCheckout() {
        assertThat(refused(new BillingOfferCreateRequest("HOLI2027", "GOLD", "MONTHLY", new BigDecimal("99"),
                "Holi offer", START, END)).getMessage()).contains("Unknown plan");
        assertThat(refused(new BillingOfferCreateRequest("HOLI2027", "PLUS", "WEEKLY", new BigDecimal("99"),
                "Holi offer", START, END)).getMessage()).contains("MONTHLY or YEARLY");

        when(prices.lockActive(plusId, "YEARLY")).thenReturn(Optional.empty());
        assertThat(refused(new BillingOfferCreateRequest("HOLI2027", "PLUS", "YEARLY", new BigDecimal("99"),
                "Holi offer", START, END)).getMessage()).contains("no active yearly price");

        plusMonthly.setRazorpayPlanId(null);
        ApiException noRazorpay = refused(request("99", START, END));
        assertThat(noRazorpay.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noRazorpay.getMessage()).contains("no Razorpay plan id");
    }

    @Test
    void theCode_andTheName_areChecked() {
        assertThat(refused(new BillingOfferCreateRequest("HO", "PLUS", "MONTHLY", new BigDecimal("99"),
                "Holi offer", START, END)).getMessage()).contains("3 to 40");
        assertThat(refused(new BillingOfferCreateRequest("HOLI 2027", "PLUS", "MONTHLY", new BigDecimal("99"),
                "Holi offer", START, END)).getMessage()).contains("3 to 40");
        assertThat(refused(new BillingOfferCreateRequest("HOLI2027", "PLUS", "MONTHLY", new BigDecimal("99"),
                "   ", START, END)).getMessage()).contains("Give the offer a name");
        assertThat(refused(new BillingOfferCreateRequest("HOLI2027", "PLUS", "MONTHLY", new BigDecimal("99"),
                "Holi\u0000", START, END)).getMessage()).contains("cannot be saved");
    }

    @Test
    void theWindow_endsAfterItStarts_endsInTheFuture_andStartsNoEarlierThanTheGrace() {
        assertThat(refused(request("99", END, END)).getMessage()).contains("end must be after the start");
        assertThat(refused(request("99", END, START)).getMessage()).contains("end must be after the start");
        assertThat(refused(request("99", NOW.minus(Duration.ofHours(2)), NOW)).getMessage()).contains("already passed");
        Instant justPastGrace = NOW.minus(BillingOfferAdminService.START_GRACE).minusSeconds(1);
        assertThat(refused(request("99", justPastGrace, END)).getMessage()).contains("start is in the past");
    }

    @Test
    void aStartExactlyAtTheGraceEdge_isAccepted() {
        serviceAt(NOW).create(admin, request("99", NOW.minus(BillingOfferAdminService.START_GRACE), END));
        verify(offers).saveAndFlush(any());
    }

    @Test
    void anOverlappingOffer_isRefusedWith409_namingTheOtherInIst() {
        BillingOffer diwali = offer(IstClock.at(LocalDate.of(2027, 3, 21), LocalTime.MIDNIGHT), END.plus(1, ChronoUnit.DAYS));
        diwali.setCode("SPRING2027");
        diwali.setLabel("Spring offer");
        when(offers.findOverlapping(plusId, "MONTHLY", START, END)).thenReturn(List.of(diwali));

        ApiException e = refused(request("99", START, END));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getMessage()).contains("Spring offer").contains("SPRING2027").contains("21 Mar 2027, 00:00 IST")
                .contains("End that offer first");
    }

    @Test
    void aCodeAlreadyUsedOnThePlanAndCycle_isRefusedWith409_upFrontOrWhenARaceHitsTheUniqueIndex() {
        when(offers.existsByCodeAndPlanIdAndBillingCycle("HOLI2027", plusId, "MONTHLY")).thenReturn(true);
        ApiException upFront = refused(request("99", START, END));
        assertThat(upFront.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(upFront.getMessage()).contains("already used");

        when(offers.existsByCodeAndPlanIdAndBillingCycle("HOLI2027", plusId, "MONTHLY")).thenReturn(false);
        when(offers.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));
        assertThatThrownBy(() -> serviceAt(NOW).create(admin, request("99", START, END)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("already used");
                });
        verify(audit, never()).record(any(), anyString(), anyString(), any(), anyMap());
    }

    // --- end now ---

    @Test
    void endingALiveOffer_setsItsEndToNow_keepsItsStart_andAuditsTheOldWindow() {
        BillingOffer live = offer(NOW.minus(Duration.ofDays(2)), END);
        when(offers.lockById(live.getId())).thenReturn(Optional.of(live));

        BillingOfferAdminDto dto = serviceAt(NOW).endNow(admin, live.getId());

        assertThat(live.getEndsAt()).isEqualTo(NOW);
        assertThat(live.getStartsAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(dto.state()).isEqualTo(BillingOfferState.ENDED);
        assertThat(dto.appliedAtCheckout()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(admin), eq("BILLING_OFFER_ENDED"), eq("BillingOffer"), eq(live.getId()), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("endsAt", NOW.toString()).containsEntry("endedBeforeStart", false);
        assertThat(metadata.getValue().get("before").toString()).contains(END.toString());
    }

    @Test
    void endingAnUpcomingOffer_leavesAWindowEntirelyInThePast() {
        BillingOffer upcoming = offer(START, END);
        when(offers.lockById(upcoming.getId())).thenReturn(Optional.of(upcoming));

        serviceAt(NOW).endNow(admin, upcoming.getId());

        assertThat(upcoming.getEndsAt()).isEqualTo(NOW);
        assertThat(upcoming.getStartsAt()).isEqualTo(NOW.minus(1, ChronoUnit.MICROS));
        assertThat(upcoming.getEndsAt()).isAfter(upcoming.getStartsAt()); // the table's CHECK holds
        assertThat(upcoming.isLiveAt(NOW)).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(admin), eq("BILLING_OFFER_ENDED"), eq("BillingOffer"), eq(upcoming.getId()), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("endedBeforeStart", true);
        assertThat(metadata.getValue().get("before").toString()).contains(START.toString());
    }

    @Test
    void endingAnOfferThatStartsAtThisVeryInstant_isTreatedAsUpcoming() {
        BillingOffer startingNow = offer(NOW, END);
        when(offers.lockById(startingNow.getId())).thenReturn(Optional.of(startingNow));

        serviceAt(NOW).endNow(admin, startingNow.getId());

        assertThat(startingNow.getEndsAt()).isAfter(startingNow.getStartsAt());
        assertThat(startingNow.isLiveAt(NOW)).isFalse();
    }

    @Test
    void endingAnEndedOffer_is409_andEndingAMissingOne_is404() {
        BillingOffer ended = offer(NOW.minus(Duration.ofDays(5)), NOW);
        when(offers.lockById(ended.getId())).thenReturn(Optional.of(ended));
        assertThatThrownBy(() -> serviceAt(NOW).endNow(admin, ended.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        UUID missing = UUID.randomUUID();
        when(offers.lockById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> serviceAt(NOW).endNow(admin, missing))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(offers, never()).saveAndFlush(any());
    }

    // --- list and plan options ---

    @Test
    void theList_marksOnlyTheOfferCheckoutWouldCharge_asApplied() {
        BillingOffer live = offer(NOW.minus(Duration.ofDays(1)), END);
        BillingOffer past = offer(NOW.minus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(20)));
        BillingOffer later = offer(END, END.plus(Duration.ofDays(3)));
        when(offers.findAllByOrderByStartsAtDescCreatedAtDesc()).thenReturn(List.of(later, live, past));
        when(prices.findByActiveTrue()).thenReturn(List.of(plusMonthly));
        when(offerService.liveOffer(plusId, "MONTHLY", NOW)).thenReturn(Optional.of(live));

        List<BillingOfferAdminDto> rows = serviceAt(NOW).list();

        assertThat(rows).extracting(BillingOfferAdminDto::state)
                .containsExactly(BillingOfferState.UPCOMING, BillingOfferState.LIVE, BillingOfferState.ENDED);
        assertThat(rows).extracting(BillingOfferAdminDto::appliedAtCheckout).containsExactly(false, true, false);
        assertThat(rows).allSatisfy(r -> assertThat(r.regularPrice()).isEqualByComparingTo("249.00"));
    }

    @Test
    void aLiveOfferCheckoutSkips_isListedAsNotApplied() {
        BillingOffer live = offer(NOW.minus(Duration.ofDays(1)), END);
        when(offers.findAllByOrderByStartsAtDescCreatedAtDesc()).thenReturn(List.of(live));
        when(prices.findByActiveTrue()).thenReturn(List.of(plusMonthly));
        when(offerService.liveOffer(plusId, "MONTHLY", NOW)).thenReturn(Optional.empty());

        BillingOfferAdminDto row = serviceAt(NOW).list().get(0);
        assertThat(row.state()).isEqualTo(BillingOfferState.LIVE);
        assertThat(row.appliedAtCheckout()).isFalse();
    }

    @Test
    void planOptions_arePaidActivePlansOnly_cheapestFirst_withCheckoutReadiness() {
        UUID freeId = UUID.randomUUID();
        Plan free = new Plan();
        ReflectionTestUtils.setField(free, "id", freeId);
        free.setCode("FREE");
        free.setName("Free");
        UUID retiredId = UUID.randomUUID();
        Plan retired = new Plan();
        ReflectionTestUtils.setField(retired, "id", retiredId);
        retired.setCode("OLD");
        retired.setName("Old");
        retired.setActive(false);
        when(plans.findAll()).thenReturn(List.of(plus, free, retired));
        BillingPrice plusYearly = price(plusId, "YEARLY", "1999.00", null);
        when(prices.findByActiveTrue()).thenReturn(List.of(plusYearly, price(freeId, "MONTHLY", "0.00", null),
                plusMonthly, price(retiredId, "MONTHLY", "99.00", "plan_old")));

        List<BillingOfferPlanOptionDto> options = serviceAt(NOW).planOptions();

        assertThat(options).extracting(BillingOfferPlanOptionDto::billingCycle).containsExactly("MONTHLY", "YEARLY");
        assertThat(options).extracting(BillingOfferPlanOptionDto::planCode).containsOnly("PLUS");
        assertThat(options).extracting(BillingOfferPlanOptionDto::checkoutReady).containsExactly(true, false);
    }
}
