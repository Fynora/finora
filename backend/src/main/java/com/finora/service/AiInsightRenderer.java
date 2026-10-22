package com.finora.service;

import com.finora.service.AiInsightFacts.Fact;
import com.finora.service.AiInsightFacts.FactSheet;
import com.finora.service.AiInsightFacts.Period;
import com.finora.service.AiInsightFacts.Subject;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a checked template into the text the user reads, with the server's own names and figures
 * (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md §5.1); writes the rules text for
 * "Why?" (§5.3) and the basis line under every text (§8.1).
 */
public final class AiInsightRenderer {

    private AiInsightRenderer() {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);

    /** Only call with a template {@link AiInsightChecker} passed: every placeholder exists in {@code sheet}. */
    public static String render(AiInsightTemplate t, FactSheet sheet) {
        StringBuilder text = new StringBuilder();
        for (AiInsightTemplate.Sentence s : t.sentences()) {
            Matcher m = PLACEHOLDER.matcher(s.template());
            StringBuilder sentence = new StringBuilder();
            while (m.find()) {
                String id = m.group(1);
                String value;
                if (id.contains(".")) {
                    value = sheet.fact(id).display();
                } else {
                    Subject subject = sheet.subject(id);
                    value = subject.displayName();
                }
                m.appendReplacement(sentence, Matcher.quoteReplacement(value));
            }
            m.appendTail(sentence);
            if (!text.isEmpty()) text.append(' ');
            text.append(sentence.toString().strip());
        }
        return text.toString().replaceAll("\\s{2,}", " ");
    }

    /** "14 payments this month against 6 payments in a usual month; the largest was ₹2,400." */
    public static String whyRulesText(FactSheet rowSheet) {
        Fact thisMonth = suffix(rowSheet, ".payments_this_month");
        Fact usual = suffix(rowSheet, ".payments_usual");
        Fact largest = suffix(rowSheet, ".largest_1");
        StringBuilder text = new StringBuilder();
        text.append(thisMonth.display()).append(" this month against ").append(usual.display());
        if (largest != null) text.append("; the largest was ").append(largest.display());
        return text.append('.').toString();
    }

    /** "Compared with your usual month (Jul–Sep)", or "This month only" when nothing is compared. */
    public static String basisFor(FactSheet sheet) {
        boolean compares = sheet.facts().stream().anyMatch(f -> f.period() == Period.USUAL);
        if (!compares || sheet.usualMonths().isEmpty()) return "This month only";
        String first = YearMonth.parse(sheet.usualMonths().get(0)).format(SHORT_MONTH);
        String last = YearMonth.parse(sheet.usualMonths().get(sheet.usualMonths().size() - 1)).format(SHORT_MONTH);
        return "Compared with your usual month (" + (first.equals(last) ? first : first + "–" + last) + ")";
    }

    private static Fact suffix(FactSheet sheet, String suffix) {
        return sheet.facts().stream().filter(f -> f.id().endsWith(suffix)).findFirst().orElse(null);
    }
}
