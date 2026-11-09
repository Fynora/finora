package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.SuspensionDtos.CreateSuspensionRequest;
import com.finora.dto.SuspensionDtos.SuspensionStatusDto;
import com.finora.security.CurrentUser;
import com.finora.service.AccountSuspensionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The suspended screen (privacy workstream 10). Both routes are on SuspendedAccountInterceptor's
 * allowlist, which is the only way a suspended account reaches anything; an active account gets 404
 * from the service.
 */
@RestController
@RequestMapping("/api/v1/users/me/suspension")
public class AccountSuspensionController {

    private final AccountSuspensionService suspensionService;
    private final CurrentUser currentUser;

    public AccountSuspensionController(AccountSuspensionService suspensionService, CurrentUser currentUser) {
        this.suspensionService = suspensionService;
        this.currentUser = currentUser;
    }

    // Method names become OpenAPI operation ids: unique ones, so adding these renumbers no other
    // controller's "status"/"request" operation in the generated client types.
    @GetMapping
    public ApiResponse<SuspensionStatusDto> suspensionStatus() {
        return ApiResponse.ok(suspensionService.status(currentUser.id()));
    }

    @PostMapping("/requests")
    public ApiResponse<SuspensionStatusDto> raiseSuspensionRequest(@Valid @RequestBody CreateSuspensionRequest request) {
        return ApiResponse.ok(suspensionService.request(currentUser.id(), request.type(), request.text()));
    }
}
