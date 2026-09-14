package com.finora.repository;

import com.finora.entity.BillingPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingPriceRepository extends JpaRepository<BillingPrice, UUID> {
    Optional<BillingPrice> findByPlanIdAndBillingCycleAndActiveTrue(UUID planId, String billingCycle);

    /** Includes deactivated rows -- unlike the method above, deliberately not scoped to the
     *  currently-active price. {@code RazorpayWebhookDispatcher.recoverOrderFromNotes} needs this
     *  for an order it is reconstructing after the fact, possibly well after the price that was
     *  active at the real checkout time has since been superseded; {@code SubscriptionOrder.amount}
     *  is audit/support-visibility only (never read by entitlements or payments, per that class's
     *  own doc), so a historical price is the correct answer here, not the current one. */
    List<BillingPrice> findByPlanIdAndBillingCycle(UUID planId, String billingCycle);
}
