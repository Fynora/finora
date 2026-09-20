package com.finora.imports.pdf;

import java.util.List;

/**
 * Content a PDF parser had to throw away because the bytes that described it were destroyed.
 *
 * <p>PDFBox is lenient, and it fails quietly in two distinct ways, both of which make extraction
 * "succeed" with fewer rows and leave nothing downstream able to tell:
 * <ul>
 *   <li>a text operator's operands are gone -- it logs the failure and carries on with the rest of the
 *       page ({@code failedTextOperators});</li>
 *   <li>a compressed content stream is corrupt or ends early -- it stops decoding at the damage and
 *       returns what it had, with no operator ever failing ({@code corruptContentStreams}). Measured on a
 *       real statement: a page decoded to 2,790 of 14,402 bytes, and the last transaction and the printed
 *       summary that would have exposed the loss went with it.</li>
 * </ul>
 * This is the parser's own record that it happened -- the only place that knows -- so verification can say
 * so instead of rating a partial import CLEAN.
 *
 * @param failedTextOperators  how many text-positioning/showing operators failed to process
 * @param corruptContentStreams how many page content streams had corrupt or incomplete compressed data
 * @param pages                the 1-based pages affected by either, ascending and distinct
 */
public record ContentDamage(int failedTextOperators, int corruptContentStreams, List<Integer> pages) {

    public static final ContentDamage NONE = new ContentDamage(0, 0, List.of());

    public ContentDamage {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public boolean isDamaged() {
        return failedTextOperators > 0 || corruptContentStreams > 0;
    }
}
