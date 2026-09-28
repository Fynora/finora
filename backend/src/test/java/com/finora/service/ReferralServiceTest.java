package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.entity.Referral;
import com.finora.entity.ReferralCharge;
import com.finora.entity.ReferralCode;
import com.finora.entity.ReferralGrant;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.notification.api.NotificationService;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.ReferralChargeRepository;
import com.finora.repository.ReferralCodeRepository;
import com.finora.repository.ReferralGrantRepository;
import com.finora.repository.ReferralRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReferralServiceTest {

    private ReferralCodeRepository referralCodeRepository;
    private ReferralRepository referralRepository;
    private WalletLedgerRepository walletLedgerRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private UserRepository userRepository;
    private AuditService auditService;
    private ReferralGrantRepository referralGrantRepository;
    private NotificationService notificationService;
    private ReferralChargeRepository referralChargeRepository;
    private ReferralService service;

    private final UUID referrerId = UUID.randomUUID();
    private final UUID referredId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referralCodeRepository = mock(ReferralCodeRepository.class);
        referralRepository = mock(ReferralRepository.class);
        walletLedgerRepository = mock(WalletLedgerRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        auditService = mock(AuditService.class);
        referralGrantRepository = mock(ReferralGrantRepository.class);
        notificationService = mock(NotificationService.class);
        referralChargeRepository = mock(ReferralChargeRepository.class);
        service = new ReferralService(referralCodeRepository, referralRepository, walletLedgerRepository,
                refreshTokenRepository, userRepository, auditService, referralGrantRepository, notificationService,
                referralChargeRepository);
        when(referralRepository.save(any(Referral.class))).thenAnswer(inv -> {
            Referral r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            return r;
        });
        when(referralCodeRepository.save(any(ReferralCode.class))).thenAnswer(inv -> {
            ReferralCode c = inv.getArgument(0);
            if (c.getId() == null) ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            return c;
        });
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(any())).thenReturn(List.of());
    }

    @Test
    void myCode_generatesAndPersistsOne_whenTheUserHasNoneYet() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.empty());
        when(referralCodeRepository.existsByCode(any())).thenReturn(false);

        String code = service.myCode(referrerId);

        assertThat(code).isNotBlank();
        verify(referralCodeRepository).save(any(ReferralCode.class));
    }

    @Test
    void redeemCode_isANoOp_whenTheCodeIsBlank() {
        service.redeemCode(referredId, "  ");

        verifyNoInteractions(referralCodeRepository);
        verify(referralRepository, never()).save(any());
    }

    @Test
    void redeemCode_isASilentNoOp_whenTheCodeIsNotRecognized_neverBlockingSignup() {
        when(referralCodeRepository.findByCode("BADCODE1")).thenReturn(Optional.empty());

        service.redeemCode(referredId, "badcode1");

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void redeemCode_isASilentNoOp_whenTheCodeBelongsToTheAccountBeingCreated() {
        ReferralCode ownCode = new ReferralCode();
        ownCode.setUserId(referredId);
        ownCode.setCode("SELFCODE");
        when(referralCodeRepository.findByCode("SELFCODE")).thenReturn(Optional.of(ownCode));

        service.redeemCode(referredId, "selfcode");

        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void redeemCode_createsARegisteredReferral_forAValidCode() {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("VALIDCOD");
        when(referralCodeRepository.findByCode("VALIDCOD")).thenReturn(Optional.of(code));

        service.redeemCode(referredId, "  validcod  ");

        var captor = org.mockito.ArgumentCaptor.forClass(Referral.class);
        verify(referralRepository).save(captor.capture());
        assertThat(captor.getValue().getReferrerUserId()).isEqualTo(referrerId);
        assertThat(captor.getValue().getReferredUserId()).isEqualTo(referredId);
        assertThat(captor.getValue().getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
        verify(auditService).record(eq(referredId), eq("REFERRAL_REGISTERED"), eq("Referral"), any(),
                eq(java.util.Map.of("referrerUserId", referrerId.toString())));
    }

    @Test
    void onReferredUserCharged_movesRegisteredToSubscribed() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
        verify(referralRepository).save(referral);
        verify(auditService).record(eq(referredId), eq("REFERRAL_SUBSCRIBED"), eq("Referral"), any(), any());
    }

    @Test
    void onReferredUserCharged_isANoOp_whenTheUserWasNeverReferred() {
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.empty());

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onReferredUserCharged_isANoOp_whenTheReferralIsNotCurrentlyRegistered() {
        Referral referral = new Referral();
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onReferredUserCharged(referredId, "PREMIUM", "RAZORPAY", "pay_test_1");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onReferredUserCharged_isANoOp_forADowngradeToFree() {
        service.onReferredUserCharged(referredId, "FREE", "RAZORPAY", "pay_test_1");

        verifyNoInteractions(referralRepository);
    }

    private ReferralCode codeWithCounter(int count) {
        ReferralCode code = new ReferralCode();
        code.setUserId(referrerId);
        code.setCode("ABCD1234");
        code.setPremiumMilestoneCounter(count);
        return code;
    }

    private void givenReferralReachingSubscribed() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));
    }

    private void givenAQualifyingReferral() {
        Referral qualifying = new Referral();
        qualifying.setReferrerUserId(referrerId);
        qualifying.setReferredUserId(referredId);
        qualifying.setStatus(Referral.STATUS_SUBSCRIBED);
        ReflectionTestUtils.setField(qualifying, "id", UUID.randomUUID());
        when(referralRepository.findFirstByReferrerUserIdAndStatusIn(referrerId, List.of(Referral.STATUS_SUBSCRIBED, Referral.STATUS_REWARDED)))
                .thenReturn(Optional.of(qualifying));
        when(referralGrantRepository.save(any(ReferralGrant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void onReferredUserCharged_incrementsTheOneMilestoneCounterAndNotifiesProgress() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(1);
        code.setPlusMilestoneCounter(1);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(2);
        // The retired 3-referral counter is no longer touched.
        assertThat(code.getPlusMilestoneCounter()).isEqualTo(1);
        verify(notificationService).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_FRIEND_SUBSCRIBED && req.userId().equals(referrerId)
                        && "2".equals(req.params().get("count"))));
        verify(notificationService, never()).request(argThat(req -> req.type() == NotificationType.REFERRAL_MILESTONE_REACHED));
    }

    @Test
    void onReferredUserCharged_reachingThreeNoLongerFiresAnyMilestone() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(2);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(3);
        verify(notificationService, never()).request(argThat(req -> req.type() == NotificationType.REFERRAL_MILESTONE_REACHED));
    }

    @Test
    void onReferredUserCharged_reachingSevenFiresMilestoneReachedForPlus() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(6);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(7);
        verify(notificationService).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_MILESTONE_REACHED
                        && ReferralGrant.TIER_PLUS.equals(req.params().get("tier"))));
        verify(notificationService, never()).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_MILESTONE_REACHED
                        && ReferralGrant.TIER_PREMIUM.equals(req.params().get("tier"))));
    }

    @Test
    void onReferredUserCharged_eighthReferralDoesNotRepeatTheMilestoneNotification() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(7);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(8);
        verify(notificationService, never()).request(argThat(req -> req.type() == NotificationType.REFERRAL_MILESTONE_REACHED));
    }

    // Redeeming subtracts 7 instead of resetting, so an unredeemed 14 is a second earned month
    // and must get its own "redeem it now".
    @Test
    void onReferredUserCharged_reachingFourteenFiresTheMilestoneAgain() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(13);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(14);
        verify(notificationService).request(argThat(req ->
                req.type() == NotificationType.REFERRAL_MILESTONE_REACHED
                        && ReferralGrant.TIER_PLUS.equals(req.params().get("tier"))));
    }

    @Test
    void onReferredUserCharged_incrementUsesTheRowLockedRead() {
        givenReferralReachingSubscribed();
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(codeWithCounter(0)));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(referralCodeRepository).findByUserIdForUpdate(referrerId);
        verify(referralCodeRepository, never()).findByUserId(referrerId);
    }

    @Test
    void onReferredUserCharged_selfReferralSharingDeviceIncrementsNeitherCounter() {
        givenReferralReachingSubscribed();
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("1.2.3.4"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("1.2.3.4"));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(referralCodeRepository, never()).save(any());
        verify(notificationService, never()).request(any());
    }

    @Test
    void redeemMilestone_atSevenGrantsPlusAndResetsTheCounter() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(7)));
        when(referralCodeRepository.consumeMilestoneIfAtLeast(referrerId, 7)).thenReturn(1);
        givenAQualifyingReferral();

        service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS);

        verify(referralCodeRepository).consumeMilestoneIfAtLeast(referrerId, 7);
        verify(referralGrantRepository).save(argThat(g ->
                g.getUserId().equals(referrerId) && ReferralGrant.TIER_PLUS.equals(g.getTier())
                        && ReferralGrant.STATUS_PENDING.equals(g.getStatus())));
    }

    // App builds already on phones still show the old "toward Premium" row at 7 and send PREMIUM.
    // That reward must still be redeemable, and it is Plus now.
    @Test
    void redeemMilestone_premiumFromAnOlderAppBuildAlsoGrantsPlus() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(7)));
        when(referralCodeRepository.consumeMilestoneIfAtLeast(referrerId, 7)).thenReturn(1);
        givenAQualifyingReferral();

        service.redeemMilestone(referrerId, ReferralGrant.TIER_PREMIUM);

        verify(referralGrantRepository).save(argThat(g -> ReferralGrant.TIER_PLUS.equals(g.getTier())));
        verify(referralGrantRepository, never()).save(argThat(g -> ReferralGrant.TIER_PREMIUM.equals(g.getTier())));
    }

    @Test
    void redeemMilestone_atThreeReferralsIsRejected() {
        ReferralCode code = codeWithCounter(3);
        code.setPlusMilestoneCounter(3);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("need 7");
        verify(referralCodeRepository, never()).consumeMilestoneIfAtLeast(any(), anyInt());
        verify(referralGrantRepository, never()).save(any());
    }

    @Test
    void redeemMilestone_oneBelowSevenIsRejected() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(6)));

        assertThatThrownBy(() -> service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS))
                .isInstanceOf(ApiException.class);
        verify(referralCodeRepository, never()).consumeMilestoneIfAtLeast(any(), anyInt());
        verify(referralGrantRepository, never()).save(any());
    }

    @Test
    void redeemMilestone_unknownTierIsABadRequest() {
        assertThatThrownBy(() -> service.redeemMilestone(referrerId, "GOLD"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown reward tier");
        verifyNoInteractions(referralGrantRepository);
    }

    // Regression test for a real race: two concurrent redeem requests (a double-click, two open
    // tabs) both reading the same pre-reset counter and both passing a Java-side check would
    // create two grants for one threshold crossing. The atomic consumeMilestoneIfAtLeast is
    // what actually closes that race -- this proves the service reacts correctly when it loses
    // that race (0 rows updated), not just that the happy path calls it. Same shape as
    // creditReward's own creditReward_rejectsWhenTheWalletInsertLosesTheConcurrencyRace test.
    @Test
    void redeemMilestone_rejectsWhenTheCounterResetLosesTheConcurrencyRace() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(7)));
        // A concurrent request already reset it to 0 first -- this UPDATE now affects no rows.
        when(referralCodeRepository.consumeMilestoneIfAtLeast(referrerId, 7)).thenReturn(0);

        assertThatThrownBy(() -> service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("just redeemed by another request");

        verify(referralGrantRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // Regression test: a referral that already contributed to the counter can later be
    // hard-deleted (account purge) or cash-REWARDED by an admin, leaving zero
    // SUBSCRIBED/REWARDED referrals to point at even though the counter legitimately reached the
    // threshold. Redemption must still succeed -- earned_from_referral_id is informational only
    // (nullable in the schema), never a reason to block a reward the atomic reset already granted.
    @Test
    void redeemMilestone_stillSucceedsWhenNoQualifyingReferralCanBeFoundForTraceability() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(7)));
        when(referralCodeRepository.consumeMilestoneIfAtLeast(referrerId, 7)).thenReturn(1);
        when(referralRepository.findFirstByReferrerUserIdAndStatusIn(referrerId, List.of(Referral.STATUS_SUBSCRIBED, Referral.STATUS_REWARDED)))
                .thenReturn(Optional.empty());
        when(referralGrantRepository.save(any(ReferralGrant.class))).thenAnswer(inv -> inv.getArgument(0));

        service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS);

        verify(referralGrantRepository).save(argThat(g -> g.getEarnedFromReferralId() == null));
    }

    @Test
    void myReferrals_reportsTheRetiredPlusCounterAsZeroEvenWhenTheColumnIsNot() {
        ReferralCode code = codeWithCounter(4);
        code.setPlusMilestoneCounter(3);
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(code));
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of());
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(java.math.BigDecimal.ZERO);

        var dto = service.myReferrals(referrerId);

        assertThat(dto.plusMilestoneCounter()).isZero();
        assertThat(dto.premiumMilestoneCounter()).isEqualTo(4);
    }

    /** A stored counter below 0 (refund after redemption) is shown as 0 progress plus the debt,
     *  never as "-2 / 7" -- app builds already on phones read premiumMilestoneCounter only. */
    @Test
    void myReferrals_reportsANegativeCounterAsZeroProgressPlusReferralsOwed() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(-2)));
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of());
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(java.math.BigDecimal.ZERO);

        var dto = service.myReferrals(referrerId);

        assertThat(dto.premiumMilestoneCounter()).isZero();
        assertThat(dto.referralsOwed()).isEqualTo(2);
    }

    @Test
    void myReferrals_owesNothingNormally() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(4)));
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of());
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(java.math.BigDecimal.ZERO);

        assertThat(service.myReferrals(referrerId).referralsOwed()).isZero();
    }

    @Test
    void myReferrals_includesTheCodeListAndWalletBalance() {
        ReferralCode existing = new ReferralCode();
        existing.setUserId(referrerId);
        existing.setCode("ABCD1234");
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(existing));

        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REWARDED);
        referral.setReward(new BigDecimal("250.00"));
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of(referral));

        User referred = new User();
        ReflectionTestUtils.setField(referred, "id", referredId);
        referred.setFullName("Jane Doe");
        when(userRepository.findAllById(any())).thenReturn(List.of(referred));
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(new BigDecimal("250.00"));

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.code()).isEqualTo("ABCD1234");
        assertThat(dto.referrals()).hasSize(1);
        assertThat(dto.referrals().get(0).referredUserFullName()).isEqualTo("Jane Doe");
        assertThat(dto.referrals().get(0).status()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(dto.walletBalance()).isEqualByComparingTo("250.00");
        // referralCount is kept only for frontend/src/pages/Billing.tsx's pre-existing MVP shape
        // (see MyReferralsDto's own doc comment) -- must always track referrals.size(), never be
        // independently wrong.
        assertThat(dto.referralCount()).isEqualTo(1);
    }

    // Regression test: myReferrals previously read referralCodeRepository directly instead of
    // going through myCode(), so a user who opened this page before ever hitting /my-code got
    // code: null back -- a broken share link, not just a missing convenience.
    @Test
    void myReferrals_lazilyCreatesTheCode_whenTheUserHasNoneYet() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.empty());
        when(referralCodeRepository.existsByCode(any())).thenReturn(false);
        when(referralRepository.findByReferrerUserIdOrderByCreatedAtDesc(referrerId)).thenReturn(List.of());
        when(walletLedgerRepository.sumAmountByUserId(referrerId)).thenReturn(BigDecimal.ZERO);

        MyReferralsDto dto = service.myReferrals(referrerId);

        assertThat(dto.code()).isNotBlank();
        verify(referralCodeRepository).save(any(ReferralCode.class));
    }

    @Test
    void listAll_mapsReferrerAndReferredIdentity() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        Page<Referral> page = new PageImpl<>(List.of(referral), PageRequest.of(0, 20), 1);
        when(referralRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);

        User referrer = new User();
        ReflectionTestUtils.setField(referrer, "id", referrerId);
        referrer.setEmail("referrer@example.com");
        User referred = new User();
        ReflectionTestUtils.setField(referred, "id", referredId);
        referred.setEmail("referred@example.com");
        when(userRepository.findAllById(any())).thenReturn(List.of(referrer, referred));

        PagedResponse<AdminReferralSummaryDto> result = service.listAll(0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).referrerEmail()).isEqualTo("referrer@example.com");
        assertThat(result.content().get(0).referredEmail()).isEqualTo("referred@example.com");
    }

    @Test
    void creditReward_rejectsAReferralThatIsNotYetSubscribed() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findById(any())).thenReturn(Optional.of(referral));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), new BigDecimal("100"), "test", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("current status: REGISTERED");
        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    void creditReward_rejectsWhenReferrerAndReferredShareADeviceOrIp() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("1.2.3.4"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("1.2.3.4"));

        assertThatThrownBy(() -> service.creditReward(referral.getId(), new BigDecimal("100"), "test", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("self-referral");
        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    void creditReward_writesAWalletEntryAndMarksTheReferralRewarded() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(walletLedgerRepository.insertReferralRewardIfAbsent(eq(referrerId), eq(new BigDecimal("250.00")), eq(referral.getId())))
                .thenReturn(1);

        service.creditReward(referral.getId(), new BigDecimal("250.00"), "successful referral", adminId);

        verify(walletLedgerRepository).insertReferralRewardIfAbsent(referrerId, new BigDecimal("250.00"), referral.getId());
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(referral.getReward()).isEqualByComparingTo("250.00");
        verify(auditService).record(eq(referrerId), eq("REFERRAL_REWARD_CREDITED"), eq("Referral"), any(), any());
    }

    // Regression test: two concurrent credit requests for the same referral could both pass the
    // SUBSCRIBED status check above before either committed. insertReferralRewardIfAbsent (backed
    // by V168's partial unique index) is what actually closes that race -- this proves the service
    // reacts correctly when it loses that race (0 rows inserted), not just that the happy path
    // calls it.
    @Test
    void creditReward_rejectsWhenTheWalletInsertLosesTheConcurrencyRace() {
        Referral referral = new Referral();
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findById(referral.getId())).thenReturn(Optional.of(referral));
        when(walletLedgerRepository.insertReferralRewardIfAbsent(any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.creditReward(referral.getId(), new BigDecimal("250.00"), "test", adminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("just credited by another request");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
        assertThat(referral.getReward()).isNull();
        verify(referralRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    // ---- referral_charges: which charge counted, and taking it back ----

    @Test
    void onReferredUserCharged_recordsTheChargeAsCounted() {
        givenReferralReachingSubscribed();
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(codeWithCounter(0)));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(referralChargeRepository).save(argThat(c -> c.getReferrerUserId().equals(referrerId)
                && "RAZORPAY".equals(c.getProvider()) && "pay_test_1".equals(c.getChargeRef()) && c.isCounted()
                && c.getReferralId() != null));
    }

    @Test
    void onReferredUserCharged_recordsASharedDeviceChargeAsNotCounted() {
        givenReferralReachingSubscribed();
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referrerId)).thenReturn(List.of("1.2.3.4"));
        when(refreshTokenRepository.findDistinctLastSeenIpsByUserId(referredId)).thenReturn(List.of("1.2.3.4"));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(referralChargeRepository).save(argThat(c -> !c.isCounted()));
    }

    @Test
    void onReferredUserCharged_withoutAChargeIdIsANoOp() {
        givenReferralReachingSubscribed();

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", null);
        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", " ");

        verify(referralRepository, never()).save(any());
        verify(referralCodeRepository, never()).save(any());
        verify(referralChargeRepository, never()).save(any());
    }

    /** A purchase event re-delivered after its charge was refunded must not count that charge again. */
    @Test
    void onReferredUserCharged_aChargeAlreadyRecordedNeverCountsAgain() {
        givenReferralReachingSubscribed();
        when(referralChargeRepository.existsByProviderAndChargeRef("REVENUECAT", "txn_1")).thenReturn(true);

        service.onReferredUserCharged(referredId, "PLUS", "REVENUECAT", "txn_1");

        verify(referralRepository, never()).save(any());
        verify(referralCodeRepository, never()).save(any());
        verify(referralChargeRepository, never()).save(any());
    }

    private ReferralCharge chargeRow(boolean counted, UUID referralId) {
        ReferralCharge charge = new ReferralCharge();
        ReflectionTestUtils.setField(charge, "id", UUID.randomUUID());
        charge.setReferrerUserId(referrerId);
        charge.setReferralId(referralId);
        charge.setProvider("RAZORPAY");
        charge.setChargeRef("pay_test_1");
        charge.setCounted(counted);
        when(referralChargeRepository.findForUpdate("RAZORPAY", "pay_test_1")).thenReturn(Optional.of(charge));
        return charge;
    }

    private Referral subscribedReferral(String status) {
        Referral referral = new Referral();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(referral, "id", id);
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(status);
        when(referralRepository.findById(id)).thenReturn(Optional.of(referral));
        return referral;
    }

    @Test
    void onChargeReversed_takesOneOffTheCounterAndMovesTheReferralBackToRegistered() {
        Referral referral = subscribedReferral(Referral.STATUS_SUBSCRIBED);
        ReferralCharge charge = chargeRow(true, referral.getId());
        ReferralCode code = codeWithCounter(3);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(2);
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
        assertThat(charge.getReversedAt()).isNotNull();
        assertThat(charge.getReversalReason()).isEqualTo("REFUND");
        verify(referralChargeRepository).save(charge);
        verify(auditService).record(eq(referrerId), eq("REFERRAL_CHARGE_REVERSED"), eq("ReferralCharge"),
                eq(charge.getId()), argThat(m -> Integer.valueOf(2).equals(m.get("counterAfter"))));
    }

    /** The month was already redeemed: it is kept (no grant touched), but the referral is owed --
     *  the counter goes below 0 instead of flooring, so "redeem, then refund everyone" is not free. */
    @Test
    void onChargeReversed_afterARedemptionLeavesTheReferralOwed() {
        chargeRow(true, subscribedReferral(Referral.STATUS_SUBSCRIBED).getId());
        ReferralCode code = codeWithCounter(0);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(-1);
        verify(referralCodeRepository).save(code);
        verifyNoInteractions(referralGrantRepository);
        // The notice shows progress as the app does: never below 0.
        verify(notificationService).request(argThat(req -> req.type() == NotificationType.REFERRAL_REVERSED
                && "0".equals(req.params().get("count"))));
    }

    @Test
    void onChargeReversed_tellsTheReferrerOnce() {
        chargeRow(true, subscribedReferral(Referral.STATUS_SUBSCRIBED).getId());
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(codeWithCounter(3)));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");
        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        verify(notificationService, times(1)).request(argThat(req -> req.type() == NotificationType.REFERRAL_REVERSED
                && req.userId().equals(referrerId) && "2".equals(req.params().get("count"))
                && req.notificationKey().startsWith("REFERRAL_REVERSED_")));
    }

    /** Climbing back from a refund debt passes through 0 -- a multiple of 7, but no reward. */
    @Test
    void onReferredUserCharged_repayingADebtToZeroFiresNoMilestone() {
        givenReferralReachingSubscribed();
        ReferralCode code = codeWithCounter(-1);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        assertThat(code.getPremiumMilestoneCounter()).isZero();
        verify(notificationService, never()).request(argThat(req -> req.type() == NotificationType.REFERRAL_MILESTONE_REACHED));
        verify(notificationService).request(argThat(req -> req.type() == NotificationType.REFERRAL_FRIEND_SUBSCRIBED
                && "0".equals(req.params().get("count"))));
    }

    @Test
    void onReferredUserCharged_progressShownNeverNegative() {
        givenReferralReachingSubscribed();
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(codeWithCounter(-3)));

        service.onReferredUserCharged(referredId, "PLUS", "RAZORPAY", "pay_test_1");

        verify(notificationService).request(argThat(req -> req.type() == NotificationType.REFERRAL_FRIEND_SUBSCRIBED
                && "0".equals(req.params().get("count"))));
    }

    @Test
    void redeemMilestone_whileOwingReportsZeroNotANegativeCount() {
        when(referralCodeRepository.findByUserId(referrerId)).thenReturn(Optional.of(codeWithCounter(-2)));

        assertThatThrownBy(() -> service.redeemMilestone(referrerId, ReferralGrant.TIER_PLUS))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("you have 0, need 7");
    }

    @Test
    void onChargeReversed_ofAnUncountedChargeRevertsTheStatusButLeavesTheCounter() {
        Referral referral = subscribedReferral(Referral.STATUS_SUBSCRIBED);
        chargeRow(false, referral.getId());

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
        verify(referralCodeRepository, never()).findByUserIdForUpdate(any());
        verify(referralCodeRepository, never()).save(any());
    }

    @Test
    void onChargeReversed_twiceTakesOnlyOneOff() {
        chargeRow(true, subscribedReferral(Referral.STATUS_SUBSCRIBED).getId());
        ReferralCode code = codeWithCounter(3);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");
        service.onChargeReversed("RAZORPAY", "pay_test_1", "CHARGEBACK_LOST");

        assertThat(code.getPremiumMilestoneCounter()).isEqualTo(2);
        verify(referralChargeRepository, times(1)).save(any());
    }

    /** No row yet: recorded as born-reversed so a late-arriving charge with this id never counts. */
    @Test
    void onChargeReversed_ofAChargeWithNoRowMarksItReversedAndChangesNothingElse() {
        when(referralChargeRepository.findForUpdate("RAZORPAY", "pay_other")).thenReturn(Optional.empty());
        when(referralChargeRepository.insertReversedIfAbsent("RAZORPAY", "pay_other", "REFUND")).thenReturn(1);

        service.onChargeReversed("RAZORPAY", "pay_other", "REFUND");
        service.onChargeReversed("RAZORPAY", null, "REFUND");

        verify(referralChargeRepository).insertReversedIfAbsent("RAZORPAY", "pay_other", "REFUND");
        verify(referralChargeRepository, times(1)).insertReversedIfAbsent(any(), any(), any());
        verify(referralChargeRepository, never()).save(any());
        verifyNoInteractions(referralCodeRepository, auditService);
    }

    /** The born-reversed insert lost to a concurrent transaction inserting the charge's own row:
     *  the reversal must then act on that row, not drop out. */
    @Test
    void onChargeReversed_whenTheChargeRowAppearsConcurrentlyReversesIt() {
        Referral referral = subscribedReferral(Referral.STATUS_SUBSCRIBED);
        ReferralCharge charge = new ReferralCharge();
        ReflectionTestUtils.setField(charge, "id", UUID.randomUUID());
        charge.setReferrerUserId(referrerId);
        charge.setReferralId(referral.getId());
        charge.setProvider("RAZORPAY");
        charge.setChargeRef("pay_test_1");
        charge.setCounted(true);
        when(referralChargeRepository.findForUpdate("RAZORPAY", "pay_test_1"))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(charge));
        when(referralChargeRepository.insertReversedIfAbsent("RAZORPAY", "pay_test_1", "REFUND")).thenReturn(0);
        ReferralCode code = codeWithCounter(1);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(code.getPremiumMilestoneCounter()).isZero();
        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REGISTERED);
        assertThat(charge.getReversedAt()).isNotNull();
    }

    /** The referred account was purged: its referral row is gone (referral_id NULL), the count
     *  still comes back off. */
    @Test
    void onChargeReversed_afterTheReferredAccountWasPurgedStillTakesOneOff() {
        chargeRow(true, null);
        ReferralCode code = codeWithCounter(1);
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(code));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(code.getPremiumMilestoneCounter()).isZero();
        verify(referralRepository, never()).save(any());
    }

    /** A referral an admin cash-credited keeps REWARDED; the wallet credit is not reversed here. */
    @Test
    void onChargeReversed_leavesAnAdminRewardedReferralAlone() {
        Referral referral = subscribedReferral(Referral.STATUS_REWARDED);
        chargeRow(true, referral.getId());
        when(referralCodeRepository.findByUserIdForUpdate(referrerId)).thenReturn(Optional.of(codeWithCounter(1)));

        service.onChargeReversed("RAZORPAY", "pay_test_1", "REFUND");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        verify(referralRepository, never()).save(any());
        verifyNoInteractions(walletLedgerRepository);
    }
}
