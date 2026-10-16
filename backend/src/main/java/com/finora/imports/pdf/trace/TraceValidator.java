package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PositionedText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a trace is safe to commit and still worth committing.
 *
 * Those are two separate questions and both were previously answered by "the person capturing it
 * should read the file first". That is not a check, it is a hope: the redactor's own doc comment
 * already said the capturer is the last reviewer standing between a customer's statement and the
 * repository, and a customer's name and account number reached the repository anyway.
 *
 * <h2>Four checks</h2>
 *
 * <ol>
 *   <li><b>No unmasked PII.</b> The same patterns {@code scripts/check-fixture-hygiene.sh} blocks
 *       commits on, applied to the trace's own text and its metadata comment lines, plus any
 *       letter, number or mark outside ASCII (every rule
 *       that keeps text unmasked keeps ASCII only). A trace that trips this must never be
 *       committed, regardless of how useful its structure is.</li>
 *   <li><b>Required structural evidence preserved.</b> Declared per trace in its own metadata. This
 *       is the check that would have caught the incident that motivated all of this: the deposit
 *       traces were captured with "Maturity Date" masked to "Xxxxxxxx Date", and every test kept
 *       passing because nothing asserted the evidence was still there.</li>
 *   <li><b>The trace still parses into a table.</b> A trace that yields no sections is not evidence
 *       of a layout, it is a file.</li>
 *   <li><b>Capability evidence.</b> The capabilities the trace claims to protect actually activate
 *       when it is run through the locator -- so a trace cannot claim to guard a capability it
 *       does not exercise.</li>
 * </ol>
 *
 * A trace is evidence, not test data: it exists to preserve the real-world document structure that
 * motivated a capability. Checks 2 and 4 are what keep that true over time.
 */
public final class TraceValidator {

    private TraceValidator() {}

    // Deliberately the same shapes check-fixture-hygiene.sh blocks on, so the two cannot drift into
    // disagreeing about what counts as customer data.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern INDIAN_MOBILE = Pattern.compile("(\\+?91[-. ]?)?[6-9][0-9]{9}\\b");
    private static final Pattern IFSC = Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(.)\\1{3,}|example|sample|test|dummy|fake|placeholder|redacted|noreply|localhost");

    public record Finding(Severity severity, String check, String detail) {
        public enum Severity { BLOCKER, REVIEW }

        @Override
        public String toString() { return severity + " [" + check + "] " + detail; }
    }

    public record Result(String traceName, TraceMetadata metadata, List<Finding> findings,
                         int sections, int tables, int rows, List<String> preservedHeaders,
                         List<String> missingHeaders, List<String> activatedCapabilities) {

        /** True when nothing blocks this trace from being committed. */
        public boolean isCommittable() {
            return findings.stream().noneMatch(f -> f.severity() == Finding.Severity.BLOCKER);
        }

        public List<Finding> blockers() {
            return findings.stream().filter(f -> f.severity() == Finding.Severity.BLOCKER).toList();
        }
    }

    /** Validates a trace's contents without needing it committed first -- the capture path calls
     *  this before writing, so a failing trace is never written to disk in the first place. */
    public static Result validate(String traceName, String content) {
        TraceMetadata metadata = TraceMetadata.parse(content);
        List<PositionedText> runs = TraceFormat.parse(content);
        List<Finding> findings = new ArrayList<>();

        findings.addAll(scanForPii(runs));
        findings.addAll(scanTexts(commentLines(content)));

        String allText = runs.stream().map(PositionedText::text)
                .reduce(new StringBuilder(), (sb, t) -> sb.append(t).append('\n'), StringBuilder::append)
                .toString();

        List<String> preserved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String required : metadata.requiredHeaders()) {
            if (allText.toLowerCase().contains(required.toLowerCase())) {
                preserved.add(required);
            } else {
                missing.add(required);
                findings.add(new Finding(Finding.Severity.BLOCKER, "structural-evidence",
                        "required header \"" + required + "\" did not survive redaction -- this trace "
                                + "no longer contains the evidence it exists to preserve"));
            }
        }

        var ctx = new com.finora.imports.DocumentContext("PDF", "TraceValidator");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);
        List<String> activated = ctx.capabilities().stream()
                .map(com.finora.dto.ImportDto.CapabilityActivation::capability).distinct().sorted().toList();

        int rowCount = doc.sections().stream().mapToInt(s -> s.rows().size()).sum();
        if (doc.sections().isEmpty()) {
            findings.add(new Finding(Finding.Severity.BLOCKER, "parses",
                    "the trace yields no table sections at all -- it is not evidence of a layout"));
        }

        for (String claimed : metadata.capabilities()) {
            if (!activated.contains(claimed)) {
                findings.add(new Finding(Finding.Severity.REVIEW, "capability-evidence",
                        "claims to protect " + claimed + ", which did not activate on it"));
            }
        }

        if (metadata.hasNoProvenance()) {
            findings.add(new Finding(Finding.Severity.REVIEW, "provenance",
                    "no metadata block -- redaction provenance cannot be established, so this trace "
                            + "cannot be shown to still contain its evidence"));
        }

        if (TraceMetadata.hasNoWidths(runs)) {
            // Separate from provenance on purpose: a trace can have impeccable provenance and still
            // be unable to exercise width-dependent bucketing, and reporting both as "legacy" hides
            // which of the two problems a recapture would fix.
            //
            // Read off the ROWS, not the version stamp. The two are written independently -- a
            // capture stamps v3 unconditionally -- so a v3 trace can carry no widths at all, and
            // telling that reader to "recapture at v3" would be advice they have already followed.
            // The two states need different remedies, so they get different messages.
            String remedy = metadata.traceVersion() < TraceMetadata.FIRST_VERSION_WITH_WIDTHS
                    ? "The format itself has no width column at trace v" + metadata.traceVersion()
                            + ". Recapture at trace v" + TraceMetadata.CURRENT_TRACE_VERSION + " to fix."
                    : "The format carries a width column and every value in it is 0.00, so the widths "
                            + "were dropped during capture rather than absent by format. A recapture "
                            + "under redactor v5 or later keeps them (a masked run's rounded to a "
                            + "whole point).";
            findings.add(new Finding(Finding.Severity.REVIEW, "widths",
                    "rows carry no width, so every run has endX == x. Any capability guarded on a "
                            + "measured width -- RIGHT_ALIGNED_AMOUNTS, and the column bucketing it "
                            + "corrects -- cannot activate on this trace however good its content "
                            + "is. " + remedy));
        }

        return new Result(traceName, metadata, findings, doc.sections().size(), doc.sections().size(),
                rowCount, preserved, missing, activated);
    }

    /**
     * Which kinds of customer data survived redaction, by name only. The server's refusal check:
     * unlike {@link #validate}, a layout with no table is not a reason to refuse, and the matched
     * text is never returned, so a refusal can be shown to staff without showing the data. It also
     * refuses any unmasked text outside ASCII, as {@link #validate} blocks it: see
     * {@link #hasUnmaskedTextInAnotherScript}.
     */
    public static List<String> privacyKinds(List<PositionedText> runs) {
        LinkedHashSet<String> kinds = new LinkedHashSet<>();
        for (PositionedText run : runs) {
            String text = run.text();
            if (text == null || text.isBlank()) continue;
            if (matchesUnplaceholdered(EMAIL, text)) kinds.add("email address");
            if (matchesUnplaceholdered(INDIAN_MOBILE, text)) kinds.add("phone number");
            if (hasUnmaskedIfsc(text)) kinds.add("IFSC branch code");
            if (hasUnmaskedTextInAnotherScript(text)) kinds.add("unmasked text in another script");
        }
        if (hasNumberSplitIntoGroups(runs)) kinds.add(NUMBER_SPLIT_INTO_GROUPS);
        return List.copyOf(kinds);
    }

    static final String NUMBER_SPLIT_INTO_GROUPS = "number split into groups";

    static final String UNMASKED_NUMBER = "unmasked number";
    static final String COPY_DOES_NOT_LINE_UP = "copy does not line up with the statement";
    private static final Pattern IFSC_IN_TEXT = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{4})0[A-Z0-9]{6}(?![A-Za-z0-9])");

    /**
     * The exact backstop: compares a copy with the statement it came from, digit by digit, using
     * only the statement's own text (read by {@link NumberGroups}, the redactor's token rules play
     * no part). Refused, by kind name only:
     * <ul>
     *   <li>"number split into groups": a digit of a long number in the statement (six or more
     *       digits joined by short separators, across runs and across a wrapped line) still shown
     *       in the copy at the same place;</li>
     *   <li>"unmasked number": any other digit shown in the copy that is not part of a value the
     *       policy keeps (a date, an HH:MM time, an amount) -- the redactor keeps no plain integer,
     *       so this includes digits glued to a slash, an at sign, a colon or an underscore;</li>
     *   <li>"copy does not line up with the statement": a different number of runs or of code
     *       points in a run, which a redactor that only substitutes characters never produces.</li>
     * </ul>
     * Every number the copy shows (any Unicode number category) is judged, so digits moved to
     * another place are caught too. A '9' is never counted (it reads the same masked or not), and
     * neither is the fixed fifth character '0' of an IFSC whose copy is the IFSC rule's own output:
     * it is the format, not data.
     */
    public static List<String> copyLeaks(List<PositionedText> original, List<PositionedText> copy) {
        if (original.size() != copy.size()) return List.of(COPY_DOES_NOT_LINE_UP);
        NumberGroups.Kind[][] kinds = NumberGroups.scanByLine(original);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (int r = 0; r < original.size(); r++) {
            String o = original.get(r).text();
            String c = copy.get(r).text();
            if (o == null || c == null) {
                // Both missing lines up; only one missing does not.
                if (o != null || c != null) found.add(COPY_DOES_NOT_LINE_UP);
                continue;
            }
            int[] copyCps = c.codePoints().toArray();
            if (copyCps.length != o.codePointCount(0, o.length())) {
                found.add(COPY_DOES_NOT_LINE_UP);
                continue;
            }
            boolean[] ifscZero = new boolean[o.length()];
            var ifsc = IFSC_IN_TEXT.matcher(o);
            while (ifsc.find()) {
                int zero = ifsc.start() + 4;
                int cpStart = o.codePointCount(0, ifsc.start());
                String copied = new String(copyCps, cpStart, Math.min(11, copyCps.length - cpStart));
                if (copied.equals(ifsc.group(1) + "0XXXXXX")) ifscZero[zero] = true;
            }
            // Every number the COPY shows is judged, wherever it came from: it passes only where the
            // statement has the same code point at the same place and it is part of a value there.
            int cpIndex = 0;
            for (int i = 0; i < o.length(); ) {
                int cp = o.codePointAt(i);
                int shown = copyCps[cpIndex];
                if (PdfTraceRedactor.isNumber(Character.getType(shown)) && shown != '9' && !ifscZero[i]) {
                    NumberGroups.Kind kind = kinds[r][i];
                    boolean sameValueDigit = shown == cp && kind == NumberGroups.Kind.UNIT;
                    if (!sameValueDigit) {
                        found.add(shown == cp && kind == NumberGroups.Kind.LONG ? NUMBER_SPLIT_INTO_GROUPS : UNMASKED_NUMBER);
                    }
                }
                i += Character.charCount(cp);
                cpIndex++;
            }
        }
        return List.copyOf(found);
    }

    /**
     * The fail-closed backstop behind the redactor's number rules. Printed lines are rebuilt from
     * the runs and read with the redactor's own model ({@link NumberGroups}), treating a group made
     * only of '9' as the redactor's mask. Refused: a number of six or more digits in clear made of
     * groups joined by short separators (a mobile, card, account or Aadhaar number printed in
     * pieces, in one run, across runs or wrapped onto the next line), and a short group in clear glued to a slash, an at sign, a
     * colon, an underscore or a format character (an identifier's digits, as in a UPI id wrapped
     * onto the next line). The redactor never keeps either, so a refusal means it regressed. One
     * kind covers all three, including a long number printed in one piece. Not part of
     * {@link #validate}: committed traces were captured by earlier redactors.
     */
    static boolean hasNumberSplitIntoGroups(List<PositionedText> runs) {
        List<NumberGroups.Line> lines = NumberGroups.lines(runs);
        List<NumberGroups.Kind[]> scanned = NumberGroups.scanLines(lines, true);
        for (int l = 0; l < lines.size(); l++) {
            String text = lines.get(l).text();
            NumberGroups.Kind[] kinds = scanned.get(l);
            for (int p = 0; p < kinds.length; p++) {
                if (kinds[p] == NumberGroups.Kind.LONG) return true;
                if (kinds[p] == NumberGroups.Kind.SHORT
                        && ((p > 0 && isIdentifierGlue(text.charAt(p - 1)))
                        || (p + 1 < text.length() && isIdentifierGlue(text.charAt(p + 1))))) return true;
            }
        }
        return false;
    }

    private static boolean isIdentifierGlue(char c) {
        return c == '/' || c == '@' || c == ':' || c == '_' || Character.getType(c) == Character.FORMAT;
    }

    /**
     * The fail-closed backstop behind the redactor. Every rule that keeps text unmasked keeps plain
     * ASCII only (the vocabulary is built from ASCII words, and the date, amount, IFSC and currency
     * rules match ASCII), so a non-ASCII letter, number or mark still in a redacted run was not
     * masked, whatever it says. The redactor masks these too; this catches the next way one gets
     * past it.
     */
    private static boolean hasUnmaskedTextInAnotherScript(String text) {
        return text.codePoints().anyMatch(TraceValidator::isUnmaskedTextInAnotherScript);
    }

    private static boolean isUnmaskedTextInAnotherScript(int cp) {
        return cp > 127 && PdfTraceRedactor.isTextCodePoint(cp);
    }

    /**
     * PII scan over the trace's own text.
     *
     * Placeholder-shaped values are allowed by the same {@code is_placeholder} rule the commit hook
     * uses -- a redacted email is still email-shaped, and blocking those would make every correctly
     * redacted trace unpublishable.
     */
    private static List<Finding> scanForPii(List<PositionedText> runs) {
        return scanTexts(runs.stream().map(PositionedText::text).toList());
    }

    private static List<Finding> scanTexts(List<String> texts) {
        List<Finding> findings = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            checkPattern(findings, EMAIL, text, "email address");
            checkPattern(findings, INDIAN_MOBILE, text, "phone number");
            checkIfsc(findings, text);
            checkOtherScripts(findings, text);
        }
        return findings;
    }

    /**
     * The file's comment lines: the format header and the metadata block. They are written by
     * people (where a trace came from, why it was captured) and committed with the runs, so the
     * same privacy checks run over them. The run parser skips them, so without this a phone number
     * in a motivation line was never seen.
     */
    private static List<String> commentLines(String content) {
        return content.lines().filter(line -> line.startsWith("#")).toList();
    }

    /** The same backstop {@link #privacyKinds} applies, on a trace about to be (or already)
     *  committed: up to redactor version 3 a non-ASCII word was kept verbatim. Unlike the other
     *  findings this one never repeats what it found -- a count is enough to act on, and the text
     *  could be a customer's name in a script the reader cannot check by eye. */
    private static void checkOtherScripts(List<Finding> findings, String text) {
        long unmasked = text.codePoints().filter(TraceValidator::isUnmaskedTextInAnotherScript).count();
        if (unmasked > 0) {
            findings.add(new Finding(Finding.Severity.BLOCKER, "pii",
                    "unmasked text in another script survived redaction: " + unmasked
                            + " letter, number or mark code point(s), not shown"));
        }
    }

    private static void checkPattern(List<Finding> findings, Pattern pattern, String text, String what) {
        for (String hit : unplaceholderedHits(pattern, text)) {
            findings.add(new Finding(Finding.Severity.BLOCKER, "pii",
                    "unmasked " + what + " survived redaction: " + hit));
        }
    }

    /** An IFSC's 4-letter bank prefix is deliberately preserved (it is what lets a trace test bank
     *  detection); only a real-looking BRANCH code is a finding. */
    private static void checkIfsc(List<Finding> findings, String text) {
        for (String ifsc : unmaskedIfscs(text)) {
            findings.add(new Finding(Finding.Severity.BLOCKER, "pii",
                    "unmasked IFSC branch code survived redaction: " + ifsc));
        }
    }

    /** Every match of {@code pattern} in {@code text} that neither the placeholder rule nor the
     *  already-masked rule exempts. The one place that decides what counts as a surviving email
     *  address or phone number, shared by {@link #validate} and {@link #privacyKinds} so the two
     *  cannot disagree. */
    private static List<String> unplaceholderedHits(Pattern pattern, String text) {
        List<String> hits = new ArrayList<>();
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            String hit = matcher.group();
            if (PLACEHOLDER.matcher(hit).find()) continue;
            if (isAlreadyMasked(hit)) continue;
            hits.add(hit);
        }
        return hits;
    }

    /**
     * Whether every letter and digit in a hit is the redactor's own mask character. A short email
     * (three letters before the at sign, three in the domain) masks to runs of x too short for the
     * placeholder rule's run of four: still email-shaped, so it was refused although nothing in it
     * is real. One unmasked letter or digit, such as a kept
     * vocabulary word as the local part, and it is still a hit.
     */
    private static boolean isAlreadyMasked(String hit) {
        return hit.chars().filter(Character::isLetterOrDigit).allMatch(c -> c == 'X' || c == 'x' || c == '9');
    }

    private static boolean matchesUnplaceholdered(Pattern pattern, String text) {
        return !unplaceholderedHits(pattern, text).isEmpty();
    }

    /** Every IFSC in {@code text} whose 6-character branch part is not a masked run of one
     *  character. Shared by {@link #validate} and {@link #privacyKinds}. */
    private static List<String> unmaskedIfscs(String text) {
        List<String> found = new ArrayList<>();
        var matcher = IFSC.matcher(text);
        while (matcher.find()) {
            String branch = matcher.group().substring(5, 11);
            if (branch.matches("^(.)\\1{5}$")) continue;
            found.add(matcher.group());
        }
        return found;
    }

    private static boolean hasUnmaskedIfsc(String text) {
        return !unmaskedIfscs(text).isEmpty();
    }
}
