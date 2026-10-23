package com.finora.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finora.entity.Category;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The fact sheet behind every AI insight text (docs/superpowers/specs/2026-10-22-ai-insights-foundation-design.md
 * §4.1). Everything the model may see is one of the typed records below -- an allowlist, never an
 * entity or DTO serialised and pruned. What it carries: category names for built-in categories,
 * opaque tokens for categories the user created, and figures with the exact display form the app
 * shows. What it never carries: payee or people's names, UPI ids, account details, narrations, or the
 * raw "To look at" row keys (they hold category and payee names; the model sees row ids only).
 *
 * <p>The model never writes a figure or a name itself: it writes placeholders, and
 * {@code AiInsightRenderer} fills them from {@link Subject#displayName()} and {@link Fact#display()}.
 */
public final class AiInsightFacts {

    private AiInsightFacts() {}

    public enum Unit { RUPEES, COUNT, PERCENT, DAY }

    public enum Period { THIS_MONTH, USUAL }

    /** {@code id} is what a template uses ({@code {food}}); {@code modelName} is what the model is told
     *  the subject is called; {@code displayName} is what the user sees. They differ only for a
     *  category the user created, whose name never reaches the model. */
    public record Subject(String id, String displayName, String modelName) {}

    /** {@code id} is {@code subjectId + "." + metric}; the period is part of the metric's name
     *  ({@code this_month}, {@code usual}). {@code value} is signed (a fall is negative) so the checker
     *  can test direction words; {@code display} is unsigned. */
    public record Fact(String id, String subjectId, String metric, Period period, Unit unit, BigDecimal value,
                       String display) {}

    /** A "To look at" row as the model sees it: an id ({@code row_1}), its kind and the subject it is
     *  about. {@code key} (the acknowledgement key, which names categories and payees) stays here. */
    public record Row(String id, String key, String kind, String subjectId) {}

    public static final Subject MONTH = new Subject("month", "month", "month");

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** {@code usualMonths}: the months the usual figures are averaged over ({@code yyyy-MM}, oldest
     *  first), for the "Compared with your usual month (Jul–Sep)" line. Empty when nothing is compared. */
    public record FactSheet(String month, List<Subject> subjects, List<Fact> facts, List<Row> rows,
                            List<String> usualMonths) {

        public FactSheet {
            subjects = List.copyOf(subjects);
            facts = List.copyOf(facts);
            rows = List.copyOf(rows);
            usualMonths = List.copyOf(usualMonths);
        }

        public Fact fact(String id) {
            return facts.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
        }

        public Subject subject(String id) {
            return subjects.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        }

        /** SHA-256 hex of the sheet's facts, subjects (display names included, so a renamed category
         *  refreshes its text) and month -- not its rows: marking a row "Got it" must not regenerate
         *  the text (§5.4). Order-independent. */
        public String hash() {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("month", month);
            canonical.put("subjects", subjects.stream().sorted(Comparator.comparing(Subject::id))
                    .map(s -> Map.of("id", s.id(), "displayName", s.displayName(), "modelName", s.modelName()))
                    .toList());
            canonical.put("facts", facts.stream().sorted(Comparator.comparing(Fact::id))
                    .map(f -> Map.of("id", f.id(), "subjectId", f.subjectId(), "metric", f.metric(),
                            "period", f.period().name(), "unit", f.unit().name(),
                            "value", f.value().stripTrailingZeros().toPlainString()))
                    .toList());
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(CANONICAL.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                throw new IllegalStateException("Could not hash an AI insight fact sheet", e);
            }
        }

        /** Exactly what the model is sent: subjects by id and model name, facts with their display
         *  forms, and rows by id. Never a display name of a user category, a row key or a raw value. */
        public String modelJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("month", month);
            json.put("subjects", subjects.stream()
                    .map(s -> Map.of("id", s.id(), "name", s.modelName())).toList());
            json.put("facts", facts.stream().map(f -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.id());
                m.put("subject", f.subjectId());
                m.put("metric", f.metric());
                m.put("period", f.period().name());
                m.put("unit", f.unit().name());
                m.put("display", f.display());
                // The display is unsigned, so a change fact says its direction: without it the model
                // cannot tell a rise from a fall and guesses (checker rule 5).
                if (isChange(f)) m.put("direction", direction(f));
                return m;
            }).toList());
            if (!rows.isEmpty()) {
                json.put("rows", rows.stream()
                        .map(r -> Map.of("id", r.id(), "kind", r.kind(), "subject", r.subjectId())).toList());
            }
            try {
                return CANONICAL.writeValueAsString(json);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not write an AI insight fact sheet", e);
            }
        }

        /** The sheet as stored beside its text (ai_insight_texts.sheet), so any text can be traced to its facts. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month);
            m.put("subjects", subjects.stream().map(s -> Map.of(
                    "id", s.id(), "displayName", s.displayName(), "modelName", s.modelName())).toList());
            m.put("facts", facts.stream().map(f -> Map.of(
                    "id", f.id(), "subjectId", f.subjectId(), "metric", f.metric(), "period", f.period().name(),
                    "unit", f.unit().name(), "value", f.value().toPlainString(), "display", f.display())).toList());
            m.put("rows", rows.stream().map(r -> Map.of(
                    "id", r.id(), "key", r.key(), "kind", r.kind(), "subjectId", r.subjectId())).toList());
            m.put("usualMonths", usualMonths);
            return m;
        }

        @SuppressWarnings("unchecked")
        public static FactSheet fromMap(Map<String, Object> m) {
            List<Subject> subjects = ((List<Map<String, Object>>) m.getOrDefault("subjects", List.of())).stream()
                    .map(s -> new Subject((String) s.get("id"), (String) s.get("displayName"), (String) s.get("modelName")))
                    .toList();
            List<Fact> facts = ((List<Map<String, Object>>) m.getOrDefault("facts", List.of())).stream()
                    .map(f -> new Fact((String) f.get("id"), (String) f.get("subjectId"), (String) f.get("metric"),
                            Period.valueOf((String) f.get("period")), Unit.valueOf((String) f.get("unit")),
                            new BigDecimal((String) f.get("value")), (String) f.get("display")))
                    .toList();
            List<Row> rows = ((List<Map<String, Object>>) m.getOrDefault("rows", List.of())).stream()
                    .map(r -> new Row((String) r.get("id"), (String) r.get("key"), (String) r.get("kind"),
                            (String) r.get("subjectId")))
                    .toList();
            List<String> usualMonths = (List<String>) m.getOrDefault("usualMonths", List.of());
            return new FactSheet((String) m.get("month"), subjects, facts, rows, usualMonths);
        }
    }

    /** A change between this month and the usual month ({@code change_pct}, {@code money_out_change_pct}). */
    static boolean isChange(Fact f) {
        return f.unit() == Unit.PERCENT && f.metric().endsWith("change_pct");
    }

    /** UP, DOWN or SAME, as the figure is shown: a change that rounds to "0%" is SAME, so the model is
     *  never told a 0.3% change "rose" and writes "rose 0%". Rounded as {@link #displayFor} rounds. */
    static String direction(Fact f) {
        int sign = f.value().setScale(0, RoundingMode.HALF_UP).signum();
        return sign > 0 ? "UP" : sign < 0 ? "DOWN" : "SAME";
    }

    /** A fact with its display form rendered. {@code countNoun} names a COUNT ("payment"); null otherwise. */
    public static Fact fact(String subjectId, String metric, Period period, Unit unit, BigDecimal value, String countNoun) {
        return new Fact(subjectId + "." + metric, subjectId, metric, period, unit, value,
                displayFor(unit, period, value, countNoun));
    }

    /**
     * The form the app shows a figure in. Rupees: whole rupees with Indian grouping (₹1,23,456).
     * Percent: a whole number with %. Count: "6 payments" ("1 payment"). Day: "3rd". All unsigned.
     * A usual-month figure carries " in a usual month", so it can never be presented as this month's.
     */
    public static String displayFor(Unit unit, Period period, BigDecimal value, String countNoun) {
        BigDecimal whole = value.abs().setScale(0, RoundingMode.HALF_UP);
        String shown = switch (unit) {
            case RUPEES -> "₹" + indianGrouping(whole.toPlainString());
            case PERCENT -> whole.toPlainString() + "%";
            case COUNT -> countNoun == null ? whole.toPlainString()
                    : whole.toPlainString() + " " + countNoun + (whole.compareTo(BigDecimal.ONE) == 0 ? "" : "s");
            case DAY -> ordinal(whole.intValue());
        };
        return period == Period.USUAL ? shown + " in a usual month" : shown;
    }

    /** Tokens for the categories the user created, numbered by category id so the same categories
     *  always get the same tokens. Built-in categories get none. */
    public static Map<UUID, String> tokensFor(Collection<Category> categories) {
        List<Category> userCategories = new ArrayList<>(categories.stream().filter(c -> !c.isSystem()).toList());
        userCategories.sort(Comparator.comparing(Category::getId));
        Map<UUID, String> tokens = new LinkedHashMap<>();
        for (int i = 0; i < userCategories.size(); i++) {
            tokens.put(userCategories.get(i).getId(), "[category-" + (i + 1) + "]");
        }
        return tokens;
    }

    /** A built-in category is sent by name (a fixed taxonomy). A category the user created is sent as
     *  its token only: its name could be anything a person types, a friend's name included. */
    public static Subject categorySubject(Category c, Map<UUID, String> tokenById) {
        if (c.isSystem()) {
            return new Subject(slug(c.getName()), c.getName(), c.getName());
        }
        String token = tokenById.get(c.getId());
        if (token == null) {
            throw new IllegalArgumentException("No token for a user-created category -- build tokens with tokensFor");
        }
        String number = token.substring("[category-".length(), token.length() - 1);
        return new Subject("category_" + number, c.getName(), token);
    }

    static String slug(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "category" : s;
    }

    private static String indianGrouping(String digits) {
        if (digits.length() <= 3) return digits;
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder out = new StringBuilder();
        while (rest.length() > 2) {
            out.insert(0, "," + rest.substring(rest.length() - 2));
            rest = rest.substring(0, rest.length() - 2);
        }
        return rest + out + "," + last3;
    }

    private static String ordinal(int day) {
        int tens = day % 100;
        if (tens >= 11 && tens <= 13) return day + "th";
        return switch (day % 10) {
            case 1 -> day + "st";
            case 2 -> day + "nd";
            case 3 -> day + "rd";
            default -> day + "th";
        };
    }
}
