package com.finora.service;

import com.finora.dto.PagedResponse;
import com.finora.dto.ReferralDtos.AdminReferralSummaryDto;
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
        service = new ReferralService(referralCodeRepository, referralRepository, walletLedgerRepository,
                refreshTokenRepository, userRepository, auditService);
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
    void onPlanChanged_movesRegisteredToSubscribed() {
        Referral referral = new Referral();
        referral.setReferrerUserId(referrerId);
        referral.setReferredUserId(referredId);
        referral.setStatus(Referral.STATUS_REGISTERED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onPlanChanged(referredId, "PLUS");

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_SUBSCRIBED);
        verify(referralRepository).save(referral);
        verify(auditService).record(eq(referredId), eq("REFERRAL_SUBSCRIBED"), eq("Referral"), any(), any());
    }

    @Test
    void onPlanChanged_isANoOp_whenTheUserWasNeverReferred() {
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.empty());

        service.onPlanChanged(referredId, "PLUS");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onPlanChanged_isANoOp_whenTheReferralIsNotCurrentlyRegistered() {
        Referral referral = new Referral();
        referral.setStatus(Referral.STATUS_SUBSCRIBED);
        when(referralRepository.findByReferredUserId(referredId)).thenReturn(Optional.of(referral));

        service.onPlanChanged(referredId, "PREMIUM");

        verify(referralRepository, never()).save(any());
    }

    @Test
    void onPlanChanged_isANoOp_forADowngradeToFree() {
        service.onPlanChanged(referredId, "FREE");

        verifyNoInteractions(referralRepository);
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

        service.creditReward(referral.getId(), new BigDecimal("250.00"), "successful referral", adminId);

        var captor = org.mockito.ArgumentCaptor.forClass(WalletLedgerEntry.class);
        verify(walletLedgerRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(referrerId);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("250.00");
        assertThat(captor.getValue().getReason()).isEqualTo(WalletLedgerEntry.REASON_REFERRAL_REWARD);
        assertThat(captor.getValue().getReferenceId()).isEqualTo(referral.getId());

        assertThat(referral.getStatus()).isEqualTo(Referral.STATUS_REWARDED);
        assertThat(referral.getReward()).isEqualByComparingTo("250.00");
        verify(auditService).record(eq(referrerId), eq("REFERRAL_REWARD_CREDITED"), eq("Referral"), any(), any());
    }
}
