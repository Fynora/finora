package com.finora.service;

import com.finora.service.AiInsightFacts.Fact;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Subject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks every AI insight reply passes before anyone sees it
 * (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §5.2). All deterministic, all
 * here, all versioned together as {@link #POLICY_VERSION}: change a word list or a rule and bump it,
 * so stored texts written under the old policy are regenerated rather than served (§5.4).
 *
 * <p>Order of the checks (only the recorded reason depends on it -- every failing reply is rejected):
 * schema and placeholders, then literal numbers and names, then policy (advice, products, predictions),
 * then fact binding, direction and period, then the row order. A filter, not a compliance proof: the
 * offline evaluation (§7.2) measures what it wrongly passes and wrongly rejects.
 */
public final class AiInsightChecker {

    private AiInsightChecker() {}

    public static final String POLICY_VERSION = "policy-v1";

    static final Set<String> SENTENCE_TYPES = Set.of("OBSERVATION", "COMPARISON", "EXPLANATION");
    static final int MAX_SENTENCES = 4;

    /** Rule 3: a figure is only ever a placeholder -- no number words, either. */
    static final List<String> NUMBER_WORDS = List.of(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
            "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "hundreds", "thousand",
            "thousands", "lakh", "lakhs", "crore", "crores", "million", "billion", "dozen", "dozens",
            "once", "twice", "thrice", "double", "doubled", "doubles", "triple", "tripled", "half", "halved",
            "quarter", "percent", "rupee", "rupees", "rs", "inr", "first", "second", "third", "fourth", "fifth");
    /** Rule 3: dates are placeholders too. "may" is left out (it is mostly the verb); a capitalised
     *  "May" inside a sentence is caught separately. */
    static final List<String> DATE_WORDS = List.of(
            "january", "february", "march", "april", "june", "july", "august", "september", "october",
            "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov",
            "dec", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "yesterday",
            "today", "tomorrow");
    private static final Pattern LITERAL_SYMBOLS = Pattern.compile("[0-9₹$€£%]");

    /** Rule 7: advice or instruction phrasing. */
    static final List<String> ADVICE_PHRASES = List.of(
            "should", "shouldn't", "recommend", "recommended", "consider", "considering", "try", "try to", "avoid",
            "cut", "reduce", "save", "set a", "need to", "needs to", "make sure", "it's a good idea",
            "it is a good idea", "good idea", "advise", "advice", "might not want", "want to", "you could",
            "think about", "better to", "ought to", "must");
    /** Rule 7: a sentence that opens with a bare verb is an instruction. */
    static final List<String> BARE_VERB_OPENERS = List.of(
            "cut", "try", "set", "reduce", "avoid", "save", "spend", "consider", "keep", "stop", "start", "make",
            "plan", "move", "switch", "invest", "check", "review", "limit", "watch", "cancel", "pay", "use", "think",
            "look", "aim", "track", "budget", "shift", "put", "add", "skip", "buy", "sell", "trim", "lower", "raise");
    /** Rule 8: investment, insurance and loan products (spec §2, the SEBI line). ELSS, 80C and tax saving
     *  per SEBI's investment adviser FAQ Q16. Category names are never written by the model (rule 3), so
     *  the Insurance and Loan EMI categories are safe. */
    static final List<String> PRODUCT_WORDS = List.of(
            "invest", "invests", "invested", "investing", "investment", "investments", "mutual fund", "mutual funds",
            "fund", "funds", "sip", "sips", "stock", "stocks", "equity", "equities", "share price", "share prices",
            "portfolio", "nav", "returns", "insurance", "policy", "policies", "loan", "loans", "credit card offer",
            "fd", "fds", "fixed deposit", "fixed deposits", "gold", "crypto", "bitcoin", "elss", "80c", "tax saving",
            "tax-saving", "bond", "bonds", "ppf", "nps", "demat");
    /** Rule 9: statements about the future belong to project 3 (forecasts). */
    static final List<String> PREDICTION_PHRASES = List.of(
            "will", "won't", "going to", "next month", "next year", "next week", "expect", "expected", "expecting",
            "likely", "unlikely", "forecast", "predict", "predicted", "soon", "upcoming", "by the end of");
    /** Rule 5. */
    static final List<String> UP_WORDS = List.of(
            "up", "more", "higher", "rose", "rise", "risen", "rising", "increase", "increased", "grew", "grown",
            "jumped", "climbed", "above");
    static final List<String> DOWN_WORDS = List.of(
            "down", "less", "lower", "fell", "fall", "fallen", "falling", "decrease", "decreased", "dropped", "drop",
            "below", "fewer", "shrank");
    /** Rule 6. */
    static final List<String> USUAL_WORDS = List.of("usual", "usually", "normally", "typically", "average", "ordinarily");
    static final List<String> THIS_MONTH_PHRASES = List.of("this month", "so far");
    private static final int PERIOD_WINDOW_WORDS = 3;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern PLACEHOLDER_ID = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)?");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z'\\-]*|[0-9]+[a-z]*");

    /** One piece of a template: a word of prose, or a placeholder. */
    private record Token(String word, String placeholder) {}

    public static AiInsightReason check(AiInsightTemplate t, FactSheet sheet, boolean expectOrder) {
        // 1. Schema.
        if (t.sentences().isEmpty() || t.sentences().size() > MAX_SENTENCES) return AiInsightReason.SCHEMA_MISMATCH;
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            if (s.type() == null || !SENTENCE_TYPES.contains(s.type()) || s.template() == null || s.template().isBlank()) {
                return AiInsightReason.SCHEMA_MISMATCH;
            }
            if (s.template().contains("*") || s.template().contains("#") || s.template().contains("`")) {
                return AiInsightReason.SCHEMA_MISMATCH;
            }
        }
        // 2. Every placeholder exists in this sheet; nothing brace-like is left over.
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            Matcher m = PLACEHOLDER.matcher(s.template());
            while (m.find()) {
                String id = m.group(1);
                if (!PLACEHOLDER_ID.matcher(id).matches()) return AiInsightReason.UNKNOWN_FACT;
                if (id.contains(".") ? sheet.fact(id) == null : sheet.subject(id) == null) return AiInsightReason.UNKNOWN_FACT;
            }
            String prose = PLACEHOLDER.matcher(s.template()).replaceAll(" ");
            if (prose.contains("{") || prose.contains("}") || prose.contains("[category-")) return AiInsightReason.UNKNOWN_FACT;
        }
        // 3. No literal figure or date: every one comes from a placeholder.
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            String prose = PLACEHOLDER.matcher(s.template()).replaceAll(" ");
            if (LITERAL_SYMBOLS.matcher(prose).find()) return AiInsightReason.LITERAL_NUMBER;
            List<String> words = words(prose);
            if (containsAny(words, NUMBER_WORDS) || containsAny(words, DATE_WORDS)) return AiInsightReason.LITERAL_NUMBER;
            if (Pattern.compile("(?<!^)(?<![.!?]\\s)\\bMay\\b").matcher(prose.strip()).find()) {
                return AiInsightReason.LITERAL_NUMBER;
            }
        }
        // 7-9. Policy.
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            List<Token> tokens = tokens(s.template());
            List<String> words = words(PLACEHOLDER.matcher(s.template()).replaceAll(" "));
            if (containsAny(words, ADVICE_PHRASES)) return AiInsightReason.ADVICE;
            if (!tokens.isEmpty() && tokens.get(0).word() != null && BARE_VERB_OPENERS.contains(tokens.get(0).word())) {
                return AiInsightReason.ADVICE;
            }
            if (containsAny(words, PRODUCT_WORDS)) return AiInsightReason.PRODUCT_WORD;
            if (containsAny(words, PREDICTION_PHRASES)) return AiInsightReason.PREDICTION;
        }
        // 4. Fact binding.
        long categorySubjects = sheet.subjects().stream().filter(sub -> !sub.id().equals(AiInsightFacts.MONTH.id())).count();
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            String lastSubject = null;
            for (Token tok : tokens(s.template())) {
                if (tok.placeholder() == null) continue;
                if (!tok.placeholder().contains(".")) {
                    lastSubject = tok.placeholder();
                    continue;
                }
                Fact f = sheet.fact(tok.placeholder());
                if (f.subjectId().equals(AiInsightFacts.MONTH.id())) continue;
                if (lastSubject == null ? categorySubjects != 1 : !lastSubject.equals(f.subjectId())) {
                    return AiInsightReason.FACT_BINDING;
                }
            }
        }
        // 5. Direction words agree with the change of every subject the sentence is about.
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            List<String> words = words(PLACEHOLDER.matcher(s.template()).replaceAll(" "));
            boolean up = containsAny(words, UP_WORDS);
            boolean down = containsAny(words, DOWN_WORDS);
            if (!up && !down) continue;
            Set<Integer> signs = new HashSet<>();
            for (String subjectId : subjectsReferenced(s.template(), sheet)) {
                Fact change = sheet.fact(subjectId + "." + (subjectId.equals(AiInsightFacts.MONTH.id())
                        ? "money_out_change_pct" : "change_pct"));
                if (change != null && change.value().signum() != 0) signs.add(change.value().signum());
            }
            if (signs.isEmpty()) continue;
            if (up && !down && signs.contains(-1)) return AiInsightReason.DIRECTION_MISMATCH;
            if (down && !up && signs.contains(1)) return AiInsightReason.DIRECTION_MISMATCH;
            if (up && down && signs.size() < 2) return AiInsightReason.DIRECTION_MISMATCH;
        }
        // 6. A usual figure is never presented as this month's, nor the other way round.
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            List<Token> tokens = tokens(s.template());
            for (int i = 0; i < tokens.size(); i++) {
                String id = tokens.get(i).placeholder();
                if (id == null || !id.contains(".")) continue;
                String before = String.join(" ", wordsBefore(tokens, i));
                Period period = sheet.fact(id).period();
                if (period == Period.THIS_MONTH && containsAny(words(before), USUAL_WORDS)) {
                    return AiInsightReason.PERIOD_MISMATCH;
                }
                if (period == Period.USUAL && containsAny(words(before), THIS_MONTH_PHRASES)) {
                    return AiInsightReason.PERIOD_MISMATCH;
                }
            }
        }
        // 10. The order holds only rows the server sent, each once (rows left out are appended later).
        if (expectOrder) {
            Set<String> sent = new HashSet<>();
            sheet.rows().forEach(r -> sent.add(r.id()));
            Set<String> seen = new HashSet<>();
            for (String id : t.order()) {
                if (!sent.contains(id) || !seen.add(id)) return AiInsightReason.BAD_ORDER;
            }
        }
        return AiInsightReason.OK;
    }

    private static List<Token> tokens(String template) {
        List<Token> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        int at = 0;
        while (m.find()) {
            for (String w : words(template.substring(at, m.start()))) out.add(new Token(w, null));
            out.add(new Token(null, m.group(1)));
            at = m.end();
        }
        for (String w : words(template.substring(at))) out.add(new Token(w, null));
        return out;
    }

    private static List<String> wordsBefore(List<Token> tokens, int index) {
        List<String> out = new ArrayList<>();
        for (int i = index - 1; i >= 0 && out.size() < PERIOD_WINDOW_WORDS; i--) {
            if (tokens.get(i).word() != null) out.add(0, tokens.get(i).word());
        }
        return out;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('’', '\''));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** True when any entry (a word or a phrase of several words) appears as whole words. */
    private static boolean containsAny(List<String> words, List<String> entries) {
        String padded = " " + String.join(" ", words) + " ";
        for (String entry : entries) {
            if (padded.contains(" " + entry + " ")) return true;
        }
        return false;
    }

    private static Set<String> subjectsReferenced(String template, FactSheet sheet) {
        Set<String> subjects = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            String id = m.group(1);
            if (id.contains(".")) {
                Fact f = sheet.fact(id);
                if (f != null) subjects.add(f.subjectId());
            } else {
                Subject s = sheet.subject(id);
                if (s != null) subjects.add(s.id());
            }
        }
        return subjects;
    }
}
