package com.finora.imports.pdf.ocr;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bounds on {@link TesseractEngine}'s subprocesses: one process, and one whole document.
 *
 * <p>Each test swaps the binary for a small shell script, so a process that hangs, floods stdout or
 * fails can be produced on demand. The scripts {@code exec} their last command so the process the
 * engine started is the one that sleeps -- a child of the shell would survive the shell being killed
 * and keep the stdout pipe open, which is not how the real binary behaves.
 */
@DisabledOnOs(OS.WINDOWS)
class TesseractEngineTimeoutTest {

    private static final String TSV_HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    @TempDir
    Path dir;

    @Test
    void aProcessThatNeverFinishesIsKilledAndReportedAsAnIOException() throws Exception {
        Path pid = dir.resolve("pid");
        // Writes part of its output first, so the read and the wait are both in progress when it hangs.
        Path script = script("echo $$ > '" + pid + "'\nprintf '" + TSV_HEADER + "\\n'\nexec sleep 30");
        TesseractEngine engine = engine(script, Duration.ofSeconds(1), Duration.ofSeconds(30));

        long start = System.nanoTime();
        assertThatThrownBy(() -> engine.recognise(pdf(1), 72))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("did not finish page 1 of 1 within 1s and was killed");
        assertThat(elapsed(start)).isLessThan(Duration.ofSeconds(10));

        long processId = Long.parseLong(Files.readString(pid).trim());
        ProcessHandle handle = ProcessHandle.of(processId).orElse(null);
        if (handle != null) {
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertThat(handle.isAlive()).isFalse();
        }
    }

    @Test
    void theRenderedPageIsDeletedWhenItsProcessIsKilled() throws Exception {
        Path seen = dir.resolve("seen");
        Path script = script("echo \"$1\" > '" + seen + "'\nexec sleep 30");
        TesseractEngine engine = engine(script, Duration.ofSeconds(1), Duration.ofSeconds(30));

        assertThatThrownBy(() -> engine.recognise(pdf(1), 72)).isInstanceOf(IOException.class);

        Path png = Path.of(Files.readString(seen).trim());
        assertThat(png).doesNotExist();
        assertThat(png.getParent()).doesNotExist();
    }

    @Test
    void theDocumentBudgetStopsRecognitionPartWayThroughAScan() throws Exception {
        Path calls = dir.resolve("calls");
        // Each page finishes well inside the per-process bound with an empty, valid reading -- the
        // low-confidence share of no words is 0, so no page is retried.
        Path script = script("echo x >> '" + calls + "'\nsleep 0.5\nprintf '" + TSV_HEADER + "\\n'");
        TesseractEngine engine = engine(script, Duration.ofSeconds(30), Duration.ofSeconds(2));

        long start = System.nanoTime();
        assertThatThrownBy(() -> engine.recognise(pdf(10), 72))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("of 10: the document's 2s recognition budget is spent");
        assertThat(elapsed(start)).isLessThan(Duration.ofSeconds(10));
        // Fewer processes than pages is the claim. No lower bound: on a loaded machine the budget
        // can run out before the first stand-in process has run a line (seen at load average 26).
        int started = Files.exists(calls) ? Files.readAllLines(calls).size() : 0;
        assertThat(started).isLessThan(10);
    }

    @Test
    void aProcessIsCutShortByWhatIsLeftOfTheBudgetNotOnlyByItsOwnBound() throws Exception {
        Path script = script("exec sleep 30");
        TesseractEngine engine = engine(script, Duration.ofSeconds(30), Duration.ofSeconds(1));

        long start = System.nanoTime();
        assertThatThrownBy(() -> engine.recognise(pdf(1), 72))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("page 1 of 1: the document's 1s recognition budget is spent");
        assertThat(elapsed(start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void outputLargerThanAPipeBufferIsReadInFull() throws Exception {
        // ~1.3 MB, far past a pipe's 64 KB: a reader that waited for exit first would deadlock here.
        int words = 40_000;
        Path script = script("printf '" + TSV_HEADER + "\\n'\n"
                + "i=0\nwhile [ $i -lt " + words + " ]; do\n"
                + "  printf '5\\t1\\t1\\t1\\t1\\t1\\t10\\t20\\t30\\t12\\t96\\tword\\n'\n"
                + "  i=$((i+1))\ndone");
        TesseractEngine engine = engine(script, Duration.ofSeconds(60), Duration.ofSeconds(120));

        List<OcrEngine.RecognisedText> runs = engine.recognise(pdf(1), 72);

        assertThat(runs).hasSize(words);
        assertThat(runs.getFirst().text()).isEqualTo("word");
        assertThat(runs.getFirst().confidence()).isEqualTo(0.96f);
    }

    @Test
    void aNonZeroExitIsStillAnIOException() throws Exception {
        Path script = script("printf '" + TSV_HEADER + "\\n'\nexit 3");
        TesseractEngine engine = engine(script, Duration.ofSeconds(30), Duration.ofSeconds(30));

        assertThatThrownBy(() -> engine.recognise(pdf(1), 72))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exited non-zero");
    }

    @Test
    void anInterruptedCallerKillsTheProcessAndKeepsItsInterruptFlag() throws Exception {
        Path pid = dir.resolve("pid");
        Path script = script("echo $$ > '" + pid + "'\nexec sleep 30");
        TesseractEngine engine = engine(script, Duration.ofSeconds(30), Duration.ofSeconds(60));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                engine.recognise(pdf(1), 72);
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(pid) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        caller.interrupt();
        caller.join(Duration.ofSeconds(10));

        assertThat(caller.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(IOException.class).hasMessageContaining("interrupted");
        assertThat(interruptedAfter).isTrue();
        ProcessHandle handle = ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).orElse(null);
        if (handle != null) {
            handle.onExit().get(5, TimeUnit.SECONDS);
            assertThat(handle.isAlive()).isFalse();
        }
    }

    private TesseractEngine engine(Path script, Duration processTimeout, Duration documentBudget) {
        return new TesseractEngine(Optional.of(script.toString()), processTimeout, documentBudget);
    }

    private Path script(String body) throws IOException {
        Path script = Files.createTempFile(dir, "fake-tesseract-", ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }

    /** Blank pages, small so rendering costs nothing next to the bounds under test. */
    private static byte[] pdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(new PDRectangle(72, 72)));
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
