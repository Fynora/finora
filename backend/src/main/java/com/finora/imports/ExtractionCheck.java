package com.finora.imports;

import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.dto.ImportDto.UnparseableRow;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one rule that decides whether the engine got anything usable out of a document.
 *
 * <p>Extracted from {@code ImportService} when admin analysis needed it too. Left where it was, the
 * two paths would have disagreed about the same file: a document yielding no transactions is
 * {@code IMPORT_001} for a customer, and the admin tool — which calls the preview generator
 * directly, below the layer that threw — would have recorded it as PARSED with zero rows. An
 * analysis workbench whose verdict differs from what the customer actually got is worse than no
 * workbench, because the engineer investigating a complaint would be looking at a different
 * outcome than the one being complained about.
 */
final class ExtractionCheck {

    private ExtractionCheck() {
    }

    /**
     * The whole-document form: throws when NO section of a document staged a transaction.
     *
     * <p>P-002 Fix 1. Both callers used to ask this question only of documents that located a
     * single section, and both said so in a comment ("more than one detected section means the
     * engine plainly found something"). That reading is wrong on real statements: a credit-card
     * statement whose fee schedule and MITC paragraphs are each mistaken for a table header
     * produces eight located sections and not one transaction, and {@link
     * StagedAccountSectionFilter} passes every one of them through precisely because none has rows
     * -- it defers the verdict to this check. Gated on section count, this check never saw the
     * document, and the user was offered eight empty accounts to confirm. The number of sections
     * says how the page was cut up; it says nothing about whether anything was read.
     *
     * <p>Summed across sections rather than asked per section, deliberately: one empty section
     * inside a document that parsed elsewhere is a non-account (the filter's job, and it already
     * does it), whereas an empty document is a failed extraction (this check's job). The recovered
     * line count quoted in the message is whole-document for the same reason.
     *
     * <p>For a one-section document this is exactly the argument the single-section call site used
     * to build by hand, so that long-standing rejection keeps its code, its message and its count.
     */
    static void rejectIfNothingWasExtracted(List<StagedAccountSection> sections, DocumentContext ctx) {
        rejectIfNothingWasExtracted(wholeDocumentView(sections), ctx);
    }

    /**
     * Every section's rows and recovered text as one {@link StagingResponse}, built only to be read
     * by the check above and then dropped -- nothing receives it.
     *
     * <p>{@code detectedAccount} is null on purpose. A document's sections can have detected
     * different accounts, and choosing one of them here would be inventing an answer to a question
     * nobody asked; the check reads only {@code rows()} and the SIZE of {@code unparseableRows()}.
     */
    private static StagingResponse wholeDocumentView(List<StagedAccountSection> sections) {
        List<StagedRow> rows = new ArrayList<>();
        List<UnparseableRow> recovered = new ArrayList<>();
        int totalParsed = 0;
        int flaggedDuplicates = 0;
        for (StagedAccountSection section : sections) {
            if (section.rows() != null) rows.addAll(section.rows());
            if (section.unparseableRows() != null) recovered.addAll(section.unparseableRows());
            totalParsed += section.totalParsed();
            flaggedDuplicates += section.flaggedDuplicates();
        }
        return new StagingResponse(rows, totalParsed, flaggedDuplicates, null, recovered);
    }

    /**
     * Throws when a document produced no transactions at all.
     *
     * <p>Distinguishes the two ways that happens, because they need different fixes: no table
     * found anywhere ({@code IMPORT_NO_HEADER_DETECTED}) is a layout the engine does not
     * recognise, whereas a table located but unreadable ({@code IMPORT_NO_TRANSACTIONS_FOUND}) is
     * a layout it recognises and cannot parse.
     */
    static void rejectIfNothingWasExtracted(StagingResponse staged, DocumentContext ctx) {
        if (!staged.rows().isEmpty()) return;

        // Checked first, because it is the most specific thing knowable and the only one of the
        // three that is certain. A document with no extractable text is not a layout the engine
        // failed to recognise -- there was nothing to lay out. Told it "could not find a
        // transaction table", a user looks for a problem with their statement's format; the actual
        // answer is that Finora cannot read images yet, which is ours and not theirs.
        //
        // Before this, a scanned PDF and a genuinely empty one were indistinguishable to the user:
        // both produced zero rows, zero recovered lines and the same message. The one fact the
        // engine knew for certain never reached them.
        //
        // The message states the observation and the limitation, and claims neither that the file
        // is a bank statement nor that recognition would succeed on it. Those are not established
        // by an absence of text.
        if (ctx != null && ctx.hasNoExtractableText()) {
            throw new ApiException(ErrorCode.IMPORT_SCANNED_OCR_REQUIRED,
                    "This PDF has no text in it -- every page is an image, so there was nothing for "
                            + "Finora to read. Statements exported directly from your bank's website "
                            + "or app usually contain text and import correctly.");
        }

        // Checked before the generic locatedATable branch below, and for the same reason
        // hasNoExtractableText() is checked first: it is the most specific thing knowable. A
        // statement that states its own zero activity did not defeat extraction -- there was
        // nothing here to extract -- and IMPORT_007's "could not read any transactions" is simply
        // false about the cause. See ExplicitZeroActivityDetector's own doc comment for the
        // evidence and IMPORT_NO_ACTIVITY_IN_PERIOD's for why this is a separate code rather than
        // a reworded IMPORT_007.
        int recoveredLines = staged.unparseableRows() == null ? 0 : staged.unparseableRows().size();
        if (ctx != null && ctx.explicitZeroActivityDeclared()) {
            // Same recovered-lines suffix the generic branch below appends, and for the same
            // reason: a row declaring the statement's own zero activity does not mean every OTHER
            // row in this section parsed cleanly. A boilerplate/disclaimer row can still land in
            // unparseableRows() alongside it, and that diagnostic must not silently vanish just
            // because this branch's cause is different from IMPORT_007's.
            throw new ApiException(ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD,
                    ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD.defaultMessage()
                            + (recoveredLines > 0
                            ? " " + recoveredLines + " line(s) of text were recovered and recorded for review."
                            : ""));
        }

        int transactionShaped = countTransactionShaped(staged.unparseableRows());
        boolean locatedATable = ctx != null && ctx.buildMetadata().tables() > 0;
        ErrorCode code = locatedATable ? ErrorCode.IMPORT_NO_TRANSACTIONS_FOUND : ErrorCode.IMPORT_NO_HEADER_DETECTED;
        throw new ApiException(code.defaultStatus(), code,
                (locatedATable
                        ? "Finora found a transaction table in this statement but could not read any transactions from it."
                        : "Finora could not find a transaction table anywhere in this statement.")
                        + (recoveredLines > 0
                        ? " " + recoveredLines + " line(s) of text were recovered and recorded for review."
                        : ""),
                // ImportJobWorker.carriesRecoveredEvidence reads looksLikeAStatement to tell "the
                // wrong file" (an invoice, a bill, a résumé, a T&C page -- FAIL_FAST, the user's own
                // fix is in the message) apart from "a statement in a layout the engine could not
                // anchor into a table" (a parser gap, worth the triage queue). recoveredLines cannot
                // make that distinction: it counts every line the parser set aside, so it is
                // non-zero for any document with text in it. Kept unchanged, for the message and
                // for existing readers; transactionShapedLines is the count that means something.
                Map.of("recoveredLines", recoveredLines,
                        "transactionShapedLines", transactionShaped,
                        "looksLikeAStatement", transactionShaped >= MIN_TRANSACTION_SHAPED_LINES));
    }

    /**
     * How many recovered rows must carry both a date and a money amount before the document is
     * treated as a statement the engine could not read, rather than the wrong file. One row is not
     * a table; two is the smallest thing that can be called one. Deliberately low: a real statement
     * in an unread layout recovers dozens, so the threshold only has to clear an incidental pair.
     */
    static final int MIN_TRANSACTION_SHAPED_LINES = 2;

    private static final java.util.regex.Pattern DATE_SHAPE = java.util.regex.Pattern.compile(
            "(?<![\\d/.\\-])(?:\\d{1,2}[/.\\-]\\d{1,2}[/.\\-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2}"
                    // Day + month name, the year optional: some statements (HSBC's) print none at all.
                    + "|\\d{1,2}[ \\-](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*(?:[ \\-,]*\\d{2,4})?)"
                    + "(?![\\d/])",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** A money amount as banks print it: at least two decimals' worth of precision, optional
     *  thousands separators. Neither side may touch another digit or a dot, so the fragments of a
     *  dot-separated date ("12.03.2026") are not mistaken for an amount. */
    private static final java.util.regex.Pattern AMOUNT_SHAPE = java.util.regex.Pattern.compile(
            "(?<![\\d.,])(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)\\.\\d{2}(?![\\d.])");

    /**
     * Recovered rows that read like a transaction: a date and an amount, together in one row. Judged
     * on all of a row's cells joined, because a PDF line arrives as one string and a CSV row as one
     * cell per column, and either way it is the combination that means something.
     */
    static int countTransactionShaped(List<UnparseableRow> recovered) {
        if (recovered == null) return 0;
        int count = 0;
        for (UnparseableRow row : recovered) {
            if (row == null || row.raw() == null) continue;
            String text = String.join(" ", row.raw().values().stream()
                    .filter(java.util.Objects::nonNull).toList());
            if (DATE_SHAPE.matcher(text).find() && AMOUNT_SHAPE.matcher(text).find()) count++;
        }
        return count;
    }
}
