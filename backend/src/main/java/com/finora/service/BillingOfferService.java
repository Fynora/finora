package com.finora.service;

import com.finora.dto.BillingDtos.BillingOfferDto;
import com.finora.entity.BillingOffer;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.repository.BillingOfferRepository;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Billing offers (V297): which first-period price applies to a checkout right now. The single
 * source for both the price checkout charges and the price the pricing pages show, so the two
 * can't disagree.
 */
@Service
public class BillingOfferService {

    private final BillingOfferRepository billingOfferRepository;
    private final BillingPriceRepository billingPriceRepository;
    private final PlanRepository planRepository;

    public BillingOfferService(BillingOfferRepository billingOfferRepository,
                               BillingPriceRepository billingPriceRepository, PlanRepository planRepository) {
        this.billingOfferRepository = billingOfferRepository;
        this.billingPriceRepository = billingPriceRepository;
        this.planRepository = planRepository;
    }

    /** The offer a checkout for this plan and cycle gets at {@code now}, if any. An offer whose
     *  price is not below the plan's regular price is ignored -- it would not be an offer. */
    @Transactional(readOnly = true)
    public Optional<BillingOffer> liveOffer(UUID planId, String billingCycle, Instant now) {
        BillingPrice regular = billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue(planId, billingCycle)
                .orElse(null);
        if (regular == null) return Optional.empty();
        return billingOfferRepository.findLiveAt(now).stream()
                .filter(o -> o.getPlanId().equals(planId) && o.getBillingCycle().equals(billingCycle))
                .filter(o -> o.getFirstPeriodPrice().compareTo(regular.getPrice()) < 0)
                .findFirst();
    }

    /** Every offer live at {@code now}, one per plan and cycle, for the pricing pages. */
    @Transactional(readOnly = true)
    public List<BillingOfferDto> liveOffers(Instant now) {
        List<BillingOfferDto> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BillingOffer offer : billingOfferRepository.findLiveAt(now)) {
            if (!seen.add(offer.getPlanId() + "/" + offer.getBillingCycle())) continue;
            Plan plan = planRepository.findById(offer.getPlanId()).orElse(null);
            BillingPrice regular = billingPriceRepository
                    .findByPlanIdAndBillingCycleAndActiveTrue(offer.getPlanId(), offer.getBillingCycle()).orElse(null);
            if (plan == null || regular == null || offer.getFirstPeriodPrice().compareTo(regular.getPrice()) >= 0) {
                continue;
            }
            result.add(new BillingOfferDto(plan.getCode(), offer.getBillingCycle(), offer.getCode(), offer.getLabel(),
                    offer.getFirstPeriodPrice(), regular.getPrice(), offer.getEndsAt()));
        }
        return result;
    }

    /** When the first regular charge falls for a checkout made at {@code paidAt}: one billing
     *  period later, on India's calendar (so 31 Oct monthly becomes 30 Nov, as Razorpay's own
     *  monthly periods do). */
    public static Instant regularBillingStart(Instant paidAt, String billingCycle) {
        ZonedDateTime start = paidAt.atZone(BillingCalendar.ZONE);
        return (BillingPrice.CYCLE_YEARLY.equals(billingCycle) ? start.plusYears(1) : start.plusMonths(1)).toInstant();
    }
}
