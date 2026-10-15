package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import com.finora.util.BankRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips a captured trace (see {@link TraceFormat}) of personal data while leaving the document's structure
 * exactly as it was.
 *
 * The rule is an ALLOWLIST, not a denylist: a token is kept only if it is recognisably part of a
 * statement's furniture -- a column header, a metadata label, a date, an amount, a bank's name.
 * Everything else is replaced character-for-character. That direction is the whole point. A
 * denylist of "things that look like PII" fails open on the case nobody anticipated, and the
 * failure mode is a customer's name in a public repository. This fails closed: an unrecognised
 * token is redacted even when it was harmless, and the cost of that is a slightly less readable
 * fixture rather than a privacy incident.
 *
 * Redaction preserves LENGTH and CHARACTER CLASS (letter -&gt; X, digit -&gt; 9, punctuation kept),
 * because length is structure here: it is what makes a narration wrap onto a second line, what
 * decides where a run's x-extent ends, and therefore what reproduces the bug. Length is counted in
 * code points, in every script: a letter outside the Basic Multilingual Plane (two UTF-16 units)
 * masks to one X, and a mark masks to its own x.
 *
 * Dates and amounts are deliberately preserved. Once names, account numbers, addresses, emails and
 * narration text are gone, a bare "24,462.00" or "10/07/2026" identifies nobody -- and keeping them
 * is what allows a trace to regression-test date parsing, running balances and (once it exists)
 * reconciliation against the statement's own arithmetic. Redacting them would leave a fixture that
 * can only ever test header detection. From version 5 the same holds for the value shapes reading
 * takes amounts and directions from: an amount with a rupee prefix or a glued Cr/Dr, a
 * comma-grouped amount, a glued or apostrophe-year date, and a lone C or D as a whole run. A plain
 * integer is masked, as it always was.
 */
public final class PdfTraceRedactor {

    private PdfTraceRedactor() {}

    /**
     * Bumped by hand when the redaction ALGORITHM changes -- a new pattern, a changed masking rule,
     * a different tokenisation. Not for allowlist edits, which {@link #allowlistFingerprint()}
     * tracks automatically.
     *
     * Recorded into every captured trace so a trace states which redactor produced it. Without it,
     * "was this trace captured before or after we fixed X" is answerable only by reading commit
     * dates.
     */
    // Version 4: letters, numbers and marks in every script are masked, one character per code
    // point. Up to version 3 a token made only of non-ASCII letters or digits (a Devanagari or
    // Tamil word, full-width or accented Latin, Devanagari digits) was kept verbatim, and combining
    // marks were copied out as if they were punctuation.
    // Version 5: an IFSC keeps the punctuation at its edges, so its masked token is as long as the
    // real one. An invisible format character inside a token is masked to x. Kept now: amounts with
    // a currency prefix or a glued Cr/Dr, glued day-month dates, apostrophe-year dates,
    // comma-grouped amounts and lone C/D markers. (The
    // reader's own label words were added to the allowlist at the same time; the allowlist
    // fingerprint tracks that on its own.) A changed run keeps its width rounded to a whole point
    // instead of losing it. Digits are read per printed line (NumberGroups): groups joined by up to
    // three non-word characters of any kind are one number, and six or more digits together are
    // masked, the values in such a number included; a number token is kept only when every digit
    // is part of a value (a date, an HH:MM time, an amount), so no plain integer is kept (keeping
    // integers of up to five digits was tried within this version and measured to win no
    // matches); comma-grouped amounts keep at most eight integer digits; a numeric date must have
    // real day/month parts. Every format
    // character is masked, a lone C/D is kept only as a whole run, and a changed run's x is rounded
    // like its width. TraceValidator.privacyKinds refuses what these rules never keep, and
    // TraceValidator.copyLeaks compares the copy with the statement digit by digit.
    // Version 6: a printed line whose whole text is a listed bank sentence (BankSentences) is kept
    // as printed. A line holding a sentence and anything else is masked as before.
    public static final int REDACTOR_VERSION = 6;

    /**
     * A short hash of the effective allowlist, recomputed from the live vocabulary every call.
     *
     * This is the mechanism that makes stale-trace detection automatic instead of a process someone
     * has to remember. A trace records the fingerprint it was captured under; when the allowlist
     * changes, every committed trace's recorded fingerprint stops matching the current one, and
     * {@code TraceCorpusHealthTest} names exactly which traces are affected.
     *
     * The motivating incident: the allowlist had no deposit vocabulary, so three captured traces
     * had "Maturity Date" masked to "Xxxxxxxx Date" -- the precise headers product classification
     * reads, removed from the fixtures meant to regression-test reading them. The allowlist was
     * then fixed, and nothing anywhere connected that fix to the traces it invalidated. A committed
     * trace cannot be un-redacted, so the damage is permanent and was only found by dumping a
     * fixture by hand and noticing the words were wrong.
     *
     * Derived from the vocabulary rather than hand-maintained precisely so it cannot be forgotten:
     * editing STRUCTURAL_WORDS changes it whether or not anyone thought about the traces. From
     * version 6 the bank sentences ({@link BankSentences}) are part of it, so a list change changes
     * it too.
     */
    public static String allowlistFingerprint() {
        return allowlistFingerprint(BankSentences.listed());
    }

    /** {@link #allowlistFingerprint()} with the given bank sentences in place of the production list. */
    static String allowlistFingerprint(Set<String> sentences) {
        StringBuilder canonical = new StringBuilder();
        // Sorted so the fingerprint depends on the allowlist's CONTENT, not on the order words
        // happen to be declared in -- reordering the list is not a reason to invalidate the corpus.
        new java.util.TreeSet<>(vocabulary()).forEach(word -> canonical.append(word).append('\n'));
        // The bank sentences, from version 6, after a separator no word contains. Only when there
        // are any, so an empty list leaves the fingerprint every trace was captured under unchanged.
        if (!sentences.isEmpty()) {
            canonical.append("--- bank sentences\n");
            new java.util.TreeSet<>(sentences).forEach(sentence -> canonical.append(sentence).append('\n'));
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 8).toUpperCase(Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // DATE_LIKE, the all-numeric date: since redactor version 5 only a real date shape is kept
    // (NumberGroups.isRealNumericDate: a day, a month and a year, or a year, a month and a day).
    // Up to version 4 any "\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}" was kept whole, so a ten-digit
    // number printed as 1234-56-7890 passed as a date.
    // A real Kotak Mahindra Bank credit-card statement states every pre-table metadata date
    // ("16-Feb-2026", "02-Apr-2026") in this named-month shape rather than DATE_LIKE's all-numeric
    // one. Without this, the day and year survive as bare digit tokens (neither DATE_LIKE nor
    // AMOUNT_LIKE matches a lone "16" or "2026" with no decimal fraction) but the month name does
    // not -- it is not in STRUCTURAL_WORDS -- so the whole date silently degrades to "99-Xxx-9999"
    // and a trace meant to regression-test parsing this exact format carries no real date to parse.
    // Matched and preserved WHOLE, the same "dates are structure, not identity" reasoning DATE_LIKE
    // above already applies.
    static final Pattern NAMED_MONTH_DATE_LIKE = Pattern.compile(
            "\\d{1,2}-(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-\\d{2,4}",
            Pattern.CASE_INSENSITIVE);
    // A named-month date printed with SPACES rather than hyphens: "06 Jul 26" on a real SBI Card
    // statement's every transaction row, "May 04" (month first, no year) on a real Standard
    // Chartered export's Date and Value Date cells. Both are several tokens to the loop below, so
    // the month name -- not in STRUCTURAL_WORDS -- was masked and the day and year degraded to
    // bare 9s: "99 Xxx 99". Every date row of the card statement then had no date at all, the
    // locator found 4 rows where the real document has 59, and the trace could not evidence the
    // rule it was captured for (DATELESS_AMOUNT_ROW_SPLIT). Matched over the whole text ahead of
    // the token loop and preserved WHOLE, the same "dates are structure, not identity" reasoning
    // as DATE_LIKE. Redactor version 3.
    // The month is the three-letter abbreviation or the full name and nothing else: "Mar" must
    // not admit "Martin 12" or "12 Marketing", which would keep a counterparty's name.
    private static final String MONTH_NAME =
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?"
                    + "|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)";
    // Version 5: a two-digit year after an apostrophe ("12 Jul '26", "12 Jul'26", "Jul '26"), which
    // CsvParser reads (APOSTROPHE_TWO_DIGIT_YEAR). Before, the month and day were kept and the year
    // fell outside the span, so the copy's dates lost their year.
    private static final String APOSTROPHE_YEAR = "\\s*['\u2018\u2019]\\d{2}";
    static final Pattern SPACED_NAMED_MONTH_DATE_LIKE = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\p{M}])(?:\\d{1,2}\\s+" + MONTH_NAME + "(?:,?\\s+\\d{2,4}|" + APOSTROPHE_YEAR + ")?"
                    + "|" + MONTH_NAME + "\\s+\\d{1,2}(?:,?\\s+\\d{2,4})?"
                    + "|" + MONTH_NAME + APOSTROPHE_YEAR + ")(?![\\p{L}\\p{N}\\p{M}])",
            Pattern.CASE_INSENSITIVE);
    /** Version 5: a day glued to its month, with or without a glued year ("12Jul", "12Jul2026"),
     *  which CsvParser reads by splitting at the letter-digit boundaries (MISSING_SEPARATOR). */
    static final Pattern GLUED_DAY_MONTH_DATE_LIKE = Pattern.compile(
            "\\d{1,2}" + MONTH_NAME + "(?:\\d{4}|\\d{2})?", Pattern.CASE_INSENSITIVE);
    /**
     * An integer shape a number rule recognises: comma-grouped in the Indian or the western way
     * ("1,00,000", "12,345"), or plain. Matching it only makes a token a candidate: it is kept only
     * when its digits are a value on the printed line (see {@link #allUnit}), which a comma-grouped
     * amount within its cap can be and a plain integer never is.
     */
    private static final String AMOUNT_SHAPED_INTEGER =
            "(?:\\d{1,3}(?:,\\d{2})*,\\d{3}|\\d{1,3}(?:,\\d{3})+|\\d{1,5})";
    private static final Pattern AMOUNT_SHAPED_INTEGER_LIKE = Pattern.compile(AMOUNT_SHAPED_INTEGER);
    /** Any amount this class keeps on its own: with decimals, or an amount-shaped integer. */
    private static final String AMOUNT_VALUE = "(?:[\\d,]+\\.\\d{1,2}|" + AMOUNT_SHAPED_INTEGER + ")";
    /** Version 5: an amount with a glued Cr or Dr ("1,234.00Cr", "500.00DR") -- that suffix only,
     *  in any case. CsvParser.parseNumeric reads the direction from it. */
    private static final Pattern AMOUNT_WITH_DIRECTION_LIKE = Pattern.compile(
            AMOUNT_VALUE + "(?i:cr|dr)");
    /** Version 5: an amount with a glued rupee prefix ("Rs.1,234.00", "INR1,234.00"), optionally
     *  with a glued Cr or Dr. The prefix is the only text kept; a prefix on a six-digit integer is
     *  not an amount shape this class keeps, so that stays masked. */
    private static final Pattern CURRENCY_AMOUNT_LIKE = Pattern.compile(
            "(?i:rs\\.?|inr\\.?)" + AMOUNT_VALUE + "(?i:cr|dr)?");
    /** Version 5: the one-letter direction markers TransactionNormalizer reads ("C", "D", any case),
     *  only as a token of their own with no punctuation attached. */
    private static final Pattern DIRECTION_LETTER = Pattern.compile("[CcDd]");
    private static final Pattern AMOUNT_LIKE = Pattern.compile(
            "\\(?-?[\\d,]+\\.\\d{1,2}\\)?");
    /** Account numbers, card numbers and payment references. Never preserved, whatever else the
     *  token looks like -- this check runs before the amount/date ones. */
    private static final Pattern LONG_DIGIT_RUN = Pattern.compile("\\d{8,}");
    /** An IFSC is split rather than kept or dropped whole: the 4-letter prefix identifies the BANK
     *  and is the signal bank detection is built on, while the 6-character branch code identifies
     *  a specific branch and is the part worth removing. Masking the whole token would leave a
     *  trace unable to regression-test the detection path at all. */
    private static final Pattern IFSC = Pattern.compile("([A-Z]{4})0[A-Z0-9]{6}");

    /**
     * Statement furniture the pipeline itself keys on. Losing any of these would stop a trace from
     * exercising the very code path it was captured to cover -- redact "Narration" and header
     * detection has nothing to detect.
     */
    private static final Set<String> STRUCTURAL_WORDS = new LinkedHashSet<>(List.of(
            // column headers and their variants
            "date", "txn", "transaction", "value", "dt", "narration", "description", "particulars",
            "remarks", "details", "withdrawal", "withdrawals", "deposit", "deposits", "debit",
            "debits", "credit", "credits", "amount", "amt", "balance", "closing", "opening",
            "chq", "cheque", "ref", "no", "reference", "dr", "cr", "count",
            // metadata labels
            "statement", "account", "accounts", "savings", "current", "summary", "total", "from",
            "to", "as", "on", "period", "ifsc", "micr", "rtgs", "neft", "branch", "code", "bank",
            "limited", "ltd", "type", "currency", "inr", "nomination", "registered", "customer",
            "name", "id", "email", "address", "city", "state", "country", "phone", "contact",
            "holder", "holders", "joint", "nominee", "status", "open", "limit", "od", "sweep",
            "fd", "hold", "unclear", "withdrawable", "available", "card", "number", "due",
            "minimum", "payment", "page", "of", "continued", "brought", "carried", "forward",
            "cust", "cif", "ccy",
            // Financial-product vocabulary. Added after a captured trace was found to have redacted
            // "Maturity Date" to "Xxxxxxxx Date" and "Deposit(Mnth)" to "Deposit(Xxxx)" -- the exact
            // column headers ProductEvidenceCollector classifies on, gone from the fixture that was
            // supposed to regression-test classifying them. None of these words identify anybody;
            // they are statement furniture in precisely the sense this allowlist exists to keep.
            // A committed trace cannot be un-redacted, so traces captured before this are missing
            // this vocabulary permanently and need re-capturing to exercise product classification.
            "maturity", "matures", "principal", "interest", "rate", "roi", "tenure", "tenor",
            "installment", "instalment", "installments", "instalments", "recurring", "term",
            "monthly", "mnth", "frequency", "paid", "due", "start", "end", "renewal", "scheme",
            "emi", "loan", "outstanding", "disbursed", "repayment", "overdraft", "folio", "nav",
            "units", "isin", "demat", "ppf", "epf", "nps", "uan", "pran", "rd", "running",
            // Transaction-instrument prefixes. Structural (they classify the row), and they carry
            // no identity on their own -- the counterparty that follows them is what gets masked.
            "upi", "neft", "imps", "rtgs", "ach", "atm", "pos", "ecs", "nach", "int", "chgs",
            // generic connective words that carry no identity on their own
            "a", "an", "and", "or", "the", "for", "is", "not", "applicable", "yes", "y", "n",
            // Credit-card ledger category headers and payment-instruction furniture. Added after a
            // real Kotak Mahindra Bank credit-card statement was found to have its own
            // "Payments and Other Credits"/"Primary Card Transactions"/"Retail Purchases and Cash
            // Transactions" section-header vocabulary masked away by capture -- the exact bare,
            // dateless label lines the row-continuation merge needs to recognize and drop rather
            // than sweep into an adjacent transaction's description, gone from the fixture meant to
            // regression-test that. Same for the document's "Remember to pay by <date>"/"Pay your
            // Credit card bills using the following:" sentences, neither a "Due Date" label nor any
            // already-covered trailing-content marker. None of these identify anybody.
            "payments", "other", "primary", "transactions", "retail", "purchases", "cash",
            "remember", "pay", "by", "your", "bills", "using", "following",
            // A real IndusInd (CRED RuPay) credit-card statement's own header and legend furniture.
            // Its amount header reads "Amount (in `)" and its rewards column "CRED Points"; masked,
            // the header runs lose their measured widths (see redact's own width rule), the table
            // has no right edge, and MARGIN_PANEL_TEXT_EXCLUDED -- the capability the trace was
            // captured to protect -- cannot activate on the fixture at all. "CRED Points earned via
            // spending" is PAGE_LEGEND_BLOCK_START's own trigger sentence for this layout, and the
            // two-line "CRED Points / Transferred*" heading beside it is what the legend suppression
            // now keeps out of the leading-narration buffer. A product name, a rewards label and
            // connective words; none of them identify anybody.
            "in", "merchant", "category", "cred", "points", "earned", "via", "spending", "transferred",
            // "Rs" -- the Indian Rupee abbreviation this same statement's own amount-column header
            // uses ("Amount (Rs.)R", the trailing "R" its own separate text run), distinct from
            // "inr" above (already allowlisted).
            "rs",
            // ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED's own trigger vocabulary. A real Central Bank of
            // India export and a real PNB ONE export each close with a regulatory-boilerplate sentence
            // ("Unless a constituent notifies the Bank immediately of any discrepancy...") that the
            // trigger pattern itself matches on -- masking any of these words away leaves a captured
            // trace unable to exercise the trigger it exists to protect. None of these identify
            // anybody; it is the same fixed regulatory disclosure on both documents.
            "unless", "constituent", "notifies", "immediately", "any", "discrepancy",
            // The reading code's own label and marker words, taken from its constants (see
            // ReaderKeywordVocabularyTest, which fails when one of those constants gains a word this
            // list does not keep): CreditCardSummaryExtractor's previous-balance, fees, purchases
            // and cash-advance labels, and PdfTableLocator's page-legend sentences, illustration,
            // EMI-balances and loan-summary table markers and "date & time" hint. Masked, a blanked
            // copy lost a card's opening balance, glued a page footer onto a transaction and read a
            // section count differently. Statement furniture; none of them identify anybody.
            "previous", "dues", "charges", "fees", "fee", "taxes", "purchase", "spends", "made",
            "this", "cycle", "advance", "advances", "highlighted", "grey", "color", "includes",
            "funds", "earmarked", "should", "be", "payable", "illustration", "will", "indicate",
            "calculating", "balances", "table", "time"));

    /**
     * A bare single letter immediately trailing an already-preserved currency marker WITHIN THE
     * SAME run -- e.g. this same real Kotak Mahindra Bank statement's "Amount" column header, whose
     * own text is one single {@code PositionedText} run reading {@code "(Rs.)R"} (verified against
     * the real PDF's own positioned output; the closing paren is what splits it into the two tokens
     * {@code "Rs."} and {@code "R"} that {@link #redactText} processes). The trailing "R" is a
     * rendering artifact of the rupee glyph, not a word this class's word-level allowlist could
     * ever recognize on its own -- {@link #STRUCTURAL_WORDS} above is deliberately word-shaped.
     *
     * <p><b>Why masking it broke a whole column, not just cosmetics.</b> Up to redactor version 4,
     * {@link #redact} zeroed the WIDTH of any run whose redacted text differed from the original at
     * all (it now rounds it; see that method's own doc comment) -- but this run is 5/6ths real text
     * ("(Rs.)") and one masked letter, and the whole run's width -- this header cell's own trailing
     * edge -- paid for that one letter. {@code PdfTableLocator}'s RIGHT_ALIGNED_AMOUNTS correction
     * -- the mechanism that reassigns a right-aligned numeric value from its naive nearest-header
     * bucket to the column its printed position actually lines up with -- is itself guarded on
     * {@code width() > 0} (see that capability's own real-corpus history in
     * CapabilityCoverageService), so it silently declined to run for this column at all. Every one
     * of this section's 20 real purchase rows landed under a phantom "(Rs.)" column instead of
     * "Amount" -- the one name {@code TransactionNormalizer.AMOUNT_HINTS} actually recognizes -- and
     * staged as unparseable "no column recognized as an amount or balance" in the trace-driven
     * regression suite, even though the real (unredacted) document parses every one of them
     * correctly. A redaction-fixture artifact, not a production defect -- confirmed by running the
     * real PDF bytes (not the trace) through the identical pipeline.
     *
     * <p>Deliberately its own narrow check rather than a bare "r" added to STRUCTURAL_WORDS: an
     * unconditional single-letter allowance would preserve a stray initial anywhere in a document (a
     * genuine, if low-information, piece of PII), where this scopes the exception to the one real
     * shape that motivates it -- immediately following a currency marker's own closing paren, not
     * any lone letter anywhere.
     */
    private static final java.util.regex.Pattern TRAILING_CURRENCY_MARKER_LETTER =
            Pattern.compile("^[A-Za-z]$");

    private static boolean isTrailingCurrencyMarkerLetter(String token, StringBuilder alreadyBuilt) {
        if (!TRAILING_CURRENCY_MARKER_LETTER.matcher(token).matches()) return false;
        String precedingLower = alreadyBuilt.toString().toLowerCase(Locale.ROOT);
        return precedingLower.endsWith("rs.)") || precedingLower.endsWith("rs)");
    }

    /**
     * Redacts every run, preserving the measured width of runs redaction left byte-identical and
     * rounding it to a whole point for every run it changed.
     *
     * <p>The discriminator is exact rather than heuristic: redaction is purely character-substituting
     * ({@link #mask}, which preserves length and every non-alphanumeric character), so
     * {@code redacted.equals(original)} is true if and only if nothing about the run was hidden.
     *
     * <p><b>Why width is kept at all.</b> {@code PdfTableLocator}'s right-edge correction
     * (RIGHT_ALIGNED_AMOUNTS) and {@code StatementSummaryExtractor#valueUnder} are guarded on
     * {@code width() > 0}; with every width zeroed, no trace at any version could reach them, and the
     * corpus was structurally blind to the exact class of defect it exists to catch — a short
     * right-aligned amount bucketing into the wrong column.
     *
     * <p><b>Why a changed run's width is rounded.</b> An unmasked run's width is a deterministic
     * function of text this same file already publishes verbatim, so it discloses nothing new and is
     * kept exactly. A masked run's exact width is a real-valued observation about hidden characters
     * — for alphabetic tokens it constrains the letter multiset — so up to redactor version 4 it was
     * dropped to 0. That changed readings: reading real statements with only those widths zeroed
     * (text otherwise unchanged) changed the result on 14 files of the corpus, through the
     * page-shows-a-space evidence built from run ends, the right-aligned amount and column-span
     * corrections, and on card statements the summary extractor's label geometry. From version 5
     * the width is kept rounded to a whole point: the run's right edge stays within half a point of
     * where it was printed, which is what those readers need, while letters, which differ in width
     * by fractions of a point, are far less constrained than by the measured value.
     *
     * <p><b>Why runs are not redacted one by one alone.</b> Whether a date or an amount is a value
     * depends on its neighbours, in the same run or the next one: glued to another number it is part
     * of that number. Digits are therefore read per printed line first (see {@link NumberGroups}),
     * and a token is kept only when its digits are a value that is not part of a long number. A
     * changed run's x and width are rounded to a whole point.
     *
     * <p><b>Bank sentences.</b> From version 6, every run of a printed line whose whole text is a
     * listed bank sentence is copied as printed (see {@link #runsOfListedLines}).
     */
    public static List<PositionedText> redact(List<PositionedText> runs) {
        return redact(runs, BankSentences.listed());
    }

    /** {@link #redact(List)} with the given bank sentences in place of the production list. */
    static List<PositionedText> redact(List<PositionedText> runs, Set<String> sentences) {
        Set<Integer> keep = runsOfListedLines(runs, sentences);
        Set<String> vocabulary = vocabulary();
        NumberGroups.Kind[][] kinds = NumberGroups.scanByLine(runs);
        List<PositionedText> out = new ArrayList<>(runs.size());
        for (int r = 0; r < runs.size(); r++) {
            PositionedText run = runs.get(r);
            String redacted = keep.contains(r) ? run.text()
                    : redactText(run.text(), vocabulary, new Context(run.text(), kinds[r]));
            boolean unmasked = java.util.Objects.equals(redacted, run.text());
            // A changed run's x is rounded like its width: an exact x beside a rounded width would
            // let the width be recovered from the gap to the next run.
            out.add(new PositionedText(redacted, unmasked ? run.x() : Math.round(run.x()), run.y(),
                    run.pageIndex(), unmasked ? run.width() : Math.round(run.width())));
        }
        return out;
    }

    /**
     * The runs of every printed line whose whole joined text, normalised, is a listed bank sentence
     * (see {@link BankSentences}). Lines are the redactor's own ({@link NumberGroups#lines}, with
     * its margin), the same lines its digit rules read. A line holding a sentence and anything else
     * is not a match, so nothing printed beside a sentence is kept with it; a sentence wrapped over
     * two printed lines matches on neither.
     */
    static Set<Integer> runsOfListedLines(List<PositionedText> runs, Set<String> sentences) {
        if (sentences.isEmpty()) return Set.of();
        Set<Integer> keep = new java.util.HashSet<>();
        for (NumberGroups.Line line : NumberGroups.lines(runs, NumberGroups.REDACTOR_GAP_MARGIN)) {
            if (!sentences.contains(BankSentences.normalise(line.text()))) continue;
            for (int[] o : line.origin()) {
                if (o != null) keep.add(o[0]);
            }
        }
        return keep;
    }

    /** One line of text masked exactly as a run with this text would be by {@link #redact}. */
    public static String redactLine(String text) {
        return redactText(text, vocabulary());
    }

    /** Structural words plus every bank's own identifiers, taken from {@link BankRegistry} so this
     *  never drifts as banks are added. A bank's name is not identifying once the account holder's
     *  is gone, and preserving it is what lets a trace regression-test bank detection. */
    static Set<String> vocabulary() {
        Set<String> vocabulary = new LinkedHashSet<>(STRUCTURAL_WORDS);
        for (BankRegistry.BankInfo bank : BankRegistry.all()) {
            vocabulary.add(bank.id().toLowerCase(Locale.ROOT));
            addWords(vocabulary, bank.officialName());
            addWords(vocabulary, bank.shortName());
        }
        return vocabulary;
    }

    private static void addWords(Set<String> vocabulary, String phrase) {
        if (phrase == null) return;
        for (String word : phrase.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isBlank()) vocabulary.add(word);
        }
    }

    static String redactText(String text, Set<String> vocabulary) {
        return redactText(text, vocabulary,
                text == null ? null : new Context(text, NumberGroups.scan(text)));
    }

    /** {@code ctx} says, per char of {@code text}, how each digit groups into a number on its
     *  printed line, and which line chars stand just before and after the run (see
     *  {@link NumberGroups}). */
    static String redactText(String text, Set<String> vocabulary, Context ctx) {
        if (text == null || text.isBlank()) return text;
        // A lone C or D is a direction marker only as a whole run (a Type column cell); anywhere
        // else a single letter may be an initial.
        if (DIRECTION_LETTER.matcher(text.strip()).matches()) return text;
        // Spaced named-month dates first: they span several tokens, so the per-token loop below
        // cannot see them whole. Each span is copied verbatim and the text between spans is
        // redacted as before.
        var spaced = SPACED_NAMED_MONTH_DATE_LIKE.matcher(text);
        StringBuilder joined = null;
        int last = 0;
        while (spaced.find()) {
            // Copied whole only when its digits stand apart as a date; a date glued into a longer
            // number is redacted token by token instead.
            if (!allUnit(ctx, spaced.start(), spaced.end())) continue;
            if (joined == null) joined = new StringBuilder(text.length());
            joined.append(redactTokens(text.substring(last, spaced.start()), vocabulary, ctx, last));
            joined.append(spaced.group());
            last = spaced.end();
        }
        if (joined != null) {
            joined.append(redactTokens(text.substring(last), vocabulary, ctx, last));
            return joined.toString();
        }
        return redactTokens(text, vocabulary, ctx, 0);
    }

    /** The whole run being redacted and how its digits group on the printed line. */
    record Context(String text, NumberGroups.Kind[] kinds) {}

    /**
     * A token a number rule matched is kept only when every digit in it is part of a value that
     * stands apart on its printed line (a date, an HH:MM time, a decimal amount, a comma-grouped
     * amount within its cap) and not part of a long number (see {@link NumberGroups}). A plain
     * integer is never kept: keeping integers of up to five digits was tried and measured to win no
     * matches, while every way of printing a number in pieces leaked through it.
     */
    private static boolean allUnit(Context ctx, int from, int to) {
        for (int k = from; k < to; k++) {
            char c = ctx.text().charAt(k);
            if (c >= '0' && c <= '9' && (ctx.kinds() == null || ctx.kinds()[k] != NumberGroups.Kind.UNIT)) return false;
        }
        return true;
    }

    private static String redactTokens(String text, Set<String> vocabulary, Context ctx, int offset) {
        if (text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        // Split keeping the separators, so every space and its exact position survives -- the
        // whitespace between two runs is part of the layout being reproduced.
        // Walked by code point, so a character outside the Basic Multilingual Plane is one
        // character here rather than two surrogate halves that no rule recognises.
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isTextCodePoint(cp)) {
                int start = i;
                while (i < text.length() && (isTokenChar(text.codePointAt(i)) || formatRunInsideToken(text, i))) {
                    i += Character.charCount(text.codePointAt(i));
                }
                String token = text.substring(start, i);
                // See TRAILING_CURRENCY_MARKER_LETTER's own doc comment -- checked ahead of the
                // ordinary vocabulary/date/amount rules below, against what THIS SAME redaction
                // pass has already appended for this run, not a separate lookup.
                out.append(isTrailingCurrencyMarkerLetter(token, out) ? token
                        : redactToken(token, vocabulary, ctx, offset + start));
            } else {
                // An invisible format character (a zero-width space, a tag character) can carry a
                // hidden message anywhere, so it is masked wherever it stands.
                out.appendCodePoint(Character.getType(cp) == Character.FORMAT ? 'x' : cp);
                i += Character.charCount(cp);
            }
        }
        return out.toString();
    }

    /**
     * Whether a code point can carry what a document says, in any script: a letter, a number or a
     * mark (a Devanagari vowel sign, a Tamil virama, an accent written as its own character), plus
     * a private-use glyph (a font with no Unicode mapping can extract a name as private-use code
     * points) and an unpaired surrogate (a broken character whose meaning is unknown). Everything
     * of these kinds is masked unless a rule below keeps it, and every rule that keeps text keeps
     * plain ASCII only. {@link TraceValidator#privacyKinds} uses the same test as its backstop: a
     * non-ASCII code point of these kinds still in a redacted run was not masked.
     *
     * <p>Up to redactor version 3 the test was ASCII letters and digits only: a token made only of
     * non-ASCII letters or digits was returned verbatim, and marks were copied out as punctuation.
     */
    static boolean isTextCodePoint(int cp) {
        int type = Character.getType(cp);
        return type == Character.UPPERCASE_LETTER || type == Character.LOWERCASE_LETTER
                || type == Character.TITLECASE_LETTER || type == Character.MODIFIER_LETTER
                || type == Character.OTHER_LETTER
                || isNumber(type)
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    }

    static boolean isNumber(int type) {
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /**
     * Whether the code point at {@code i} is an invisible format character (Unicode category Cf: a
     * zero-width joiner or non-joiner, a soft hyphen) with more text after it, so it sits inside a
     * token rather than at its end. Inside a token it is masked to x like a mark, so a masked word
     * does not show where its joins were; at a token's end or outside any token it carries nothing
     * and is copied, so an amount followed by one stays a kept amount.
     */
    private static boolean formatRunInsideToken(String text, int i) {
        int j = i;
        while (j < text.length() && Character.getType(text.codePointAt(j)) == Character.FORMAT) {
            j += Character.charCount(text.codePointAt(j));
        }
        return j > i && j < text.length() && isTextCodePoint(text.codePointAt(j));
    }

    /** A token runs across '.' and ',' so that "24,462.00" and "10/07/2026" are judged whole rather
     *  than as the fragments "24", "462" and "00". */
    private static boolean isTokenChar(int cp) {
        return isTextCodePoint(cp) || cp == '.' || cp == ',' || cp == '/' || cp == '-';
    }

    /** The token without the punctuation at either end. */
    private static String stripPunctuation(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && !isTextCodePoint(token.codePointAt(start))) {
            start += Character.charCount(token.codePointAt(start));
        }
        while (end > start && !isTextCodePoint(token.codePointBefore(end))) {
            end -= Character.charCount(token.codePointBefore(end));
        }
        return token.substring(start, end);
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 128);
    }

    private static String redactToken(String token, Set<String> vocabulary, Context ctx, int offset) {
        String bare = stripPunctuation(token);
        if (bare.isEmpty()) return token;
        int bareStart = offset + token.indexOf(bare);

        var ifsc = IFSC.matcher(bare);
        if (ifsc.matches()) {
            // The punctuation at the token's edges is kept, as every other rule keeps it, so the
            // masked token is as long as the real one.
            int start = token.indexOf(bare);
            return token.substring(0, start) + ifsc.group(1) + "0XXXXXX" + token.substring(start + bare.length());
        }

        // Order matters: a long digit run is an account or reference number even when it would
        // otherwise pass as an amount, so it is checked first and always redacted.
        if (LONG_DIGIT_RUN.matcher(bare).find()) return mask(token);
        boolean integerShaped = AMOUNT_SHAPED_INTEGER_LIKE.matcher(bare).matches()
                || ((AMOUNT_WITH_DIRECTION_LIKE.matcher(bare).matches() || CURRENCY_AMOUNT_LIKE.matcher(bare).matches())
                        && !DECIMAL_END.matcher(bare).find());
        if (integerShaped || NumberGroups.isRealNumericDate(bare) || AMOUNT_LIKE.matcher(bare).matches()
                || NAMED_MONTH_DATE_LIKE.matcher(bare).matches()
                || GLUED_DAY_MONTH_DATE_LIKE.matcher(bare).matches()
                || AMOUNT_WITH_DIRECTION_LIKE.matcher(bare).matches()
                || CURRENCY_AMOUNT_LIKE.matcher(bare).matches()) {
            return allUnit(ctx, bareStart, bareStart + bare.length()) ? token : mask(token);
        }
        // ASCII only: some characters lower-case into ASCII (the Kelvin sign becomes "k"), and a
        // word spelled with one is not the vocabulary word.
        if (isAscii(bare) && vocabulary.contains(bare.toLowerCase(Locale.ROOT))) return token;

        // '/' and '-' are part of a token so that dates and amounts are judged whole, which also
        // glues compound labels together: "RTGS/NEFT" and "UPI-SOMEONE" arrive as single tokens and
        // would be masked entirely for not appearing in the vocabulary -- taking the "RTGS/NEFT
        // IFSC" label with them, and with it the trace's ability to test bank detection. Once the
        // whole token has failed every rule above, judge its parts individually.
        if (bare.indexOf('/') >= 0 || bare.indexOf('-') >= 0) {
            StringBuilder rejoined = new StringBuilder();
            int start = 0;
            for (int i = 0; i <= token.length(); i++) {
                if (i == token.length() || token.charAt(i) == '/' || token.charAt(i) == '-') {
                    rejoined.append(redactToken(token.substring(start, i), vocabulary, ctx, offset + start));
                    if (i < token.length()) rejoined.append(token.charAt(i));
                    start = i + 1;
                }
            }
            return rejoined.toString();
        }
        return mask(token);
    }

    /** An amount whose number ends in one or two decimals, before an optional glued Cr/Dr. */
    private static final Pattern DECIMAL_END = Pattern.compile("\\d\\.\\d{1,2}(?i:cr|dr)?$");

    /** One character out per code point in: a number becomes 9, an upper-case letter X, any other
     *  text code point (a lower-case or uncased letter, a mark, a private-use glyph) x, and
     *  punctuation is kept. */
    private static String mask(String token) {
        StringBuilder masked = new StringBuilder(token.length());
        token.codePoints().forEach(cp -> masked.appendCodePoint(maskOf(cp)));
        return masked.toString();
    }

    /** What {@link #mask} writes for one code point: '9' for a number, 'X' for an upper-case
     *  letter, 'x' for any other text code point or a format character (one only reaches a mask
     *  from inside a token), and the code point itself for anything else. */
    public static int maskOf(int cp) {
        if (isNumber(Character.getType(cp))) return '9';
        if (isTextCodePoint(cp)) return Character.isUpperCase(cp) ? 'X' : 'x';
        if (Character.getType(cp) == Character.FORMAT) return 'x';
        return cp;
    }
}
