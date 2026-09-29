package com.finora.imports;

import com.finora.config.EmailProperties;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.service.EmailMessage;
import com.finora.service.EmailProvider;
import com.finora.service.EmailResult;
import com.finora.service.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LayoutReviewAlertServiceTest {

    private UserRepository userRepository;
    private EmailProvider emailProvider;
    private LayoutReviewAlertService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailProvider = mock(EmailProvider.class);
        EmailProperties emailProperties = mock(EmailProperties.class);
        when(emailProperties.getAdminAppBaseUrl()).thenReturn("https://admin.example.com");
        when(emailProvider.send(any())).thenReturn(EmailResult.success(ProviderType.RESEND, "msg-1"));
        service = new LayoutReviewAlertService(userRepository, emailProvider, emailProperties);
    }

    private static User admin(String email) {
        User user = new User();
        user.setEmail(email);
        return user;
    }

    @Test
    void emailsEveryLayoutCuratorWithTheReasonsInPlainWordsAndALinkToTheQueue() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin("a@example.com"), admin("b@example.com")));

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("NEW_LAYOUT", "BLANK_DESCRIPTIONS"), "SA-000123",
                "Kotak Mahindra Bank — Credit Card v2");

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, times(2)).send(sent.capture());
        EmailMessage message = sent.getAllValues().get(0);
        assertThat(message.subject()).contains("FP-1-ABCDEF12");
        assertThat(message.html())
                .contains("A statement layout Finora has not seen before")
                .contains("Most transactions staged with no description")
                .contains("SA-000123")
                .contains("Kotak Mahindra Bank &mdash; Credit Card v2")
                .contains("https://admin.example.com/layout-intelligence?tab=review");
    }

    @Test
    void sendsNothingWhenNoAdminHoldsThePermission() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of());

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("NEW_LAYOUT"), null, null);

        verify(emailProvider, never()).send(any());
    }

    @Test
    void oneFailedRecipientDoesNotStopTheOthers() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin("a@example.com"), admin("b@example.com")));
        when(emailProvider.send(any())).thenThrow(new RuntimeException("provider down"))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-2"));

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("STAGING_FAILED"), "SA-000124", null);

        verify(emailProvider, times(2)).send(any());
    }

    @Test
    void aPerRuleVerificationReasonNamesTheRule() {
        assertThat(LayoutReviewAlertService.describe("VERIFICATION_NOT_PASSED:BALANCE_CHAIN"))
                .isEqualTo("A verification check did not pass (warning or failure) — BALANCE_CHAIN");
        assertThat(LayoutReviewAlertService.describe("NEW_LAYOUT")).isEqualTo("A statement layout Finora has not seen before");
        assertThat(LayoutReviewAlertService.describe("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
    }
}
