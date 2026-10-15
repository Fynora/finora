package com.finora.imports.pdf.fixtures;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traces live in the private Fynora/finora-statement-traces repository, which the backend's
 * {@code private-traces} Maven profile points {@code finora.traces.dir} at. These pin that the
 * loader reads whatever folder that property names, and that a missing folder means no traces
 * rather than an error -- the public build has none at all.
 *
 * <p>{@code @Isolated} because both tests change a JVM-wide system property, and
 * junit-platform.properties runs test classes concurrently: without it, a trace-backed class
 * running at the same moment would read this test's temporary folder instead of the real one.
 */
@Isolated
class PdfTraceLocationTest {

    @Test
    void tracesAreListedFromTheConfiguredFolder(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b-synthetic.trace"), "# finora-pdf-trace v3\n");
        Files.writeString(dir.resolve("a-synthetic.trace"), "# finora-pdf-trace v3\n");
        Files.writeString(dir.resolve("notes.txt"), "x");
        String before = System.getProperty("finora.traces.dir");
        System.setProperty("finora.traces.dir", dir.toString());
        try {
            assertThat(PdfTrace.committedTraceNames()).containsExactly("a-synthetic", "b-synthetic");
            assertThat(PdfTrace.read("a-synthetic")).startsWith("# finora-pdf-trace v3");
        } finally {
            if (before == null) System.clearProperty("finora.traces.dir");
            else System.setProperty("finora.traces.dir", before);
        }
    }

    @Test
    void withNoFolderThereAreNoTraces(@TempDir Path dir) {
        String before = System.getProperty("finora.traces.dir");
        System.setProperty("finora.traces.dir", dir.resolve("absent").toString());
        try {
            assertThat(PdfTrace.committedTraceNames()).isEmpty();
        } finally {
            if (before == null) System.clearProperty("finora.traces.dir");
            else System.setProperty("finora.traces.dir", before);
        }
    }
}
