package com.finora.imports.original;

import com.finora.exception.ApiException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns a held statement into what the admin portal shows: PNG page images for a PDF, text lines for
 * a CSV. The file itself is never sent. Messages name no content: the cause is named by class only.
 */
@Component
public class OriginalStatementRenderer {

    /** 1.5 times a PDF point: an A4 page comes out about 893 pixels wide, readable on a laptop. */
    static final float SCALE = 1.5f;
    /** A page larger than this is scaled down, so one odd page cannot exhaust memory. */
    static final long MAX_PIXELS = 4_000_000L;
    static final int MAX_CSV_LINES = 5_000;

    public int pageCount(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    public byte[] renderPage(byte[] pdf, int pageNumber) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "That page is not in this statement.");
            }
            PDRectangle box = doc.getPage(pageNumber - 1).getCropBox();
            float scale = SCALE;
            double pixels = (double) box.getWidth() * SCALE * box.getHeight() * SCALE;
            if (pixels > MAX_PIXELS) scale = (float) (SCALE * Math.sqrt(MAX_PIXELS / pixels));
            BufferedImage image = new PDFRenderer(doc).renderImage(pageNumber - 1, scale, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    public List<String> csvLines(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8).lines().limit(MAX_CSV_LINES).toList();
    }

    /** A locked PDF reaches here only when no saved password unlocked it (a saved one that fails is
     *  refused earlier, by the queue), so it is named in the blanked copy's words. */
    private static ApiException unreadable(IOException e) {
        if (e instanceof InvalidPasswordException) {
            return new ApiException(HttpStatus.CONFLICT, "This statement is password-protected and no password is saved.");
        }
        return new ApiException(HttpStatus.CONFLICT,
                "The statement could not be opened for viewing (" + e.getClass().getSimpleName() + ").");
    }
}
