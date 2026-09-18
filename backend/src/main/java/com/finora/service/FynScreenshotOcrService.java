package com.finora.service;

import com.finora.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Turns a user-attached screenshot into plain text Fyn can read, via the same local {@code
 * tesseract} binary {@code TesseractEngine} uses for scanned bank statements -- but plain-text
 * output, not that class's word-level TSV with bounding boxes, since a chat turn needs readable
 * text, not column positions. Kept as its own small class rather than folded into {@link
 * com.finora.imports.pdf.ocr.TesseractEngine} (which only ever recognises a rendered PDF page, not
 * an arbitrary uploaded image) -- see {@code OcrEngine}'s own doc comment on deliberately not
 * growing that contract to cover a second shape of caller.
 *
 * <p><b>Privacy boundary.</b> The image itself never leaves this process and is never persisted --
 * only the text this class extracts from it is handed to {@code FynChatOrchestrationService}, which
 * sends it to Anthropic the same way any other chat message is sent. {@link FynOcrRedactor} runs
 * on that extracted text (inside {@link #extractText}, before length truncation) to strip account
 * numbers, card numbers, UPI VPAs/UTR references, phone numbers and IFSC codes before it reaches
 * that call -- see that class's own doc comment for exactly what is and, just as importantly, is
 * NOT caught (merchant names and narrations are free text with no reliable structural shape, and
 * still reach Claude verbatim; this closes the Tier 4 gap, not the Tier 2/3 one). A security/
 * privacy audit (2026-09-18) found this class's own OCR output previously reached Claude with no
 * redaction at all, despite this comment's earlier, incorrect claim that the never-raw posture was
 * already preserved -- fixed here, not merely re-asserted. The tradeoff is a photographed receipt
 * or a screenshot with unusual fonts/layout may OCR poorly or not at all, where a raw image would
 * have worked.
 *
 * <h2>Cost/availability ordering (same audit, F-05)</h2>
 *
 * <p>{@code ChatController} runs Fyn's own quota/cost/kill-switch preflight ({@code
 * FynChatOrchestrationService#preflightChat}) BEFORE calling {@link #describeForChat} now -- a
 * user whose free daily question limit is already spent, or whose Fyn access is disabled by the
 * cost governor, is rejected before this class ever spawns a {@code tesseract} process, not after.
 * This class closes the other three gaps the same finding raised: {@link #available()} probes the
 * {@code tesseract} binary once at class-load time rather than on every single request; {@link
 * #ocrPermits} bounds how many OCR calls can run at once on this instance, rejecting fast (no
 * blocking wait, same "reject immediately rather than park a request thread" philosophy {@code
 * ImportConcurrencyLimiter} already uses) once the limit is reached, since a native subprocess
 * spawn is real, bounded-but-nonzero CPU/thread cost that an unlimited burst of requests could
 * otherwise pile up; and {@link #validate} now checks the upload's REAL decoded shape (a genuine
 * PNG/JPEG header, or a genuine WebP RIFF container signature), not just its declared {@code
 * Content-Type} and byte count, so a non-image file wearing an image MIME type is rejected before
 * reaching {@code tesseract} at all. Deliberately not a Redis-backed cross-instance limiter like
 * {@code ImportConcurrencyLimiter}'s -- that class needs fleet-wide coordination because it
 * protects a genuinely shared, scarce resource (the DB connection pool); OCR concurrency is a
 * per-instance CPU/thread concern with no cross-instance resource to coordinate, so a local-only
 * semaphore is the right-sized fix, not an under-engineered one.
 */
@Component
public class FynScreenshotOcrService {

    private static final Logger log = LoggerFactory.getLogger(FynScreenshotOcrService.class);

    // Same CodeQL-motivated reasoning as TesseractEngine's own RESOLVED_TESSERACT_PATH: resolved
    // once against $PATH by this class's own trusted code and cached, rather than re-resolved by
    // the OS on every call, so nothing later on PATH can silently swap out which binary "tesseract"
    // means between calls. Not shared with TesseractEngine's copy -- these are two independent,
    // narrow-purpose classes (see this class's own doc comment), and duplicating ~10 lines of
    // self-contained path resolution is cheaper than coupling them to a shared utility neither
    // otherwise needs.
    private static final Optional<String> RESOLVED_TESSERACT_PATH = resolveOnPath("tesseract");

    // Found in the same audit as the redaction fix above (F-05): this used to spawn a fresh
    // `tesseract --version` process on EVERY call to available(), which ChatController calls on
    // every single screenshot request. tesseract's own presence/version on this container's PATH
    // cannot change over the life of one running process (it is baked into the Docker image, not
    // installed at runtime), so probing once at class-load time -- the same point
    // RESOLVED_TESSERACT_PATH above already resolves at -- and caching the result is strictly
    // equivalent to the old behavior for every request after the first, at a fraction of the cost.
    private static final boolean AVAILABLE = probeAvailability();

    // Bounds how many OCR calls can be mid-tesseract-subprocess at once on this instance -- see
    // this class's own doc comment (Cost/availability ordering) for why this is a local semaphore,
    // not a Redis-backed one. Sized well under Tomcat's default request-thread pool so an OCR
    // burst can never starve every other endpoint of a thread, the same reasoning
    // ImportConcurrencyLimiter's own doc comment gives for its own ceiling.
    private static final int MAX_CONCURRENT_OCR = 4;

    // Bounds decoded-pixel exposure for the two formats this class can actually check (PNG/JPEG,
    // both natively readable by javax.imageio -- see validateRealImageShape's own doc comment for
    // why WebP does not get the same dimension check). 6000px comfortably covers any real
    // phone/desktop screenshot (a 6K monitor is 6016x3384) while still bounding a pathological
    // upload.
    private static final int MAX_IMAGE_DIMENSION_PX = 6000;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 8MB -- comfortably above a phone screenshot
    // Bounds how much OCR'd text (and cost) one screenshot can inject into a chat turn -- a dense
    // wall-of-text image (or an adversarial one) shouldn't be able to smuggle an unbounded prompt
    // past ChatRequest.message's own 2000-char limit for the plain-text path.
    private static final int MAX_EXTRACTED_CHARS = 4000;
    // Found while reviewing this class: unlike /chat's ChatRequest.message (a @Size(max = 2000)
    // -validated DTO field), this endpoint's message is a plain @RequestParam String with no
    // framework-level bound at all -- an unrelated gap from the OCR-text cap above, since that
    // only bounds what comes FROM the image, not what the caller types alongside it. Same limit as
    // ChatRequest.message, for the same reason and for consistency between the two chat paths.
    private static final int MAX_MESSAGE_CHARS = 2000;
    // Generous against a real screenshot (typically well under 2s) -- exists to bound the worst
    // case, not to be tuned close to normal latency. Found while reviewing this class: without it,
    // a hung tesseract process (a pathological or corrupt image) blocks the request thread
    // forever, and since the temp file is only cleaned up in this method's own `finally`, a hang
    // leaks that temp file forever too -- the method never reaches `finally` while stuck inside
    // readAllBytes(). Any authenticated user could trigger this with one upload.
    private static final long OCR_TIMEOUT_SECONDS = 15;

    // Local, per-instance -- see this class's own doc comment (Cost/availability ordering) for why
    // this is not Redis-backed. Non-fair (plain no-arg Semaphore constructor): fairness only
    // matters for threads actually parked waiting on the semaphore, and tryAcquire() never parks
    // -- same reasoning ImportConcurrencyLimiter's own doc comment already gives for its own
    // semaphore's fairness setting.
    private final Semaphore ocrPermits = new Semaphore(MAX_CONCURRENT_OCR);

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

    private static boolean probeAvailability() {
        if (RESOLVED_TESSERACT_PATH.isEmpty()) return false;
        try {
            return new ProcessBuilder(RESOLVED_TESSERACT_PATH.get(), "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Whether the {@code tesseract} binary is on PATH -- callers must check this before {@link
     *  #describeForChat}, which throws if it isn't, same contract as {@code
     *  TesseractEngine#recognise}. Cached at class-load time (see {@link #AVAILABLE}'s own doc
     *  comment) -- this no longer spawns a process on every call. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** Request-shape checks only -- no OCR call, no dependency on {@link #available()}. Exposed
     *  separately so a caller (the controller) can reject a bad upload with a clear 400 before
     *  even checking whether OCR itself is available, rather than have "wrong file type" and "OCR
     *  is down" collapse into the same response. {@link #describeForChat} also calls this itself,
     *  so it remains safe to call directly (as this class's own tests do) without duplicating the
     *  checks at every call site. {@code message} may be null (it's optional on this endpoint).
     *
     * @throws IllegalArgumentException for a bad upload (wrong image type/size, or too-long text).
     *          {@code GlobalExceptionHandler}'s generic {@code IllegalArgumentException} handler
     *          would also catch this if left to propagate, but with a deliberately vague message
     *          ("One of the request's parameters is not valid.") -- callers should catch this
     *          explicitly and use {@code getMessage()} for a message that actually says what to
     *          fix, the same reason {@code ChatController} already does for the image checks. */
    public void validate(MultipartFile image, String message) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("No screenshot was attached.");
        }
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Screenshot is too large -- please attach one under 8MB.");
        }
        String contentType = image.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported image type -- please attach a PNG, JPEG, or WebP screenshot.");
        }
        if (message != null && message.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("Message is too long -- please keep it under " + MAX_MESSAGE_CHARS + " characters.");
        }
        validateRealImageShape(image, contentType);
    }

    /**
     * Found in the same audit as the rest of this class's F-05 fixes: this used to trust the
     * declared {@code Content-Type} header alone -- a non-image file (or an image of a completely
     * different format) wearing an {@code image/png} label would sail through {@link #validate}
     * and only fail once {@code tesseract} itself choked on it, after already paying for a
     * subprocess spawn. This reads the upload's real header and rejects anything that is not
     * genuinely what it claims to be, before that cost is paid.
     *
     * <p>PNG/JPEG go through {@link ImageIO}, which has a real built-in reader for both -- {@code
     * reader.getWidth(0)}/{@code getHeight(0)} read only the header, not the full pixel data, so
     * this also gets a real dimension check (bounding {@link #MAX_IMAGE_DIMENSION_PX}) at
     * essentially no extra cost. WebP gets a narrower check: the JDK's built-in {@code ImageIO}
     * has no WebP reader at all (confirmed by listing {@code ImageIO.getReaderMIMETypes()} --
     * PNG/JPEG/TIFF/BMP/GIF/WBMP only), and this project has no third-party WebP plugin dependency
     * -- real dimension validation would need either adding one or hand-parsing the VP8/VP8L/VP8X
     * bitstream, and this fix attempts neither. WebP gets a magic-byte check only (the RIFF/WEBP
     * container signature), which still closes the "declared image/webp but isn't an image at
     * all" gap. This is a smaller, deliberately-scoped gap, not silently assumed away: {@code
     * tesseract} -- not this JVM -- is what actually decodes any of these bytes, and it is already
     * bounded by {@link #OCR_TIMEOUT_SECONDS}'s hard kill and {@link #ocrPermits}'s concurrency
     * cap regardless of what it is asked to decode, so a WebP decompression bomb costs at most one
     * killed subprocess on one of a bounded number of concurrent permits, not an unbounded resource
     * exhaustion.
     */
    private void validateRealImageShape(MultipartFile image, String contentType) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Couldn't read that file -- please try a different screenshot.");
        }
        if ("image/webp".equals(contentType)) {
            if (!isWebpSignature(bytes)) {
                throw new IllegalArgumentException(
                        "That file doesn't look like a real image -- please attach a PNG, JPEG, or WebP screenshot.");
            }
            return;
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                throw new IllegalArgumentException(
                        "That file doesn't look like a real image -- please attach a PNG, JPEG, or WebP screenshot.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IllegalArgumentException(
                        "That file doesn't look like a real image -- please attach a PNG, JPEG, or WebP screenshot.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis);
                if (reader.getWidth(0) > MAX_IMAGE_DIMENSION_PX || reader.getHeight(0) > MAX_IMAGE_DIMENSION_PX) {
                    throw new IllegalArgumentException(
                            "That screenshot's dimensions are too large -- please attach a smaller image.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "That file doesn't look like a real image -- please attach a PNG, JPEG, or WebP screenshot.");
        }
    }

    /** The fixed 12-byte RIFF/WEBP container signature every WebP file starts with, regardless of
     *  which of the three WebP sub-formats (VP8/VP8L/VP8X) it actually contains -- see {@link
     *  #validateRealImageShape}'s own doc comment for why this class checks no further than this
     *  signature for WebP specifically. */
    private static boolean isWebpSignature(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    /**
     * Validates the upload, OCRs it, and combines the result with the user's own text into one
     * message for {@code FynChatOrchestrationService.sendMessage} -- the orchestration loop itself
     * needs no changes, since this only ever produces a plain-text user turn like any other.
     *
     * @throws IllegalArgumentException for a bad upload (wrong type, empty, too large, a too-long
     *          message, or a file that isn't really the image type it claims to be) -- callers
     *          should map this to a 400, not treated as an OCR/availability failure.
     * @throws ApiException (503) if every OCR concurrency permit is already in use -- see {@link
     *          #ocrPermits}'s own doc comment. Callers do not need to catch this specially; it is
     *          meant to propagate to {@code GlobalExceptionHandler} like any other {@code
     *          ApiException}.
     * @throws IOException if {@code tesseract} itself fails to run -- callers must check {@link
     *          #available()} first; this is not meant as that check's replacement.
     */
    public String describeForChat(MultipartFile image, String userMessage) throws IOException {
        validate(image, userMessage);

        String extractedText = extractTextGated(image.getBytes(), extensionFor(image.getContentType()));
        String question = (userMessage == null || userMessage.isBlank())
                ? "What can you tell me about this screenshot?"
                : userMessage.trim();

        if (extractedText.isBlank()) {
            return "The user attached a screenshot, but no readable text could be extracted from it "
                    + "(OCR found nothing) -- let them know you couldn't read it and ask them to describe "
                    + "what they need instead.\n\nUser's question: " + question;
        }
        return "The user attached a screenshot. Text extracted from it via OCR (it may contain "
                + "recognition errors -- treat it as a rough transcription, not a verified quote). "
                + "Account numbers, card numbers, UPI IDs, IFSC codes and phone numbers have been "
                + "replaced with placeholders like [redacted-number] before reaching you -- this is "
                + "expected, not an OCR failure; never invent a real-looking value to fill one in, "
                + "and if the user's question depends on one, tell them you can't see it and ask them "
                + "to type it instead:\n\n"
                + extractedText + "\n\n---\nUser's question: " + question;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    /**
     * Reject-fast gate around the actual {@code tesseract} spawn -- see {@link #ocrPermits}'s own
     * doc comment. No blocking wait: {@code tryAcquire()} never parks the calling (Tomcat request)
     * thread, the same "reject immediately rather than queue" philosophy {@code
     * ImportConcurrencyLimiter#runGated} already uses for the identical reason (a parked request
     * thread is itself a scarce, shared resource this must not spend).
     */
    private String extractTextGated(byte[] imageBytes, String extension) throws IOException {
        if (!ocrPermits.tryAcquire()) {
            log.warn("Fyn screenshot OCR: rejected -- no processing slot available ({}/{} slots in use)",
                    MAX_CONCURRENT_OCR - ocrPermits.availablePermits(), MAX_CONCURRENT_OCR);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Fyn is processing a lot of screenshots right now. Please try again in a moment.");
        }
        try {
            return extractText(imageBytes, extension);
        } finally {
            ocrPermits.release();
        }
    }

    /**
     * {@code tesseract <image> stdout} -- no trailing config name, which is what makes this plain
     * text rather than TSV/hOCR/searchable-PDF output.
     *
     * <p>Stdout is read on its own thread, concurrently with the {@link #OCR_TIMEOUT_SECONDS}
     * bound on {@link Process#waitFor(long, TimeUnit)} -- reading and waiting can't be done
     * sequentially here: reading first (as originally written) blocks forever if the process
     * hangs without closing its output, since {@code readAllBytes()} won't return until EOF; and
     * waiting first risks the classic subprocess deadlock, where tesseract blocks trying to write
     * a full stdout buffer that nothing is draining, while this thread blocks in {@code waitFor}
     * for an exit that write is blocking on. Reading concurrently avoids both: a real hang still
     * gets caught by the timeout, and {@code destroyForcibly()} on timeout closes the process's
     * streams, which unblocks the reader thread too.
     */
    private String extractText(byte[] imageBytes, String extension) throws IOException {
        String tesseract = RESOLVED_TESSERACT_PATH.orElseThrow(
                () -> new IOException("tesseract is not on PATH -- callers must check available() first"));
        Path work = Files.createTempDirectory("fyn-screenshot-ocr-");
        try {
            File image = work.resolve("screenshot." + extension).toFile();
            Files.write(image.toPath(), imageBytes);

            Process process = new ProcessBuilder(tesseract, image.getAbsolutePath(), "stdout")
                    .redirectErrorStream(false)
                    .start();
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });

            boolean exitedInTime;
            try {
                exitedInTime = process.waitFor(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("interrupted while OCRing an uploaded screenshot", e);
            }
            if (!exitedInTime) {
                process.destroyForcibly();
                log.warn("Fyn screenshot OCR: tesseract timed out after {}s, killed", OCR_TIMEOUT_SECONDS);
                throw new IOException("tesseract timed out OCRing an uploaded screenshot");
            }

            byte[] outBytes;
            try {
                // The process has already exited, so its stdout is closed and this resolves
                // immediately -- the short timeout here is only a last-resort safety net, not
                // expected to ever actually trip.
                outBytes = stdout.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted reading tesseract output", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("failed reading tesseract output for an uploaded screenshot", e);
            }
            if (process.exitValue() != 0) {
                throw new IOException("tesseract exited non-zero for an uploaded screenshot");
            }

            String out = new String(outBytes, StandardCharsets.UTF_8);
            // Redact BEFORE truncating -- see FynOcrRedactor's own doc comment for why this order
            // matters (truncating first can cut a PII shape in half at the boundary and let the
            // remaining half slip through unredacted).
            String trimmed = FynOcrRedactor.redact(out.trim());
            if (trimmed.length() > MAX_EXTRACTED_CHARS) {
                log.warn("Fyn screenshot OCR: extracted text truncated from {} to {} chars",
                        trimmed.length(), MAX_EXTRACTED_CHARS);
                trimmed = trimmed.substring(0, MAX_EXTRACTED_CHARS) + "\n[truncated]";
            }
            return trimmed;
        } finally {
            // Same reasoning as TesseractEngine's own cleanup: a real user's screenshot, not
            // something to leave sitting in the temp directory beyond this one call.
            deleteRecursively(work);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A leftover temp file is not a reason to fail the chat turn.
                }
            });
        }
    }
}
