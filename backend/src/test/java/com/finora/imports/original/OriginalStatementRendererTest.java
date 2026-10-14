package com.finora.imports.original;

import com.finora.exception.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OriginalStatementRendererTest {

    private final OriginalStatementRenderer renderer = new OriginalStatementRenderer();

    static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                    s.beginText();
                    s.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    s.newLineAtOffset(72, 720);
                    s.showText("Synthetic page " + (i + 1));
                    s.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void countsPages() throws IOException {
        assertThat(renderer.pageCount(pdfWithPages(3))).isEqualTo(3);
    }

    @Test
    void rendersAPageAsAPng() throws IOException {
        byte[] png = renderer.renderPage(pdfWithPages(2), 2);
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isBetween(800, 1300);
    }

    @Test
    void aPageOutsideTheDocumentIsNotFound() throws IOException {
        byte[] pdf = pdfWithPages(2);
        assertThatThrownBy(() -> renderer.renderPage(pdf, 0))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> renderer.renderPage(pdf, 3))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void bytesThatAreNotAPdfAreAConflictNamingNoContent() {
        byte[] junk = "not a pdf".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> renderer.pageCount(junk))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).doesNotContain("not a pdf");
                });
    }

    /** A locked PDF with no saved password says so, as the blanked copy does, rather than naming an
     *  exception class. */
    @Test
    void aLockedPdfIsAConflictSayingNoPasswordIsSaved() throws IOException {
        byte[] locked;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(out);
            locked = out.toByteArray();
        }
        byte[] pdf = locked;
        assertThatThrownBy(() -> renderer.pageCount(pdf))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("This statement is password-protected and no password is saved.");
                });
        assertThatThrownBy(() -> renderer.renderPage(pdf, 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void aHugePageIsScaledDownToTheCap() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage(new PDRectangle(5000, 5000)));
            doc.save(out);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(renderer.renderPage(out.toByteArray(), 1)));
            assertThat((long) image.getWidth() * image.getHeight())
                    .isLessThanOrEqualTo(OriginalStatementRenderer.MAX_PIXELS);
        }
    }

    @Test
    void csvLinesAreReturnedAsUtf8TextCappedAtTheLimit() {
        String many = IntStream.range(0, OriginalStatementRenderer.MAX_CSV_LINES + 5)
                .mapToObj(i -> "r" + i).collect(Collectors.joining("\n"));
        List<String> lines = renderer.csvLines(many.getBytes(StandardCharsets.UTF_8));
        assertThat(lines).hasSize(OriginalStatementRenderer.MAX_CSV_LINES);
        assertThat(lines.get(0)).isEqualTo("r0");
    }
}
