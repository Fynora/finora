package com.finora.invite;

import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.security.CurrentUser;
import com.finora.support.ClientIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The invite-a-friend card on the import summary. The user is always the signed-in caller, never a
 * parameter. See {@link InvitePromptService} for when the card is shown.
 */
@RestController
@RequestMapping("/api/v1/invite-prompt")
public class InvitePromptController {

    private final InvitePromptService service;
    private final CurrentUser currentUser;
    private final ClientIdentity clientIdentity;

    public InvitePromptController(InvitePromptService service, CurrentUser currentUser,
                                  ClientIdentity clientIdentity) {
        this.service = service;
        this.currentUser = currentUser;
        this.clientIdentity = clientIdentity;
    }

    /** Whether to show the card for the statement import(s) a confirm just made. */
    @GetMapping
    public ApiResponse<InvitePromptDto.InvitePromptResponse> check(
            @RequestParam(name = "statementImportIds", required = false) List<UUID> statementImportIds) {
        if (statementImportIds != null && statementImportIds.size() > InvitePromptService.MAX_IDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Too many statement imports.");
        }
        return ApiResponse.ok(service.check(currentUser.id(), statementImportIds == null ? List.of() : statementImportIds));
    }

    /** Shown, Share tapped, or Not now. */
    @PostMapping("/events")
    public ApiResponse<Void> event(@Valid @RequestBody InvitePromptDto.InvitePromptEventRequest request) {
        service.record(currentUser.id(), request.event(), clientIdentity.platform());
        return ApiResponse.ok(null);
    }
}
