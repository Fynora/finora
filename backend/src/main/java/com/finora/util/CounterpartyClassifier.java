package com.finora.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides {@link CounterpartyType} from a narration alone -- no merchant lookup, no learned state,
 * no direction.
 *
 * <h2>Why this exists as its own layer</h2>
 *
 * <p>Measured on the real 29-statement corpus, of the 1,098 rows that still fell through to "Other"
 * after structural person detection shipped, <b>628 (57.2%) carried conclusive evidence of a
 * business counterparty</b> -- 543 a merchant-acquiring rail marker, a further 85 a corporate suffix
 * -- and every one of them was filed as "Other" anyway. The evidence was already being computed;
 * {@link PersonToPersonTransferDetector} used it only to VETO a person classification and then
 * discarded it. This class is that discarded signal, kept.
 *
 * <h2>Order is the design</h2>
 *
 * <p>The checks run most-conclusive first, and the order is not cosmetic:
 *
 * <ol>
 *   <li><b>Financial institution before business.</b> The business-token vocabulary deliberately
 *       contains BANK, FINANCE, INSURANCE, NBFC and AMC -- correct for vetoing a person, wrong as a
 *       final type. Checking FI first stops every bank charge and interest credit typing as a
 *       generic BUSINESS.</li>
 *   <li><b>Government before business</b>, for the same reason: a tax body is not a merchant, and
 *       several government narrations carry business-shaped tokens.</li>
 *   <li><b>Acquirer rail before corporate suffix.</b> A rail marker is structural evidence about how
 *       the money settled; a suffix is evidence about how a payee spells their name. When they
 *       disagree the rail is right, because a merchant QR cannot be collected on by an individual.</li>
 *   <li><b>Person last of the positive answers.</b> The detector's own known limitation is that a
 *       suffix-less 2-4 word brand is indistinguishable from a person's name, so anything with a
 *       business or institutional signal must be taken off the table before it is consulted.</li>
 * </ol>
 *
 * <p>Everything else is {@link CounterpartyType#UNKNOWN}. That is a real answer here, not a
 * failure: roughly 470 corpus rows carry no marker, no suffix and no name shape, and claiming a
 * type for them would be the confident-wrong-answer this codebase treats as worse than an honest
 * unknown.
 */
public final class CounterpartyClassifier {

    private CounterpartyClassifier() {}

    /**
     * Which revision of the rules above produced a stored answer.
     *
     * <p>Persisted per row as {@code transactions.counterparty_classifier_version} (V143) so that
     * "the classifier has never run here" and "the classifier ran and found nothing" stay tellable
     * apart -- V142's {@code NOT NULL DEFAULT 'UNKNOWN'} collapses them, and they are not the same
     * fact. {@code UNKNOWN} is a real answer for roughly a fifth of real rows, so a row that merely
     * predates the backfill must not be mistaken for one the classifier has already given up on.
     *
     * <p><b>Bumping this schedules a re-type of every row below it.</b> That is the intended
     * mechanism, not a side effect: these rules changed three times in the week they were written
     * (#790, #794, #815), each time recognising counterparties the previous revision could not, and
     * without a version there is no way to ask "which rows were typed by the old vocabulary".
     * CounterpartyBackfillSweepService drains the resulting backlog in bounded batches.
     *
     * <p>Bump this when a change makes the classifier answer DIFFERENTLY for some input -- a new
     * pattern, a reordered check, a widened vocabulary. Do not bump it for a comment, a rename or a
     * refactor that provably cannot change an answer.
     *
     * <p>Safe to re-type today only because nothing but this classifier ever writes those columns.
     * The first feature that lets a PERSON correct a counterparty must add its own exclusion to
     * {@code TransactionRepository.findRowsNeedingCounterpartyTyping}, exactly as
     * {@code category_manually_set} guards the category columns -- a version comparison alone will
     * not protect a human's answer.
     */
    // 3: PersonToPersonTransferDetector reads the fixed name slot of slash-delimited UPI narrations
    //    (UPI/CR/<ref>/<name>/<bank>/..., UPIAB/..., "..._<name>" tails) -- measured on the corpus,
    //    52 rows UNKNOWN -> PERSON and one PERSON -> UNKNOWN (a "GOOGLE IN" payee), every flip read.
    // 4: the counterparty KEY changed (CounterpartyIdentity): a masked VPA is a weak "masked:" key, a
    //    payment app's name is never the sender, and a reference no longer takes the payee's name
    //    with it. Bumped so the backfill sweep re-keys stored rows; sender rules move with them.
    // 5: PersonToPersonTransferDetector treats the DC0099 merchant branch code as a merchant-acquiring
    //    rail, and reads only the payee slot of HDFC's UPI layout, never its free-text remark --
    //    measured on the corpus, 3 rows PERSON -> BUSINESS and 1 UNKNOWN -> BUSINESS (all merchants),
    //    7 UNKNOWN -> PERSON (all people), and 35 already-BUSINESS rows stop reading as a personal
    //    transfer. Then, in the same unreleased revision: the passport portal and the exam body join
    //    GOVERNMENT (6 rows), a two-word cafe chain joins the merchant vocabulary (7 rows PERSON ->
    //    BUSINESS), and a known merchant in any segment is no longer read as a name (16 more
    //    already-BUSINESS rows). Then: every check reads only the counterparty's part of the
    //    narration (a remark and a printed bank branch are not evidence), and the repeated-reference
    //    layout reads only its payee slot -- 12 rows to PERSON from BUSINESS, FINANCIAL_INSTITUTION or
    //    UNKNOWN (all people), 4 FINANCIAL_INSTITUTION -> BUSINESS (all businesses), no row leaves
    //    PERSON. Last, a "CO" inside a person's name reads as "care of", not "& Co" (1 row BUSINESS
    //    -> PERSON; the three companies ending in CO are unchanged), and "resort"/"shopee" stop a
    //    name without vetoing a person (a resort and a shop, typed BUSINESS a step earlier, read
    //    UNKNOWN). Every flip read.
    // 6: a remark's strong signals (bank mechanism, government body, merchant rail) count when the
    //    payee's own words say nothing, never over a person; a web-domain handle's ".co." is not
    //    "& Co". No corpus row changes; both are pinned by constructed tests.
    // 7: the counterparty KEY changed again (CounterpartyIdentity): Kotak's "SentIMPS<ref><payee>/"
    //    is keyed on the payee instead of the free-text note, a VPA local part split by a line wrap
    //    is rejoined, and "Pay for Intent" is no key. The same Kotak layout is typed from its payee
    //    slot, never its free-text note. Measured on the corpus: 37 keys change and 13 rows move
    //    UNKNOWN -> PERSON (one payee, whose 14th row a name-like note had already typed PERSON);
    //    every one read.
    // 8: the counterparty KEY changed again (CounterpartyIdentity, 2026-10-02): the id slot of the
    //    standard "UPI/<DR|CR>/<ref>/<name>/<bank>/<id>" layout is read even when cut before the "@",
    //    a line wrap anywhere inside an id is rejoined (not only a scrap of three characters), a
    //    linked-account "-1" suffix is dropped, and a hyphen inside a "/"-separated id is kept.
    //    Measured on the corpus (1,936 rows): 182 keys change (101 name -> id, 75 fragment -> whole
    //    id, 4 none -> id, 2 name -> masked), no type changes; every distinct change read. 13 keys
    //    now join rows of one person or shop that had two keys; none joins two different payees.
    // 9: shops a bank prints like people (2026-10-02). In "UPI/<DR|CR>/<ref>/<name>/<bank>/<id>" some
    //    banks cut the name to eight characters and the id before its "@", so a metro operator, an
    //    app store and a phone company read as two-word names. A UPI id that begins with a known
    //    merchant's name is now that merchant's, and three merchant id families (two payment
    //    brands' numbered ids, a card-machine provider) and one more merchant pseudo-branch count as
    //    a merchant rail. Measured on the corpus (1,936 rows): 2 rows PERSON -> BUSINESS (one shop
    //    paid under its owner's full name), no other change; on a tester's statement the seven
    //    payees it typed PERSON read BUSINESS. No key changes.
    // 10: the counterparty KEY changed (CounterpartyIdentity, 2026-10-03): an id in the standard
    //    layout's id slot printed with no "@" is the weak "cut:" key instead of "vpa:" -- the bank cut
    //    it, and the start of one payment brand's id is shared by its shops. Measured on the corpus
    //    (1,936 rows): the key changes only on that bank's 17 cut rows (vpa: -> cut:, same text), no
    //    type changes. Bumped so the backfill sweep re-keys stored rows.
    // 11: new merchant words in CategoryRules (2026-10-03), which MerchantIdentityLookup also reads,
    //    so a payments app's bill-payment id and an office suite's id now name a merchant. Measured on
    //    the corpus (1,936 rows): 2 rows UNKNOWN -> BUSINESS, no other change, no key changes; on a
    //    tester's statements 5 more rows, the same two ids. Bumped so the backfill re-types stored rows.
    // 12: a payer's note in the standard slash layout (2026-10-05). "UPI/<DR|CR>/<ref>/<name>/<bank>/
    //    <id>/<note>" had its note read as the payee's own words, so a friend who wrote "cashback",
    //    "interest" or "reward" was typed FINANCIAL_INSTITUTION; the note is now set aside as the
    //    hyphen layout's already was, and still speaks when the payee's words say nothing. Measured on
    //    the corpus (1,969 rows): no type or key changes; 5 rows of shop payments whose app-written note
    //    the person check had read as a name stop counting as a person (already BUSINESS, category
    //    unchanged). Bumped so the backfill re-types stored rows a friend's note made a bank.
    public static final short VERSION = 12;

    /**
     * Bank-generated activity, where the counterparty is the institution itself. These words are
     * about the MECHANISM (interest posting, a mandate debit, an ATM withdrawal, a charge), which is
     * why they outrank a payee-name signal: there is no payee.
     */
    private static final Pattern FINANCIAL_MECHANISM = Pattern.compile(
            // "int" as a bare token is included because that is how statements actually write
            // interest ("SB INT CREDIT", "INT CR"); the longer spellings alone matched none of it.
            "(?i)\\b(int|intcr|interest|intt|sbint|nach|ach[cd]r|ecs|mandate|atm|wdl|withdrawal"
            // Cashback and reward credits: the counterparty is the card issuer or the bank running
            // the programme, never a merchant and never a person. 18 of the 40 inbound rows in the
            // rail-less residue are these, so they were the largest single group there by count --
            // though near-zero by value, which is why they never surfaced in a value-weighted view.
            + "|cashback|rewards?|reward\\s*points?"
            + "|chrg|chrgs|charges|servicetax|folio|redemption|dividend)\\b");

    /**
     * Institution-shaped payee names. Checked after the mechanism words above.
     *
     * <p>"bank" here is the same word that names the STATEMENT ISSUER in real narrations ("HDFC
     * BANK LIMITED UPI-<payee>-..." -- every row on an HDFC/Kotak/SBI statement carries this
     * prefix). Matched unconditionally, it typed the issuer's own boilerplate as the counterparty
     * for every such row, whoever the payee actually was. {@link #matchesOutsideIssuerPrefix} is
     * what makes that safe -- see its own doc comment.
     */
    private static final Pattern FINANCIAL_ENTITY = Pattern.compile(
            "(?i)\\b(bank|nbfc|amc|broking|securities|insurance|assurance|mutualfunds?"
            + "|mutual\\s+fund|depository|cdsl|nsdl)\\b");

    /**
     * Government and tax bodies. Thinly evidenced (6 of 1,869 corpus rows), and kept narrow for that
     * reason -- every token here is unambiguous in Indian narrations, because a vaguer list would
     * type more rows wrongly than it typed rightly.
     */
    private static final Pattern GOVERNMENT = Pattern.compile(
            "(?i)\\b(gst|gstn|incometax|income\\s+tax|itd|tds|tcs\\s+challan|challan|epfo|epf"
            + "|uidai|cbdt|treasury|municipal|nagar\\s*nigam|panchayat|rto"
            // The passport portal and the civil-services exam body, both paid through a
            // government handle: 4 passport rows (typed PERSON from a 3-word payee name, or
            // UNKNOWN) and 2 exam-fee rows (UNKNOWN) on the corpus.
            + "|passport\\s*seva|passportseva|upsc)\\b");

    /**
     * Whether a government or tax body is named -- exposed so the person check can decline these
     * rows, as it declines a merchant rail, rather than suggesting "Personal Transfer" for a fee
     * paid to the state.
     */
    static boolean namesGovernmentBody(String description) {
        return description != null && GOVERNMENT.matcher(description).find();
    }

    /**
     * Corporate suffixes proper -- narrower than the detector's full trade vocabulary.
     *
     * <p>"ltd"/"limited" are also issuer-boilerplate words (see {@link #FINANCIAL_ENTITY}'s doc
     * comment) -- same {@link #matchesOutsideIssuerPrefix} discount applies here.
     */
    private static final Pattern CORPORATE_SUFFIX = Pattern.compile(
            "(?i)\\b(pvt|private|ltd|limited|llp|inc|corp|corporation|enterprises?|ventures?"
            + "|technologies|solutions|industries|associates|holdings)\\b");

    /**
     * Types the counterparty behind a narration.
     *
     * @param description the raw narration; null or blank yields {@link CounterpartyType#UNKNOWN}
     */
    public static CounterpartyType classify(String description) {
        if (description == null || description.isBlank()) return CounterpartyType.UNKNOWN;

        // Only the counterparty's part of the narration is evidence -- a free-text remark or the bank
        // branch printed after it is not. See PersonToPersonTransferDetector.counterpartyText.
        String text = PersonToPersonTransferDetector.counterpartyText(description);
        int markerStart = PersonToPersonTransferDetector.transferMarkerStart(text);

        if (FINANCIAL_MECHANISM.matcher(text).find()) return CounterpartyType.FINANCIAL_INSTITUTION;
        if (namesGovernmentBody(text)) return CounterpartyType.GOVERNMENT;
        if (matchesOutsideIssuerPrefix(FINANCIAL_ENTITY, text, markerStart)) return CounterpartyType.FINANCIAL_INSTITUTION;

        // Reuses the detector's own marker pattern rather than a second copy -- see
        // PersonToPersonTransferDetector.hasMerchantAcquirerMarker for why that matters.
        if (PersonToPersonTransferDetector.hasMerchantAcquirerMarker(text)) return CounterpartyType.BUSINESS;

        // A named merchant entity is business identity, full stop. Reached through
        // MerchantIdentityLookup rather than CategoryRules so this layer never depends on the
        // categorization engine: "Amazon is a known merchant" is the fact needed here, and "Amazon
        // means Shopping" is emphatically not. 130 corpus rows were recognised as a brand by the
        // category layer while this classifier still answered UNKNOWN -- an incoherent pair of
        // answers about the same row.
        if (MerchantIdentityLookup.namesKnownMerchant(text)) return CounterpartyType.BUSINESS;
        // The same fact read from the payee's UPI id, for banks that cut the name to eight
        // characters and the id before its "@" -- see MerchantIdentityLookup.handleNamesKnownMerchant.
        if (MerchantIdentityLookup.handleNamesKnownMerchant(CounterpartyIdentity.payeeHandle(description))) {
            return CounterpartyType.BUSINESS;
        }

        if (matchesOutsideIssuerPrefix(CORPORATE_SUFFIX, text, markerStart)) return CounterpartyType.BUSINESS;

        if (PersonToPersonTransferDetector.isNamedIndividualTransfer(description)) return CounterpartyType.PERSON;

        // A trade word with no rail marker and no corporate suffix -- "MEDICAL", "SWEETS",
        // "TRAVELS". Weaker evidence than the two business checks above, so it sits BELOW the person
        // check rather than above it. That placement costs nothing: the detector already vetoes on
        // these same tokens, so any row reaching this line carrying one is a row the person check
        // has itself just declined to claim.
        if (PersonToPersonTransferDetector.hasBusinessToken(text)) return CounterpartyType.BUSINESS;

        // The payee's own words said nothing. Only now may the remark speak, and only in its strong
        // forms -- never a trade word or a bank name, which a payer can write about anything. It is
        // never reached for a payee that reads as a person (that returned PERSON above), so a friend
        // cannot be made a bank or a business by what was typed in the remark.
        String remark = PersonToPersonTransferDetector.remarkText(description);
        if (!remark.isBlank()) {
            if (FINANCIAL_MECHANISM.matcher(remark).find()) return CounterpartyType.FINANCIAL_INSTITUTION;
            if (namesGovernmentBody(remark)) return CounterpartyType.GOVERNMENT;
            if (PersonToPersonTransferDetector.hasMerchantAcquirerMarker(remark)) return CounterpartyType.BUSINESS;
        }

        return CounterpartyType.UNKNOWN;
    }

    /**
     * Whether {@code pattern} matches {@code description} somewhere other than the statement
     * issuer's own name preceding the transfer marker.
     *
     * <p>Real narrations prefix EVERY row with the statement owner's own institution ("HDFC BANK
     * LIMITED UPI-<payee>-...", "KOTAK MAHINDRA BANK LIMITED NEFT-...", "STATE BANK OF INDIA
     * UPI-..." -- see {@code PersonToPersonTransferDetector.ISSUER_NAME_TOKENS}). Matched
     * unconditionally, "bank" (in {@link #FINANCIAL_ENTITY}) and "limited"/"ltd" (in
     * {@link #CORPORATE_SUFFIX}) fire on that boilerplate and type the issuer as the counterparty
     * regardless of who the actual payee is -- confirmed to flip a real person's name to
     * FINANCIAL_INSTITUTION on an HDFC-issued statement format with no relation to the payee.
     *
     * <p>Only text BEFORE the marker gets this discount, and only for the three issuer-name words
     * -- exactly {@code PersonToPersonTransferDetector.containsBusinessSignal}'s own scoping,
     * reused here via {@code isIssuerNameToken}/{@code transferMarkerStart} rather than a second
     * copy, so the two classes cannot drift apart on what counts as issuer boilerplate. A match
     * after the marker, or a match before it that is NOT one of those three words, still counts --
     * an actual institution or business named as the counterparty is not boilerplate.
     *
     * @param markerStart index of the first transfer-protocol marker, or -1 if none is present (in
     *                    which case every match counts, as there is no boilerplate region to
     *                    discount)
     */
    private static boolean matchesOutsideIssuerPrefix(Pattern pattern, String description, int markerStart) {
        Matcher matcher = pattern.matcher(description);
        while (matcher.find()) {
            if (markerStart < 0 || matcher.start() >= markerStart) return true;
            if (!PersonToPersonTransferDetector.isIssuerNameToken(matcher.group())) return true;
        }
        return false;
    }
}
