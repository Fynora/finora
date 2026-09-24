package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPriceRepositoryIT extends AbstractIntegrationTest {

    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private PlanRepository planRepository;

    @Test
    void findsTheActivePriceForAPlanAndCycle() {
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isPresent();
        assertThat(monthly.get().getPrice()).isEqualByComparingTo(new BigDecimal("799.00"));
    }

    @Test
    void freePlanHasNoBillingPriceRow() {
        Plan free = planRepository.findByCode("FREE").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(free.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isEmpty();
    }

    /**
     * V224: new checkouts get Rs 249 / Rs 1,999 (GST-inclusive). Prices only -- deliberately not the
     * active rows' razorpay_plan_id: BillingControllerIT and RazorpayWebhookDispatcherIT overwrite
     * that column on the shared seeded rows with plan_test_* ids and never restore it, and every IT
     * class shares one Postgres, so asserting it here passes or fails on test order (it failed on
     * main's CI run for #1748). V224MigrationTest pins the new plan ids against the migration itself.
     */
    @Test
    void plusIsRepricedTo249And1999() {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();

        BillingPrice monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), BillingPrice.CYCLE_MONTHLY).orElseThrow();
        BillingPrice yearly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), BillingPrice.CYCLE_YEARLY).orElseThrow();

        assertThat(monthly.getPrice()).isEqualByComparingTo(new BigDecimal("249.00"));
        assertThat(yearly.getPrice()).isEqualByComparingTo(new BigDecimal("1999.00"));
    }

    /** V224 deactivates the old Plus rows rather than deleting them: a webhook for a subscription
     *  still on an old Razorpay plan must keep resolving to Plus (RazorpayWebhookDispatcher looks
     *  up by razorpay_plan_id across every row, active or not). */
    @Test
    void theOldPlusPricesAreKeptButInactive() {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();

        var monthlyRows = billingPriceRepository.findByPlanIdAndBillingCycle(plus.getId(), BillingPrice.CYCLE_MONTHLY);
        var yearlyRows = billingPriceRepository.findByPlanIdAndBillingCycle(plus.getId(), BillingPrice.CYCLE_YEARLY);

        assertThat(monthlyRows).anySatisfy(bp -> {
            assertThat(bp.getRazorpayPlanId()).isEqualTo("plan_TYgEidywnYfCIM");
            assertThat(bp.getPrice()).isEqualByComparingTo(new BigDecimal("399.00"));
            assertThat(bp.isActive()).isFalse();
        });
        assertThat(yearlyRows).anySatisfy(bp -> {
            assertThat(bp.getRazorpayPlanId()).isEqualTo("plan_TYgFh62Y5hbRMn");
            assertThat(bp.isActive()).isFalse();
        });
        assertThat(monthlyRows.stream().filter(BillingPrice::isActive)).hasSize(1);
        assertThat(yearlyRows.stream().filter(BillingPrice::isActive)).hasSize(1);
    }
}
