package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import com.finora.security.CurrentUser;
import com.finora.security.OwnershipGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/setu/links")
public class AccountAggregatorLinkController {

    private final SetuConsentService consentService;
    private final CurrentUser currentUser;
    private final AccountAggregatorLinkRepository links;
    private final AccountRepository accountRepository;
    private final AccountAggregatorIdentityResolutionService identityResolutionService;

    public AccountAggregatorLinkController(SetuConsentService consentService, CurrentUser currentUser,
                                            AccountAggregatorLinkRepository links, AccountRepository accountRepository,
                                            AccountAggregatorIdentityResolutionService identityResolutionService) {
        this.consentService = consentService;
        this.currentUser = currentUser;
        this.links = links;
        this.accountRepository = accountRepository;
        this.identityResolutionService = identityResolutionService;
    }

    @PostMapping
    public ResponseEntity<InitiateLinkResponse> initiate(@RequestBody InitiateLinkRequest request) {
        SetuConsentService.InitiateLinkResult result =
                consentService.initiateLink(currentUser.id(), request.fiType(), request.idempotencyKey());
        return ResponseEntity.ok(new InitiateLinkResponse(
                result.link().getId(), result.link().getStatus(), result.redirectUrl()));
    }

    @PostMapping("/{linkId}/confirm-existing-account")
    public ResponseEntity<Void> confirmExistingAccount(@PathVariable UUID linkId,
                                                        @RequestBody ConfirmExistingAccountRequest request) {
        AccountAggregatorLink link = ownedLink(linkId);
        Account account = OwnershipGuard.requireOwned(accountRepository.findById(request.accountId()),
                Account::getUserId, currentUser.id(), "Account");
        identityResolutionService.confirmExistingAccount(link, account.getId());
        return ResponseEntity.ok().build();
    }

    private AccountAggregatorLink ownedLink(UUID linkId) {
        return OwnershipGuard.requireOwned(links.findById(linkId),
                AccountAggregatorLink::getUserId, currentUser.id(), "AccountAggregatorLink");
    }

    public record InitiateLinkRequest(FiType fiType, String idempotencyKey) {}

    public record InitiateLinkResponse(UUID linkId, AccountAggregatorLinkStatus status, String redirectUrl) {}

    public record ConfirmExistingAccountRequest(UUID accountId) {}
}
