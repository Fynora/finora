package com.finora.service;

import com.finora.entity.CounterpartyCategoryObservation;
import com.finora.entity.SharedMerchantCategory;
import com.finora.entity.Transaction;
import com.finora.repository.CounterpartyCategoryObservationRepository;
import com.finora.repository.SharedMerchantCategoryRepository;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SharedCorpusServiceTest {

    private CounterpartyCategoryObservationRepository observations;
    private SharedMerchantCategoryRepository corpus;
    private SharedCorpusService service;
    private final List<CounterpartyCategoryObservation> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        observations = mock(CounterpartyCategoryObservationRepository.class);
        corpus = mock(SharedMerchantCategoryRepository.class);
        service = new SharedCorpusService(observations, corpus);

        stored.clear();
        when(observations.save(any())).thenAnswer(inv -> {
            CounterpartyCategoryObservation o = inv.getArgument(0);
            stored.add(o);
            return o;
        });
        when(observations.findByCounterpartyKeyAndDirection(any(), any()))
                .thenAnswer(inv -> new ArrayList<>(stored));
    }

    @Test
    void isEligible_vpaKeyBusinessType_true() {
        assertThat(SharedCorpusService.isEligible("vpa:zepto", CounterpartyType.BUSINESS)).isTrue();
        assertThat(SharedCorpusService.isEligible("vpa:groww", CounterpartyType.FINANCIAL_INSTITUTION)).isTrue();
    }

    @Test
    void isEligible_nameKeyOrPersonType_false() {
        assertThat(SharedCorpusService.isEligible("name:rahul", CounterpartyType.BUSINESS)).isFalse();
        assertThat(SharedCorpusService.isEligible("vpa:rahul", CounterpartyType.PERSON)).isFalse();
        assertThat(SharedCorpusService.isEligible("vpa:incometax", CounterpartyType.GOVERNMENT)).isFalse();
        assertThat(SharedCorpusService.isEligible(null, CounterpartyType.BUSINESS)).isFalse();
    }

    @Test
    void recordObservation_ineligibleCounterparty_writesNothing() {
        service.recordObservation(UUID.randomUUID(), "name:rahul", CounterpartyType.PERSON,
                Transaction.Type.EXPENSE, "Dining");

        verifyNoInteractions(observations, corpus);
    }

    @Test
    void recordObservation_twoVotersOnly_staysUnpromoted() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");

        assertThat(stored).hasSize(2);
        verify(corpus, never()).save(any());
    }

    @Test
    void recordObservation_thirdDistinctVoterWithClearMajority_promotesToTrusted() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        for (int i = 0; i < 3; i++) {
            service.recordObservation(UUID.randomUUID(), "vpa:newmerchant", CounterpartyType.BUSINESS,
                    Transaction.Type.EXPENSE, "Dining");
        }

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
        assertThat(saved.getCategory()).isEqualTo("Dining");
        assertThat(saved.getDistinctUserCount()).isEqualTo(3);
    }

    @Test
    void recordObservation_threeVotersNoMajority_promotesToDisputed() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");
        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Groceries");
        service.recordObservation(UUID.randomUUID(), "vpa:ambiguous", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Electronics");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.DISPUTED);
    }

    @Test
    void recordObservation_threeVotersTwoOneSplit_belowSeventyPercent_isDisputed() {
        when(corpus.findByCounterpartyKeyAndDirection(any(), any())).thenReturn(Optional.empty());

        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Dining");
        service.recordObservation(UUID.randomUUID(), "vpa:lowvolume", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");

        // 2/3 = 66.7% < 70%: Disputed via the share test alone.
        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.DISPUTED);
    }

    // ---- Task 3: contradiction handling (Revalidating) ----

    @Test
    void recordObservation_contradictsTrustedRow_flipsToRevalidatingImmediately() {
        SharedMerchantCategory trusted = new SharedMerchantCategory();
        trusted.setCounterpartyKey("vpa:kronos");
        trusted.setDirection(Transaction.Type.INCOME);
        trusted.setStatus(SharedMerchantCategory.Status.TRUSTED);
        trusted.setCategory("Salary");
        trusted.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        trusted.setDistinctUserCount(8);
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(trusted));

        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus, atLeastOnce()).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(SharedMerchantCategory.Status.REVALIDATING);
        assertThat(saved.getRevalidatingSince()).isNotNull();
        assertThat(saved.getCategory()).isEqualTo("Salary");
    }

    @Test
    void recordObservation_agreesWithTrustedRow_doesNotEnterRevalidating() {
        SharedMerchantCategory trusted = new SharedMerchantCategory();
        trusted.setCounterpartyKey("vpa:zepto");
        trusted.setDirection(Transaction.Type.EXPENSE);
        trusted.setStatus(SharedMerchantCategory.Status.TRUSTED);
        trusted.setCategory("Shopping");
        trusted.setCategoryDistribution(Map.of("Shopping", BigDecimal.ONE));
        trusted.setDistinctUserCount(5);
        when(corpus.findByCounterpartyKeyAndDirection("vpa:zepto", Transaction.Type.EXPENSE))
                .thenReturn(Optional.of(trusted));
        // The corpus row's distinctUserCount alone doesn't drive recompute -- recomputeAndPromote
        // re-reads the real observation log, so the fixture must seed it to match, not just set
        // a number on the corpus row mock.
        List<CounterpartyCategoryObservation> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CounterpartyCategoryObservation o = new CounterpartyCategoryObservation();
            o.setCounterpartyKey("vpa:zepto");
            o.setDirection(Transaction.Type.EXPENSE);
            o.setCategory("Shopping");
            o.setUserId(UUID.randomUUID());
            o.setCounterpartyTypeAtVote(CounterpartyType.BUSINESS);
            o.setCreatedAt(Instant.now());
            history.add(o);
        }
        when(observations.findByCounterpartyKeyAndDirection("vpa:zepto", Transaction.Type.EXPENSE))
                .thenReturn(history);

        service.recordObservation(UUID.randomUUID(), "vpa:zepto", CounterpartyType.BUSINESS,
                Transaction.Type.EXPENSE, "Shopping");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SharedMerchantCategory.Status.TRUSTED);
    }

    @Test
    void recordObservation_whileRevalidatingAndCooldownNotElapsed_staysRevalidating() {
        SharedMerchantCategory revalidating = new SharedMerchantCategory();
        revalidating.setCounterpartyKey("vpa:kronos");
        revalidating.setDirection(Transaction.Type.INCOME);
        revalidating.setStatus(SharedMerchantCategory.Status.REVALIDATING);
        revalidating.setCategory("Salary");
        revalidating.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        revalidating.setDistinctUserCount(8);
        revalidating.setRevalidatingSince(Instant.now().minus(Duration.ofDays(1)));
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(revalidating));
        when(observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(1L);

        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        // Still cooling down -- the correct behavior is to touch nothing further (the row is
        // already Revalidating in the DB; there's nothing new to persist until the window
        // elapses), not to re-save an unchanged row.
        verify(corpus, never()).save(any());
    }

    @Test
    void recordObservation_whileRevalidatingAndThreeObservationsElapsed_recomputesNormally() {
        SharedMerchantCategory revalidating = new SharedMerchantCategory();
        revalidating.setCounterpartyKey("vpa:kronos");
        revalidating.setDirection(Transaction.Type.INCOME);
        revalidating.setStatus(SharedMerchantCategory.Status.REVALIDATING);
        revalidating.setCategory("Salary");
        revalidating.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        revalidating.setDistinctUserCount(8);
        revalidating.setRevalidatingSince(Instant.now().minus(Duration.ofDays(5)));
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(revalidating));
        when(observations.countByCounterpartyKeyAndDirectionAndCreatedAtAfter(any(), any(), any()))
                .thenReturn(3L);
        List<CounterpartyCategoryObservation> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(observationOf("Salary", Instant.now().minus(Duration.ofDays(400))));
        for (int i = 0; i < 3; i++) history.add(observationOf("Business Expenses", Instant.now()));
        when(observations.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(history);

        service.recordObservation(UUID.randomUUID(), "vpa:kronos", CounterpartyType.BUSINESS,
                Transaction.Type.INCOME, "Business Expenses");

        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus, atLeastOnce()).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus()).isIn(SharedMerchantCategory.Status.TRUSTED, SharedMerchantCategory.Status.DISPUTED);
        assertThat(saved.getRevalidatingSince()).isNull();
    }

    // ---- Fix: time-only exit from Revalidating (spec §7's "or just time, with nothing further
    // disagreeing" path -- reactive recordObservation never fires when no further observation
    // arrives, so a scheduled sweep must catch rows whose cooldown elapsed anyway) ----

    @Test
    void reevaluateTimedOutRevalidations_dueRow_recomputesAgainstFullHistory() {
        SharedMerchantCategory revalidating = new SharedMerchantCategory();
        revalidating.setCounterpartyKey("vpa:kronos");
        revalidating.setDirection(Transaction.Type.INCOME);
        revalidating.setStatus(SharedMerchantCategory.Status.REVALIDATING);
        revalidating.setCategory("Salary");
        revalidating.setCategoryDistribution(Map.of("Salary", BigDecimal.ONE));
        revalidating.setDistinctUserCount(8);
        revalidating.setRevalidatingSince(Instant.now().minus(Duration.ofDays(95)));
        when(corpus.findByStatusAndRevalidatingSinceBefore(any(), any(), any()))
                .thenReturn(List.of(revalidating));
        when(corpus.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(Optional.of(revalidating));
        List<CounterpartyCategoryObservation> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(observationOf("Salary", Instant.now().minus(Duration.ofDays(400))));
        history.add(observationOf("Business Expenses", Instant.now().minus(Duration.ofDays(95))));
        when(observations.findByCounterpartyKeyAndDirection("vpa:kronos", Transaction.Type.INCOME))
                .thenReturn(history);

        int count = service.reevaluateTimedOutRevalidations(500);

        assertThat(count).isEqualTo(1);
        ArgumentCaptor<SharedMerchantCategory> captor = ArgumentCaptor.forClass(SharedMerchantCategory.class);
        verify(corpus, atLeastOnce()).save(captor.capture());
        SharedMerchantCategory saved = captor.getValue();
        assertThat(saved.getStatus())
                .isIn(SharedMerchantCategory.Status.TRUSTED, SharedMerchantCategory.Status.DISPUTED);
        assertThat(saved.getRevalidatingSince()).isNull();
    }

    @Test
    void reevaluateTimedOutRevalidations_noneDue_returnsZeroWithoutTouchingCorpus() {
        when(corpus.findByStatusAndRevalidatingSinceBefore(any(), any(), any()))
                .thenReturn(List.of());

        int count = service.reevaluateTimedOutRevalidations(500);

        assertThat(count).isEqualTo(0);
        verify(corpus, never()).save(any());
    }

    private static CounterpartyCategoryObservation observationOf(String category, Instant createdAt) {
        CounterpartyCategoryObservation o = new CounterpartyCategoryObservation();
        o.setCounterpartyKey("vpa:kronos");
        o.setDirection(Transaction.Type.INCOME);
        o.setCategory(category);
        o.setUserId(UUID.randomUUID());
        o.setCounterpartyTypeAtVote(CounterpartyType.BUSINESS);
        o.setCreatedAt(createdAt);
        return o;
    }
}
