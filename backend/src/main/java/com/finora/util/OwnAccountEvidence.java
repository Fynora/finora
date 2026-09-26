package com.finora.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence that a transaction moved money between the user's OWN accounts (Plan 3,
 * docs/superpowers/specs/2026-09-26-own-account-transfers-design.md). Stateless; the
 * reconciliation pass decides what to do with it.
 *
 * <p>Two kinds, both measured on the real corpus: the 12-digit UPI/IMPS reference both legs of a
 * real own transfer print, and the user's own name in the fixed slot a bank prints the sender or
 * payee in. The slot matters: a salary NEFT prints the employee's own name as BENEFICIARY, so a
 * "narration contains the owner's name" rule would remove salary from income.
 */
public final class OwnAccountEvidence {

    private OwnAccountEvidence() {}

    private static final Pattern REFERENCE = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");

    /** Sender/payee slots, one pattern per observed bank shape; group 1 is the slot. Order matters
     *  only where two could match the same text, and none of these overlap. */
    private static final List<Pattern> SLOTS = List.of(
            // UPI dash: "UPI-<name>-<vpa>-..." (optionally after a bank-name prefix)
            Pattern.compile("(?i)(?:^|\\s)UPI-([^-]{2,60})-"),
            // UPI slash: "UPI/CR/<ref>/<name>/<bank>/", "UPIAB/<ref>/CR/<name>/", "UPIAR/<ref>/DR/<name>/"
            Pattern.compile("(?i)\\bUPI(?:AB|AR)?/(?:(?:CR|DR)/)?[^/]*?/(?:(?:CR|DR)/)?([^/]{2,60})/"),
            // UPI tail: ".../UPI_<name>"
            Pattern.compile("(?i)\\bUPI_([A-Za-z][A-Za-z .]{0,60})\\s*$"),
            // IMPS: "IMPS-CR/<name>/"
            Pattern.compile("(?i)\\bIMPS-CR/([^/]{2,60})/"),
            // IMPS glued: "SentIMPS<12 digits><name>/"
            Pattern.compile("(?i)\\bSentIMPS\\d{12}([^/]{2,60})/"),
            // NEFT credit: "NEFT CR-<ifsc>-<remitter>-<beneficiary>-<ref>" -- remitter only
            Pattern.compile("(?i)\\bNEFT CR-[A-Z0-9]{11}-([^-]{2,80})-"),
            // NEFT credit, star: "NEFT*<ifsc>*<ref>*<remitter> ..."
            Pattern.compile("(?i)\\bNEFT\\*[A-Z0-9]{11}\\*[^*]*\\*([^*]{2,80})"));

    private static final Set<String> TITLES = Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "smt");
    private static final Set<String> RELATION_WORDS = Set.of("so", "do", "wo", "co");

    public static Set<String> references(String description) {
        Set<String> out = new LinkedHashSet<>();
        if (description == null) return out;
        Matcher m = REFERENCE.matcher(description);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static Optional<String> counterpartySlot(String description) {
        if (description == null) return Optional.empty();
        for (Pattern p : SLOTS) {
            Matcher m = p.matcher(description);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    public static List<List<String>> ownerNames(Collection<String> holderNames) {
        Set<List<String>> out = new LinkedHashSet<>();
        for (String holder : holderNames) {
            List<String> words = nameWords(holder, false);
            if (words.size() >= 2) out.add(words);
        }
        return new ArrayList<>(out);
    }

    public static boolean namesOwner(String slot, List<List<String>> ownerNames) {
        List<String> words = nameWords(slot, true);
        if (words.size() < 2) return false;
        for (List<String> owner : ownerNames) {
            if (matches(words, owner)) return true;
        }
        return false;
    }

    private static boolean matches(List<String> slot, List<String> owner) {
        if (owner.size() < 2 || !slot.get(0).equals(owner.get(0))) return false;
        int shared = Math.min(slot.size(), owner.size());
        for (int i = 1; i < shared; i++) {
            if (!owner.get(i).startsWith(slot.get(i))) return false;
        }
        for (int i = shared; i < slot.size(); i++) {
            String extra = slot.get(i);
            if (owner.stream().noneMatch(w -> w.startsWith(extra))) return false; // a longer, different name
        }
        return true;
    }

    /** Lower-case letter-only words, titles and single letters dropped. For a slot, the text is
     *  first cut at the first word carrying a digit and at a relation marker ("S O", "D/O"). */
    private static List<String> nameWords(String text, boolean cutAtMarkers) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String[] raw = text.trim().split("\\s+");
        for (int i = 0; i < raw.length; i++) {
            String token = raw[i];
            if (cutAtMarkers && token.chars().anyMatch(Character::isDigit)) break;
            String word = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (cutAtMarkers && isRelationMarker(raw, i)) break;
            if (word.length() < 2 || TITLES.contains(word)) continue;
            out.add(word);
        }
        return out;
    }

    private static boolean isRelationMarker(String[] raw, int i) {
        String word = raw[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (RELATION_WORDS.contains(word)) return true;
        if (i + 1 >= raw.length) return false;
        String next = raw[i + 1].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return word.length() == 1 && "sdwc".contains(word) && next.equals("o");
    }
}
