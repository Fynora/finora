package com.finora.service;

import com.finora.util.PersonToPersonTransferDetector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides people's names from text sent to the AI model and puts them back in what it returns, for
 * one request.
 *
 * <p>{@link #shield} replaces each name it finds with a numbered token, {@code [name-1]}, the same
 * token every time the same name appears; {@link #unshield} swaps the tokens in the model's reply
 * back for the real names, so the user still reads "Priya Sharma" in Fyn's answer while the model
 * only ever saw {@code [name-1]}. Names found:
 *
 * <ul>
 *   <li>the people the user has paid or been paid by, read from the payee slot of their own person
 *       payments ({@link FynNameShields#peopleNames}) -- each full name, and its first name, so
 *       "Priya" alone in a question is hidden too once a payment to "PRIYA SHARMA" is on record;</li>
 *   <li>the account holders' own names -- the profile name and the holder on each of the user's
 *       accounts -- whole, cut short or glued ({@link PersonToPersonTransferDetector#maskHolderName});</li>
 *   <li>inside a screenshot's text block, any line or field that reads as a name
 *       ({@link PersonToPersonTransferDetector#maskPersonNames}), the same rules the narration path
 *       uses;</li>
 *   <li>and every name already found, wherever else it appears in the request.</li>
 * </ul>
 *
 * <p>Labels the user wrote, a category for instance, are matched against those names, never read
 * by shape: the shape rules were built for bank narrations, and on a label they read "Apple
 * Purchases" or "Dining Out" as a person (measured), which would hide real categories from the
 * model. Not caught: the name of someone the user has never paid, typed outside a screenshot or a
 * holder name. The privacy policy says so.
 */
public final class FynNameShield {

    private static final Pattern TOKEN = Pattern.compile("(?i)\\[\\s*name[-\\s]?(\\d+)\\s*]");

    private final List<String> holderNames;
    private final Map<String, String> tokenBySpanKey = new HashMap<>();
    private final Map<Integer, String> spanByNumber = new LinkedHashMap<>();
    /** The form each name last took in text this shield hid -- what {@link #unshield} puts back, so
     *  a category label "Priya Sharma" comes back as written, not as the payment's "PRIYA SHARMA". */
    private final Map<String, String> surfaceByToken = new HashMap<>();
    private Pattern knownNames;
    private int knownNamesSize = -1;

    FynNameShield(List<String> holderNames) {
        this(holderNames, java.util.Set.of());
    }

    FynNameShield(List<String> holderNames, java.util.Collection<String> peopleNames) {
        this.holderNames = List.copyOf(holderNames);
        for (String name : peopleNames) {
            if (name != null && !name.isBlank()) tokenFor(name);
        }
    }

    /** The token standing for {@code span}; the same span, ignoring case and spacing, gets the same one. */
    String tokenFor(String span) {
        String trimmed = span.trim();
        String key = trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String token = tokenBySpanKey.computeIfAbsent(key, k -> {
            int number = spanByNumber.size() + 1;
            spanByNumber.put(number, trimmed);
            return "[name-" + number + "]";
        });
        surfaceByToken.put(token, trimmed);
        return token;
    }

    /** {@code text} with every name this shield knows or finds replaced by its token. Null-safe. */
    public String shield(String text) {
        if (text == null || text.isEmpty()) return text;
        // Known names first, whole, so a holder word inside one ("SHARMA" in "PRIYA SHARMA") does
        // not split it; then the holder patterns and the screenshot block; then known names once
        // more, now including any the screenshot turned up, wherever else they appear.
        String out = replaceKnownNames(text);
        for (String holder : holderNames) {
            out = PersonToPersonTransferDetector.maskHolderName(out, holder, this::tokenFor);
        }
        out = shieldScreenshotBlock(out);
        return replaceKnownNames(out);
    }

    /** Every name found so far, wherever it appears, a full name before a shorter name inside it.
     *  A name of plain words is looked up word by word in {@link #tokenBySpanKey}: a user's payees
     *  can run to two thousand names, and one regex alternation over them all measured 183 ms per
     *  call on a 7,000-character chat history -- seconds over a chat turn's dozen calls. The few
     *  names holding other characters ("D'SOUZA") go through a regex over just those. */
    private String replaceKnownNames(String text) {
        if (spanByNumber.isEmpty()) return text;
        if (knownNamesSize != spanByNumber.size()) {
            maxNameWords = 0;
            List<String> otherNames = new ArrayList<>();
            for (String span : spanByNumber.values()) {
                if (span.length() < 3) continue;
                if (PLAIN_NAME.matcher(span).matches()) {
                    maxNameWords = Math.max(maxNameWords, span.split("\\s+").length);
                } else {
                    otherNames.add(span);
                }
            }
            String alternation = otherNames.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(span -> Pattern.quote(span.replaceAll("\\s+", " ")).replace(" ", "\\E\\s+\\Q"))
                    .collect(java.util.stream.Collectors.joining("|"));
            knownNames = alternation.isEmpty() ? null
                    : Pattern.compile("(?i)(?<![A-Za-z])(?:" + alternation + ")(?![A-Za-z])");
            knownNamesSize = spanByNumber.size();
        }
        String out = replacePlainNames(text);
        if (knownNames == null) return out;
        return knownNames.matcher(out).replaceAll(m -> {
            String token = tokenBySpanKey.get(m.group().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
            if (token == null) return Matcher.quoteReplacement(m.group());
            surfaceByToken.put(token, m.group());
            return Matcher.quoteReplacement(token);
        });
    }

    /** A name of letters only, its words separated by whitespace. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z]+(?:\\s+[A-Za-z]+)*");
    private static final Pattern LETTER_RUN = Pattern.compile("[A-Za-z]+");
    private int maxNameWords;

    /** The plain-word names in {@code text}: at each word, the longest run of up to
     *  {@link #maxNameWords} whitespace-separated words that is a known name. A word is a run of
     *  letters, so a name never matches inside a longer word -- the same bounds the regex sets.
     *  The words of a token already written ("[name-1]", "[redacted-id]") are never read. */
    private String replacePlainNames(String text) {
        if (maxNameWords == 0) return text;
        List<int[]> words = new ArrayList<>();
        Matcher w = LETTER_RUN.matcher(text);
        while (w.find()) words.add(new int[] {w.start(), w.end()});
        StringBuilder out = null;
        int kept = 0;
        for (int i = 0; i < words.size(); i++) {
            int start = words.get(i)[0];
            if (start > 0 && text.charAt(start - 1) == '[') continue;
            for (int n = Math.min(maxNameWords, words.size() - i); n >= 1; n--) {
                int end = words.get(i + n - 1)[1];
                if (!joinedByWhitespace(text, words, i, n)) continue;
                String span = text.substring(start, end);
                if (span.length() < 3) continue;
                String token = tokenBySpanKey.get(span.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
                if (token == null) continue;
                surfaceByToken.put(token, span);
                if (out == null) out = new StringBuilder(text.length());
                out.append(text, kept, start).append(token);
                kept = end;
                i += n - 1;
                break;
            }
        }
        return out == null ? text : out.append(text, kept, text.length()).toString();
    }

    /** Whether words {@code i} to {@code i + n - 1} have only whitespace between them. */
    private static boolean joinedByWhitespace(String text, List<int[]> words, int i, int n) {
        for (int k = i; k < i + n - 1; k++) {
            int gapStart = words.get(k)[1];
            int gapEnd = words.get(k + 1)[0];
            if (gapEnd == gapStart) return false;
            for (int c = gapStart; c < gapEnd; c++) {
                if (!Character.isWhitespace(text.charAt(c))) return false;
            }
        }
        return true;
    }

    /** {@code text} with each token put back to the name it stands for. A token the model made up
     *  is left as it is. Null-safe. */
    public String unshield(String text) {
        if (text == null || spanByNumber.isEmpty()) return text;
        // The number is matched as text, never parsed: a token the model makes up with a number too
        // long for an int must come back unchanged, not fail the whole reply.
        return TOKEN.matcher(text).replaceAll(m -> {
            String span = surfaceByToken.get("[name-" + m.group(1).replaceFirst("^0+(?=\\d)", "") + "]");
            return Matcher.quoteReplacement(span != null ? span : m.group());
        });
    }

    /** Line by line inside a screenshot's text block -- the one place a free-text line is read as a
     *  possible name. A question the user typed is not: "Good morning" is not a name, and reading it
     *  as one would hide the question from the model. */
    private String shieldScreenshotBlock(String text) {
        int start = text.indexOf(FynScreenshotOcrService.SCREENSHOT_TEXT_START);
        if (start < 0) return text;
        int blockStart = start + FynScreenshotOcrService.SCREENSHOT_TEXT_START.length();
        int end = text.indexOf(FynScreenshotOcrService.SCREENSHOT_TEXT_END, blockStart);
        if (end < 0) end = text.length();
        List<String> lines = new ArrayList<>();
        for (String line : text.substring(blockStart, end).split("\n", -1)) {
            lines.add(PersonToPersonTransferDetector.maskPersonNames(line, this::tokenFor));
        }
        return text.substring(0, blockStart) + String.join("\n", lines) + text.substring(end);
    }
}
