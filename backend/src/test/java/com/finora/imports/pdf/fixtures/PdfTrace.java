package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.trace.PdfTraceRedactor;
import com.finora.imports.pdf.trace.TraceFormat;
import com.finora.imports.pdf.trace.TraceMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A statement's extracted text layer -- every {@link PositionedText} run with its coordinates --
 * as a flat, human-readable file.
 *
 * This exists because the bugs worth regression-testing in this pipeline are POSITIONAL. Column
 * anchors that don't line up with their headers, a page banner whose x lands in the date column, a
 * multi-word header arriving as two separate runs: none of that survives being described in prose,
 * and none of it can be reproduced by hand-building a synthetic PDF, because rebuilding the PDF
 * rebuilds the layout and loses the exact fragmentation that caused the bug.
 *
 * A trace is captured from the real file once (see PdfPipelineDiagnostic's capture mode), redacted
 * (see {@link PdfTraceRedactor}), and committed. What lands in the repo is structurally identical
 * to the customer's document and contains none of their data -- which is what makes the Synthetic
 * Fixture Policy affordable to follow rather than a tax people route around.
 *
 * The format is deliberately plain text rather than anything binary or generated: a fixture nobody
 * can read is a fixture nobody will check for PII before committing.
 *
 * <pre>
 * # finora-pdf-trace v1
 * # page &lt;tab&gt; x &lt;tab&gt; y &lt;tab&gt; text
 * 0	35.00	720.50	Txn Date
 * </pre>
 */
public final class PdfTrace {

    private PdfTrace() {}

    /** Backward-compatible: writes a v1 trace with no provenance. See {@link TraceFormat}. */
    public static String format(List<PositionedText> runs) { return TraceFormat.format(runs); }

    /** A v3 trace: the run data preceded by the provenance block. See {@link TraceFormat}. */
    public static String format(List<PositionedText> runs, TraceMetadata metadata) { return TraceFormat.format(runs, metadata); }

    /** Reads either row shape. See {@link TraceFormat#parse}. */
    public static List<PositionedText> parse(String content) { return TraceFormat.parse(content); }

    /** The provenance of a committed trace, or {@link TraceMetadata#legacyV1()} when it predates
     *  metadata. */
    public static TraceMetadata metadata(String traceName) {
        return TraceMetadata.parse(read(traceName));
    }

    /** Loads a committed trace from {@link #tracesDir()}. */
    public static List<PositionedText> load(String traceName) {
        return parse(read(traceName));
    }

    /** Where traces live: the private repository's {@code traces/} under the backend's
     *  {@code private-traces} Maven profile, {@code src/test/resources/traces} otherwise -- empty in
     *  the public repository since traces moved to Fynora/finora-statement-traces. Read on every
     *  call rather than once, so a test can point it at a folder of its own. */
    public static Path tracesDir() {
        return Path.of(System.getProperty("finora.traces.dir", "src/test/resources/traces"));
    }

    /** The raw file contents, metadata block included. */
    public static String read(String traceName) {
        Path path = tracesDir().resolve(traceName + ".trace");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("No such trace fixture: " + traceName);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read trace fixture " + traceName, e);
        }
    }

    /** Every committed trace, by name. The corpus is small enough to enumerate from disk, and
     *  deriving it means a newly captured trace is covered by the corpus-health checks the moment
     *  it lands rather than when someone adds it to a list. No folder means no traces. */
    public static List<String> committedTraceNames() {
        String[] files = tracesDir().toFile().list((d, name) -> name.endsWith(".trace"));
        if (files == null) return List.of();
        return Arrays.stream(files)
                .map(name -> name.substring(0, name.length() - ".trace".length()))
                .sorted()
                .toList();
    }
}
