package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.ReferralRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One referral per person, against the real schema: a user referred by several friends keeps only
 *  the first code used, whichever path it came through (signup or "enter a friend's code" later). */
class ReferralApplyCodeIT extends AbstractIntegrationTest {

    @Autowired private ReferralService referralService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private UserRepository userRepository;

    private User newUser() {
        User user = new User();
        user.setEmail("apply-code-it-" + UUID.randomUUID() + "@example.com"); // synthetic-ok: fixture, not a real account
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Apply Code IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void theFirstCodeWinsAndEveryLaterOneIsRefused() {
        User friendA = newUser();
        User friendB = newUser();
        User friendC = newUser();
        User joiner = newUser();
        String codeA = referralService.myCode(friendA.getId());
        String codeB = referralService.myCode(friendB.getId());
        String codeC = referralService.myCode(friendC.getId());

        referralService.applyCode(joiner.getId(), codeA);

        assertThatThrownBy(() -> referralService.applyCode(joiner.getId(), codeB))
                .isInstanceOf(ApiException.class).hasMessageContaining("already used a referral code");
        assertThatThrownBy(() -> referralService.applyCode(joiner.getId(), codeC))
                .isInstanceOf(ApiException.class).hasMessageContaining("already used a referral code");

        var referral = referralRepository.findByReferredUserId(joiner.getId()).orElseThrow();
        assertThat(referral.getReferrerUserId()).isEqualTo(friendA.getId());
        assertThat(referralService.canApplyCode(joiner.getId())).isFalse();
    }

    @Test
    void aCodeGivenAtSignupAlsoBlocksAnotherLater() {
        User friendA = newUser();
        User friendB = newUser();
        User joiner = newUser();

        // The signup path (register(), or a Google/Apple sign-up that created the account).
        referralService.redeemCode(joiner.getId(), referralService.myCode(friendA.getId()));

        assertThat(referralService.canApplyCode(joiner.getId())).isFalse();
        assertThatThrownBy(() -> referralService.applyCode(joiner.getId(), referralService.myCode(friendB.getId())))
                .isInstanceOf(ApiException.class).hasMessageContaining("already used a referral code");
        assertThat(referralRepository.findByReferredUserId(joiner.getId()).orElseThrow().getReferrerUserId())
                .isEqualTo(friendA.getId());
    }

    @Test
    void aNewUserWithNoReferralAndNoSubscriptionCanApply() {
        User joiner = newUser();

        assertThat(referralService.canApplyCode(joiner.getId())).isTrue();
        assertThat(referralService.myReferrals(joiner.getId()).canApplyCode()).isTrue();
    }
}
