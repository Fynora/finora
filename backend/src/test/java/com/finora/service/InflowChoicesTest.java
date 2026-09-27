package com.finora.service;

import com.finora.entity.InflowKind;
import com.finora.entity.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InflowChoicesTest {

    private static InflowKind kind(String name) {
        InflowKind k = new InflowKind();
        ReflectionTestUtils.setField(k, "id", UUID.randomUUID());
        k.setName(name);
        k.setCountsAsIncome(true);
        return k;
    }

    private static Transaction credit(String key) {
        Transaction t = new Transaction();
        t.setTxnType(Transaction.Type.INCOME);
        t.setCounterpartyKey(key);
        return t;
    }

    @Test void rowChoiceBeatsSenderRule() {
        InflowKind row = kind("Row kind"), sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(row.getId(), row, sender.getId(), sender),
                Map.of("vpa:asha", sender.getId()));
        Transaction t = credit("vpa:asha");
        t.setInflowKindId(row.getId());
        assertThat(c.chosenFor(t)).isEqualTo(new InflowChoices.Chosen(row, InflowChoices.Scope.ROW));
    }

    @Test void senderRuleAppliesWithoutARowChoice() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("vpa:asha", sender.getId()));
        assertThat(c.chosenFor(credit("vpa:asha"))).isEqualTo(new InflowChoices.Chosen(sender, InflowChoices.Scope.SENDER));
    }

    @Test void blankOrNullKeyNeverMatchesARule() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("", sender.getId()));
        assertThat(c.chosenFor(credit(""))).isNull();
        assertThat(c.chosenFor(credit(null))).isNull();
    }

    @Test void aDebitHasNoChoice() {
        InflowKind sender = kind("Sender kind");
        InflowChoices c = new InflowChoices(Map.of(sender.getId(), sender), Map.of("vpa:asha", sender.getId()));
        Transaction t = credit("vpa:asha");
        t.setTxnType(Transaction.Type.EXPENSE);
        assertThat(c.chosenFor(t)).isNull();
    }

    @Test void noneChoosesNothing() {
        assertThat(InflowChoices.NONE.chosenFor(credit("vpa:asha"))).isNull();
    }
}
