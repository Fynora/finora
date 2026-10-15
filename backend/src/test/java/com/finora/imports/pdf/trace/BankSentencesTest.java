package com.finora.imports.pdf.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bank-sentence list's invariant: every entry is already normalised and can never carry a
 * reference, an account number or a contact. Every string here is synthetic.
 */
class BankSentencesTest {

    @Test
    void normaliseLowersCollapsesAndTrims() {
        assertThat(BankSentences.normalise("  This  is A\tComputer   Generated Statement "))
                .isEqualTo("this is a computer generated statement");
    }

    @Test
    void normaliseOfNullIsEmpty() {
        assertThat(BankSentences.normalise(null)).isEmpty();
    }

    @Test
    void everyProductionEntryIsSafe() {
        BankSentences.entries().forEach(BankSentences::requireSafe);
    }

    @Test
    void everyProductionEntryIsAlreadyNormalised() {
        BankSentences.entries().forEach(e -> assertThat(BankSentences.normalise(e)).isEqualTo(e));
    }

    @Test
    void everyProductionEntryIsListed() {
        BankSentences.entries().forEach(e -> assertThat(BankSentences.isListed(e)).isTrue());
    }

    @Test
    void aSafeEntryIsAccepted() {
        assertThat(BankSentences.forTesting("This is a computer generated statement"))
                .containsExactly("this is a computer generated statement");
    }

    @Test
    void anEntryWithADigitIsRefused() {
        assertRefused("call us on 1800 for help today");
    }

    @Test
    void anEntryWithANonAsciiDigitIsRefused() {
        assertRefused("call us on १८०० for help today");
    }

    @Test
    void anEntryOfThreeWordsIsRefused() {
        assertRefused("computer generated statement");
    }

    @Test
    void anEmptyEntryIsRefused() {
        assertRefused("");
    }

    @Test
    void anEntryWithAnAtIsRefused() {
        assertRefused("write to care at bank@example.com now");   // synthetic-ok
    }

    @Test
    void anEntryShapedLikeAnIfscIsRefused() {
        assertRefused("branch code is abcd0xyzxyz here");   // synthetic-ok
    }

    @Test
    void anIfscShapeInsidePunctuationIsRefused() {
        assertRefused("branch code is (abcd0xyzxyz) here");   // synthetic-ok
    }

    @Test
    void aNonAsciiEntryIsRefused() {
        assertRefused("this is a computer generated stätement");
    }

    @Test
    void anEntryNotYetNormalisedIsRefused() {
        assertRefused("This is a computer generated statement");
    }

    @Test
    void refusalsNameTheRuleNotTheText() {
        assertThatThrownBy(() -> BankSentences.requireSafe("call us on 1800 for help today"))
                .hasMessageNotContaining("1800").hasMessageContaining("digit");
    }

    private static void assertRefused(String entry) {
        assertThatThrownBy(() -> BankSentences.requireSafe(entry)).isInstanceOf(IllegalArgumentException.class);
    }
}
