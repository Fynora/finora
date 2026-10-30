package com.finora.service;

import com.finora.dto.BillingDtos.BillingOfferDto;
import com.finora.entity.BillingOffer;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BillingOfferServiceTest {

    private final BillingOfferRepository offers = mock(BillingOfferRepository.class);
    private final BillingPriceRepository prices = mock(BillingPriceRepository.class);
    private final PlanRepository plans = mock(PlanRepository.class);
    private final BillingOfferService service = new BillingOfferService(offers, prices, plans);

    private final UUID plusId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-01T06:00:00Z");

    @BeforeEach
    void setUp() {
        Plan plus = new Plan();
        ReflectionTestUtils.setField(plus, "id", plusId);
        plus.setCode("PLUS");
        when(plans.findById(plusId)).thenReturn(Optional.of(plus));
        price("MONTHLY", "249.00");
        price("YEARLY", "1999.00");
    }

    private void price(String cycle, String amount) {
        BillingPrice price = new BillingPrice();
        price.setPlanId(plusId);
        price.setBillingCycle(cycle);
        price.setPrice(new BigDecimal(amount));
        when(prices.findByPlanIdAndBillingCycleAndActiveTrue(plusId, cycle)).thenReturn(Optional.of(price));
    }

    private BillingOffer offer(String cycle, String amount) {
        BillingOffer offer = new BillingOffer();
        offer.setCode("DIWALI2026");
        offer.setPlanId(plusId);
        offer.setBillingCycle(cycle);
        offer.setFirstPeriodPrice(new BigDecimal(amount));
        offer.setLabel("Diwali offer");
        offer.setStartsAt(Instant.parse("2026-10-29T18:30:00Z"));
        offer.setEndsAt(Instant.parse("2026-11-07T18:30:00Z"));
        return offer;
    }

    @Test
    void returnsTheLiveOfferForThePlanAndCycle() {
        BillingOffer monthly = offer("MONTHLY", "125.00");
        when(offers.findLiveAt(now)).thenReturn(List.of(monthly, offer("YEARLY", "999.00")));

        assertThat(service.liveOffer(plusId, "MONTHLY", now)).contains(monthly);
    }

    @Test
    void anOfferNotBelowTheRegularPriceIsIgnored() {
        when(offers.findLiveAt(now)).thenReturn(List.of(offer("MONTHLY", "249.00")));

        assertThat(service.liveOffer(plusId, "MONTHLY", now)).isEmpty();
        assertThat(service.liveOffers(now)).isEmpty();
    }

    @Test
    void noOfferWhenNothingIsLive() {
        when(offers.findLiveAt(any())).thenReturn(List.of());

        assertThat(service.liveOffer(plusId, "MONTHLY", now)).isEmpty();
    }

    @Test
    void liveOffersListsOnePerPlanAndCycleWithTheRegularPrice() {
        // The repository returns cheapest first; a second, dearer offer on the same plan and cycle
        // is the one checkout would not use, so the page must not show it either.
        when(offers.findLiveAt(now)).thenReturn(List.of(
                offer("MONTHLY", "125.00"), offer("MONTHLY", "150.00"), offer("YEARLY", "999.00")));

        List<BillingOfferDto> live = service.liveOffers(now);

        assertThat(live).hasSize(2);
        assertThat(live.get(0).planCode()).isEqualTo("PLUS");
        assertThat(live.get(0).firstPeriodPrice()).isEqualByComparingTo("125.00");
        assertThat(live.get(0).regularPrice()).isEqualByComparingTo("249.00");
        assertThat(live.get(1).billingCycle()).isEqualTo("YEARLY");
        assertThat(live.get(1).regularPrice()).isEqualByComparingTo("1999.00");
        assertThat(live.get(1).endsAt()).isEqualTo(Instant.parse("2026-11-07T18:30:00Z"));
    }

    @Test
    void isLiveFromStartUntilJustBeforeEnd() {
        BillingOffer offer = offer("MONTHLY", "125.00");

        assertThat(offer.isLiveAt(Instant.parse("2026-10-29T18:30:00Z"))).isTrue();
        assertThat(offer.isLiveAt(Instant.parse("2026-11-07T18:29:59Z"))).isTrue();
        assertThat(offer.isLiveAt(Instant.parse("2026-11-07T18:30:00Z"))).isFalse();
        assertThat(offer.isLiveAt(Instant.parse("2026-10-29T18:29:59Z"))).isFalse();
    }

    @Test
    void regularBillingStartsOnePeriodLaterOnIndiasCalendar() {
        // 31 Oct 23:00 IST is still 31 Oct in India (17:30 UTC); a month on is the last day of
        // November, not 1 Dec.
        Instant lateOct31 = Instant.parse("2026-10-31T17:30:00Z");
        assertThat(BillingCalendar.dateOf(BillingOfferService.regularBillingStart(lateOct31, "MONTHLY")))
                .isEqualTo(java.time.LocalDate.of(2026, 11, 30));
        // 23:30 IST on 6 Nov is 18:00 UTC; UTC arithmetic would give the same date here, but the
        // time of day is kept, so the first charge is a full period after the payment.
        Instant nov6 = Instant.parse("2026-11-06T18:00:00Z");
        assertThat(BillingOfferService.regularBillingStart(nov6, "MONTHLY"))
                .isEqualTo(Instant.parse("2026-12-06T18:00:00Z"));
        assertThat(BillingOfferService.regularBillingStart(nov6, "YEARLY"))
                .isEqualTo(Instant.parse("2027-11-06T18:00:00Z"));
    }
}
