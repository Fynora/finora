package com.finora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
 * only the text this class extracts from it is handed to {@code FynChatOrchestrationService}, which sends it to Anthropic
 * the same way any other chat message is sent. This preserves the same "never raw account/
 * transaction data to the model" posture every other Fyn tool already holds (see {@code
 * ai_audit_log}'s own doc comment) -- the tradeoff is a photographed receipt or a screenshot with
 * unusual fonts/layout may OCR poorly or not at all, where a raw image would have worked.
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

    /** Whether the {@code tesseract} binary is on PATH -- callers must check this before {@link
     *  #describeForChat}, which throws if it isn't, same contract as {@code
     *  TesseractEngine#recognise}. */
    public static boolean available() {
        if (RESOLVED_TESSERACT_PATH.isEmpty()) return false;
        try {
            return new ProcessBuilder(RESOLVED_TESSERACT_PATH.get(), "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
    }

    /**
     * Validates the upload, OCRs it, and combines the result with the user's own text into one
     * message for {@code FynChatOrchestrationService.sendMessage} -- the orchestration loop itself
     * needs no changes, since this only ever produces a plain-text user turn like any other.
     *
     * @throws IllegalArgumentException for a bad upload (wrong type, empty, too large, or a
     *          too-long message) -- callers should map this to a 400, not treated as an
     *          OCR/availability failure.
     * @throws IOException if {@code tesseract} itself fails to run -- callers must check {@link
     *          #available()} first; this is not meant as that check's replacement.
     */
    public String describeForChat(MultipartFile image, String userMessage) throws IOException {
        validate(image, userMessage);

        String extractedText = extractText(image.getBytes(), extensionFor(image.getContentType()));
        String question = (userMessage == null || userMessage.isBlank())
                ? "What can you tell me about this screenshot?"
                : userMessage.trim();

        if (extractedText.isBlank()) {
            return "The user attached a screenshot, but no readable text could be extracted from it "
                    + "(OCR found nothing) -- let them know you couldn't read it and ask them to describe "
                    + "what they need instead.\n\nUser's question: " + question;
        }
        return "The user attached a screenshot. Text extracted from it via OCR (it may contain "
                + "recognition errors -- treat it as a rough transcription, not a verified quote):\n\n"
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
            String trimmed = out.trim();
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
