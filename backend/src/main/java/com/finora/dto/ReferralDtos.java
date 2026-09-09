package com.finora.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Referral program DTOs (proposal §4) -- the user-facing "my code / my referrals / my wallet"
 *  surface and the admin-facing referral dashboard, kept together the same way BillingDtos keeps
 *  its user- and admin-facing records together. */
public class ReferralDtos {

    /** GET /api/v1/referrals/my-code -- lazily generated on first request, see
     *  {@code ReferralService.myCode}. */
    public record MyReferralCodeDto(String code) {}

    /** One row in a user's own "who I referred" list. */
    public record MyReferralDto(
            UUID referralId, String referredUserFullName, String status, BigDecimal reward, Instant createdAt
    ) {}

    /** GET /api/v1/referrals/mine. {@code code} is the user's own shareable code (kept at the top
     *  level, not a separate round trip -- both web and mobile read it directly off this
     *  response). {@code walletBalance} is the same computed SUM
     *  {@code WalletLedgerRepository.sumAmountByUserId} returns -- never a stored field.
     *  {@code referralCount} is kept alongside the richer {@code referrals} list purely for
     *  backward compatibility -- {@code frontend/src/pages/Billing.tsx} (a separate, in-flight
     *  redesign PR this work does not touch) already reads {@code referralCount} off this same
     *  endpoint's pre-existing MVP shape; removing it would silently break that page's build. It
     *  is always {@code referrals.size()}, never independently computed. */
    public record MyReferralsDto(String code, List<MyReferralDto> referrals, BigDecimal walletBalance, int referralCount) {}

    /** Admin Portal, Referral dashboard -- one row per referral, both parties identified (an admin
     *  reviewing for abuse needs to see who's on each side, unlike the user-facing view above). */
    public record AdminReferralSummaryDto(
            UUID referralId,
            UUID referrerUserId, String referrerEmail, String referrerFullName,
            UUID referredUserId, String referredEmail, String referredFullName,
            String status, BigDecimal reward, Instant createdAt
    ) {}

    /** Admin-only, manual (the actual reward amount is a per-referral product decision -- see
     *  {@code ReferralService.creditReward}'s own doc comment for why crediting is an admin
     *  action rather than an automatic one). */
    public record CreditReferralRewardRequest(
            @NotNull @DecimalMin(value = "0.01", message = "Reward amount must be greater than zero") BigDecimal amount,
            @NotBlank(message = "A reason is required") String reason
    ) {}
}
