package com.finora.integrations.setu;

import com.finora.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountAggregatorLinkControllerTest {

    private SetuConsentService consentService;
    private CurrentUser currentUser;
    private AccountAggregatorLinkController controller;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consentService = mock(SetuConsentService.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.id()).thenReturn(userId);
        controller = new AccountAggregatorLinkController(consentService, currentUser);
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
}
