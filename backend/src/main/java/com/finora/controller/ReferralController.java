package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.ReferralDtos.MyReferralCodeDto;
import com.finora.dto.ReferralDtos.MyReferralsDto;
import com.finora.dto.ReferralDtos.RedeemMilestoneRequest;
import com.finora.security.CurrentUser;
import com.finora.service.ReferralService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current user's own referral code, their referrals, and their wallet balance. */
@RestController
@RequestMapping("/api/v1/referrals")
public class ReferralController {

    private final ReferralService referralService;
    private final CurrentUser currentUser;

    public ReferralController(ReferralService referralService, CurrentUser currentUser) {
        this.referralService = referralService;
        this.currentUser = currentUser;
    }

    @GetMapping("/my-code")
    public ApiResponse<MyReferralCodeDto> myCode() {
        return ApiResponse.ok(new MyReferralCodeDto(referralService.myCode(currentUser.id())));
    }

    @GetMapping("/mine")
    public ApiResponse<MyReferralsDto> mine() {
        return ApiResponse.ok(referralService.myReferrals(currentUser.id()));
    }

    @PostMapping("/redeem")
    public ApiResponse<Void> redeem(@Valid @RequestBody RedeemMilestoneRequest request) {
        referralService.redeemMilestone(currentUser.id(), request.tier());
        return ApiResponse.ok(null, "Reward redeemed");
    }
}
