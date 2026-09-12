package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/setu/links")
public class AccountAggregatorLinkController {

    private final SetuConsentService consentService;
    private final CurrentUser currentUser;

    public AccountAggregatorLinkController(SetuConsentService consentService, CurrentUser currentUser) {
        this.consentService = consentService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<InitiateLinkResponse> initiate(@RequestBody InitiateLinkRequest request) {
        SetuConsentService.InitiateLinkResult result =
                consentService.initiateLink(currentUser.id(), request.fiType(), request.idempotencyKey());
        return ResponseEntity.ok(new InitiateLinkResponse(
                result.link().getId(), result.link().getStatus(), result.redirectUrl()));
    }

    public record InitiateLinkRequest(FiType fiType, String idempotencyKey) {}

    public record InitiateLinkResponse(java.util.UUID linkId, AccountAggregatorLinkStatus status,
                                        String redirectUrl) {}
}
