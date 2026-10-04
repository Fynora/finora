package com.finora.util;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keyword-based auto-categorization rules — the server-side counterpart of the
 * client-side rule engine used in the browser prototype, so behavior stays
 * consistent whichever layer ends up doing the categorization.
 *
 * This is intentionally simple pattern matching, not a trained model. Swapping
 * in a real classifier (or calling out to OpenAI per the PRD's "AI layer") is a
 * drop-in replacement for {@link #suggestCategory} — see CategorizationService.
 */
public final class CategoryRules {

    private CategoryRules() {}

    // Built from an explicit char code rather than the string literal "\\b" -- a single
    // backslash in a Java string literal ("\b") is the escape sequence for the ASCII backspace
    // control character, not a literal backslash, so writing the word-boundary token directly
    // as a string literal is one keystroke away from silently compiling into something that
    // matches nothing. Building it from (char) 0x5C removes that ambiguity entirely.
    private static final String WORD_BOUNDARY = String.valueOf((char) 0x5C) + "b";

    // A change here that can move a row off "Other" or "Personal Transfer": raise
    // CategorizationService.SUGGESTION_VERSION so rows already waiting are re-checked.
    public static final Map<String, List<String>> RULES = new LinkedHashMap<>();
    static {
        // "kronos" (a real workforce-management/payroll platform, now part of UKG) added after
        // mining this project's own real bank-statement corpus's current residual "Other" bucket
        // (2026-09-14 pass, docs/superpowers/plans/2026-09-14-vocabulary-mining-pass-2.md) --
        // narrations referencing it appear as NEFT credits across 2 distinct documents. Mapped to
        // Salary on the assumption a credit naming a payroll platform is a salary deposit (see
        // CategoryRulesTest's Kronos test comment for the reasoning and its caveat). Safe as a
        // bare keyword: a distinctive proper noun, not a substring of any other keyword here.
        RULES.put("Salary", List.of("salary", "payroll", "income tax refund", "stipend", "kronos"));
        // "housingcom" (Housing.com, printed as one contiguous word on the real statement) added
        // after checking this project's own real bank-statement corpus (docs/superpowers/specs/
        // 2026-09-01-transaction-categorization-design.md §1) -- mapped to Rent on the assumption
        // this is a rent-payment-facilitator narration (see CategoryRulesTest's Housingcom test
        // comment for the reasoning and its caveat). Safe as a bare keyword: word-boundary matching
        // only matches the exact bounded token "housingcom", never as a prefix inside a longer run
        // like "housingcommunity" (guarded by suggestCategory_housingCommunityIsNotMisclassifiedAsRent).
        RULES.put("Rent", List.of("house rent", "rent paid", "rent payment", "monthly rent", "rent due", "landlord", "housing society", "maintenance chg", "housingcom"));
        RULES.put("Groceries", List.of("bigbasket", "blinkit", "zepto", "grofers", "dmart", "grocery", "supermarket"));
        // "asspl" (Amazon Seller Services' actual card-statement abbreviation) and "cinnabon"
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- both real, verified misses,
        // safe as bare keywords: neither is a substring of any other keyword or common English/
        // Indian-banking-narration word, so word-boundary matching has nothing plausible to
        // misfire against.
        // "gokhana" is a workplace-cafeteria ordering platform, and the single highest-frequency
        // unmatched brand in the corpus: 105 rows across 6 of the 29 statements, i.e. multiple
        // distinct people, which is what separates real vocabulary from overfitting to one payer.
        // "tobox" (Tobox Ventures Private Limited, the registered corporate name behind
        // "Gokhana" -- a real narration links them directly: "TOBOX VENTURES PRIVATE LIMITED/
        // GOKHANA.") added after re-checking this project's own real bank-statement corpus for
        // additional vocabulary beyond the 2026-09-01 review (docs/superpowers/plans/2026-09-05-
        // categorization-vocabulary-expansion.md Task 1). Kept as a bare word rather than "tobox
        // ventures" because one real statement truncates the narration to "TOBOX VENT" -- a
        // two-word phrase keyword would miss that form. Safe as a bare keyword: not a substring of,
        // or a container of, any other keyword in this table.
        // "chinese factory", "cream house", and "lassi wassi" (real restaurant/dessert-parlor/
        // beverage-shop names) added after mining this project's own real bank-statement corpus's
        // current residual "Other" bucket (2026-09-14 pass, docs/superpowers/plans/2026-09-14-
        // vocabulary-mining-pass-2.md) -- each appears across 3 distinct documents, the same
        // multi-payer bar "gokhana" was held to. Safe as bare phrases: none is a substring of, or
        // contains, any other keyword in this table.
        // "tea post" (a tea-cafe chain) added 2026-09-28: a two-word brand the person check reads as
        // a name, so it typed PERSON and fell to "Personal Transfer" on 7 corpus rows across 4
        // documents and 2 accounts, in both the HDFC and the slash UPI layout. The whole phrase, as
        // with the other brands here -- a bare "tea" names a drink, not a business.
        RULES.put("Dining", List.of("swiggy", "zomato", "eatclub", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana", "tobox", "chinese factory", "cream house", "lassi wassi", "tea post"));
        // "indian railways" (the national railway institution, named directly rather than
        // through its "irctc" booking portal already above) added after re-checking this
        // project's own real bank-statement corpus for additional vocabulary beyond the
        // 2026-09-01 review (docs/superpowers/plans/2026-09-05-categorization-vocabulary-
        // expansion.md Task 1). Kept as the full two-word phrase, not a bare "indian": a bare
        // keyword would misfire on real "INDIAN CLEARING CORP" settlement narrations seen in the
        // same corpus (guarded by suggestCategory_indianClearingCorpIsNotMisclassifiedAsTransport).
        // "pune metro" and, below, "apple services" (2026-10-02) name an operator rather than add a
        // word: "metro" already files the fare. A bank that cuts the payee's name to eight
        // characters and its UPI id before the "@" leaves only the id ("punemetro...",
        // "appleservices..."), which MerchantIdentityLookup.handleNamesKnownMerchant reads against
        // these names; without them a tester's metro fares and app-store charges typed as people.
        // "punemetro", "parkplus", "hp petro", "airtelautopay", "airtelprepaid" and "gpay utility"
        // (2026-10-03): measured on a tester's savings statement, whose bank cuts the payee's name to
        // eight characters and fuses the UPI id into one word before cutting it, so the operator's
        // own words above never stand apart -- the metro's id, a parking app, a fuel pump's cut name,
        // the telecom's autopay and prepaid ids, and a payments app's bill-payment id all fell to
        // "Other" and waited for the user. "punemetro" and "airtelprepaid" are fusion-tolerant (see
        // FUSION_TOLERANT_KEYWORDS): the bank cuts the id after them at varying lengths.
        RULES.put("Transport", List.of("uber", "ola", "rapido", "irctc", "petrol", "fuel", "metro", "fastag", "parking", "indian railways", "pune metro",
                "punemetro", "parkplus", "hp petro"));
        RULES.put("Utilities", List.of("electricity", "power bill", "water bill", "gas bill", "broadband", "airtel", "jio", "recharge",
                "airtelautopay", "airtelprepaid", "gpay utility"));
        // "pureplay" (Pureplay Skin Sciences, a real D2C skincare/personal-care e-commerce brand)
        // added after checking this project's own real bank-statement corpus (docs/superpowers/
        // specs/2026-09-01-transaction-categorization-design.md §1) -- a real, verified miss, safe
        // as a bare keyword: not a substring of any other keyword or common English/Indian-banking-
        // narration word, so word-boundary matching has nothing plausible to misfire against.
        // "global fashion" (a real clothing/apparel retailer, 3 distinct documents) and "ekart"
        // (Flipkart's own logistics/delivery arm -- flagged as a candidate in the 2026-09-05
        // categorization-vocabulary-expansion plan's Task 1 and deferred pending this follow-up)
        // added after mining this project's own real bank-statement corpus's current residual
        // "Other" bucket (2026-09-14 pass, docs/superpowers/plans/2026-09-14-vocabulary-mining-
        // pass-2.md). Safe as bare words/phrases: neither collides with any other keyword here.
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl", "pureplay", "global fashion", "ekart"));
        RULES.put("Health", List.of("pharmacy", "apollo", "medplus", "hospital", "clinic", "netmeds", "1mg"));
        RULES.put("Entertainment", List.of("netflix", "prime video", "hotstar", "spotify", "bookmyshow", "pvr", "inox"));
        // "mutualfunds" is not redundant with "mutual fund": matching is word-boundary over the
        // NORMALIZED description, and normalize() only replaces non-alphanumerics with spaces -- it
        // never splits a run-together word. The unspaced form is what actually appears on real
        // statements (12 rows on the corpus, all previously "Other"), so the spaced keyword could
        // never reach them.
        // "nse mf" (National Stock Exchange's mutual-fund investment platform) added after
        // checking this project's own real bank-statement corpus -- kept as the exact two-word
        // phrase seen on the real narration ("NSE MF"), the same choice already made for
        // "cc payment": matching the full phrase rather than a bare "mf" avoids the false-positive
        // risk a 2-letter fragment would carry.
        //
        // Second pass (2026-09-21), mined from the same 29-statement real corpus and grouped by what
        // the evidence supports:
        //  - Seen on the corpus, previously "Other": "indian clearing" (Indian Clearing Corporation,
        //    the BSE clearing house that collects mutual-fund SIP debits -- 28 outflow rows, mostly
        //    "ACH D- INDIAN CLEARING CORP-..." mandate debits that carry no "mutual fund" word at
        //    all; 2 further inflow rows were left "Other" here because their wrapped narration
        //    splits the word itself, "INDIAN C LEARING"),
        //    "nextbillion" (the former name of the Groww broker entity, on 4 inflows from its
        //    "client account"),
        //    "nse zerod" (a bank-truncated "NSE ZERODHA"), "hsbc mf", "nippon life asset" (a
        //    truncated "...ASSET MANAGEMENT" AMC mandate debit) and "nsdl findiv" (a dividend
        //    credit).
        //  - Not seen on the corpus, added because each is a long, distinctive brand or a
        //    brand+noun phrase that cannot plausibly appear inside an unrelated narration:
        //    the discount/online brokers and investment apps below. Word-boundary matched, like
        //    every keyword here that is not in FUSION_TOLERANT_KEYWORDS.
        //  - Deliberately NOT added, with the corpus reason: "dhan" (8 rows, every one a person's or
        //    a shop's name that merely contains it as a substring, none a broker), "navi" (a place
        //    name), "ipo" (a remark typed into a person-to-person payment), "nsdl" alone (NSDL also
        //    runs a payments bank), "nippon" alone (also an insurer), "icici prudential" (also an
        //    insurer), a bare "mf", and "capital" (an unidentified payee).
        //
        // Third pass (2026-10-02): "indian c learing", the split form of those 2 inflows, after all.
        // The split is not a misspelling: HDFC prints the narration in fixed 40-character lines and
        // this fund-transfer format's prefix ("FT- ", a 10-digit reference, a 14-digit number and
        // " - ") was the same width on both corpus rows, so the wrap landed after the same "C"
        // both times. The parser keeps
        // the space at that wrap deliberately (NarrationLineBreaks glues only on a digit or a
        // separator; two plain words give no evidence either way), so the keyword matches the text
        // as it arrives. Until #1888 these rows reached Investments only because the clearing
        // house's merchant also absorbed every other payee whose name began "indian"; the two-word
        // merchant key ended that, and they fell to "Other". The phrase is word-boundary matched
        // and cannot plausibly occur in an unrelated narration. A wrap landing anywhere else in
        // the name is not matched; none was seen on the corpus.
        RULES.put("Investments", List.of("mutual fund", "mutualfunds", "sip", "zerodha", "groww", "upstox", "nps", "ppf", "demat", "nse mf",
                "indian clearing", "indian c learing", "nextbillion", "nse zerod", "hsbc mf", "nippon life asset", "nsdl findiv",
                "angel one", "angelone", "5paisa", "kuvera", "indmoney", "smallcase", "sharekhan",
                "paytm money", "etmoney", "et money", "motilal oswal",
                "icici direct", "icicidirect", "hdfc securities", "icici securities", "kotak securities"));
        RULES.put("Fees/Interest", List.of("annual fee", "late fee", "finance charge", "interest charged", "penalty"));
        // "cc payment" added after checking this project's own real bank-statement corpus (see
        // Shopping/Dining comment above) -- a real BharatBillPay narration ("BPPY CC PAYMENT")
        // abbreviated past what "credit card payment"/"card bill payment" already catch. Safe as a
        // two-word phrase: word-boundary matching means "cc" alone is never checked in isolation.
        //
        // "rtgs" and "self transfer" added per the reconciliation benchmark's post-fix failure
        // analysis (docs/proposals/reconciliation-benchmark/post-fix-failure-analysis.md, item 1a):
        // ReconciliationService's transfer pass reuses this exact list to decide whether a
        // candidate pair looks like a transfer at all (see that pass's own comment), and RTGS is a
        // real, common Indian settlement rail this list was missing entirely. Deliberately NOT
        // adding "wallet" or "auto debit" in the same change -- both were considered and rejected:
        // "auto debit" in particular would misfire on a genuine EMI or insurance auto-debit, which
        // must NOT be recategorized as a transfer (see the same analysis doc's ranking table, and
        // the parked EMI/loan-repayment relationship types this project has already deliberately
        // deferred -- docs/proposals/reconciliation-benchmark/dead-relationship-types-audit.md).
        // "self transfer" is kept as the two-word phrase real UPI apps print (e.g. "UPI-SELF
        // TRANSFER-...") rather than the bare word "self", which is common enough in unrelated
        // narrations that a bare-word match would carry real false-positive risk.
        RULES.put("Transfer", List.of("credit card payment", "card bill payment", "cc payment", "autopay", "neft to", "imps to", "billdesk", "rtgs", "self transfer"));
        // Appended after the original set (see DefaultCategories, which this list
        // now mirrors) rather than interleaved — insertion order is match priority for
        // suggestCategory's first-match-wins loop, and none of these keywords collide with the
        // rules above, so appending can't change any existing categorization.
        // "emi" and "ngo" deliberately excluded as bare keywords here: contains()-based matching
        // means a 3-letter substring hits far more than intended — "emi" is inside "premium"
        // (so an insurance payment would misfire as Loan EMI before Insurance's own rule ever
        // runs) and inside "academic"/"chemistry", and "ngo" is inside "mongo"/"flamingo"/
        // "bingo"/"tango" (so e.g. a "MongoDB" hosting charge would misfire as a donation). The
        // compound phrases below keep the same real-world coverage without the false positives.
        RULES.put("Loan EMI", List.of("loan emi", "emi payment", "emi deduction", "personal loan", "home loan", "car loan", "auto loan"));
        // "pmjjby" (Pradhan Mantri Jeevan Jyoti Bima Yojana, a real Government of India life-
        // insurance scheme) added after checking this project's own real bank-statement corpus --
        // safe as a bare keyword for the same reason "pureplay" above is: a distinctive acronym,
        // not a substring of any other keyword or common narration word.
        // "pmsby" (Pradhan Mantri Suraksha Bima Yojana, the same government's accident-insurance
        // scheme) added on the same evidence: its premium debit sits beside the PMJJBY one on a
        // real statement, in the identical "JNS-<scheme>-..." narration shape, and stayed "Other"
        // without it. It reached Insurance before #1888 only because both debits shared one
        // merchant keyed on their common "jns" prefix.
        RULES.put("Insurance", List.of("insurance", "lic premium", "policybazaar", "premium payment", "pmjjby", "pmsby"));
        // "nwd" (Non-Home-branch Withdrawal, the standard NPCI/bank narration code for an ATM
        // withdrawal at another bank's machine) added after checking this project's own real
        // bank-statement corpus -- the only real ATM row in it ("NWD-416021XXXXXX5853-...") was
        // falling through every keyword above to "Other". Safe as a bare 3-letter keyword despite
        // the "emi"/"ngo" caution below: unlike those two, "nwd" is not a substring of any common
        // English or Indian-banking-narration word, so the word-boundary matching this file already
        // requires (see RULE_PATTERNS below) has nothing plausible to misfire against.
        RULES.put("Cash Withdrawal", List.of("atm withdrawal", "atm wdl", "cash withdrawal", "cash wdl", "nwd"));
        RULES.put("Travel", List.of("makemytrip", "goibibo", "yatra", "airbnb", "oyo", "indigo", "spicejet", "vistara", "hotel booking"));
        // "appleservices", "googleworkspace", "amazonaws" (2026-10-03): the same fused, cut UPI ids
        // on the same statement -- an app store, an office suite and a cloud provider's monthly charges.
        RULES.put("Subscriptions", List.of("google one", "icloud", "adobe", "microsoft 365", "linkedin premium", "apple services",
                "appleservices", "googleworkspace", "amazonaws"));
        RULES.put("Education", List.of("udemy", "coursera", "byjus", "tuition fee", "school fee", "college fee"));
        RULES.put("Gifts & Donations", List.of("donation", "charity", "ngo donation", "gift"));
    }

    /**
     * Every keyword this table matches on, flattened and unordered.
     *
     * <p>Exposed for {@link MerchantIdentityLookup}, which needs the VOCABULARY without the
     * category mapping: "Amazon is a known merchant" is an identity fact, while "Amazon means
     * Shopping" is a categorization fact, and only the first one belongs to a counterparty
     * decision. Handing out the terms rather than a second copy of them is deliberate -- the
     * duplicated-marker-set problem is already documented on
     * {@code PersonToPersonTransferDetector.hasMerchantAcquirerMarker}, and this table has grown
     * three times in the last week alone.
     */
    public static Set<String> allKeywords() {
        return RULES.values().stream().flatMap(List::stream).collect(Collectors.toUnmodifiableSet());
    }

    public static String normalize(String desc) {
        if (desc == null) return "";
        return desc.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    // A clock time in a UPI narration (Bank of Baroda prints hh:mm:ss after the RRN) and the literal
    // RRN label (Central Bank prints "UPI/RRN <n>/...") name no counterparty. Left in, they became the
    // merchant ("upi 02 44 32", "upi rrn upi ...") and, through MerchantNormalizationEngine's grouping
    // key, pooled unrelated payees under one merchant: measured on the real corpus, all 218 Central
    // Bank UPI rows under one merchant and every Bank of Baroda UPI row under a time of day.
    private static final Pattern CLOCK_TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    private static final Pattern RRN_LABEL = Pattern.compile("(?i)\\bRRN\\b[\\s:.#-]*\\d*");

    /** Strips numeric reference codes to surface a clean merchant token, e.g. "SWIGGY*ORDR9182" -> "swiggy". */
    public static String extractMerchant(String desc) {
        String cleaned = desc == null ? null
                : RRN_LABEL.matcher(CLOCK_TIME.matcher(desc).replaceAll(" ")).replaceAll(" ");
        String n = normalize(cleaned).replaceAll("\\b[a-z]*\\d{4,}[a-z]*\\b", " ").replaceAll("\\s+", " ").trim();
        String[] tokens = n.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, tokens.length); i++) {
            // CodeQL (java/misleading-indentation), 2026-09-04: braced explicitly -- the inner,
            // brace-less `if (sb.length() > 0) sb.append(' ');` was always correctly scoped to
            // just that one statement by Java's own grammar (sb.append(tokens[i]) always ran
            // unconditionally), but reads as the classic dangling-if footgun on a quick skim.
            if (tokens[i].length() > 1) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i]);
            }
        }
        return sb.length() > 0 ? sb.toString() : (n.isEmpty() ? "unknown" : n);
    }

    /**
     * {@link #extractMerchant} reduced further for the per-transaction "who was this with" label
     * (Transaction.merchant) the UI shows and looks up on Logo.dev by name.
     *
     * extractMerchant()'s raw output must stay untouched for its other callers, which reduce both
     * sides of a comparison the same way (see MerchantNormalizationEngine.groupingKey,
     * which already skips {@link PaymentRailTokens} for that grouping key) -- changing what
     * extractMerchant itself returns for a bare rail narration would change that grouping key too
     * and defeat the deliberate null-means-"don't group" behavior documented there.
     *
     * A display label that survives as nothing but a rail word ("upi", "ach") is actively
     * misleading rather than merely uninformative: MerchantLogo looks it up on Logo.dev by name
     * and gets back a real, unrelated company that happens to trademark that word. Null lets
     * Transaction.merchant stay unset, and the ledger UI already falls back to the transaction's
     * own description when merchant is empty.
     */
    public static String extractMerchantLabel(String desc) {
        if (desc != null && !desc.isBlank()) {
            java.util.Optional<String> structured = payeeOfStructuredNarration(desc);
            if (structured != null) return structured.orElse(null);
        }
        String merchant = extractMerchant(desc);
        String[] tokens = merchant.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            // Plan 5: a leading rail word is dropped rather than kept as part of the name.
            if (!PaymentRailTokens.isRailToken(tokens[i])) {
                return String.join(" ", java.util.Arrays.copyOfRange(tokens, i, tokens.length));
            }
        }
        return null;
    }

    /** The label {@link #extractMerchantLabel(String, com.finora.entity.Transaction.Type)} gives every interest
     *  credit. The web and mobile MerchantLogo components hold a copy, so they never look it up on Logo.dev. */
    public static final String INTEREST_LABEL = "interest";

    /**
     * {@link #extractMerchantLabel(String)} for a row whose direction is known -- the label stored on
     * Transaction.merchant. The one difference: interest the bank credited
     * ({@link BankActivityCategory#isInterestEarned}) is labelled {@link #INTEREST_LABEL}, unless the
     * narration names who paid it.
     *
     * <p>The narration of an interest credit carries the date it was earned for, and only
     * references of four digits or more are stripped, so the day survived into the label: a bank
     * that credits interest daily gave every row a label of its own, and a quarterly credit's label
     * named the quarter's last day. Measured on the real corpus (2026-10-04): 9 interest credits
     * across 8 statements carried 5 different labels, and one had lost the word "interest"
     * altogether (only the first four words of a narration are kept).
     *
     * <p>A structured narration whose payee field names someone ("NEFT CR-&lt;IFSC&gt;-&lt;LENDER&gt;-INTEREST
     * PAID") keeps that name: interest from a lender or another bank's deposit is told apart from
     * the account's own by who paid it, and the name carries no date. A payee field that is itself
     * the interest phrase names nobody, and gets the one label.
     *
     * <p>Money in only: a debit worded the same way keeps the label it always had. Refund matching
     * pairs a credit with a debit on the same account by this label, so ReconciliationService
     * never takes a name match on an interest credit as evidence -- a debit narrated just
     * "INTEREST" reduces to the same label.
     */
    public static String extractMerchantLabel(String desc, com.finora.entity.Transaction.Type direction) {
        if (BankActivityCategory.isInterestEarned(desc, direction)) {
            String payee = structuredPayee(desc);
            if (payee == null || BankActivityCategory.namesInterest(payee)) return INTEREST_LABEL;
        }
        return extractMerchantLabel(desc);
    }

    /**
     * The payee field of a structured narration, or null when the narration is not structured or
     * its payee field names nobody. The same field {@link #extractMerchantLabel} shows; it is also
     * what MerchantNormalizationEngine groups a new description by, because {@link #extractMerchant}
     * keeps only the first four words and so drops a payee printed after a long prefix
     * ("Payment from PhonePe_NAME").
     */
    public static String structuredPayee(String desc) {
        if (desc == null || desc.isBlank()) return null;
        java.util.Optional<String> structured = payeeOfStructuredNarration(desc);
        return structured == null ? null : structured.orElse(null);
    }

    // ---- Plan 5, task 1: the payee field of a structured narration -------------------------------
    //
    // Measured on the 33-document corpus: the name used to be the first four words of the
    // narration, so 1,671 of 1,940 rows began with a rail word ("upi ..."), 274 carried a payment
    // app's or bank's handle ("... ybl", "... paytm") and 179 carried digits. Structured narrations
    // name their payee in a field of their own -- UPI-NAME-HANDLE-IFSC-REF-NOTE, or
    // UPI/[DR|CR]/REF/NAME/HANDLE/..., with IMPS, NEFT and mobile-banking variants -- so the name is
    // taken from that field.

    /** Head words that make a narration structured: rails, their direction words, and a channel. */
    private static final Set<String> HEAD_WORDS = Set.of(
            "upi", "imps", "neft", "rtgs", "mob", "mmt", "ib", "ach", "nach", "dr", "cr", "p2a", "p2m");
    /** Tokens that are never part of a payee field's name. */
    private static final Set<String> FILLER_TOKENS = Set.of(
            "upi", "imps", "neft", "rtgs", "mob", "mmt", "ib", "ach", "nach", "dr", "cr", "p2a", "p2m",
            "na", "null", "rrn", "from", "upiintent");
    /** An IFSC, matched from the start of a field with its spaces removed: a line wrap can split it
     *  ("U TIB0...", "IOB A0001 ...") and an account number can follow it. */
    private static final Pattern IFSC_FIELD = Pattern.compile("(?i)^[a-z]{4}0[a-z0-9]{6}");
    /** A field that is only the money's direction, spelt out: the real slice small finance bank
     *  layout, "UPI-Debit-REF-NAME-IFSC-HANDLE-NOTE" (and "UPI-Credit-..."). Read as a name, it
     *  labelled every UPI row on that statement "debit" or "credit". Only the WHOLE field: a payee
     *  whose name merely starts with one of these words keeps it. Measured on the corpus: the slice
     *  statement is the only one printing either word as a field of its own. */
    private static final Pattern DIRECTION_FIELD = Pattern.compile("(?i)debit|credit");
    /** A code mixing letters and digits with no space in it: a transaction or merchant ID. */
    private static final Pattern CODE_FIELD = Pattern.compile("^(?=.*\\d)(?=.*[A-Za-z])\\S{6,}$");

    /**
     * The payee of a structured narration, or null when {@code desc} is not structured (the caller
     * then falls back to the leading words). An empty Optional means structured but naming no
     * payee, so no name at all rather than a guess.
     */
    private static java.util.Optional<String> payeeOfStructuredNarration(String desc) {
        java.util.regex.Matcher glued = OwnAccountEvidence.GLUED_IMPS_PAYEE.matcher(desc);
        if (glued.find()) return java.util.Optional.ofNullable(nameOf(glued.group(1)));
        String cleaned = RRN_LABEL.matcher(CLOCK_TIME.matcher(desc).replaceAll(" ")).replaceAll(" ").trim();
        String[] fields = fieldsOf(cleaned);
        if (fields == null) {
            // ICICI prints the payee ahead of the rail: "NAME UPI/NAME/HANDLE/NOTE/BANK/REF/CODE".
            String[] slash = cleaned.split("/");
            if (slash.length < 2 || !normalize(slash[0]).endsWith(" upi")) return null;
            return java.util.Optional.ofNullable(nameOf(slash[0]));
        }
        for (int i = 1; i < fields.length; i++) {
            String field = fields[i].trim();
            if (field.isEmpty()) continue;
            if (DIRECTION_FIELD.matcher(field).matches()) continue;
            if (field.contains("@")) {
                // The payee is printed before the handle in almost every corpus layout; what follows
                // is a note, a bank or an IFSC, often cut off or split by a line wrap ("UP", "IOB
                // A0001 ..."). One Standard Chartered layout prints the name DIRECTLY after the
                // handle (.../HANDLE/ NAME/IFSC/...), so only that one next field is still read, and
                // only when it names something of at least three letters. Otherwise the handle's own
                // name is the answer.
                String next = i + 1 < fields.length ? fields[i + 1].trim() : "";
                String after = next.contains("@") || isNotAName(next) ? null : nameOf(next);
                String fromHandle = handleName(field);
                // The next field can be the handle printed again ("SAMPLEPAYEE30 <ref>"), which is
                // no better a name than the handle's own, cleaned of its digits.
                boolean repeatsHandle = after != null && fromHandle != null
                        && after.replaceAll("[^a-z]", "").equals(fromHandle.replace(" ", ""));
                if (after != null && !repeatsHandle && after.replace(" ", "").length() >= 3) {
                    return java.util.Optional.of(after);
                }
                return java.util.Optional.ofNullable(fromHandle);
            }
            if (isNotAName(field)) continue;
            // A reference, phone or account number has no letters, so nameOf returns null for it. A
            // name field that also carries a code ("SAMPLE PERSON S1234567 CHO") keeps its words.
            String name = nameOf(field);
            if (name != null) return java.util.Optional.of(name);
        }
        return java.util.Optional.empty();
    }

    /**
     * The fields of a structured narration, or null when it is not one. Slash layouts are split on
     * "/" only, because a handle can itself contain a hyphen ("samplepayee30-1@okaxis") -- the head
     * before the first "/" may still be hyphenated ("MOB-IMPS-CR/..."). A narration whose part
     * before the first "/" is not a rail head is split on "-" instead: the HDFC layout, whose note
     * can carry a date with slashes in it.
     */
    private static String[] fieldsOf(String cleaned) {
        String[] slash = cleaned.split("/");
        if (slash.length >= 2 && isHead(slash[0])) return slash;
        String[] hyphen = cleaned.split("-");
        if (hyphen.length >= 2 && isHead(hyphen[0])) return hyphen;
        return null;
    }

    /** A field that is never a payee's name: an IFSC (possibly split by a line wrap), a letter-digit
     *  code, or a real bank's code. */
    private static boolean isNotAName(String field) {
        return IFSC_FIELD.matcher(field.replace(" ", "")).lookingAt()
                || CODE_FIELD.matcher(field).matches()
                || isBankCode(field)
                || INTENT_BOILERPLATE.matcher(field.trim()).matches();
    }

    /** The note a UPI intent payment carries in place of a payee ("Pay for Intent", measured on a
     *  real CBI statement). */
    static final Pattern INTENT_BOILERPLATE = Pattern.compile("(?i)pay\\s+for\\s+intent");

    /** A real bank's four-letter IFSC code printed as a field of its own ("YESB", "UTIB"). Only the
     *  registry's codes count: a short all-capitals payee ("JIO", "LIC") is a name. */
    private static boolean isBankCode(String field) {
        return BANK_CODES.contains(field.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private static final Set<String> BANK_CODES = BankRegistry.all().stream()
            .map(BankRegistry.BankInfo::ifscPrefix)
            .filter(java.util.Objects::nonNull)
            .map(code -> code.toUpperCase(java.util.Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** True when every word of the first field is a rail, direction or channel word, including a
     *  rail with a bank's short suffix ("UPIAB", "UPIAR"). */
    private static boolean isHead(String field) {
        String n = normalize(field);
        if (n.isEmpty()) return false;
        for (String word : n.split(" ")) {
            if (!HEAD_WORDS.contains(word) && !word.matches("upi[a-z]{1,3}")) return false;
        }
        return true;
    }

    /** A field's words, minus filler, at most four -- the same length the name always had. A
     *  payment app's own name is dropped when a name remains beside it ("Payment from PhonePe_NAME"
     *  names NAME), and kept when it is the only name (a refund from the app itself). Null when
     *  nothing with letters remains. */
    private static String nameOf(String field) {
        List<String> words = new java.util.ArrayList<>();
        boolean leading = true;
        for (String word : normalize(field).split(" ")) {
            // Filler is stripped only from the front of a field ("UPI_NAME", "Payment from ..."):
            // inside a name a word such as "DR" is part of it.
            if (leading && (FILLER_TOKENS.contains(word) || PaymentRailTokens.isRailToken(word))) continue;
            // A word carrying a run of four or more digits is a code -- the same rule extractMerchant
            // applies -- while a brand with a few digits ("one97") stays.
            if (word.length() < 2 || !word.matches(".*[a-z].*") || word.matches(".*\\d{4,}.*")) continue;
            leading = false;
            words.add(word);
        }
        List<String> named = words.stream().filter(w -> !PAYMENT_APP_HANDLE_WORDS.contains(w)).toList();
        List<String> chosen = named.isEmpty() ? words : named;
        if (chosen.isEmpty()) return null;
        return String.join(" ", chosen.subList(0, Math.min(4, chosen.size())));
    }

    /**
     * Whether {@code word} (already normalised) is a payment app's name or handle suffix, which
     * names how the money moved and never who received it. MerchantNormalizationEngine uses this to
     * refuse a grouping key made of nothing else.
     */
    public static boolean isPaymentAppWord(String word) {
        return word != null && PAYMENT_APP_HANDLE_WORDS.contains(word);
    }

    /** Payment apps whose own name, or QR-code prefix, starts a handle that names no payee. */
    private static final Set<String> PAYMENT_APP_HANDLE_WORDS = Set.of(
            "paytm", "paytmqr", "bharatpe", "gpay", "phonepe", "phonepemerchant", "razorpay", "payu",
            "cashfree", "billdesk", "upi", "ybl", "axl", "ibl");

    /**
     * The name part of a UPI handle. Used only when no field names the payee (Bank of Baroda prints
     * only the handle). A line wrap can put a space inside the handle ("samplepoun d@okicici"), so
     * the part before "@" is rejoined first. Each dot- or underscore-separated piece keeps its
     * leading letters ("samplestore27" and "sample035store" are "samplestore" and "sample"). A piece
     * that is a payment app's own word, or whose leading letters are fewer than three (a generated
     * code such as "s70k13"), names nobody. Null when no piece of three or
     * more letters remains, which covers a phone number or a code on its own.
     */
    /** A handle piece's leading letters. A generated code ("s70k13", "q1a2b3") leads with fewer than
     *  three, so the length check below rejects it; a payment app's own prefix is rejected by name. */
    private static final Pattern HANDLE_PIECE = Pattern.compile("^([a-z]+)");

    private static String handleName(String field) {
        String local = field.substring(0, field.indexOf('@')).replaceAll("\\s+", "").toLowerCase();
        StringBuilder sb = new StringBuilder();
        int pieces = 0;
        for (String piece : local.split("[._]+")) {
            java.util.regex.Matcher m = HANDLE_PIECE.matcher(piece);
            if (!m.lookingAt()) continue; // empty, or digits first
            String letters = m.group(1);
            if (letters.length() < 3 || PAYMENT_APP_HANDLE_WORDS.contains(letters)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(letters);
            if (++pieces == 2) break;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // Compiled once at class-load time rather than per suggestCategory() call -- this runs once
    // per CSV row during statement import, so recompiling the same ~90 patterns on every row
    // would be pure waste.
    //
    // Word-boundary matching (not String.contains) is required here: naive substring matching
    // means "rent" matches inside "current" (a phrase that shows up in nearly every Indian bank
    // statement line, e.g. "UPI-CURRENT A/C"), and "ola" (Transport) matches inside "cola" (as
    // in a Coca-Cola purchase on a grocery/dining line). Both are real, evidenced false-positive
    // risks, not theoretical -- fixed once, systemically, for every keyword at once rather than
    // patched keyword-by-keyword.
    // Reconciliation benchmark, investment-transfer word-boundary finding (docs/proposals/
    // reconciliation-benchmark/corpus-frequency-analysis.md, "Finding F"): a broker's aggregator ID
    // gets fused into one token by some payment gateways with no separating character at all --
    // e.g. "UPI-ICCLGROWWPAY-<ref>-BSE", where "groww" is preceded by "l" and followed by "p" with
    // no boundary on either side. Measured directly against this project's own real corpus: 8 of 19
    // real transaction lines mentioning "Groww" carried it ONLY in this fused form -- 42%, not a
    // theoretical edge case.
    //
    // This is deliberately NOT a blanket loosening of word-boundary matching -- the comment on
    // RULE_PATTERNS above exists precisely because that already burned this codebase twice ("rent"
    // inside "current", "ola" inside "cola"). The three brand names below are exempted individually,
    // and only because each is long and phonetically distinctive enough that an accidental
    // substring collision inside an unrelated English or Indian-banking-narration word is not a
    // realistic risk the way it would be for a short or generic keyword. Every other Investments
    // keyword ("sip", "nps", "ppf", "mutualfunds", "demat", "nse mf") stays word-boundary-matched:
    // "sip" in particular is exactly the short, common-substring case ("gossip", "sipping") this
    // exemption must never be widened to cover without the same kind of real-corpus evidence
    // gathered for these three first.
    // "punemetro" and "airtelprepaid" (2026-10-03): an operator's UPI id, which the bank cuts at a
    // length that varies with the layout ("...prepaidUP", "...ccadri"), so no word boundary follows
    // the brand. Both are long and specific enough that no English or Indian name contains them.
    private static final Set<String> FUSION_TOLERANT_KEYWORDS = Set.of("groww", "zerodha", "upstox", "punemetro", "airtelprepaid");

    private static final Map<String, List<Pattern>> RULE_PATTERNS = new LinkedHashMap<>();
    static {
        for (var entry : RULES.entrySet()) {
            List<Pattern> patterns = entry.getValue().stream()
                    .map(w -> FUSION_TOLERANT_KEYWORDS.contains(w)
                            ? Pattern.compile(Pattern.quote(w))
                            : Pattern.compile(WORD_BOUNDARY + Pattern.quote(w) + WORD_BOUNDARY))
                    .toList();
            RULE_PATTERNS.put(entry.getKey(), patterns);
        }
    }

    /** A UPI handle: '@' and the letters, digits and dots after it -- the same span
     *  RuleEngineService.containsOutsideUpiHandle skips for GLOBAL rules. */
    private static final Pattern UPI_HANDLE = Pattern.compile("@[\\p{L}\\p{N}.]+");

    /** Returns a rule-based category guess, or "Other" if nothing matches. Callers should check
     *  a per-user learned-mapping table (MerchantCategoryMap) BEFORE falling back to this.
     *
     *  <p>UPI handles are removed before matching. A handle names the payment app or bank, never
     *  the merchant, and normalize() turns '@' into a space -- so a shop paid through a handle
     *  named exactly 'airtel' or 'jio' read as the word "airtel" and was filed under Utilities. */
    public static String suggestCategory(String description) {
        String norm = normalize(description == null ? null : UPI_HANDLE.matcher(description).replaceAll(" "));
        for (var entry : RULE_PATTERNS.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(norm).find()) return entry.getKey();
            }
        }
        return "Other";
    }
}
