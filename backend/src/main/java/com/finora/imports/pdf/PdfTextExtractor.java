package com.finora.imports.pdf;

import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mechanical PDF text extraction: given the raw file bytes, returns every text run PDFBox finds,
 * each with its page-relative x/y coordinates -- nothing more. Deliberately does NOT use
 * PDFTextStripper's default plain-text output ({@code stripper.getText(document)}); that API
 * discards column position entirely, which is the exact information needed to tell a debit
 * amount from a credit amount in a real bank statement table (verified empirically before
 * writing this class -- see this package's own doc comment). Overriding
 * {@code writeString(String, List<TextPosition>)} instead is what keeps that position
 * information available to {@link PdfTableLocator}.
 */
@Component
public class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    // SEC-02 (docs/quality/bug-reports/2026-08-19-security-review-findings.md) default, mirrored
    // in application.yml's app.import.pdf.max-pages -- keeping both in sync is why the property
    // string embeds this constant rather than repeating the number.
    private static final int DEFAULT_MAX_PAGES = 500;

    private final int maxPages;

    /** Test/manual-construction convenience -- every one of this class's ~40 existing call sites
     *  predates the page-count ceiling below and has no reason to care what it's set to. */
    public PdfTextExtractor() {
        this(DEFAULT_MAX_PAGES);
    }

    /** {@code @Autowired} because there are now two constructors and Spring will not guess between
     *  them (see PdfPreviewGenerator's own constructor for the identical situation and reasoning). */
    @Autowired
    public PdfTextExtractor(@Value("${app.import.pdf.max-pages:" + DEFAULT_MAX_PAGES + "}") int maxPages) {
        this.maxPages = maxPages;
    }

    /** Unprotected documents, and every caller that predates password support. */
    public List<PositionedText> extract(byte[] fileBytes) throws IOException {
        return extract(fileBytes, null);
    }

    /**
     * @param password the document open password, or null/blank when none was supplied.
     *
     * <p>Passing a password to a document that is NOT encrypted is harmless -- PDFBox ignores it
     * and opens normally, so callers never have to work out whether a file needs one before
     * deciding what to send. Asserted rather than assumed, so it cannot quietly stop being true
     * across a library upgrade: see PasswordProtectedPdfTest#passwordOnAnUnencryptedDocumentIsHarmless.
     *
     * <p>This is the only place that learns a document is encrypted, so it is where the two
     * outcomes are told apart. PDFBox cannot distinguish them itself: opening an encrypted PDF
     * with NO password and with the WRONG password both raise InvalidPasswordException carrying
     * the identical message. What separates them is whether this call was GIVEN a password, which
     * is knowledge only this method has.
     *
     * <p>Without the translation below, both cases escaped as a bare IOException with no matching
     * ErrorCode and surfaced to the user as a generic 500 -- "Could not read that statement", with
     * nothing to suggest a password was the problem or that entering one would fix it.
     */
    public List<PositionedText> extract(byte[] fileBytes, String password) throws IOException {
        return extractWithDiagnostics(fileBytes, password).runs();
    }

    /** The text runs, plus what the parser had to throw away to produce them. */
    public record Extraction(List<PositionedText> runs, ContentDamage damage) {}

    /**
     * Text operators whose failure loses text or the position it sits at. A failure in a graphics
     * operator (a colour, a path) costs no transactions, so only these count as damage.
     */
    private static final java.util.Set<String> TEXT_OPERATORS =
            java.util.Set.of("Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"", "Tf");

    public Extraction extractWithDiagnostics(byte[] fileBytes, String password) throws IOException {
        boolean passwordSupplied = password != null && !password.isBlank();
        List<PositionedText> result = new ArrayList<>();
        int[] failedTextOperators = {0};
        int[] corruptStreams = {0};
        java.util.SortedSet<Integer> damagedPages = new java.util.TreeSet<>();
        // PDFBox treats "" as "no password", which is exactly the previous behaviour.
        try (PDDocument document = loadOrExplain(fileBytes, passwordSupplied ? password : "", passwordSupplied)) {
            requirePageCountWithinLimit(document);
            // Before the text is read: PDFBox will decode a damaged stream as far as it can and say
            // nothing, so the only way to know a page lost content is to ask the compression layer.
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                if (declaredContentsCannotBeFound(document.getPage(i))) {
                    corruptStreams[0]++;
                    damagedPages.add(i + 1);
                }
                java.util.Iterator<org.apache.pdfbox.pdmodel.common.PDStream> streams =
                        document.getPage(i).getContentStreams();
                while (streams != null && streams.hasNext()) {
                    if (isFlateContentStreamCorrupt(streams.next())) {
                        corruptStreams[0]++;
                        damagedPages.add(i + 1);
                    }
                }
            }
            PDFTextStripper stripper = new PDFTextStripper() {
                /**
                 * PDFBox reports an operator it could not process here and then carries on with the
                 * rest of the page, so extraction "succeeds" with less than the page held. Recorded,
                 * then handed to the default handling unchanged (which logs, or rethrows).
                 */
                @Override
                protected void operatorException(org.apache.pdfbox.contentstream.operator.Operator operator,
                                                 List<org.apache.pdfbox.cos.COSBase> operands, IOException e)
                        throws IOException {
                    if (operator != null && TEXT_OPERATORS.contains(operator.getName())) {
                        failedTextOperators[0]++;
                        damagedPages.add(getCurrentPageNo());
                    }
                    super.operatorException(operator, operands, e);
                }

                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                    if (string == null || string.isBlank() || textPositions.isEmpty()) return;
                    // PDFBox's own line-grouping (which decides what one writeString call covers)
                    // is tuned for reading prose, not a table header row -- re-split via
                    // GlyphRunSplitter, whose own doc comment carries the real-document evidence
                    // and the threshold's reasoning. Each TextPosition maps to one Glyph up front,
                    // so the splitting decision itself is testable independent of PDFBox.
                    List<GlyphRunSplitter.Glyph> glyphs = new ArrayList<>(textPositions.size());
                    for (TextPosition tp : textPositions) {
                        glyphs.add(new GlyphRunSplitter.Glyph(tp.getUnicode(), tp.getXDirAdj(),
                                tp.getXDirAdj() + tp.getWidthDirAdj(), tp.getYDirAdj(), tp.getFontSizeInPt()));
                    }
                    for (List<GlyphRunSplitter.Glyph> segment : GlyphRunSplitter.split(glyphs)) {
                        emit(segment);
                    }
                }

                private void emit(List<GlyphRunSplitter.Glyph> segment) {
                    if (segment.isEmpty()) return;
                    // Built from each glyph's OWN character rather than slicing the original
                    // writeString string -- PDFBox's textPositions list can be shorter than that
                    // string (it sometimes inserts a space character with no backing TextPosition
                    // at all), so any index correlation between the two is unreliable. Reading each
                    // TextPosition's own getUnicode() has no such gap.
                    StringBuilder text = new StringBuilder();
                    for (GlyphRunSplitter.Glyph g : segment) text.append(g.text());
                    String value = text.toString();
                    if (value.isBlank()) return;
                    GlyphRunSplitter.Glyph first = segment.get(0);
                    // The run's right edge, from its LAST glyph rather than a sum of widths --
                    // glyph advances include kerning and inter-character spacing that summing
                    // would drop, and the whole point of this measurement is that it be exact
                    // enough to separate two adjacent right-aligned amount columns.
                    GlyphRunSplitter.Glyph last = segment.get(segment.size() - 1);
                    float width = Math.max(0f, last.endX() - first.x());
                    // getCurrentPageNo() is 1-based and reflects whichever page the stripper is
                    // currently walking -- correct even for a multi-page statement, since this
                    // fires once per emitted run as PDFBox processes pages in order.
                    result.add(new PositionedText(value, first.x(), first.y(),
                            getCurrentPageNo() - 1, width));
                }
            };
            stripper.setSortByPosition(true);
            try {
                stripper.getText(document); // return value discarded -- writeString() above is what we actually want
            } catch (IOException e) {
                // PDFBox stopped outright on a page whose content it could not tokenize (a destroyed
                // inline image, say). Reaching here as a raw IOException made the worker treat it as an
                // unrecognised failure: retried, then held "for review". It is a damaged file, the
                // user's to replace -- the same verdict loadOrExplain gives one that fails to open.
                log.warn("Could not read the content of an uploaded PDF -- treating as a damaged file "
                        + "rather than a server fault. PDFBox said: {}", e.getMessage());
                throw new ApiException(ErrorCode.IMPORT_CORRUPT_PDF,
                        "This PDF could not be read -- the file appears to be damaged or incomplete. "
                                + "Downloading it again from your bank usually fixes this.");
            }
        }
        return new Extraction(result, failedTextOperators[0] == 0 && corruptStreams[0] == 0
                ? ContentDamage.NONE
                : new ContentDamage(failedTextOperators[0], corruptStreams[0], new ArrayList<>(damagedPages)));
    }

    /**
     * A page whose dictionary DECLARES {@code /Contents} but whose content stream cannot be resolved --
     * the object it points at is gone or is not a stream. PDFBox then sees an empty page and extraction
     * "succeeds" without it. A page that declares no {@code /Contents} at all (or an empty array) is
     * simply blank, which is legitimate and is not reported.
     */
    static boolean declaredContentsCannotBeFound(org.apache.pdfbox.pdmodel.PDPage page) {
        org.apache.pdfbox.cos.COSDictionary dict = page.getCOSObject();
        if (dict.getItem(org.apache.pdfbox.cos.COSName.CONTENTS) == null) return false;
        org.apache.pdfbox.cos.COSBase resolved = dict.getDictionaryObject(org.apache.pdfbox.cos.COSName.CONTENTS);
        if (resolved instanceof org.apache.pdfbox.cos.COSStream) return false;
        if (resolved instanceof org.apache.pdfbox.cos.COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (!(array.getObject(i) instanceof org.apache.pdfbox.cos.COSStream)) return true;
            }
            return false;
        }
        return true;
    }

    /**
     * Whether a page's compressed content stream is corrupt or ends early -- checked on the compression
     * layer itself, which needs no guessing: a healthy deflate stream inflates to its own end.
     *
     * <p>PDFBox does not surface this. Its Flate filter stops at the corruption, logs, and returns what it
     * decoded, so the page's remaining text is simply absent -- or, when corrupted bytes still happen to
     * parse as deflate, decodes to garbage. Two independent proofs of damage, neither a guess:
     * <ul>
     *   <li>the deflate data is invalid, or ends before its final block;</li>
     *   <li>the data decoded to its end but the Adler-32 checksum the producer wrote no longer matches
     *       what came out -- the decoded content is not what was written. (Measured on the fixture: every
     *       in-stream corruption is one or the other.) A stream with no trailing checksum is not checked.</li>
     * </ul>
     * Inflated in raw mode with the two-byte zlib header skipped, so the checksum is verified here
     * explicitly rather than relied on the JDK to raise.
     *
     * <p>No verdict (false) for anything else: a stream with another or a chained filter, a zlib
     * preset dictionary, or nothing to check. A wrong answer here tells a user their good statement is
     * damaged, so an unrecognised shape is left alone.
     */
    static boolean isFlateContentStreamCorrupt(org.apache.pdfbox.pdmodel.common.PDStream stream) {
        List<org.apache.pdfbox.cos.COSName> filters = stream.getFilters();
        if (filters == null || filters.size() != 1) return false;
        String filter = filters.get(0).getName();
        if (!"FlateDecode".equals(filter) && !"Fl".equals(filter)) return false;
        byte[] raw;
        try (java.io.InputStream in = stream.getCOSObject().createRawInputStream()) {
            raw = in.readAllBytes();
        } catch (IOException e) {
            return true; // The stream's own bytes could not be read back: that is damage.
        }
        if (raw.length < 3) return false;
        int cmf = raw[0] & 0xFF;
        int flg = raw[1] & 0xFF;
        boolean plainZlibHeader = (cmf & 0x0F) == 8 && ((cmf << 8) | flg) % 31 == 0 && (flg & 0x20) == 0;
        if (!plainZlibHeader) return false;
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        java.util.zip.Adler32 decodedChecksum = new java.util.zip.Adler32();
        try {
            inflater.setInput(raw, 2, raw.length - 2);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                decodedChecksum.update(buffer, 0, n);
            }
            if (!inflater.finished()) return true;
            // The trailer is the first four bytes after the deflate data, big-endian.
            int remaining = inflater.getRemaining();
            if (remaining < 4) return false;
            int at = raw.length - remaining;
            // Already implied by remaining >= 4; stated against raw.length so the four reads below are provably in range.
            if (at < 0 || at > raw.length - 4) return false;
            long written = ((raw[at] & 0xFFL) << 24) | ((raw[at + 1] & 0xFFL) << 16)
                    | ((raw[at + 2] & 0xFFL) << 8) | (raw[at + 3] & 0xFFL);
            return written != decodedChecksum.getValue();
        } catch (java.util.zip.DataFormatException e) {
            return true;
        } finally {
            inflater.end();
        }
    }

    /**
     * SEC-02. Checked right after {@link Loader#loadPDF}, which has already parsed the document's
     * object/page graph, and before {@code stripper.getText()} -- the full walk across every
     * page's content stream, which is where an unbounded page count turns into unbounded time and
     * memory. Page count, not file size: the 10MB multipart cap (application.yml) already bounds
     * what was uploaded, but says nothing about what a small, spec-valid, highly compressed PDF
     * expands into once PDFBox decompresses it.
     */
    private void requirePageCountWithinLimit(PDDocument document) {
        int pages = document.getNumberOfPages();
        if (pages > maxPages) {
            log.warn("Rejecting a {}-page PDF -- exceeds the {}-page ceiling checked before full-text extraction",
                    pages, maxPages);
            throw new ApiException(ErrorCode.IMPORT_PDF_TOO_LARGE);
        }
    }

    /**
     * Whether opening this document with no password fails for want of one -- the same open, with
     * the same empty password, that {@link #extract(byte[])} performs, so the answer cannot
     * disagree with what the queue worker will later meet. A document encrypted with an EMPTY user
     * password opens without one and is therefore not "needing a password" here.
     *
     * <p>Answers only that question. A file that fails to load for any other reason (truncated,
     * malformed) returns false: it is not a password problem, and classifying it stays with
     * {@link #loadOrExplain}, which turns it into IMPORT_CORRUPT_PDF. This exists so the
     * asynchronous upload can refuse a locked statement while the user is still looking at the
     * password field -- see {@code ImportJobController.submit}.
     */
    public static boolean needsPassword(java.io.InputStream content) {
        try (PDDocument ignored = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(content), "")) {
            return false;
        } catch (InvalidPasswordException e) {
            return true;
        } catch (IOException | RuntimeException e) {
            // Unchecked as well as checked: PDFBox can throw a runtime exception on hostile or
            // malformed input, and this runs on the upload request thread, where letting one escape
            // would turn a file the worker classifies cleanly (IMPORT_CORRUPT_PDF) into a 500 at
            // the door. Not a password problem, so not this method's to report.
            log.debug("Password pre-check could not open the document; leaving it to the worker: {}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private PDDocument loadOrExplain(byte[] fileBytes, String password, boolean passwordSupplied) throws IOException {
        try {
            return Loader.loadPDF(fileBytes, password);
        } catch (InvalidPasswordException e) {
            // The exception itself carries no user-safe detail, and its message is identical in
            // both branches -- do not pass it through. The supplied password is never included in
            // the message or the cause chain, so it cannot reach a log or an error report.
            throw new ApiException(passwordSupplied
                    ? ErrorCode.IMPORT_PDF_PASSWORD_INVALID
                    : ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED);
        } catch (IOException e) {
            // A structurally broken PDF -- truncated by a failed download, corrupted in transit,
            // or saved by something that produced not-quite-valid output.
            //
            // Found by the e2e suite, which uploaded a deliberately corrupted file and got back
            // INTERNAL_ERROR carrying "Unexpected error: Missing root object specification in
            // trailer." Two things wrong with that, and the same two the sibling branches above
            // already get right.
            //
            // It is a 500 for a problem the server did not have. A malformed upload is the user's
            // to fix, exactly as a locked file is -- which is why IMPORT_008/009 are 422s. Reporting
            // it as a server fault sends the user to support rather than back to their bank's
            // download page, and buries it in whatever alerting watches 5xx rates.
            //
            // And it hands the user a PDFBox internal. "Missing root object specification in
            // trailer" is not something a person can act on, and library internals should not cross
            // this boundary at all. The cause is logged and kept out of the response.
            log.warn("Could not read an uploaded PDF -- treating as a damaged file rather than a "
                    + "server fault. PDFBox said: {}", e.getMessage());
            throw new ApiException(ErrorCode.IMPORT_CORRUPT_PDF,
                    "This PDF could not be read -- the file appears to be damaged or incomplete. "
                            + "Downloading it again from your bank usually fixes this.");
        }
    }
}
