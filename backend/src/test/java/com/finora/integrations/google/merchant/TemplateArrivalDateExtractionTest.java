package com.finora.integrations.google.merchant;

import com.finora.domain.Money;
import com.finora.integrations.google.GmailAccessTokenService;
import com.finora.integrations.google.GmailApiClient;
import com.finora.integrations.google.GmailConnection;
import com.finora.integrations.google.GmailConnectionRepository;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seam behind "date a receipt by the day the email arrived": Gmail's arrival timestamp on the
 * fetched message, through the extraction service, into a REAL {@link TemplateEmailParser} reading a
 * {@code {received}} template, and out as the date of the receipt that is staged. Each side is
 * tested alone elsewhere ({@code GmailReceiptExtractionServiceTest} hands the arrival day to a mocked
 * parser; {@code TemplateEngineExtensionsTest} gives the real parser an arrival day directly); this
 * is the one place that proves they are connected.
 */
class TemplateArrivalDateExtractionTest {

    private static final String TOKEN = "an-access-token";
    private static final String DOMAIN = "shop.example";

    private GmailApiClient gmail;
    private GmailReceiptExtractionService service;
    private GmailStagingBridge stagingBridge;
    private GmailConnection connection;

    @BeforeEach
    void setUp() {
        gmail = mock(GmailApiClient.class);
        stagingBridge = mock(GmailStagingBridge.class);
        GmailAccessTokenService accessTokens = mock(GmailAccessTokenService.class);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        GmailProcessedMessageRepository processedMessages = mock(GmailProcessedMessageRepository.class);

        MerchantTemplate template = new MerchantTemplate();
        template.setMerchantDomain(DOMAIN);
        template.setMerchantName("Shop");
        template.setReceiptMarker("Your order is delivered.");
        template.setAmountPattern("Total ₹{amount}");
        template.setDatePattern(MerchantTemplate.RECEIVED_PLACEHOLDER);
        template.setEnabled(true);
        MerchantTemplateRepository templates = mock(MerchantTemplateRepository.class);
        when(templates.findByMerchantDomainAndEnabledTrue(DOMAIN)).thenReturn(Optional.of(template));

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, java.util.function.Consumer.class).accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new GmailReceiptExtractionService(gmail, accessTokens, new MerchantEmailSanitizer(),
                List.of(new TemplateEmailParser(templates)), new ParsedReceiptValidator(), stagingBridge,
                processedMessages, connections, transactionTemplate);

        connection = new GmailConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setGoogleUserId("google-sub-" + UUID.randomUUID());
        connection.setGoogleEmail("mailbox@example.test");
        connection.setGrantedScopes(GmailApiClient.GMAIL_READONLY_SCOPE);
        try {
            var field = GmailConnection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        when(accessTokens.accessTokenFor(connection)).thenReturn(TOKEN);
        when(connections.findById(connection.getId())).thenReturn(Optional.of(connection));

        GmailProcessedMessage pending = GmailProcessedMessage.trusted(UUID.randomUUID(), "m1",
                GmailProcessedMessage.Outcome.DETECTED_NOT_STAGED, DOMAIN);
        pendingMessage = pending;
        when(processedMessages.findByConnectionIdAndOutcomeOrderByProcessedAtAsc(any(), any(), any()))
                .thenReturn(List.of(pending));
    }

    private GmailProcessedMessage pendingMessage;

    private static final String BODY = "<p>Your order is delivered.</p><p>Total &#8377;1491.00</p>";

    @Test
    @DisplayName("a receipt with no date is staged on the day the email arrived, read in India time")
    void stagesOnTheArrivalDay() {
        // 20:30 UTC on 1 September is 02:00 IST on 2 September.
        when(gmail.getMessageBody(TOKEN, "m1")).thenReturn(new GmailApiClient.MessageBody(
                BODY, null, Instant.parse("2026-09-01T20:30:00Z")));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.staged()).isEqualTo(1);
        ArgumentCaptor<ParsedReceipt> staged = ArgumentCaptor.forClass(ParsedReceipt.class);
        verify(stagingBridge).stage(any(), staged.capture());
        assertThat(staged.getValue().amount()).isEqualTo(Money.of(new BigDecimal("1491.00")));
        assertThat(staged.getValue().transactionDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(pendingMessage.getOutcome()).isEqualTo(GmailProcessedMessage.Outcome.PARSED);
    }

    @Test
    @DisplayName("with no arrival time Gmail could give, nothing is staged and it is reported as malformed, not dated today")
    void doesNotStageWithoutAnArrivalTime() {
        when(gmail.getMessageBody(TOKEN, "m1")).thenReturn(new GmailApiClient.MessageBody(BODY, null));

        GmailReceiptExtractionService.ExtractionResult result = service.extractFor(connection, 50);

        assertThat(result.staged()).isZero();
        assertThat(result.malformed()).isEqualTo(1);
        verify(stagingBridge, never()).stage(any(), any());
    }
}
