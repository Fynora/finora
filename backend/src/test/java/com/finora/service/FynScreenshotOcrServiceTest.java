package com.finora.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real {@code tesseract}, same "developer's local install, not a build dependency, CI has none and
 * these skip there" posture as {@code TesseractRunAssemblyTest} -- a mocked OCR call would prove
 * nothing about whether the plain-text {@code tesseract <image> stdout} invocation (no trailing
 * config name, unlike {@code TesseractEngine}'s own {@code tsv} call) actually produces plain text.
 * Validation tests (bad upload shape) need no binary and always run.
 */
class FynScreenshotOcrServiceTest {

    private final FynScreenshotOcrService service = new FynScreenshotOcrService();

    /** A synthetic PNG with real rendered text, not a fixture file -- built at test time so this
     *  doesn't depend on a binary asset living in the repo for one small test. */
    private static byte[] renderTextPng(String text) throws Exception {
        BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 100);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.drawString(text, 20, 55);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] blankPng() throws Exception {
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void extractsRealTextFromARenderedScreenshot() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                renderTextPng("SWIGGY 499"));

        String result = service.describeForChat(image, "What is this charge?");

        assertThat(result).containsIgnoringCase("SWIGGY");
        assertThat(result).contains("What is this charge?");
    }

    @Test
    void defaultsToAGenericQuestionWhenTheUserSendsNoText() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                renderTextPng("HELLO"));

        String result = service.describeForChat(image, "   ");

        assertThat(result).contains("What can you tell me about this screenshot?");
    }

    @Test
    void tellsFynWhenOcrFoundNothingReadable() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        MockMultipartFile image = new MockMultipartFile("image", "blank.png", "image/png", blankPng());

        String result = service.describeForChat(image, "What's in this?");

        assertThat(result).contains("no readable text could be extracted");
        assertThat(result).contains("What's in this?");
    }

    @Test
    void rejectsAnEmptyUpload() {
        MockMultipartFile empty = new MockMultipartFile("image", "screenshot.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.describeForChat(empty, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No screenshot was attached");
    }

    @Test
    void rejectsAnOversizedUpload() {
        MockMultipartFile tooBig = new MockMultipartFile("image", "screenshot.png", "image/png",
                new byte[9 * 1024 * 1024]);

        assertThatThrownBy(() -> service.describeForChat(tooBig, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejectsAnUnsupportedContentType() {
        MockMultipartFile pdf = new MockMultipartFile("image", "statement.pdf", "application/pdf",
                new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.describeForChat(pdf, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image type");
    }

    @Test
    void rejectsAMissingContentType() {
        MockMultipartFile noType = new MockMultipartFile("image", "screenshot", null, new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.describeForChat(noType, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported image type");
    }

    /** Unlike /chat's ChatRequest.message (a @Size(max = 2000)-validated DTO field), this
     *  endpoint's message is a plain @RequestParam String with no framework-level bound --
     *  describeForChat/validate need to enforce this themselves, or a caller could smuggle an
     *  unbounded prompt past the same limit ChatRequest.message already holds every plain-text
     *  chat turn to. */
    @Test
    void rejectsAMessageOverTheLengthLimit() {
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                new byte[]{1, 2, 3});
        String tooLong = "a".repeat(2001);

        assertThatThrownBy(() -> service.describeForChat(image, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void acceptsAMessageExactlyAtTheLengthLimit() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                renderTextPng("HELLO"));
        String exactlyAtLimit = "a".repeat(2000);

        // Must not throw -- only strictly OVER the limit is rejected, per validate()'s own `>` check.
        String result = service.describeForChat(image, exactlyAtLimit);

        assertThat(result).contains(exactlyAtLimit);
    }
}
