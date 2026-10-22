package com.finora.imports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueShapeTest {

    @Test
    void digitsBecomeNine_lettersBecomeX_andEverythingElseIsKept() {
        assertThat(ValueShape.of("17-Sept-2026 14:05")).isEqualTo("99-XXXX-9999 99:99");
        assertThat(ValueShape.of("(1,500.00)/-")).isEqualTo("(9,999.99)/-");
    }

    @Test
    void aValueAtTheLimitIsKeptWhole_andOneCharacterMoreIsCutAndMarked() {
        String atLimit = "a".repeat(ValueShape.MAX_LENGTH);

        assertThat(ValueShape.of(atLimit)).isEqualTo("X".repeat(ValueShape.MAX_LENGTH));
        assertThat(ValueShape.of(atLimit + "b")).isEqualTo("X".repeat(ValueShape.MAX_LENGTH) + "~");
    }

    @Test
    void indicVowelSignsAndViramasAreMaskedWithTheirLetters() {
        // Devanagari and Tamil sample words. The vowel signs and the virama are combining marks, not
        // letters; kept, they spelled out part of the word.
        assertThat(ValueShape.of("राम कुमार")).isEqualTo("XXX XXXXX");
        assertThat(ValueShape.of("ராம்")).isEqualTo("XXXX");
        assertThat(ValueShape.of("école")).isEqualTo("XXXXXX");
    }

    @Test
    void aLetterOutsideTheBasicPlaneIsOneMaskedCharacterNotTwoKeptSurrogates() {
        assertThat(ValueShape.of("𠀋𠀋")).isEqualTo("XX");
    }

    @Test
    void numeralsThatAreNotDecimalDigitsAreNumbersToo() {
        assertThat(ValueShape.of("½ Ⅰ ²")).isEqualTo("9 9 9");
        // Devanagari digits are decimal digits.
        assertThat(ValueShape.of("१२/०९")).isEqualTo("99/99");
    }

    @Test
    void emojiAndUnpairedSurrogatesBecomeAQuestionMark() {
        assertThat(ValueShape.of("PAID 🎂")).isEqualTo("XXXX ?");
        assertThat(ValueShape.of("A\uD800B")).isEqualTo("X?X");
    }

    @Test
    void punctuationSpacesAndCurrencySignsAreKeptBecauseTheyAreTheFormat() {
        assertThat(ValueShape.of("₹1,500.00")).isEqualTo("₹9,999.99");
        assertThat(ValueShape.of("12 –30")).isEqualTo("99 –99");
    }

    @Test
    void theLimitCountsCharactersNotUtf16Units() {
        String atLimit = "𠀋".repeat(ValueShape.MAX_LENGTH);

        assertThat(ValueShape.of(atLimit)).isEqualTo("X".repeat(ValueShape.MAX_LENGTH));
        assertThat(ValueShape.of(atLimit + "𠀋")).isEqualTo("X".repeat(ValueShape.MAX_LENGTH) + "~");
    }

    @Test
    void anEmptyValueHasAnEmptyShape() {
        assertThat(ValueShape.of("")).isEmpty();
    }
}
