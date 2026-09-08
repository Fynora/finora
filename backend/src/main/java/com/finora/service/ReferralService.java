package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCode;
import com.finora.entity.User;
import com.finora.entity.WalletLedgerEntry;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralCodeRepository;
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

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRepository referralRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ReferralService(ReferralCodeRepository referralCodeRepository, ReferralRepository referralRepository,
                            WalletLedgerRepository walletLedgerRepository, RefreshTokenRepository refreshTokenRepository,
                            UserRepository userRepository, AuditService auditService) {
        this.referralCodeRepository = referralCodeRepository;
        this.referralRepository = referralRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
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
     * {@code RevenueCatWebhookDispatcher.handleInitialPurchase} -- both represent a real, confirmed
     * charge, not merely a mandate authorization (design spec §5: {@code subscription.activated}
     * can fire with zero funds movement, so it is deliberately NOT a call site for this). A purely
     * factual transition (this user is now on a paying plan) -- no business term is being invented
     * by observing it, so it happens automatically, unlike reward crediting. Silently a no-op if
     * the user was never referred, was already past REGISTERED, or {@code newPlanCode} is FREE (a
     * downgrade/reconciliation must never re-trigger or reverse this). Takes no admin id -- unlike
     * the reward-crediting audit trail below, there is no admin in scope at a webhook call site.
     */
    @Transactional
    public void onPlanChanged(UUID userId, String newPlanCode) {
        if ("FREE".equals(newPlanCode)) return;
        referralRepository.findByReferredUserId(userId)
                .filter(r -> Referral.STATUS_REGISTERED.equals(r.getStatus()))
                .ifPresent(r -> {
                    r.setStatus(Referral.STATUS_SUBSCRIBED);
                    referralRepository.save(r);
                    auditService.record(userId, "REFERRAL_SUBSCRIBED", "Referral", r.getId(),
                            Map.of("referrerUserId", r.getReferrerUserId().toString(), "planCode", newPlanCode));
                });
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
        return new MyReferralsDto(code, dtos, balance);
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
     * fingerprinting mechanism. Idempotent by construction: only a referral currently at SUBSCRIBED
     * can be credited, so retrying against an already-REWARDED referral is rejected rather than
     * double-crediting the wallet.
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

        WalletLedgerEntry entry = new WalletLedgerEntry();
        entry.setUserId(referral.getReferrerUserId());
        entry.setAmount(amount);
        entry.setReason(WalletLedgerEntry.REASON_REFERRAL_REWARD);
        entry.setReferenceId(referral.getId());
        walletLedgerRepository.save(entry);

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
