package com.finora.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Admin surface for billing offers (V297): list, create and end-now. */
public final class BillingOfferAdminDtos {

    private BillingOfferAdminDtos() {
    }

    /** Where an offer's window stands at the moment it was read. Windows are end-exclusive. */
    public enum BillingOfferState { UPCOMING, LIVE, ENDED }

    /**
     * One offer row for the admin table.
     *
     * <p>{@code regularPrice} is the plan and cycle's active price now, or null when it has none.
     * {@code appliedAtCheckout} says whether a checkout made at the moment of reading would charge
     * this offer: false for anything not live, and also for a live offer that checkout skips because
     * its price is no longer below the regular price.
     */
    public record BillingOfferAdminDto(
            UUID id,
            String code,
            String planCode,
            String planName,
            String billingCycle,
            BigDecimal firstPeriodPrice,
            BigDecimal regularPrice,
            String label,
            Instant startsAt,
            Instant endsAt,
            BillingOfferState state,
            boolean appliedAtCheckout,
            Instant createdAt) {
    }

    /**
     * A plan and cycle an offer can be made for: a paid plan with an active price. {@code checkoutReady}
     * is false when that price has no Razorpay plan id, which checkout needs, so an offer on it is
     * refused.
     */
    public record BillingOfferPlanOptionDto(
            String planCode,
            String planName,
            String billingCycle,
            BigDecimal regularPrice,
            boolean checkoutReady) {
    }

    /**
     * Create an offer. {@code firstPeriodPrice} is the full first-period charge in rupees, GST
     * included, like {@code billing_prices.price}. The window is [startsAt, endsAt): an offer whose
     * last day is 7 Nov ends at 00:00 on 8 Nov, India time.
     */
    public record BillingOfferCreateRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 40) String planCode,
            @NotBlank @Size(max = 10) String billingCycle,
            @NotNull BigDecimal firstPeriodPrice,
            @NotBlank @Size(max = 80) String label,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt) {
    }
}
