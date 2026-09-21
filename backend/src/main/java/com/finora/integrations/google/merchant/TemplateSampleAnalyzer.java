package com.finora.integrations.google.merchant;

import com.finora.integrations.google.SenderAuthenticationService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a real receipt email and proposes the pieces of a {@link MerchantTemplate} for it, so an
 * admin creating a template picks values off the email instead of authoring patterns by hand.
 *
 * <h2>Why candidates are found by shape, not by line</h2>
 *
 * Checked on real receipts: after sanitizing, several merchants' emails are a single line of
 * hundreds of characters, and one keeps a label and its value on different lines. So this does not
 * look for labelled lines. It finds every amount and date in the text by shape, and for each builds
 * the shortest label, taken from the words just before it, that makes the pattern find THAT value
 * first.
 *
 * <h2>Every proposal is checked, not assumed</h2>
 *
 * A proposed pattern is compiled with the production matcher and run over the same text the
 * pipeline would see. It is offered only if its first match is exactly the value it was built for,
 * because {@link TemplateEmailParser} takes the first match. A label that would also match an
 * earlier figure is lengthened until it does not, and a value that cannot be told apart within a
 * few words is not offered at all.
 *
 * <p>Nothing here is stored or logged. The email is personal data: it is read in memory and the
 * result goes only to the admin who supplied it.
 */
@Component
public class TemplateSampleAnalyzer {

    /** The most characters of an email accepted; matches what the reader will read. */
    public static final int MAX_EMAIL_CHARS = MimeMessageReader.MAX_RAW_CHARS;

    private static final int MAX_CANDIDATES = 25;

    /** Real receipts sanitize to a few tens of thousands of characters. Candidates are searched for
     *  in a prefix of at most this many, and at most this many values are anchored, so an
     *  adversarial or accidental huge file cannot make the search expensive. A pattern found in
     *  the prefix reads the same value in the whole text, because the first match cannot move. */
    private static final int MAX_SEARCH_CHARS = 150_000;
    private static final int MAX_VALUES_ANCHORED = 200;
    private static final int MAX_LABEL_WORDS = 8;
    private static final int SCORE_EXTRA_WORDS = 3;
    private static final int MAX_LABEL_CHARS = 80;
    private static final int MAX_MARKERS = 10;

    /** An amount written after a currency mark, with or without paise ("₹520", "Rs. 1,491.00"). */
    private static final Pattern CURRENCY_AMOUNT = Pattern.compile(
            "(?i:₹|Rs\\.?|INR)\\s*" + MerchantTemplate.AMOUNT_CAPTURE);

    /** An amount written with paise, wherever it is ("1,491.00"). A bare integer is not an amount:
     *  every quantity and count in an email would be. */
    private static final Pattern PAISE_AMOUNT = Pattern.compile("(?<!\\d)([\\d,]{1,18}\\.\\d{2})(?![.,]?\\d)");

    private static final Pattern DATE = Pattern.compile(MerchantTemplate.DATE_CAPTURE);

    /** Words that make the figure likely the amount paid. */
    private static final Pattern PAID_WORDS = Pattern.compile(
            "(?i)grand total|net (?:amount|paid)|amount paid|total paid|payable|paid|amount charged|bill total");

    private static final Pattern TOTAL_WORDS = Pattern.compile("(?i)total|amount|fare|charged|bill");

    /** Words that make it a part of the bill, or money not spent. */
    private static final Pattern PART_WORDS = Pattern.compile(
            "(?i)sub ?total|discount|saved|saving|mrp|fee|tax|surcharge|gst|refund|coupon|off\\b");

    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    /** An email address: group 1 is the part before the @. */
    private static final Pattern ADDRESS = Pattern.compile("([\\w.+-]+)@[\\w.-]+");

    /** Footer and navigation text that every email from a merchant carries, receipts and marketing
     *  alike, so it cannot tell a receipt from anything else. */
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?i)rights reserved|privacy|terms|unsubscribe|copyright|affiliates|view (?:in|online|order)"
                    + "|download|follow us|customer care|contact us|need help|©|private limited|pvt\\.? ltd");

    /** A phrase that opens a personalised message ("Hi Asha", "Dear customer"). */
    private static final Pattern GREETING = Pattern.compile("(?i)^(?:hi|hello|dear|hey)\\b");
    private static final Pattern HAS_LETTER_WORD = Pattern.compile("\\p{L}{3,}");

    /** A value found in the email, and the pattern that reads exactly it. */
    public record Candidate(String pattern, String value, String context, boolean labelled,
                            boolean likelyTotal) {}

    /**
     * What was learned from one email.
     *
     * @param receivedOn         the day the email was sent (India time), from its Date header; null
     *                           when it could not be read
     * @param arrivalDatePattern the date pattern that dates a receipt by that day, for a merchant
     *                           whose emails print no date
     * @param html               the email's HTML body as production would read it, so a test can
     *                           run through the same sanitizer
     * @param text               that HTML after sanitizing: what the patterns are matched against
     */
    public record Analysis(String authenticatedDomain, String senderVerdict, boolean domainIsTrusted,
                           String senderName, LocalDate receivedOn, String arrivalDatePattern,
                           String html, String text,
                           List<Candidate> amounts, List<Candidate> dates,
                           List<String> receiptMarkerSuggestions, List<String> problems) {}

    private final MerchantEmailSanitizer sanitizer;
    private final SenderAuthenticationService authentication;

    public TemplateSampleAnalyzer(MerchantEmailSanitizer sanitizer, SenderAuthenticationService authentication) {
        this.sanitizer = sanitizer;
        this.authentication = authentication;
    }

    /**
     * @param rawEmail a whole email as downloaded ("Show original" then "Download original")
     * @throws IllegalArgumentException if it is empty, too large, or has no readable body
     */
    public Analysis analyze(String rawEmail) {
        MimeMessageReader.Parsed mime = MimeMessageReader.read(rawEmail);

        // The same choice extraction makes: the HTML part, else the plain-text part.
        String body = mime.html() != null ? mime.html() : mime.text();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException(
                    "No readable message body was found in this file. Use \"Download original\" from "
                            + "the email's menu in Gmail and upload that .eml file.");
        }

        SenderAuthenticationService.Result sender =
                authentication.evaluate(mime.firstHeader("authentication-results"));
        String domain = sender.authenticatedDomain();
        LocalDate receivedOn = receivedOn(mime.firstHeader("date"));

        SanitizedGmailMessage message = sanitizer.sanitize(
                "analysis-" + UUID.randomUUID(), domain != null ? domain : "unknown.invalid", body, receivedOn);
        String text = message.plainText();

        List<String> problems = new ArrayList<>();
        if (domain == null) {
            problems.add("Gmail did not authenticate this message as coming from any domain, so the "
                    + "sender domain could not be read. Enter it by hand, and check it against the "
                    + "email's original headers (the dmarc=pass header.from value).");
        }

        // Words that identify the person who received the email. They must never end up in a
        // pattern or a suggested marker, because those are saved in the template table.
        Set<String> personal = personalTokens(mime);

        List<Candidate> amounts = candidates(text, List.of(CURRENCY_AMOUNT, PAISE_AMOUNT), "{amount}",
                TemplateSampleAnalyzer::amountProbe, group -> parseAmount(group) != null, true, personal);
        List<Candidate> dates = candidates(text, List.of(DATE), "{date}",
                TemplateSampleAnalyzer::dateProbe, group -> ReceiptDateFormats.tryParse(group) != null, false,
                personal);

        if (amounts.isEmpty()) {
            problems.add("No amount was found in the readable text. If the amount is only in an attached "
                    + "PDF or an image, a template cannot read it.");
        }
        if (dates.isEmpty()) {
            problems.add(receivedOn != null
                    ? "This email prints no date with a year. Use the day the email arrived ("
                            + receivedOn + ") as the receipt date instead."
                    : "This email prints no date with a year, and the day it arrived could not be read "
                            + "from it, so a template cannot date it.");
        }

        return new Analysis(domain, sender.verdict().name(), sender.isTrusted(),
                senderName(mime.firstHeader("from")), receivedOn, MerchantTemplate.RECEIVED_PLACEHOLDER,
                body, text, amounts, dates, markerSuggestions(text, personal), problems);
    }

    // ------------------------------------------------------------------------------------------

    /** The Date header as an India-time day, or null. The header can carry a trailing comment
     *  such as "(UTC)", which RFC 1123 parsing rejects. */
    private static LocalDate receivedOn(String dateHeader) {
        if (dateHeader == null || dateHeader.isBlank()) return null;
        String cleaned = dateHeader.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
        try {
            return ZonedDateTime.parse(cleaned, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(GmailReceiptExtractionService.RECEIPT_DAY_ZONE)
                    .toLocalDate();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String group) {
        try {
            return new BigDecimal(group.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Every value of one kind in the text, each with the shortest label that makes its pattern find
     * that value first.
     *
     * @param shapes      how a value looks; group 1 is the value. A value found by more than one
     *                    shape is offered once.
     * @param placeholder {@code {amount}} or {@code {date}}
     * @param probe       builds a throwaway template carrying the candidate pattern, so the check
     *                    runs through the production compiler
     * @param valid       whether the captured text really is a value (a date must parse)
     */
    private List<Candidate> candidates(String text, List<Pattern> shapes, String placeholder,
                                       Function<String, MerchantTemplate> probe,
                                       Predicate<String> valid, boolean isAmount, Set<String> personal) {
        if (text.length() > MAX_SEARCH_CHARS) {
            text = text.substring(0, MAX_SEARCH_CHARS);
        }
        // Position of the value's first character, in text order, each found once.
        TreeMap<Integer, String> values = new TreeMap<>();
        for (Pattern shape : shapes) {
            Matcher m = shape.matcher(text);
            while (m.find()) {
                if (valid.test(m.group(1))) values.putIfAbsent(m.start(1), m.group(1));
            }
        }
        while (values.size() > MAX_VALUES_ANCHORED) {
            values.pollLastEntry();
        }

        record Scored(Candidate candidate, int score) {}
        List<Scored> found = new ArrayList<>();
        Set<String> seenPatterns = new LinkedHashSet<>();
        for (var entry : values.entrySet()) {
            int start = entry.getKey();
            Anchored anchored = anchor(text, start, placeholder, probe, isAmount, personal);
            if (anchored == null || !seenPatterns.add(anchored.pattern)) continue;

            int end = start + entry.getValue().length();
            String context = collapse(text.substring(Math.max(0, start - 40), Math.min(text.length(), end + 12)));
            int score = isAmount ? score(text, start, anchored.label) : 0;
            found.add(new Scored(new Candidate(anchored.pattern, entry.getValue(), context,
                    anchored.labelled, score > 0), score));
        }

        // Best guess first: amounts that look like the amount paid, then labelled before
        // unlabelled, then in the order they appear (the sort is stable).
        found.sort(Comparator
                .comparingInt((Scored s) -> -s.score())
                .thenComparing(s -> !s.candidate().labelled()));
        List<Candidate> ordered = new ArrayList<>();
        for (Scored s : found) {
            if (ordered.size() >= MAX_CANDIDATES) break;
            ordered.add(s.candidate());
        }
        return ordered;
    }

    /**
     * How much an amount looks like the amount paid, judged by its own label plus up to three words
     * before it, stopping at another figure. "Sub" in front of "Total" counts against it, and
     * "Paid Via Split Payment" counts for it even though the label that finds it is only "Payment";
     * but a "Fee" that belongs to the previous line item (there is a figure between) does not count
     * against a real total.
     */
    private static int score(String text, int valueStart, String label) {
        String[] tokens = collapse(text.substring(Math.max(0, valueStart - MAX_LABEL_CHARS), valueStart)).split(" ");
        int labelWords = label.isEmpty() ? 0 : label.split(" ").length;
        int from = Math.max(0, tokens.length - labelWords);
        for (int extra = 0; extra < SCORE_EXTRA_WORDS && from > 0; extra++) {
            if (HAS_DIGIT.matcher(tokens[from - 1]).find()) break;
            from--;
        }
        String window = String.join(" ", java.util.Arrays.copyOfRange(tokens, from, tokens.length));
        int score = 0;
        if (PAID_WORDS.matcher(window).find()) score += 3;
        if (TOTAL_WORDS.matcher(window).find()) score += 1;
        if (PART_WORDS.matcher(window).find()) score -= 3;
        return score;
    }

    private record Anchored(String pattern, String label, boolean labelled) {}

    /**
     * The shortest run of words before {@code valueStart} that, followed by the placeholder, finds
     * this value first. Words containing a digit end the label: a label that leans on another
     * figure would stop matching when that figure changes.
     */
    private Anchored anchor(String text, int valueStart, String placeholder,
                            Function<String, MerchantTemplate> probe, boolean isAmount, Set<String> personal) {
        int from = Math.max(0, valueStart - MAX_LABEL_CHARS);
        String before = text.substring(from, valueStart);
        String trimmed = before.stripTrailing();
        // Whether the value is set off from the label by whitespace. A currency mark is usually
        // glued to the number ("Total ₹597.59"), and a pattern that asked for a space there would
        // never match it, so the pattern records the gap exactly as the email has it.
        String gap = trimmed.length() == before.length() ? "" : " ";
        String[] words = trimmed.isBlank() ? new String[0] : trimmed.strip().split("\\s+");

        List<String> usable = new ArrayList<>();
        for (int i = words.length - 1; i >= 0 && usable.size() < MAX_LABEL_WORDS; i--) {
            if (HAS_DIGIT.matcher(words[i]).find() || isPersonal(words[i], personal)) break;
            usable.add(0, words[i]);
        }

        // Prefer a label with a real word in it; take the first (shortest) one that is unique.
        for (int take = 1; take <= usable.size(); take++) {
            String label = String.join(" ", usable.subList(usable.size() - take, usable.size()));
            if (!HAS_LETTER_WORD.matcher(label).find()) continue;
            String pattern = label + gap + placeholder;
            if (readsFirst(text, valueStart, pattern, probe, isAmount)) {
                return new Anchored(pattern, label, true);
            }
        }
        // Nothing labelled was unique. A bare currency mark, or nothing at all, is weaker but
        // still exact when it is the first such value in the email.
        if (!usable.isEmpty()) {
            String mark = usable.get(usable.size() - 1);
            String pattern = mark + gap + placeholder;
            // Only a lone symbol or short token qualifies; a long word would have matched above.
            if (mark.length() <= 4 && readsFirst(text, valueStart, pattern, probe, isAmount)) {
                return new Anchored(pattern, mark, false);
            }
        }
        if (readsFirst(text, valueStart, placeholder, probe, isAmount)) {
            return new Anchored(placeholder, "", false);
        }
        return null;
    }

    /** Whether the pattern, compiled the way production compiles it, finds this value first. */
    private boolean readsFirst(String text, int valueStart, String pattern,
                               Function<String, MerchantTemplate> probe, boolean isAmount) {
        MerchantTemplate template = probe.apply(pattern);
        Pattern compiled;
        try {
            compiled = isAmount ? template.compileAmountPattern() : template.compileDatePattern();
        } catch (IllegalStateException misconfigured) {
            return false;
        }
        Matcher m = compiled.matcher(text);
        return m.find() && m.start(1) == valueStart;
    }

    private static MerchantTemplate amountProbe(String pattern) {
        MerchantTemplate probe = new MerchantTemplate();
        probe.setAmountPattern(pattern);
        return probe;
    }

    private static MerchantTemplate dateProbe(String pattern) {
        MerchantTemplate probe = new MerchantTemplate();
        probe.setDatePattern(pattern);
        return probe;
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** The display name from a From header, decoded, or null. */
    private static String senderName(String from) {
        if (from == null) return null;
        String decoded = MimeMessageReader.decodeHeaderWords(from);
        int angle = decoded.indexOf('<');
        String name = angle > 0 ? decoded.substring(0, angle) : null;
        if (name == null) return null;
        name = name.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1).trim();
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * Short phrases from the email that could serve as the receipt marker: two or more words, no
     * digits or currency, and present in the text exactly as written, since the marker is matched
     * as a literal substring.
     *
     * <p>A marker is saved in the template table, so a phrase that names the recipient must not be
     * offered: real receipts open "Hi &lt;name&gt;" and print the customer's name and the delivery
     * contact in several places, and one click on such a suggestion would store a customer's name
     * (and make a template that only ever matches that customer). A greeting is dropped outright,
     * and so is any phrase containing a word from the recipient headers.
     */
    private static List<String> markerSuggestions(String text, Set<String> personal) {
        String head = text.length() > MAX_SEARCH_CHARS ? text.substring(0, MAX_SEARCH_CHARS) : text;
        List<String> suggestions = new ArrayList<>();
        for (String segment : head.split("(?<=[.!?])\\s+|\\n+")) {
            String phrase = segment.trim();
            if (phrase.length() < 8 || phrase.length() > 60) continue;
            if (HAS_DIGIT.matcher(phrase).find() || phrase.contains("₹") || phrase.contains("&")) continue;
            if (phrase.split("\\s+").length < 2) continue;
            if (GREETING.matcher(phrase).find() || BOILERPLATE.matcher(phrase).find()
                    || mentionsPersonal(phrase, personal)) continue;
            if (!head.contains(phrase) || suggestions.contains(phrase)) continue;
            suggestions.add(phrase);
            if (suggestions.size() >= MAX_MARKERS) break;
        }
        return suggestions;
    }

    /**
     * The words that identify who the email was sent to: the display names and address local parts
     * in its To, Cc and delivery headers (the domain part is left out, so "gmail" does not remove
     * every phrase that mentions it). Lower-case, three characters or more.
     */
    private static Set<String> personalTokens(MimeMessageReader.Parsed mime) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String header : new String[] {"to", "cc", "bcc", "delivered-to", "x-original-to"}) {
            List<String> values = mime.headers().get(header);
            if (values == null) continue;
            for (String raw : values) {
                String decoded = MimeMessageReader.decodeHeaderWords(raw);
                Matcher address = ADDRESS.matcher(decoded);
                while (address.find()) {
                    addTokens(tokens, address.group(1));
                }
                addTokens(tokens, ADDRESS.matcher(decoded).replaceAll(" "));
            }
        }
        return tokens;
    }

    private static void addTokens(Set<String> tokens, String text) {
        for (String token : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3) tokens.add(token);
            // A local part such as "asha.rao42" also identifies by its letters alone.
            String letters = token.replaceAll("\\d+", "");
            if (letters.length() >= 3) tokens.add(letters);
        }
    }

    /**
     * Whether a word identifies the recipient: it is one of their header words, or (for words of
     * five letters or more) part of one. The second case matters because many addresses have no
     * display name and join the name into the local part ("asharao42@..."), while the email says
     * "Asha Rao": neither word equals the joined token, but each is inside it.
     */
    private static boolean isPersonal(String word, Set<String> personal) {
        if (personal.isEmpty()) return false;
        for (String token : word.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (personal.contains(token)) return true;
            if (token.length() >= 5) {
                for (String p : personal) {
                    if (p.length() > token.length() && p.contains(token)) return true;
                }
            }
        }
        return false;
    }

    private static boolean mentionsPersonal(String phrase, Set<String> personal) {
        return isPersonal(phrase, personal) || looksLikeAName(phrase);
    }

    /** Words that make a two- or three-word capitalised phrase a heading rather than a person. */
    private static final Set<String> HEADING_WORDS = Set.of(
            "order", "orders", "summary", "receipt", "invoice", "fare", "trip", "payment", "booking",
            "confirmed", "confirmation", "delivered", "details", "total", "bill", "tax", "ride", "ticket",
            "successful", "journey", "status", "items", "item", "address", "delivery");

    /**
     * A phrase of two or three capitalised words and nothing else ("Deepti Sharma") is very likely
     * a person, such as the delivery contact, whose name is not in any header. It is not offered, so
     * that one click cannot store it. A heading such as "Order Summary" or "Trip Fare" is kept.
     */
    private static boolean looksLikeAName(String phrase) {
        String[] words = phrase.replaceAll("[^\\p{L}\\s]", " ").trim().split("\\s+");
        if (words.length < 2 || words.length > 3) return false;
        for (String word : words) {
            if (!word.matches("\\p{Lu}\\p{Ll}+")) return false;
            if (HEADING_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) return false;
        }
        return true;
    }
}
