package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferAdminDto;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferCreateRequest;
import com.finora.dto.BillingOfferAdminDtos.BillingOfferPlanOptionDto;
import com.finora.security.CurrentUser;
import com.finora.service.BillingOfferAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin surface for billing offers (V297), so a new offer needs neither a migration nor a deploy.
 * Gated by the subscription management permissions (V99), split the same way as
 * {@link AdminSubscriptionController}: reading needs _VIEW, changing needs _MANAGE. The rules live
 * in {@link BillingOfferAdminService}. There is no delete: the table is the record of past offers.
 */
@RestController
@RequestMapping("/api/v1/admin/billing-offers")
public class AdminBillingOfferController {

    private final BillingOfferAdminService service;
    private final CurrentUser currentUser;

    public AdminBillingOfferController(BillingOfferAdminService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Every offer, past, live and upcoming, newest start first. */
    @GetMapping
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_VIEW')")
    public ApiResponse<List<BillingOfferAdminDto>> listBillingOffers() {
        return ApiResponse.ok(service.list());
    }

    /** The paid plans and billing cycles an offer can be made for. */
    @GetMapping("/plans")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_VIEW')")
    public ApiResponse<List<BillingOfferPlanOptionDto>> listBillingOfferPlans() {
        return ApiResponse.ok(service.planOptions());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_MANAGE')")
    public ApiResponse<BillingOfferAdminDto> createBillingOffer(@Valid @RequestBody BillingOfferCreateRequest request) {
        return ApiResponse.ok(service.create(currentUser.id(), request));
    }

    /** Ends a live or upcoming offer now. Checkouts started after this get the regular price. */
    @PostMapping("/{id}/end")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGEMENT_MANAGE')")
    public ApiResponse<BillingOfferAdminDto> endBillingOffer(@PathVariable UUID id) {
        return ApiResponse.ok(service.endNow(currentUser.id(), id));
    }
}
