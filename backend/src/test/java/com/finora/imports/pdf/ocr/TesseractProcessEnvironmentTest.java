package com.finora.imports.pdf.ocr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment {@link TesseractEngine} starts each recognition process with, read back by a
 * stand-in executable rather than a real Tesseract -- the developer's Homebrew build is not linked
 * against OpenMP, so a real run could not show whether the limit reached the process.
 */
class TesseractProcessEnvironmentTest {

    @Test
    void recognitionProcess_runsWithOpenMpHeldToOneThread(@TempDir Path dir) throws Exception {
        Path printsLimit = dir.resolve("prints-limit");
        Files.writeString(printsLimit, "#!/bin/sh\nprintf %s \"$OMP_THREAD_LIMIT\"\n");
        Files.setPosixFilePermissions(printsLimit, PosixFilePermissions.fromString("rwx------"));

        Process process = TesseractEngine.tesseractProcess(List.of(printsLimit.toString())).start();

        assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("1");
        assertThat(process.waitFor()).isZero();
    }
}
