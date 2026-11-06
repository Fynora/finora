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
                "Kotak Mahindra Bank — Credit Card v2", List.of(), LayoutAlertTier.IMMEDIATE);

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

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("NEW_LAYOUT"), null, null, List.of(), LayoutAlertTier.IMMEDIATE);

        verify(emailProvider, never()).send(any());
    }

    @Test
    void oneFailedRecipientDoesNotStopTheOthers() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin("a@example.com"), admin("b@example.com")));
        when(emailProvider.send(any())).thenThrow(new RuntimeException("provider down"))
                .thenReturn(EmailResult.success(ProviderType.RESEND, "msg-2"));

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("STAGING_FAILED"), "SA-000124", null, List.of(), LayoutAlertTier.IMMEDIATE);

        verify(emailProvider, times(2)).send(any());
    }

    @Test
    void aPerRuleVerificationReasonNamesTheRule() {
        assertThat(LayoutReviewAlertService.describe("VERIFICATION_NOT_PASSED:BALANCE_CHAIN"))
                .isEqualTo("A verification check did not pass (warning or failure) — BALANCE_CHAIN");
        assertThat(LayoutReviewAlertService.describe("NEW_LAYOUT")).isEqualTo("A statement layout Finora has not seen before");
        assertThat(LayoutReviewAlertService.describe("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
    }

    @Test
    void theEmailCarriesWhatTheCheckFound_escaped_andOmitsTheBlockWhenThereIsNothing() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin("a@example.com")));

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("VERIFICATION_NOT_PASSED:BALANCE_CHAIN"), "SA-1", null,
                List.of("BALANCE_CHAIN — WARNING: 3 of 1691 rows disagree with the printed balance; the gaps cancel out",
                        "<b>RULE</b> — FAILED"), LayoutAlertTier.IMMEDIATE);
        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("NEW_LAYOUT"), "SA-2", null, null, LayoutAlertTier.IMMEDIATE);

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).html())
                .contains("What the check found")
                .contains("3 of 1691 rows disagree with the printed balance; the gaps cancel out")
                .contains("&lt;b&gt;RULE&lt;/b&gt;")
                .doesNotContain("<b>RULE</b>");
        assertThat(sent.getAllValues().get(1).html()).doesNotContain("What the check found");
    }

    @Test
    void anUrgentAlertSaysSoInTheSubjectAndAnyAlertStatesItsPriority_withoutChangingWhoIsEmailed() {
        when(userRepository.findByPermissionNameAndAccountScope("LAYOUT_REGISTRY_MANAGE", User.SCOPE_ADMIN))
                .thenReturn(List.of(admin("a@example.com")));

        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("STAGING_FAILED"), "SA-1", null, List.of(),
                LayoutAlertTier.IMMEDIATE);
        service.alertLayoutNeedsReview("FP-1-ABCDEF12", List.of("NEW_LAYOUT"), "SA-2", null, List.of(),
                LayoutAlertTier.DIGEST);

        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailProvider, times(2)).send(sent.capture());
        EmailMessage urgent = sent.getAllValues().get(0);
        EmailMessage routine = sent.getAllValues().get(1);
        assertThat(urgent.subject()).isEqualTo("[URGENT] Statement layout needs review — FP-1-ABCDEF12");
        assertThat(urgent.html()).contains("Urgent — not explained as harmless");
        // Phase 1 changes the label only: a routine alert is still sent now, with no prefix.
        assertThat(routine.subject()).isEqualTo("Statement layout needs review — FP-1-ABCDEF12");
        assertThat(routine.html()).contains("Routine — a new layout or a fully explained warning");
    }
}
