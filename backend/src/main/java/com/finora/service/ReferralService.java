package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.dto.ReferralDtos.ReferralGrantDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCharge;
import com.finora.entity.ReferralCode;
import com.finora.entity.ReferralGrant;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationRequest;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ReferralChargeRepository;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import com.finora.util.PageBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The referral program (proposal §4) -- codes, invite tracking, and reward crediting. Reward
 * CREDITING is deliberately admin-manual (see {@link #creditReward}), not automatic: the actual
 * reward amount is a per-referral product decision, same reasoning {@code SubscriptionService}
 * gives for why admin-manual plan grants exist. Everything ELSE in the lifecycle -- code
 * issuance, redemption at registration, and the REGISTERED -> SUBSCRIBED transition -- is
 * automatic, because none of those steps require inventing a business term.
 */
@Service
public class ReferralService {

    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);

    /** Referrals reaching SUBSCRIBED needed for one free month of Plus. */
    public static final int MILESTONE_REFERRALS = 7;

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ReferralGrantRepository referralGrantRepository;
    private final NotificationService notificationService;
    private final ReferralChargeRepository referralChargeRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            WalletLedgerRepository walletLedgerRepository, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuditService auditService,
                            ReferralGrantRepository referralGrantRepository, NotificationService notificationService,
                            ReferralChargeRepository referralChargeRepository) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.referralGrantRepository = referralGrantRepository;
        this.notificationService = notificationService;
        this.referralChargeRepository = referralChargeRepository;
    }

    /** Lazily creates the user's own shareable code on first request -- there is no natural
     *  earlier moment (registration itself is the one time we can't hand out "your own" code, since
     *  the account doesn't exist yet). */
    @Transactional
    public String myCode(UUID userId) {
        return referralCodeRepository.findByUserId(userId)
                .map(ReferralCode::getCode)
                .orElseGet(() -> {
                    ReferralCode created = new ReferralCode();
                    created.setUserId(userId);
                    created.setCode(generateUniqueCode());
                    return referralCodeRepository.save(created).getCode();
                });
    }

    /** 8 uppercase hex characters -- same SecureRandom + hex convention as
     *  {@code AdminMfaService.generateRecoveryCodes}, short enough to type or paste into a
     *  registration field. Retried on the (astronomically unlikely) collision against the UNIQUE
     *  column rather than trusting one draw. */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            byte[] raw = new byte[4];
            secureRandom.nextBytes(raw);
            StringBuilder hex = new StringBuilder();
            for (byte b : raw) hex.append(String.format("%02X", b));
            String code = hex.toString();
            if (!referralCodeRepository.existsByCode(code)) return code;
        }
        throw new IllegalStateException("Could not generate a unique referral code after 5 attempts.");
    }

    /**
     * Called from {@code AuthService.register()} only -- not the shared {@code createUserRecord}
     * helper, so admin-assisted signup ({@code adminCreateUser}) never creates a referral (there is
     * no organic acquisition to track there). Fails silently (logs, does not throw) on a
     * missing/invalid code: a mistyped or stale referral code must never block someone from
     * completing registration -- the one thing this method is not allowed to do is turn a cosmetic
     * referral link into a hard signup failure. Self-referral is rejected the same way: since
     * {@code referredUserId} is a brand-new account, it can never already own the code being
     * redeemed today, but the check is kept explicit rather than relying on that being true forever.
     */
    @Transactional
    public void redeemCode(UUID referredUserId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return;

        Optional<ReferralCode> code = referralCodeRepository.findByCode(rawCode.trim().toUpperCase());
        if (code.isEmpty()) {
            log.info("Referral code {} not recognized at registration for user {} -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }
        if (code.get().getUserId().equals(referredUserId)) {
            log.info("Referral code {} belongs to the account being created ({}) -- ignored, not blocking signup.",
                    rawCode, referredUserId);
            return;
        }

        Referral referral = new Referral();
        referral.setReferrerUserId(code.get().getUserId());
        referral.setReferredUserId(referredUserId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        referral = referralRepository.save(referral);

        auditService.record(referredUserId, "REFERRAL_REGISTERED", "Referral", referral.getId(),
                Map.of("referrerUserId", code.get().getUserId().toString()));
    }

    /**
     * Called from {@code RazorpayWebhookDispatcher.handleCharged} and
     * {@code RevenueCatWebhookDispatcher}'s INITIAL_PURCHASE/RENEWAL handlers, only for a real,
     * confirmed, non-trial charge -- deciding that is each dispatcher's job, since only it can read
     * its provider's payload (design spec §5: {@code subscription.activated} can fire with zero
     * funds movement, so it is deliberately NOT a call site for this; a RevenueCat free trial is
     * {@code period_type=TRIAL} with price 0). A purely factual transition (this user is now
     * paying) -- no business term is being invented by observing it, so it happens automatically,
     * unlike reward crediting. Takes no admin id -- there is no admin in scope at a webhook call
     * site.
     *
     * <p>Silently a no-op if the user was never referred, was already past REGISTERED,
     * {@code newPlanCode} is FREE (a downgrade/reconciliation must never re-trigger this), or the
     * charge has no provider id. The last one is deliberate: a charge that cannot be named cannot
     * be matched to its refund later, so counting it would reopen the refund gap
     * {@link #onChargeReversed} closes.
     *
     * <p>Records the charge in {@code referral_charges} (V241), counted or not, so its refund can
     * take the referral back. A charge already recorded never counts again: that stops a
     * redelivered purchase event from re-counting a charge that was since refunded.
     *
     * @param provider  ReferralCharge.PROVIDER_RAZORPAY or PROVIDER_REVENUECAT
     * @param chargeRef the provider's own id for this charge: Razorpay's payment id, RevenueCat's
     *                  transaction_id
     */
    @Transactional
    public void onReferredUserCharged(UUID userId, String newPlanCode, String provider, String chargeRef) {
        if ("FREE".equals(newPlanCode)) return;
        if (chargeRef == null || chargeRef.isBlank()) {
            log.warn("{} charge for user {} carries no charge id -- not counted toward a referral, since its "
                    + "refund could never be matched back to it.", provider, userId);
            return;
        }
        Referral referral = referralRepository.findByReferredUserId(userId)
                .filter(r -> Referral.STATUS_REGISTERED.equals(r.getStatus()))
                .orElse(null);
        if (referral == null) return;
        if (referralChargeRepository.existsByProviderAndChargeRef(provider, chargeRef)) {
            log.info("{} charge {} already moved a referral once -- not counted again.", provider, chargeRef);
            return;
        }

        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        referralRepository.save(referral);
        auditService.record(userId, "REFERRAL_SUBSCRIBED", "Referral", referral.getId(),
                Map.of("referrerUserId", referral.getReferrerUserId().toString(), "planCode", newPlanCode));
        boolean counted = incrementMilestoneCountersIfEligible(referral);

        ReferralCharge charge = new ReferralCharge();
        charge.setReferrerUserId(referral.getReferrerUserId());
        charge.setReferralId(referral.getId());
        charge.setProvider(provider);
        charge.setChargeRef(chargeRef);
        charge.setCounted(counted);
        referralChargeRepository.save(charge);
    }

    /**
     * A refund or a lost chargeback of a referred user's charge. If that charge is the one that
     * moved their referral to SUBSCRIBED, the referral goes back to REGISTERED and, if the charge
     * was counted, 1 comes off the referrer's milestone counter. Any other charge (a later renewal,
     * a charge before the user was referred) matches no {@code referral_charges} row and changes
     * nothing.
     *
     * <p>Works after the referred account has been purged: the purge deletes the referral and
     * payment rows, but not the {@code referral_charges} row (see V241), which still names the
     * referrer. Without that, "pay, get counted, delete the account, get refunded" would keep the
     * count.
     *
     * <p>A month already redeemed is not clawed back, but the referral is owed: the counter may go
     * below 0. Example: 7 friends pay, the referrer redeems (counter 7 to 0, a grant is created),
     * then one friend is refunded: the grant stands and the counter goes to -1, so the next
     * referral brings it back to 0 rather than to 1. Without that, "redeem at once, then have
     * every friend refunded" earned a month for free. The referrer is told (REFERRAL_REVERSED),
     * with the count shown clamped at 0; the API reports the debt separately (MyReferralsDto).
     *
     * <p>Idempotent and race-safe: the charge row is read row-locked
     * ({@link ReferralChargeRepository#findForUpdate}), and a charge already reversed is left
     * alone, so a refund and a chargeback of the same payment, or one event re-sent, reverse it
     * once. The counter is then changed under the same referral_codes row lock the increment and
     * {@link ReferralCodeRepository#consumeMilestoneIfAtLeast} take, so it cannot interleave with
     * either.
     *
     * <p>A refund that arrives before its own charge has been processed (neither provider
     * guarantees webhook order) leaves a row born reversed, with no referrer, so the charge never
     * counts when it does arrive. See {@link ReferralChargeRepository#insertReversedIfAbsent}.
     *
     * <p>A referral an admin already cash-REWARDED through the dormant {@link #creditReward} path
     * keeps its status: the wallet credit is a separate record this does not reverse. Logged for
     * manual follow-up.
     *
     * @param reason short machine-readable reason, e.g. REFUND or CHARGEBACK_LOST
     */
    @Transactional
    public void onChargeReversed(String provider, String chargeRef, String reason) {
        if (chargeRef == null || chargeRef.isBlank()) return;
        ReferralCharge charge = referralChargeRepository.findForUpdate(provider, chargeRef).orElse(null);
        if (charge == null) {
            // No row: either a charge that never moved a referral, or one whose own webhook has
            // not been processed yet (delivery order is not guaranteed). Record the id as
            // reversed so the second case can never count later.
            if (referralChargeRepository.insertReversedIfAbsent(provider, chargeRef, reason) == 1) {
                log.info("{} charge {} reversed ({}) before any referral recorded it -- marked so it never counts.",
                        provider, chargeRef, reason);
                return;
            }
            // A concurrent transaction committed this charge's row meanwhile: act on it.
            charge = referralChargeRepository.findForUpdate(provider, chargeRef).orElse(null);
            if (charge == null) return;
        }
        if (charge.getReversedAt() != null) return;

        charge.setReversedAt(Instant.now());
        charge.setReversalReason(reason);
        referralChargeRepository.save(charge);

        Integer counterAfter = null;
        if (charge.isCounted()) {
            ReferralCode code = referralCodeRepository.findByUserIdForUpdate(charge.getReferrerUserId()).orElse(null);
            if (code != null) {
                // Allowed below 0 (Sid, 2026-09-28): the month this referral helped earn was
                // already redeemed, and is kept, but the referral is owed back -- the referrer's
                // next referral repays it before counting toward a new month. A floor at 0 let
                // "redeem at once, then have every friend refunded" earn a month for free.
                code.setPremiumMilestoneCounter(code.getPremiumMilestoneCounter() - 1);
                referralCodeRepository.save(code);
                counterAfter = code.getPremiumMilestoneCounter();
                if (counterAfter < 0) {
                    log.info("Referral charge {} reversed ({}) after the month it helped earn was redeemed -- referrer {} "
                            + "now owes {} referral(s).", charge.getId(), reason, charge.getReferrerUserId(), -counterAfter);
                }
                notificationService.request(NotificationRequest.of(
                        charge.getReferrerUserId(),
                        NotificationType.REFERRAL_REVERSED,
                        NotificationCategory.FINANCIAL,
                        NotificationPriority.NORMAL,
                        "REFERRAL_REVERSED_" + charge.getId(),
                        Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                        Map.of("count", String.valueOf(Math.max(0, counterAfter)))));
            }
        }

        if (charge.getReferralId() != null) {
            referralRepository.findById(charge.getReferralId()).ifPresent(referral -> {
                if (Referral.STATUS_SUBSCRIBED.equals(referral.getStatus())) {
                    referral.setStatus(Referral.STATUS_REGISTERED);
                    referralRepository.save(referral);
                } else if (Referral.STATUS_REWARDED.equals(referral.getStatus())) {
                    log.warn("Referral {} was cash-REWARDED by an admin and its charge was since reversed ({}). "
                            + "The wallet credit is not reversed automatically -- needs manual review.",
                            referral.getId(), reason);
                }
            });
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", provider);
        metadata.put("reason", reason);
        metadata.put("counted", charge.isCounted());
        if (counterAfter != null) metadata.put("counterAfter", counterAfter);
        auditService.record(charge.getReferrerUserId(), "REFERRAL_CHARGE_REVERSED", "ReferralCharge", charge.getId(), metadata);
    }

    /**
     * Moves the self-referral fraud check that used to gate only admin-manual creditReward
     * earlier, to counter-increment time -- redemption is now self-service with no admin in the
     * loop, so the check can no longer happen only at the old manual-approval step (design spec
     * section 2). A flagged pair's referral increments neither counter and sends no notification;
     * onReferredUserCharged's own SUBSCRIBED transition above still happens either way, since that part is
     * a plain factual observation, not a reward.
     *
     * <p>One milestone: {@link #MILESTONE_REFERRALS} referrals earn one free month of Plus. The
     * earlier two-tier design (3 for Plus, 7 for Premium) was dropped while Premium is hidden from
     * sale -- a reward for a plan nobody can see or buy. The count lives in the
     * premium_milestone_counter column (the one that was already tracking toward 7 and was never
     * reset by a 3-referral redemption, so nobody lost progress in the switch);
     * plus_milestone_counter is no longer incremented or read.
     *
     * @return whether the counter was actually incremented
     */
    private boolean incrementMilestoneCountersIfEligible(Referral referral) {
        if (sharesADeviceOrIp(referral.getReferrerUserId(), referral.getReferredUserId())) {
            log.info("Referral {} not counted toward a milestone -- referrer/referred share a device/IP.",
                    referral.getId());
            return false;
        }
        // Row-locked read: see findByUserIdForUpdate for the races a plain read allowed.
        ReferralCode code = referralCodeRepository.findByUserIdForUpdate(referral.getReferrerUserId()).orElse(null);
        if (code == null) return false;

        int updated = code.getPremiumMilestoneCounter() + 1;
        code.setPremiumMilestoneCounter(updated);
        referralCodeRepository.save(code);

        notificationService.request(NotificationRequest.of(
                referral.getReferrerUserId(),
                NotificationType.REFERRAL_FRIEND_SUBSCRIBED,
                NotificationCategory.FINANCIAL,
                NotificationPriority.NORMAL,
                "REFERRAL_FRIEND_SUBSCRIBED_" + referral.getId(),
                Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                Map.of("count", String.valueOf(Math.max(0, updated)))));

        // Every multiple, not just the first: redeeming subtracts 7 rather than resetting, so an
        // unredeemed 14 is a second earned month and gets its own "redeem it now".
        // updated > 0: a counter climbing back from a refund debt passes through 0, which is a
        // multiple of 7 but no reward.
        if (updated > 0 && updated % MILESTONE_REFERRALS == 0) {
            notificationService.request(NotificationRequest.of(
                    referral.getReferrerUserId(),
                    NotificationType.REFERRAL_MILESTONE_REACHED,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "REFERRAL_MILESTONE_REACHED_PLUS_" + referral.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("tier", ReferralGrant.TIER_PLUS)));
        }
        return true;
    }

    /**
     * Self-service redemption (design spec sections 2/3). Always grants Plus and takes
     * {@link #MILESTONE_REFERRALS} off the one milestone counter (not a reset to 0, so referrals
     * beyond 7 carry over toward the next month). The self-referral fraud check already ran at counter-increment time
     * above; nothing further to check here.
     *
     * <p>{@code tier} is accepted as either PLUS or PREMIUM and treated the same: app builds
     * already installed on phones still show the old "toward Premium" row at 7 and send PREMIUM
     * when it is tapped. Rejecting that would strand a reward they were told to redeem.
     *
     * <p>The actual eligibility check is the conditional counter-reset UPDATE below, not the plain
     * read a few lines above it -- that read exists only to give a specific "not enough referrals"
     * error message before bothering to run the real check. Two concurrent redeem requests (a
     * double-click, two open tabs) both reading a pre-reset counter and both passing a Java-side
     * `if` would create two grants for one threshold crossing; see
     * {@link ReferralCodeRepository#consumeMilestoneIfAtLeast} for why the UPDATE itself is
     * what closes that race.
     *
     * @param tier ReferralGrant.TIER_PLUS or ReferralGrant.TIER_PREMIUM -- both grant Plus
     */
    @Transactional
    public void redeemMilestone(UUID userId, String tier) {
        if (!ReferralGrant.TIER_PLUS.equals(tier) && !ReferralGrant.TIER_PREMIUM.equals(tier)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown reward tier: " + tier);
        }
        ReferralCode code = referralCodeRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No referral progress to redeem."));
        int current = code.getPremiumMilestoneCounter();
        if (current < MILESTONE_REFERRALS) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Not enough referrals yet -- you have " + Math.max(0, current) + ", need " + MILESTONE_REFERRALS + ".");
        }

        int consumed = referralCodeRepository.consumeMilestoneIfAtLeast(userId, MILESTONE_REFERRALS);
        if (consumed == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This reward was just redeemed by another request. Not redeemed again.");
        }

        // Best-effort only -- see this field's own doc comment on why a referral not being found
        // here (e.g. every qualifying one was since cash-REWARDED by an admin, or hard-deleted by
        // an account purge) is never a reason to fail a redemption whose eligibility the atomic
        // reset above already confirmed.
        UUID triggeringReferralId = referralRepository
                .findFirstByReferrerUserIdAndStatusIn(userId, List.of(Referral.STATUS_SUBSCRIBED, Referral.STATUS_REWARDED))
                .map(Referral::getId)
                .orElse(null);

        ReferralGrant grant = new ReferralGrant();
        grant.setUserId(userId);
        grant.setTier(ReferralGrant.TIER_PLUS);
        grant.setStatus(ReferralGrant.STATUS_PENDING);
        grant.setEarnedFromReferralId(triggeringReferralId);
        referralGrantRepository.save(grant);

        auditService.record(userId, "REFERRAL_MILESTONE_REDEEMED", "ReferralGrant", grant.getId(), Map.of("tier", ReferralGrant.TIER_PLUS, "requestedTier", tier));
    }

    /**
     * Deliberately NOT {@code readOnly = true}: this calls {@link #myCode} via a plain
     * self-invocation, which bypasses Spring's proxy and so runs under THIS method's transaction
     * rather than getting its own -- under a read-only transaction that write would be silently
     * dropped (Hibernate's MANUAL flush mode eats it with no exception), the exact class of bug
     * this codebase has already hit twice elsewhere. Calling {@code myCode} here (not just reading
     * {@code referralCodeRepository} directly) matters: a user who opens this page before ever
     * hitting {@code /my-code} must still get a real code back, not {@code null}.
     */
    @Transactional
    public MyReferralsDto myReferrals(UUID userId) {
        String code = myCode(userId);
        var referrals = referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, User> usersById = userRepository.findAllById(
                referrals.stream().map(Referral::getReferredUserId).distinct().toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));

        var dtos = referrals.stream().map(r -> {
            User referred = usersById.get(r.getReferredUserId());
            return new MyReferralDto(r.getId(), referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }).toList();

        BigDecimal balance = walletLedgerRepository.sumAmountByUserId(userId);
        Optional<ReferralCode> referralCode = referralCodeRepository.findByUserId(userId);
        // plusMilestoneCounter is always 0: the 3-referral reward no longer exists, and a real
        // value here would make older app builds offer a Redeem at 3 that the server now rejects.
        int plusMilestoneCounter = 0;
        int storedCounter = referralCode.map(ReferralCode::getPremiumMilestoneCounter).orElse(0);
        // Negative after a refund of an already-redeemed referral -- see MyReferralsDto.
        int premiumMilestoneCounter = Math.max(0, storedCounter);
        int referralsOwed = Math.max(0, -storedCounter);
        List<ReferralGrantDto> grants = referralGrantRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(g -> new ReferralGrantDto(g.getId(), g.getTier(), g.getStatus(), g.getActivatedAt(), g.getExpiresAt()))
                .toList();
        return new MyReferralsDto(code, dtos, balance, dtos.size(), plusMilestoneCounter, premiumMilestoneCounter, grants,
                referralsOwed);
    }

    /** Admin Portal, Referral dashboard. An unconditional {@code findAll()} across the whole table
     *  would grow with referral volume -- same reasoning {@code SubscriptionService.listAll}'s own
     *  doc comment gives for the identical fix there. The user batch-fetch below is already scoped
     *  to just this page's referrer/referred ids, not the whole table. */
    @Transactional(readOnly = true)
    public PagedResponse<AdminReferralSummaryDto> listAll(int page, int size) {
        Page<Referral> referrals = referralRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size)));
        Set<UUID> userIds = new HashSet<>();
        referrals.forEach(r -> { userIds.add(r.getReferrerUserId()); userIds.add(r.getReferredUserId()); });
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return PagedResponse.of(referrals.map(r -> {
            User referrer = usersById.get(r.getReferrerUserId());
            User referred = usersById.get(r.getReferredUserId());
            return new AdminReferralSummaryDto(
                    r.getId(),
                    r.getReferrerUserId(), referrer != null ? referrer.getEmail() : null, referrer != null ? referrer.getFullName() : null,
                    r.getReferredUserId(), referred != null ? referred.getEmail() : null, referred != null ? referred.getFullName() : null,
                    r.getStatus(), r.getReward(), r.getCreatedAt());
        }));
    }

    /**
     * Admin-only, manual (see this class's own doc comment for why). Fails closed on suspected
     * self-referral -- reusing {@code RefreshToken}'s own device/IP capture, not a new
     * fingerprinting mechanism. The status check below rejects a SEQUENTIAL retry against an
     * already-REWARDED referral, but is not by itself enough to stop two CONCURRENT credit
     * requests for the same referral (a double-click, a retried request) from both reading
     * SUBSCRIBED before either commits and both crediting the wallet -- the actual insert
     * therefore goes through {@link WalletLedgerRepository#insertReferralRewardIfAbsent}, an
     * {@code INSERT ... ON CONFLICT DO NOTHING} against V168's partial unique index, same
     * check-then-act fix already used twice elsewhere in this codebase
     * ({@code NotificationRepository}/{@code MerchantAliasRepository}'s own {@code insertIfAbsent}
     * -- see either's doc comment for why a plain {@code save()} + Java-side check, or
     * {@code catch(DataIntegrityViolationException)}, is not enough).
     */
    @Transactional
    public void creditReward(UUID referralId, BigDecimal amount, String reason, UUID actingAdminId) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Referral not found."));
        if (!Referral.STATUS_SUBSCRIBED.equals(referral.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Only a referral whose referred user has subscribed can be credited (current status: "
                            + referral.getStatus() + ").");
        }
        if (sharesADeviceOrIp(referral.getReferrerUserId(), referral.getReferredUserId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Possible self-referral detected -- these two accounts share a device/IP. Reward not credited.");
        }

        int inserted = walletLedgerRepository.insertReferralRewardIfAbsent(
                referral.getReferrerUserId(), amount, referral.getId());
        if (inserted == 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This referral was just credited by another request. Reward not credited again.");
        }

        referral.setStatus(Referral.STATUS_REWARDED);
        referral.setReward(amount);
        referralRepository.save(referral);

        auditService.record(referral.getReferrerUserId(), "REFERRAL_REWARD_CREDITED", "Referral", referral.getId(),
                Map.of("amount", amount.toString(), "reason", reason, "actorId", actingAdminId.toString()));
    }

    private boolean sharesADeviceOrIp(UUID referrerUserId, UUID referredUserId) {
        Set<String> referrerIps = new HashSet<>(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerUserId));
        if (referrerIps.isEmpty()) return false;
        return refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredUserId).stream()
                .anyMatch(referrerIps::contains);
    }
}
