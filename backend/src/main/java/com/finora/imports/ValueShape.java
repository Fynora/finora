package com.finora.imports;

/**
 * A cell value's SHAPE with its content removed: numbers become 9, letters (any script, with their
 * combining marks) X, symbols such as emoji ?, and punctuation, spaces and currency signs are kept.
 *
 * <p>"We rejected 97 values in the date column" says a format is unsupported; it does not say
 * which, and the obvious next step -- print the values -- means putting statement content in a
 * diagnostic. A shape carries the whole answer and none of the data: {@code 99/99/9999} and
 * {@code 99-XXX-99 99:99:99} are immediately actionable, and neither is anybody's transaction.
 *
 * <p>Truncated, because a mis-bucketed narration line landing in the date column would otherwise
 * produce a shape as long as the sentence.
 *
 * <p>One implementation for both parsers on purpose: the PDF locator's unanchored-row reasons and
 * the CSV generator's unparseable-row reasons land in the same histogram, and two masks that
 * drifted apart would split one format into two apparent shapes.
 */
public final class ValueShape {

    /** Characters (code points) kept before the shape is cut and marked with {@code ~}. */
    static final int MAX_LENGTH = 24;

    private ValueShape() {}

    public static String of(String value) {
        StringBuilder shape = new StringBuilder();
        int i = 0;
        for (int kept = 0; i < value.length() && kept < MAX_LENGTH; kept++) {
            int codePoint = value.codePointAt(i);
            shape.appendCodePoint(maskOf(codePoint));
            i += Character.charCount(codePoint);
        }
        if (i < value.length()) shape.append('~');
        return shape.toString();
    }

    /**
     * Per code point, not per {@code char}, and by Unicode category rather than "is it an ASCII
     * letter". Masking only {@code isLetter}/{@code isDigit} chars let content through in three
     * ways: an Indic word kept its vowel signs and viramas (combining marks, not letters), a letter
     * outside the Basic Multilingual Plane arrived as two surrogate chars that are neither and was
     * kept whole, and numerals such as superscripts or fractions are numbers but not digits.
     *
     * <p>Punctuation, spaces, currency signs and the like are kept: they are the format, and they
     * are what makes a shape actionable. Other symbols (emoji and pictographs, which a narration can
     * carry), private-use and unassigned code points, and unpaired surrogates become {@code ?}.
     */
    private static int maskOf(int codePoint) {
        switch (Character.getType(codePoint)) {
            case Character.DECIMAL_DIGIT_NUMBER:
            case Character.LETTER_NUMBER:
            case Character.OTHER_NUMBER:
                return '9';
            case Character.UPPERCASE_LETTER:
            case Character.LOWERCASE_LETTER:
            case Character.TITLECASE_LETTER:
            case Character.MODIFIER_LETTER:
            case Character.OTHER_LETTER:
            case Character.NON_SPACING_MARK:
            case Character.COMBINING_SPACING_MARK:
            case Character.ENCLOSING_MARK:
                return 'X';
            case Character.OTHER_SYMBOL:
            case Character.PRIVATE_USE:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
                return '?';
            default:
                return codePoint;
        }
    }
}
