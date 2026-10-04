package com.finora.integrations.google.merchant;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;

/**
 * The date formats seen across merchant receipt templates so far — extracted from
 * {@code AmazonEmailParser} once a second and third caller (Ola, the C5.2 template engine) needed
 * the identical "given raw captured text, guess which format it's in" logic. Not merchant-specific:
 * every parser's own regex decides WHERE the date text is; this only decides HOW to read it once
 * captured.
 *
 * <p>Extraction, not new capability — {@code AmazonEmailParserTest}'s existing date-parsing
 * coverage (two templates, two formats) still passes unchanged through this class, proving the
 * move didn't alter behavior.
 */
final class ReceiptDateFormats {

    /**
     * "MMM d, yyyy" (abbreviated month) is tried before "MMMM d, yyyy" (full month) deliberately —
     * the two are mutually exclusive for any real input (a captured string is either abbreviated or
     * full, never both), so the order only affects how many guaranteed-to-fail attempts a call
     * burns before its actual format succeeds, never which format wins. PhonePe/CRED both only ever
     * produce the abbreviated form; putting it first means their (now two of six) callers succeed
     * on the first try instead of the second.
     *
     * <p>Every pattern is built by {@link #strict}, so an impossible day ("Feb 30, 2026",
     * "31/04/2026") is refused instead of being moved to the month's last day. {@code
     * ISO_LOCAL_DATE} is already strict.
     */
    private static final List<DateTimeFormatter> FORMATS = List.of(
            strict("MMM d, yyyy"),
            strict("MMMM d, yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            strict("d MMMM yyyy"),
            strict("dd/MM/yyyy"),
            strict("dd-MM-yyyy"));

    private ReceiptDateFormats() {}

    /**
     * {@code DateTimeFormatter.ofPattern} resolves SMART, which reads "31/04/2026" as 30 April: a
     * real date, a day off, with nothing to say it was guessed. STRICT refuses it, and every receipt
     * parser reports the receipt as malformed instead.
     *
     * <p>STRICT needs the era for {@code yyyy} (year-of-era), so it is defaulted to AD. {@code uuuu}
     * would not need it, but it also accepts year 0 and signed years, which no receipt prints.
     */
    private static DateTimeFormatter strict(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .parseDefaulting(ChronoField.ERA, 1)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /** Null rather than throwing — an unrecognised format is the same "template moved" signal
     *  every caller already reports as {@code ParserResult.malformed}, not a crash. */
    static LocalDate tryParse(String rawText) {
        if (rawText == null) return null;
        for (DateTimeFormatter format : FORMATS) {
            try {
                return LocalDate.parse(rawText.strip(), format);
            } catch (DateTimeParseException ignored) {
                // Try the next format -- merchant templates are not consistent about this.
            }
        }
        return null;
    }
}
