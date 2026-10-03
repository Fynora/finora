package com.finora.imports.pdf.ocr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tesseract, as a candidate.
 *
 * <h2>The whole adapter</h2>
 *
 * This is the one file OCR-3A predicted would be needed to evaluate an engine: rasterise, recognise,
 * convert coordinates, hand back {@link OcrEngine.RecognisedText}. Nothing in the parser or the
 * rest of the pipeline changed to accommodate it, no library dependency entered the build, and the
 * harness it plugs into was written and calibrated before Tesseract was installed. If this file had
 * needed to reach into the parser, the acquisition-strategy claim would have been wrong.
 *
 * <p>Promoted from test to main scope only once OCR-3A/3B's evidence justified deploying it (see
 * {@code TesseractRecogniser}). The binary itself is still an operational dependency of the
 * deployment, not this class: {@link #available()} reports its absence rather than assuming it,
 * exactly as it did under evaluation.
 *
 * <h2>Why the command line rather than a Python binding</h2>
 *
 * {@code tesseract <image> stdout tsv} already emits a word, a bounding box and a confidence per
 * run, which is exactly and only what the contract asks for. pytesseract would add a Python
 * dependency, a virtualenv and a serialisation hop to obtain the same four numbers. Fewer moving
 * parts between the engine and the scorecard also means fewer places for the evaluation to be wrong
 * in a way that flatters the engine.
 *
 * <h2>Coordinates</h2>
 *
 * Tesseract reports pixels from the top-left of the rendered image; PDFBox's
 * {@code getYDirAdj} -- what native {@link com.finora.imports.pdf.PositionedText} carries -- also
 * increases downward. So the conversion is a scale and no flip. The scale is derived from the page's
 * own width in points against the image's width in pixels rather than from the DPI constant,
 * because the renderer rounds to whole pixels and a statement's columns are separated by tens of
 * points; deriving it keeps a half-pixel rounding error from becoming a column error.
 */
public final class TesseractEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractEngine.class);

    /** Word level in Tesseract's TSV. Lower levels describe blocks and lines, not runs. */
    private static final int WORD_LEVEL = 5;

    // CodeQL (java/relative-path-command), 2026-09-04: every ProcessBuilder call here used to pass
    // the bare command name "tesseract", resolved against $PATH fresh by the OS on every single
    // invocation (available() once, run() once per page). If something earlier in the process's
    // PATH ever pointed at an attacker-controlled or otherwise wrong binary, each of those lookups
    // could resolve somewhere different. Resolved once instead, explicitly, by this class's own
    // trusted code -- scanning PATH's directories for an executable file literally named
    // "tesseract" -- and cached, so every subsequent call uses the exact same verified absolute
    // path rather than repeating an OS-level lookup that could vary between calls. A single
    // hardcoded path (e.g. Alpine's /usr/bin/tesseract, confirmed via Dockerfile's `apk add
    // tesseract-ocr`) was considered and rejected: TesseractRunAssemblyTest runs this against a
    // real binary locally too (assumeTrue(available())), where it's typically a Homebrew install
    // at a different path entirely -- hardcoding one location would silently break the other.
    private static final Optional<String> RESOLVED_TESSERACT_PATH = resolveOnPath("tesseract");

    private static Optional<String> resolveOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) return Optional.empty();
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, command);
            if (candidate.isFile() && candidate.canExecute()) {
                return Optional.of(candidate.getAbsolutePath());
            }
        }
        return Optional.empty();
    }

    public TesseractEngine() {}

    @Override
    public String name() {
        return "tesseract";
    }

    /** Whether the binary is on PATH, so a missing engine reports itself rather than failing oddly. */
    public static boolean available() {
        if (RESOLVED_TESSERACT_PATH.isEmpty()) return false;
        try {
            return new ProcessBuilder(RESOLVED_TESSERACT_PATH.get(), "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Override
    public List<RecognisedText> recognise(byte[] pdf, int dpi) throws IOException {
        Path work = Files.createTempDirectory("tesseract-eval-");
        // finally, not just a trailing call: this now runs against real uploaded statements, and a
        // page that fails to render/recognise must not leave its rasterised image -- a real
        // customer document -- sitting in the temp directory permanently. Under test/evaluation
        // scope this cost was a rare, developer-visible inconvenience; in production it is a
        // silent, unbounded disk and privacy liability.
        List<PageReadings> pages = new ArrayList<>();
        try (PDDocument in = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(in);
            for (int page = 0; page < in.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi);
                File png = work.resolve("page-" + page + ".png").toFile();
                ImageIO.write(image, "png", png);

                PDRectangle size = in.getPage(page).getMediaBox();
                float scale = size.getWidth() / image.getWidth();
                List<RecognisedText> standard = parse(run(png, null), page, scale);
                List<RecognisedText> sparse = lowConfidenceShare(standard) > SPARSE_RETRY_LOW_CONFIDENCE_SHARE
                        ? parse(run(png, SPARSE_TEXT_PSM), page, scale)
                        : null;
                pages.add(new PageReadings(standard, sparse));
            }
        } finally {
            deleteRecursively(work);
        }
        return choose(pages);
    }

    /** One page's default reading, and its sparse-text reading when the default looked unreliable
     *  enough to ask for one ({@code null} otherwise). */
    record PageReadings(List<RecognisedText> standard, List<RecognisedText> sparse) {}

    /**
     * Picks one reading per page.
     *
     * <p>Decided across the document, not page by page, because the failure is a property of how the
     * statement was printed and scanned, and every page shares that. Measured on the Union scan: its
     * last page carries four transactions above several non-grid summary tables, which dilutes its
     * low-confidence share until sparse mode is only 1.15x cleaner there -- yet the default reading
     * still merged those four rows into the summary block. Once any page has shown the document needs
     * sparse mode, every retried page takes the sparse reading unless it is actually worse.
     */
    static List<RecognisedText> choose(List<PageReadings> pages) {
        boolean documentNeedsSparse = pages.stream().anyMatch(p -> p.sparse() != null
                && lowConfidenceShare(p.sparse()) * SPARSE_MUST_BE_CLEANER_BY <= lowConfidenceShare(p.standard()));
        List<RecognisedText> runs = new ArrayList<>();
        for (int page = 0; page < pages.size(); page++) {
            PageReadings p = pages.get(page);
            boolean useSparse = documentNeedsSparse && p.sparse() != null
                    && lowConfidenceShare(p.sparse()) <= lowConfidenceShare(p.standard());
            if (p.sparse() != null) {
                log.info("OCR page {}: low-confidence share {} under default segmentation, {} under "
                                + "sparse-text; kept {}", page, String.format("%.3f", lowConfidenceShare(p.standard())),
                        String.format("%.3f", lowConfidenceShare(p.sparse())), useSparse ? "sparse-text" : "default");
            }
            runs.addAll(useSparse ? p.sparse() : p.standard());
        }
        return runs;
    }

    /**
     * Share of a page's words Tesseract scored below {@link #LOW_CONFIDENCE}, above which the page
     * is recognised a second time in sparse-text mode ({@code --psm 11}).
     *
     * <p>Measured on the two real scans available, both rendered at 300 DPI by PDFBox exactly as
     * below. A phone-scanned Union Bank statement -- a gridded table with shaded alternate rows --
     * scored 5.7-27% low-confidence words on each of its nine transaction pages under the default
     * layout analysis. That analysis fused each two-line Particulars cell into one oversized "word"
     * read as noise and merged neighbouring table rows, so a third of the statement's 289
     * transactions never staged. The HSBC scan in the corpus scored 0.2% and 0.5%. 2% sits between
     * the two with room on both sides, and keeps a clean page's cost at one recognition pass.
     *
     * <p>Measured with the tessdata_fast English model the production image pins (see the backend
     * Dockerfile), on Tesseract 5.5.3 locally and on the image's own 5.5.2: every page's share came
     * out identical on both. With the Alpine package's larger model the shares, and the readings,
     * were different -- these thresholds are a property of the model as much as of the documents.
     */
    static final double SPARSE_RETRY_LOW_CONFIDENCE_SHARE = 0.02;

    /**
     * How many times cleaner the sparse-text reading has to be before it replaces the default one.
     *
     * <p>Sparse mode is not better everywhere, which is why it is a fallback rather than the
     * default: forced onto the HSBC scan, it read a date-column prefix into a narration and moved one
     * row's date from 05 to 02 June. Measured: on every Union transaction page the sparse reading was
     * at least 3.9x cleaner (default 5.7% vs sparse 1.4% at the closest); on that statement's own
     * summary page -- no grid, which the default reads fine -- only 1.15x; on both HSBC pages sparse
     * was worse. 3x keeps the default reading wherever the two are comparable.
     */
    static final double SPARSE_MUST_BE_CLEANER_BY = 3.0;

    /** Tesseract confidence (0-1 after {@link #parse}) below which a word counts as unreliable. */
    static final float LOW_CONFIDENCE = 0.30f;

    /** Tesseract's "sparse text" page segmentation: find as much text as possible, in no order. */
    private static final String SPARSE_TEXT_PSM = "11";

    /** Words that reported a confidence and scored below {@link #LOW_CONFIDENCE}, as a share of all
     *  words. Unscored words count in the denominator only -- they are no evidence either way. */
    static double lowConfidenceShare(List<RecognisedText> runs) {
        if (runs.isEmpty()) return 0;
        long low = runs.stream()
                .filter(r -> r.confidence() != null && r.confidence() < LOW_CONFIDENCE)
                .count();
        return (double) low / runs.size();
    }

    /** {@code tesseract <png> stdout tsv} -- one row per recognised element. {@code psm} null keeps
     *  Tesseract's default page segmentation, the exact invocation every page got before the sparse
     *  retry existed. */
    private String run(File png, String psm) throws IOException {
        String tesseract = RESOLVED_TESSERACT_PATH.orElseThrow(
                () -> new IOException("tesseract is not on PATH -- callers must check available() first"));
        List<String> command = new ArrayList<>(List.of(tesseract, png.getAbsolutePath(), "stdout"));
        if (psm != null) command.addAll(List.of("--psm", psm));
        command.add("tsv");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IOException("tesseract exited non-zero for " + png);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while recognising " + png, e);
        }
        return out;
    }

    /**
     * TSV to runs.
     *
     * <p>Columns are {@code level page block par line word left top width height conf text}. Only
     * word-level rows carry text; the rest describe structure this contract deliberately does not
     * accept, since a recogniser's idea of a "line" is not evidence about a statement's rows.
     *
     * <p>Blank text is dropped and a negative confidence is reported as none. Tesseract uses -1 for
     * rows it did not score, and turning that into 0.0 would claim the engine was certain the run
     * was worthless rather than that it said nothing about it.
     */
    private static List<RecognisedText> parse(String tsv, int page, float scale) {
        List<RecognisedText> runs = new ArrayList<>();
        String[] lines = tsv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split("\t", -1);
            if (c.length < 12) continue;
            // CodeQL (java/uncaught-number-format-exception), 2026-09-04: every numeric column
            // below used to be parsed with no guard at all, unlike this class's date-parsing
            // siblings in PdfTableLocator (which check a regex match first) and
            // StatementSummaryExtractor.count() (which checks \d{1,7} first) -- both provably safe
            // by construction. This one had nothing: Tesseract's own TSV format is well-behaved in
            // practice, but it is external tool output, not something this class controls, and one
            // malformed row from an edge-case OCR run should not crash recognition for the entire
            // page's worth of real, otherwise-good rows that already parsed. One try/catch around
            // the row rather than one per column: any single NumberFormatException here means this
            // whole row's shape cannot be trusted, not just the one field that happened to throw
            // first.
            try {
                if (Integer.parseInt(c[0].trim()) != WORD_LEVEL) continue;

                String text = c[11];
                if (text == null || text.isBlank()) continue;

                float conf = Float.parseFloat(c[10].trim());
                float height = Integer.parseInt(c[9].trim()) * scale;
                runs.add(new RecognisedText(text,
                        Integer.parseInt(c[6].trim()) * scale,
                        // top + height: the baseline, which is what PDFBox reports. See the class note.
                        Integer.parseInt(c[7].trim()) * scale + height,
                        Integer.parseInt(c[8].trim()) * scale,
                        height,
                        page,
                        conf < 0 ? null : conf / 100f));
            } catch (NumberFormatException e) {
                log.warn("Skipping unparseable Tesseract TSV row on page {}: {}", page, lines[i]);
            }
        }
        return runs;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A leftover temp file is not a reason to fail an evaluation run.
                }
            });
        }
    }
}
