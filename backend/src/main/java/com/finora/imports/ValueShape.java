package com.finora.imports;

/**
 * A cell value's SHAPE with its content removed: digits become 9, letters X, everything else kept.
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

    /** Characters kept before the shape is cut and marked with {@code ~}. */
    static final int MAX_LENGTH = 24;

    private ValueShape() {}

    public static String of(String value) {
        StringBuilder shape = new StringBuilder();
        for (int i = 0; i < value.length() && i < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            shape.append(Character.isDigit(c) ? '9' : Character.isLetter(c) ? 'X' : c);
        }
        if (value.length() > MAX_LENGTH) shape.append('~');
        return shape.toString();
    }
}
