package com.finora.integrations.setu;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkControllerTest {

    private SetuConsentService consentService;
    private CurrentUser currentUser;
    private AccountAggregatorLinkRepository links;
    private AccountRepository accountRepository;
    private AccountAggregatorIdentityResolutionService identityResolutionService;
    private AccountAggregatorLinkController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consentService = mock(SetuConsentService.class);
        currentUser = mock(CurrentUser.class);
        links = mock(AccountAggregatorLinkRepository.class);
        accountRepository = mock(AccountRepository.class);
        identityResolutionService = mock(AccountAggregatorIdentityResolutionService.class);
        when(currentUser.id()).thenReturn(userId);
        controller = new AccountAggregatorLinkController(
                consentService, currentUser, links, accountRepository, identityResolutionService);
    }

    @Test
    void initiateReturnsTheLinkIdStatusAndRedirectUrl() {
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        SetuConsentService.InitiateLinkResult result =
                new SetuConsentService.InitiateLinkResult(link, "https://aa.example/redirect");
        when(consentService.initiateLink(userId, FiType.DEPOSIT, "idem-1")).thenReturn(result);

        AccountAggregatorLinkController.InitiateLinkRequest request =
                new AccountAggregatorLinkController.InitiateLinkRequest(FiType.DEPOSIT, "idem-1");
        AccountAggregatorLinkController.InitiateLinkResponse response = controller.initiate(request).getBody();

        assertThat(response.status()).isEqualTo(AccountAggregatorLinkStatus.CONSENT_PENDING);
        assertThat(response.redirectUrl()).isEqualTo("https://aa.example/redirect");
    }

    @Test
    void confirmExistingAccountRejectsAnAccountTheUserDoesNotOwn() {
        UUID linkId = UUID.randomUUID();
        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        when(links.findById(linkId)).thenReturn(java.util.Optional.of(link));

        UUID someoneElsesAccountId = UUID.randomUUID();
        Account someoneElsesAccount = new Account();
        someoneElsesAccount.setUserId(UUID.randomUUID()); // not this user
        when(accountRepository.findById(someoneElsesAccountId)).thenReturn(java.util.Optional.of(someoneElsesAccount));

        assertThatThrownBy(() -> controller.confirmExistingAccount(linkId,
                new AccountAggregatorLinkController.ConfirmExistingAccountRequest(someoneElsesAccountId)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(identityResolutionService);
    }
}
