package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.CreditCardSummaryExtractor;
import com.finora.imports.pdf.PdfTableLocator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words the reading code keys on are statement furniture, not customer data, and a blanked
 * copy that masks them reads differently: a masked "previous balance" label loses a card's opening
 * balance, a masked page-legend sentence lets a footer glue onto a transaction. This reads the
 * reader's own constants, so a keyword added to one of them later fails here until it is on the
 * redactor's allowlist (or the constant is consciously left off this list).
 */
class ReaderKeywordVocabularyTest {

    /** The reader constants whose words the redactor keeps, as {class, field}. */
    private static final List<Object[]> READER_CONSTANTS = List.of(
            new Object[] {CreditCardSummaryExtractor.class, "PREVIOUS_BALANCE_LABELS"},
            new Object[] {CreditCardSummaryExtractor.class, "FEES_LABELS"},
            new Object[] {CreditCardSummaryExtractor.class, "PURCHASES_LABELS"},
            new Object[] {CreditCardSummaryExtractor.class, "CASH_ADVANCE_LABELS"},
            new Object[] {PdfTableLocator.class, "PAGE_LEGEND_BLOCK_START"},
            new Object[] {PdfTableLocator.class, "ILLUSTRATIVE_EXAMPLE_MARKER"},
            new Object[] {PdfTableLocator.class, "EMI_BALANCES_TABLE_MARKER"},
            new Object[] {PdfTableLocator.class, "LOAN_SUMMARY_TABLE_MARKER"},
            new Object[] {PdfTableLocator.class, "DATE_HINTS"});

    private static final Pattern REGEX_ESCAPE = Pattern.compile("\\\\[A-Za-z]|\\(\\?[a-z]+\\)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+");

    @Test
    void everyWordOfTheReadersOwnConstantsIsKept() throws Exception {
        List<String> masked = new ArrayList<>();
        TreeSet<String> words = new TreeSet<>();
        for (Object[] constant : READER_CONSTANTS) {
            for (String phrase : phrases((Class<?>) constant[0], (String) constant[1])) {
                Matcher m = WORD.matcher(REGEX_ESCAPE.matcher(phrase).replaceAll(" "));
                while (m.find()) {
                    String word = m.group().toLowerCase(Locale.ROOT);
                    words.add(word);
                    if (!PdfTraceRedactor.redactLine(word).equals(word)) masked.add(constant[1] + ": " + word);
                }
            }
        }
        assertThat(words).as("the constants yielded no words -- this check is testing nothing").hasSizeGreaterThan(20);
        assertThat(masked).as("reader keywords the redactor would mask").isEmpty();
    }

    /** Every word the redactor keeps is plain ASCII: the backstop in TraceValidator relies on it. */
    @Test
    void everyVocabularyWordIsAscii() {
        assertThat(PdfTraceRedactor.vocabulary())
                .isNotEmpty()
                .allSatisfy(word -> assertThat(word.chars().allMatch(c -> c < 128)).as(word).isTrue());
    }

    private static List<String> phrases(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        if (value instanceof Pattern pattern) return List.of(pattern.pattern());
        if (value instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        throw new IllegalStateException(name + " is neither a Pattern nor a List");
    }
}
