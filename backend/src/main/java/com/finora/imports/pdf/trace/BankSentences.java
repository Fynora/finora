package com.finora.imports.pdf.trace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Footer and notice sentences a bank prints on every customer's statement. A blanked copy keeps a
 * printed line verbatim only when its whole text is one of these (see
 * docs/superpowers/specs/2026-10-15-bank-sentences-design.md). A line holding a listed sentence
 * and anything else -- a name, a number -- is not a match, so nothing beside a sentence is ever
 * kept with it.
 *
 * <p>Entries are added by a reviewed PR only, chosen by the owner from his own statements, and
 * each must pass {@link #requireSafe}: no digit, at least four words, no '@', no IFSC-shaped word,
 * ASCII only with no control character, already normalised. An entry can therefore never carry a reference, an account
 * number or a contact.
 */
public final class BankSentences {

    private BankSentences() {}

    /** An IFSC's shape, in lower case since entries are normalised: four letters, a zero, then six
     *  letters or digits, standing apart from other letters and digits. */
    private static final Pattern IFSC_SHAPE = Pattern.compile("(?<![a-z0-9])[a-z]{4}0[a-z0-9]{6}(?![a-z0-9])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** A control character other than ASCII whitespace. Some fonts print a ligature ("ffi", "fi")
     *  as one: a person reading the statement sees the letters, writes the entry without it, and
     *  a printed line holding it still has to match. */
    static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\t\\n\\x0B\\f\\r]]");

    /** Normalised entries. Grouped by the bank each was seen on, one comment per group. */
    private static final List<String> ENTRIES = List.of(
    );

    private static final Set<String> LISTED = listed(ENTRIES);

    private static Set<String> listed(List<String> entries) {
        entries.forEach(BankSentences::requireSafe);
        // Set.copyOf refuses a duplicate, so a doubled entry fails at class load.
        return Set.copyOf(entries);
    }

    /** Control characters dropped, lower case, every run of whitespace one space, trimmed. */
    public static String normalise(String line) {
        if (line == null) return "";
        String visible = CONTROL.matcher(line).replaceAll("");
        return WHITESPACE.matcher(visible.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    /** Whether {@code c} is a control character {@link #normalise} drops. */
    public static boolean isControl(char c) {
        return CONTROL.matcher(String.valueOf(c)).matches();
    }

    /** The production list, normalised. */
    public static List<String> entries() {
        return ENTRIES;
    }

    /** The production list as a set, for matching. */
    public static Set<String> listed() {
        return LISTED;
    }

    public static boolean isListed(String normalisedLine) {
        return LISTED.contains(normalisedLine);
    }

    /** A set of synthetic entries for tests, each normalised and checked as a real entry is. */
    static Set<String> forTesting(String... entries) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : entries) {
            String normalised = normalise(entry);
            requireSafe(normalised);
            out.add(normalised);
        }
        return out;
    }

    /**
     * Refuses an entry that could carry anything identifying. The message names the broken rule,
     * never the entry's text: a refused entry may be exactly the text that must not be printed.
     */
    public static void requireSafe(String entry) {
        if (entry == null) throw new IllegalArgumentException("entry is null");
        if (entry.codePoints().anyMatch(Character::isDigit)) throw new IllegalArgumentException("entry has a digit");
        if (!entry.chars().allMatch(c -> c < 128)) throw new IllegalArgumentException("entry is not ASCII");
        if (CONTROL.matcher(entry).find()) throw new IllegalArgumentException("entry has a control character");
        if (!entry.equals(normalise(entry))) throw new IllegalArgumentException("entry is not normalised");
        if (entry.isEmpty() || entry.split(" ").length < 4) throw new IllegalArgumentException("entry has fewer than 4 words");
        if (entry.indexOf('@') >= 0) throw new IllegalArgumentException("entry has an @");
        if (IFSC_SHAPE.matcher(entry).find()) throw new IllegalArgumentException("entry has an IFSC-shaped word");
    }
}
