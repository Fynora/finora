package com.finora.aisort;

import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.observability.AiSortMetrics;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.FynAvailabilityGuard;
import com.finora.service.FynCategorizationFallbackService;
import com.finora.service.FynCostGovernanceService;
import com.finora.service.TermsConsentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-more AI question is answered once even when two answers race past the offer check
 * together (AI sort break-test, 2026-11-16: "No thanks" and "Turn on AI and sort" sent at once both
 * succeeded). The race itself cannot be timed from outside, so the stamp losing it is stubbed here.
 */
class AiSortServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID importId = UUID.randomUUID();
    private UserRepository users;
    private AiSortRunRepository runs;
    private TermsConsentService termsConsent;
    private AiSortService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        runs = mock(AiSortRunRepository.class);
        termsConsent = mock(TermsConsentService.class);
        TransactionRepository transactions = mock(TransactionRepository.class);
        FynAvailabilityGuard guard = mock(FynAvailabilityGuard.class);
        FynCategorizationFallbackService fallback = mock(FynCategorizationFallbackService.class);

        User saidNo = new User();
        saidNo.recordAiProcessingConsent(false, Instant.now());
        when(users.findById(userId)).thenReturn(Optional.of(saidNo));
        when(guard.categorizationOffered()).thenReturn(true);
        String narration = "UPI-QZX HARDWARE STORE-qzxrunabcdef1234@ybl-REF881234";
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setDescription(narration);
        t.applyCounterpartyTyping(narration);
        t.setAmount(BigDecimal.valueOf(500));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTxnDate(LocalDate.of(2026, 11, 10));
        when(transactions.findWaitingExpensesInImports(eq(userId), anyCollection())).thenReturn(List.of(t));
        when(runs.findByUserIdAndImportsKey(any(), any())).thenReturn(Optional.empty());
        when(runs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new AiSortService(users, transactions, guard, mock(FynCostGovernanceService.class), fallback, runs,
                termsConsent, mock(AiSortMetrics.class));
    }

    private AiSortDto.StartRequest request(AiSortDto.Choice choice) {
        return new AiSortDto.StartRequest(List.of(importId), choice);
    }

    @Test
    void turnOnIsRefused_whenTheOtherAnswerStampedTheQuestionFirst() {
        assertThat(service.offer(userId, List.of(importId)).mode()).isEqualTo(AiSortDto.Mode.ASK);
        when(users.stampAiImportQuestion(eq(userId), any())).thenReturn(0);

        assertThatThrownBy(() -> service.start(userId, request(AiSortDto.Choice.TURN_ON_AND_SORT)))
                .isInstanceOf(ApiException.class);
        verify(termsConsent, never()).submit(any(), any(), any());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void noThanksIsRefused_whenTheOtherAnswerStampedTheQuestionFirst() {
        when(users.stampAiImportQuestion(eq(userId), any())).thenReturn(0);

        assertThatThrownBy(() -> service.start(userId, request(AiSortDto.Choice.NO_THANKS)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theFirstAnswerTurnsAiOnAndStartsTheRun() {
        when(users.stampAiImportQuestion(eq(userId), any())).thenReturn(1);

        AiSortService.Started started = service.start(userId, request(AiSortDto.Choice.TURN_ON_AND_SORT));

        assertThat(started.isNew()).isTrue();
        verify(termsConsent).submit(userId, null, true);
    }
}
