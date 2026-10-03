package com.finora.inflow;

import com.finora.entity.InflowKind;
import com.finora.entity.SenderInflowRule;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.InflowKindRepository;
import com.finora.repository.SenderInflowRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.service.InflowChoiceService;
import com.finora.service.InflowChoices;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.finora.service.FlowClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Plan 2's kind and choice rules, over mocked repositories that keep what is saved. */
class InflowKindServiceTest {

    private final UUID userId = UUID.randomUUID();
    private InflowKindRepository kinds;
    private SenderInflowRuleRepository rules;
    private TransactionRepository txns;
    private InflowKindService service;
    private final List<InflowKind> stored = new ArrayList<>();
    private final List<SenderInflowRule> storedRules = new ArrayList<>();

    @BeforeEach
    void setUp() {
        kinds = mock(InflowKindRepository.class);
        rules = mock(SenderInflowRuleRepository.class);
        txns = mock(TransactionRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        CategoryRepository categories = mock(CategoryRepository.class);
        when(kinds.findByUserId(userId)).thenAnswer(i -> List.copyOf(stored));
        when(kinds.countByUserIdAndBuiltInIsNotNull(userId))
                .thenAnswer(i -> stored.stream().filter(k -> k.getBuiltIn() != null).count());
        when(kinds.insertBuiltInIfMissing(any(), any(), anyBoolean(), any())).thenAnswer(i -> {
            InflowKind.BuiltIn b = InflowKind.BuiltIn.valueOf(i.getArgument(3));
            if (stored.stream().anyMatch(k -> k.getBuiltIn() == b)) return 0;
            stored.add(kind(i.getArgument(1), i.getArgument(2), b));
            return 1;
        });
        when(kinds.save(any())).thenAnswer(i -> {
            InflowKind k = i.getArgument(0);
            if (k.getId() == null) ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
            if (!stored.contains(k)) stored.add(k);
            return k;
        });
        when(kinds.findById(any())).thenAnswer(i -> stored.stream()
                .filter(k -> k.getId().equals(i.getArgument(0))).findFirst());
        when(rules.findByUserId(userId)).thenAnswer(i -> List.copyOf(storedRules));
        when(rules.findByUserIdAndCounterpartyKey(any(), any())).thenAnswer(i -> storedRules.stream()
                .filter(r -> r.getCounterpartyKey().equals(i.getArgument(1))).findFirst());
        when(rules.save(any())).thenAnswer(i -> {
            SenderInflowRule r = i.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
            if (!storedRules.contains(r)) storedRules.add(r);
            return r;
        });
        when(txns.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new InflowKindService(kinds, rules, txns, accounts, categories, new InflowChoiceService(kinds, rules));
    }

    private InflowKind kind(String name, boolean countsAsIncome, InflowKind.BuiltIn b) {
        InflowKind k = new InflowKind();
        ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
        k.setUserId(userId);
        k.setName(name);
        k.setCountsAsIncome(countsAsIncome);
        k.setBuiltIn(b);
        return k;
    }

    private Transaction personCredit(String key) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setTxnType(Transaction.Type.INCOME);
        t.setSource(Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setAmount(new BigDecimal("5000.00"));
        t.setTxnDate(LocalDate.of(2026, 8, 3));
        t.setDescription("UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI");
        t.setCounterpartyType(CounterpartyType.PERSON);
        t.setCounterpartyKey(key);
        when(txns.findById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    private InflowKind builtIn(InflowKind.BuiltIn b) {
        service.list(userId);
        return stored.stream().filter(k -> k.getBuiltIn() == b).findFirst().orElseThrow();
    }

    private static void assertStatus(Throwable e, HttpStatus status) {
        assertThat(e).isInstanceOf(ApiException.class);
        assertThat(((ApiException) e).getStatus()).isEqualTo(status);
    }

    @Test void listCreatesTheFiveBuiltInsOnce() {
        service.list(userId);
        service.list(userId);
        assertThat(service.list(userId)).extracting(InflowDtos.InflowKindDto::name)
                .containsExactly("Income", "Family support", "My own money", "Paid back to me", "Refund");
    }

    @Test void createRejectsATakenName() {
        service.list(userId);
        when(kinds.existsByUserIdAndNameIgnoreCase(userId, "income")).thenReturn(true);
        assertThatThrownBy(() -> service.create(userId, new InflowDtos.CreateKindRequest("income", true)))
                .satisfies(e -> assertStatus(e, HttpStatus.CONFLICT));
    }

    @Test void builtInIncomeFlagCannotChange() {
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.update(userId, income.getId(), new InflowDtos.UpdateKindRequest(null, false)))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test void builtInCanBeRenamed() {
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        assertThat(service.update(userId, family.getId(), new InflowDtos.UpdateKindRequest("From parents", null)).name())
                .isEqualTo("From parents");
    }

    @Test void builtInCannotBeDeleted() {
        InflowKind refund = builtIn(InflowKind.BuiltIn.REFUND);
        assertThatThrownBy(() -> service.delete(userId, refund.getId()))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test void deletingAKindInUseIsRefusedWithCounts() {
        service.list(userId);
        InflowKind rent = kind("Rent from tenant", true, null);
        stored.add(rent);
        when(txns.countLiveByInflowKindId(rent.getId())).thenReturn(2L);
        when(rules.countByInflowKindId(rent.getId())).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(userId, rent.getId())).satisfies(e -> {
            assertStatus(e, HttpStatus.CONFLICT);
            assertThat(((ApiException) e).getDetails()).containsEntry("rows", 2L).containsEntry("senders", 1L);
        });
        verify(kinds, never()).delete(any());
    }

    @Test void anotherUsersKindIsForbidden() {
        InflowKind theirs = kind("Theirs", true, null);
        theirs.setUserId(UUID.randomUUID());
        stored.add(theirs);
        assertThatThrownBy(() -> service.delete(userId, theirs.getId()))
                .satisfies(e -> assertStatus(e, HttpStatus.FORBIDDEN));
    }

    @Test void senderScopeWritesTheRule() {
        Transaction t = personCredit("vpa:asha");
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        when(txns.countLiveCreditsBySender(userId, "vpa:asha")).thenReturn(3L);
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(family.getId(), InflowChoices.Scope.SENDER));
        assertThat(storedRules).singleElement().satisfies(r -> {
            assertThat(r.getCounterpartyKey()).isEqualTo("vpa:asha");
            assertThat(r.getInflowKindId()).isEqualTo(family.getId());
        });
        assertThat(dto.flowClass()).isEqualTo("INCOME");
        assertThat(dto.appliedBy()).isEqualTo("SENDER");
        assertThat(dto.summary()).isEqualTo("You marked payments from this sender as Family support");
    }

    @Test void senderScopeClearsTheRowChoice() {
        Transaction t = personCredit("vpa:asha");
        InflowKind paidBack = builtIn(InflowKind.BuiltIn.PAID_BACK);
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        t.setInflowKindId(paidBack.getId());
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(family.getId(), InflowChoices.Scope.SENDER));
        assertThat(t.getInflowKindId()).isNull();
        assertThat(dto.kind().name()).isEqualTo("Family support");
    }

    @Test void rowScopeSetsOnlyTheRow() {
        Transaction t = personCredit("vpa:asha");
        InflowKind paidBack = builtIn(InflowKind.BuiltIn.PAID_BACK);
        InflowDtos.CountsAsDto dto = service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(paidBack.getId(), InflowChoices.Scope.ROW));
        assertThat(t.getInflowKindId()).isEqualTo(paidBack.getId());
        assertThat(storedRules).isEmpty();
        assertThat(dto.summary()).isEqualTo("You marked this payment as Paid back to me");
    }

    @Test void senderScopeWithoutAKeyIsRefused() {
        Transaction t = personCredit("");
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.SENDER)))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    /** Keys that join credits from different senders: a masked id, an id cut before its "@", a
     *  payment gateway's own id, a name made only of gateway words. */
    private static final List<String> KEYS_NAMING_NO_ONE = List.of(
            "masked:1111@ybl", "cut:sampleqr1111111", "vpa:pg.razorpay", "name:via razorpay");

    @Test void senderScopeOnAKeyThatNamesNoOneIsRefused_andWritesNoRule() {
        // A sender-wide choice would mark every stranger who shares the key -- and change what
        // counts as income for all of them.
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        for (String key : KEYS_NAMING_NO_ONE) {
            Transaction t = personCredit(key);
            assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                    new InflowDtos.SetChoiceRequest(family.getId(), InflowChoices.Scope.SENDER)))
                    .as(key).satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
        }
        assertThat(storedRules).isEmpty();
    }

    @Test void aKeyThatNamesNoOneOffersNoSenderChoice_butTheRowCanStillBeSet() {
        for (String key : KEYS_NAMING_NO_ONE) {
            Transaction t = personCredit(key);
            InflowDtos.CountsAsDto dto = service.countsAs(userId, t.getId());
            assertThat(dto.senderAvailable()).as(key).isFalse();
            assertThat(dto.senderRowCount()).as(key).isZero();
            InflowKind paidBack = builtIn(InflowKind.BuiltIn.PAID_BACK);
            service.setChoice(userId, t.getId(), new InflowDtos.SetChoiceRequest(paidBack.getId(), InflowChoices.Scope.ROW));
            assertThat(t.getInflowKindId()).as(key).isEqualTo(paidBack.getId());
        }
    }

    @Test void aSenderRuleSavedOnAKeyThatNamesNoOne_noLongerApplies() {
        // Saved before such keys were refused, or carried there by the counterparty backfill.
        Transaction t = personCredit("masked:1111@ybl");
        InflowKind family = builtIn(InflowKind.BuiltIn.FAMILY_SUPPORT);
        SenderInflowRule old = new SenderInflowRule();
        old.setUserId(userId);
        old.setCounterpartyKey("masked:1111@ybl");
        old.setInflowKindId(family.getId());
        storedRules.add(old);

        InflowDtos.CountsAsDto dto = service.countsAs(userId, t.getId());

        assertThat(dto.kind()).isNull();
        assertThat(dto.appliedBy()).isNull();
    }

    @Test void aDebitIsRefused() {
        Transaction t = personCredit("vpa:asha");
        t.setTxnType(Transaction.Type.EXPENSE);
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.ROW)))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test void aPairedTransferIsRefused() {
        Transaction t = personCredit("vpa:asha");
        t.setTransfer(true);
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        assertThatThrownBy(() -> service.setChoice(userId, t.getId(),
                new InflowDtos.SetChoiceRequest(income.getId(), InflowChoices.Scope.ROW)))
                .satisfies(e -> assertStatus(e, HttpStatus.BAD_REQUEST));
    }

    @Test void clearingTheRowFallsBackToTheAutomaticReading() {
        Transaction t = personCredit("vpa:asha");
        InflowKind income = builtIn(InflowKind.BuiltIn.INCOME);
        t.setInflowKindId(income.getId());
        InflowDtos.CountsAsDto dto = service.clearChoice(userId, t.getId(), InflowChoices.Scope.ROW);
        assertThat(t.getInflowKindId()).isNull();
        assertThat(dto.flowClass()).isEqualTo("UNRESOLVED");
        assertThat(dto.kind()).isNull();
    }

    @Test void countsAsForAnUnexplainedPersonCredit() {
        Transaction t = personCredit("vpa:asha");
        when(txns.countLiveCreditsBySender(userId, "vpa:asha")).thenReturn(4L);
        InflowDtos.CountsAsDto dto = service.countsAs(userId, t.getId());
        assertThat(dto.flowClass()).isEqualTo("UNRESOLVED");
        assertThat(dto.choosable()).isTrue();
        assertThat(dto.senderAvailable()).isTrue();
        assertThat(dto.senderLabel()).isEqualTo("ASHA VERMA");
        assertThat(dto.senderRowCount()).isEqualTo(4L);
        assertThat(dto.summary()).isEqualTo("Not counted yet · from a person");
    }

    @Test void everyReasonLeftForTheUserSaysSoInItsSummary() {
        // A reason missing from the map falls back to a bare "Money in", which hides that the row
        // is not counted -- the three coverage reasons are new, so pin all five.
        for (FlowClassifier.FlowReason r : List.of(FlowClassifier.FlowReason.PERSON_INFLOW,
                FlowClassifier.FlowReason.CARD_UNEXPLAINED_CREDIT, FlowClassifier.FlowReason.CASH_DEPOSIT,
                FlowClassifier.FlowReason.MERCHANT_CREDIT, FlowClassifier.FlowReason.UNKNOWN_SENDER)) {
            assertThat(InflowKindService.AUTOMATIC_SUMMARY.get(r)).as(r.name()).startsWith("Not counted yet · ");
        }
    }
}
