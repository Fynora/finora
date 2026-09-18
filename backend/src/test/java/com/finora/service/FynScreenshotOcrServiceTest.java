package com.finora.service;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Semaphore;

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

    /** Exceeds MAX_IMAGE_DIMENSION_PX (12000) on width only -- a thin real PNG rather than a huge
     *  square one, so this stays cheap to allocate (a handful of KB, not hundreds of MB) while
     *  still being a genuine, fully decodable image ImageIO can read end to end. */
    private static byte[] oversizedRealPng() throws Exception {
        BufferedImage image = new BufferedImage(12001, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** Regression fixture for this class's own bugs-and-gaps review: 8000x6000 is roughly what a
     *  48MP phone camera's default photo mode produces (see MAX_IMAGE_DIMENSION_PX's own doc
     *  comment) -- a real, intended input to this endpoint (a photographed receipt), not a
     *  screenshot. Thin-but-long, same cheap-allocation reasoning as oversizedRealPng above, sized
     *  to exceed the OLD 6000px threshold on width while staying under the current 12000px one, so
     *  this fixture specifically proves the raised threshold, not just "some large image works". */
    private static byte[] realisticHighResPhoneCameraPhoto() throws Exception {
        BufferedImage image = new BufferedImage(8000, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** The fixed 12-byte RIFF/WEBP container signature, no real pixel data -- see {@code
     *  FynOcrRedactor}'s sibling {@code FynScreenshotOcrService.isWebpSignature}: WebP gets a
     *  magic-byte check only (no ImageIO reader for it in this JDK), so this signature alone is
     *  enough to pass validation. */
    private static byte[] webpSignatureOnly() {
        return new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
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

    /**
     * Regression test for a security/privacy audit finding (2026-09-18): OCR'd text used to reach
     * Claude with no redaction at all. Renders real text through real tesseract (not a mocked
     * OCR call, same reasoning as this class's other tests) so this proves the actual end-to-end
     * path -- image bytes in, redacted text out -- not just {@link FynOcrRedactor} in isolation.
     */
    @Test
    void redactsAnAccountNumberFoundInTheScreenshot() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                renderTextPng("Acct 123456789012")); // synthetic-ok

        String result = service.describeForChat(image, "What is this?");

        assertThat(result).doesNotContain("123456789012"); // synthetic-ok
        assertThat(result).contains("[redacted-number]");
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

    /**
     * Regression tests for audit finding F-05 (2026-09-18): {@code validate} used to trust the
     * declared {@code Content-Type} header alone -- a non-image file wearing an image MIME type
     * sailed through and only failed once {@code tesseract} itself choked on it, after already
     * paying for a subprocess spawn. These need no tesseract binary at all: the rejection happens
     * inside {@code validate}, before {@code available()} is ever relevant.
     */
    @Test
    void rejectsAFileThatIsNotReallyAnImage_despiteClaimingToBePng() {
        MockMultipartFile notReallyAnImage = new MockMultipartFile("image", "screenshot.png", "image/png",
                "this is just plain text, not image bytes".getBytes());

        assertThatThrownBy(() -> service.describeForChat(notReallyAnImage, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't look like a real image");
    }

    @Test
    void rejectsAnOversizedImage_evenThoughItIsAGenuinelyValidPng() throws Exception {
        MockMultipartFile tooWide = new MockMultipartFile("image", "screenshot.png", "image/png", oversizedRealPng());

        assertThatThrownBy(() -> service.describeForChat(tooWide, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions are too large");
    }

    /**
     * Regression test for a real gap this class's own bugs-and-gaps review caught: this class's
     * doc comment names "a photographed receipt" as an intended input alongside a screenshot, and
     * {@code image/jpeg} is allowed specifically because phone cameras (not OS screenshot tools)
     * produce it -- a legitimate photo from a modern phone's default camera mode commonly exceeds
     * what a 6000px threshold (the first version of this fix shipped with) would have accepted.
     * This must NOT throw {@code IllegalArgumentException} -- tried and caught rather than {@code
     * assertThatThrownBy}, since this environment has tesseract installed and this fixture (a
     * solid-color image) OCRs cleanly to "no readable text found", throwing nothing at all; a CI
     * environment with no tesseract binary instead throws IOException downstream of validate() --
     * either outcome is fine, only a rejection by the dimension check itself is not.
     */
    @Test
    void acceptsARealisticHighResolutionPhoneCameraPhoto_notJustScreenshotSizedImages() throws Exception {
        MockMultipartFile photo = new MockMultipartFile("image", "receipt.jpg", "image/jpeg",
                realisticHighResPhoneCameraPhoto());

        try {
            service.describeForChat(photo, "hi");
        } catch (Exception e) {
            assertThat(e).as("must not be rejected by validate()'s dimension check")
                    .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    /** WebP gets a narrower check than PNG/JPEG (magic-byte signature only, no dimension check --
     *  see {@code FynOcrRedactor}'s sibling doc comment on {@code isWebpSignature} for why), so a
     *  file that merely has the right 12-byte RIFF/WEBP header -- not a complete, real WebP image
     *  -- must still pass validation. */
    @Test
    void acceptsAWebpUploadWithOnlyTheMagicByteSignature_becauseDimensionsAreNotCheckedForWebp() {
        MockMultipartFile webp = new MockMultipartFile("image", "screenshot.webp", "image/webp", webpSignatureOnly());

        // Must not throw IllegalArgumentException -- it will still fail later on the missing
        // tesseract binary or a real OCR attempt in this test environment, which is a different,
        // expected failure this test does not reach or assert on.
        assertThatThrownBy(() -> service.describeForChat(webp, "hi"))
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAFileThatClaimsToBeWebp_butHasNoRealWebpSignature() {
        MockMultipartFile fakeWebp = new MockMultipartFile("image", "screenshot.webp", "image/webp",
                "not actually a webp file".getBytes());

        assertThatThrownBy(() -> service.describeForChat(fakeWebp, "hi"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't look like a real image");
    }

    /**
     * Regression test for audit finding F-05 (2026-09-18): every OCR call used to spawn a
     * {@code tesseract} subprocess with no bound on how many could run at once. Needs no tesseract
     * binary -- exhausting every permit means {@code extractTextGated} rejects before ever
     * attempting the real subprocess spawn, so this passes identically whether or not tesseract is
     * installed in this environment.
     */
    @Test
    void rejectsWithServiceUnavailable_whenEveryOcrConcurrencySlotIsInUse() throws Exception {
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(service, "ocrPermits");
        permits.acquire(permits.availablePermits()); // exhaust every permit (MAX_CONCURRENT_OCR)
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png", blankPng());

        assertThatThrownBy(() -> service.describeForChat(image, "hi"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** The permit must come back after a rejection-causing OCR attempt -- {@link
     *  FynScreenshotOcrService#extractTextGated}'s own {@code finally} releases it regardless of
     *  outcome, so a transient burst does not permanently shrink this instance's own capacity. */
    @Test
    void releasesTheOcrPermit_afterASuccessfulCall() throws Exception {
        assumeTrue(FynScreenshotOcrService.available(), "tesseract is not installed");
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(service, "ocrPermits");
        int before = permits.availablePermits();
        MockMultipartFile image = new MockMultipartFile("image", "screenshot.png", "image/png",
                renderTextPng("HELLO"));

        service.describeForChat(image, "hi");

        assertThat(permits.availablePermits()).isEqualTo(before);
    }
}
