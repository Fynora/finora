package com.finora.service;

import com.finora.entity.Payment;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.PaymentRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates the invoice/receipt PDF for GET /api/v1/billing/history/{paymentId}/invoice.
 * <p>
 * GST handling is a deliberate product decision, not a computed fact: {@code billing_prices
 * .gst_rate} (V156) is never populated by any writer in this codebase, and Fynora Technovation
 * LLP is not GST-registered (no GSTIN configured). Rather than leave the tax line blank or invent
 * a rate from nothing, every invoice applies {@code app.billing.invoice.gst-rate-percent} (18% by
 * default) as a flat breakup of the amount actually charged -- see InvoiceProperties' own class
 * doc. The GSTIN line prints "Not applicable" instead of a fabricated number.
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    /** frontend/public/favicon.png, copied verbatim (not redrawn) -- the same BrandMark PNG that
     *  ships as the app's own tab/home-screen icon (frontend/src/components/BrandMark.tsx is the
     *  vector source of truth; this is its rasterized export), so the invoice logo can never drift
     *  from the app's actual mark the way a hand-recreated one could. */
    private static final String LOGO_RESOURCE = "branding/fynora-mark.png";

    private static final DateTimeFormatter INVOICE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    // frontend/src/index.css's own design tokens (:root / .dark light-mode values) -- the invoice
    // reuses the app's actual palette rather than inventing a separate print palette. GRAPHITE/PAPER
    // are BrandMark's fixed colours (frontend/src/components/BrandMark.tsx), not the light/dark
    // tokens, since the mark itself never changes with theme.
    private static final int[] GRAPHITE = {38, 42, 51};
    private static final int[] PAPER = {244, 241, 236};
    private static final int[] SUCCESS = {22, 163, 74};
    private static final int[] SUCCESS_BG = {220, 252, 231};
    private static final int[] BORDER = {230, 234, 242};
    private static final int[] MUTED = {100, 116, 139};
    private static final int[] LIGHT_BG = {248, 250, 252};
    private static final int[] BLACK = {0, 0, 0};

    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final InvoiceProperties invoiceProperties;
    private final byte[] logoBytes;

    public InvoiceService(PaymentRepository paymentRepository, SubscriptionRepository subscriptionRepository,
                           PlanRepository planRepository, UserRepository userRepository,
                           InvoiceProperties invoiceProperties) {
        this.paymentRepository = paymentRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.invoiceProperties = invoiceProperties;
        this.logoBytes = loadLogo();
    }

    /** Missing/unreadable is logged, not thrown -- a bundled classpath resource failing to load
     *  would mean a packaging bug, but an invoice with no logo is a far better failure mode than
     *  every invoice request answering 500 for it. */
    private static byte[] loadLogo() {
        try (InputStream in = new ClassPathResource(LOGO_RESOURCE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("Could not load {} for invoice generation -- invoices will render without a logo.",
                    LOGO_RESOURCE, e);
            return null;
        }
    }

    public record GeneratedInvoice(String fileName, byte[] pdfBytes) {}

    @Transactional(readOnly = true)
    public GeneratedInvoice generate(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Payment not found"));

        // Only a completed charge is a real invoice -- PENDING/FAILED/REFUNDED have nothing paid
        // to itemize (a REFUNDED payment did have a successful charge first, but V1 has no credit-
        // note concept yet; the original SUCCESS payment stays invoiceable, a separate refund
        // document is out of scope here).
        if (!Payment.STATUS_SUCCESS.equals(payment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "An invoice is only available for a completed payment");
        }

        String planName = "Fynora Subscription";
        String billingCycle = null;
        // Prefer what was frozen onto the payment itself at charge time (V186) -- the live
        // Subscription row is mutated in place, so if the user has since upgraded/downgraded or
        // changed billing cycle, reading it here would misdescribe what THIS payment actually paid
        // for. Falls back to the live-subscription lookup only for payments written before V186
        // (planId/billingCycle NULL on the row), where no better source exists.
        if (payment.getPlanId() != null) {
            billingCycle = payment.getBillingCycle();
            Optional<Plan> plan = planRepository.findById(payment.getPlanId());
            if (plan.isPresent()) {
                planName = plan.get().getName();
            }
        } else if (payment.getSubscriptionId() != null) {
            Optional<Subscription> subscription = subscriptionRepository.findById(payment.getSubscriptionId());
            if (subscription.isPresent()) {
                billingCycle = subscription.get().getBillingCycle();
                Optional<Plan> plan = planRepository.findById(subscription.get().getPlanId());
                if (plan.isPresent()) {
                    planName = plan.get().getName();
                }
            }
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));

        String invoiceNumber = invoiceNumber(payment);
        byte[] pdf = renderPdf(payment, planName, billingCycle, user, invoiceNumber);
        return new GeneratedInvoice(invoiceNumber + ".pdf", pdf);
    }

    /** Stable and deterministic from the payment id -- re-requesting the same payment's invoice
     *  always returns the same number, with no write needed on what is otherwise a plain GET. */
    private String invoiceNumber(Payment payment) {
        String year = INVOICE_DATE_FORMAT.format(payment.getCreatedAt()).substring(7);
        String shortId = payment.getId().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return "INV-" + year + "-" + shortId;
    }

    private byte[] renderPdf(Payment payment, String planName, String billingCycle, User user, String invoiceNumber) {
        BigDecimal gstRate = invoiceProperties.getGstRatePercent() == null
                ? BigDecimal.valueOf(18) : invoiceProperties.getGstRatePercent();
        BigDecimal total = payment.getAmount();
        BigDecimal divisor = BigDecimal.ONE.add(gstRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
        BigDecimal baseAmount = total.divide(divisor, 2, RoundingMode.HALF_UP);
        BigDecimal taxAmount = total.subtract(baseAmount);

        // Membership period this specific charge covers -- deliberately NOT read off the live
        // Subscription row: Subscription.startDate is set once at FREE-plan signup provisioning
        // and is never updated by handleActivated on upgrade (confirmed by reading that method),
        // so it predates paid membership entirely; Subscription.renewalDate reflects whatever the
        // MOST RECENT charge set it to, which is wrong for an older, already-superseded invoice.
        // A charge always starts its own coverage period the instant it succeeds -- payment
        // .getCreatedAt() IS that instant -- and a subscription's own billingCycle says exactly how
        // long the period lasts from there, so this is computed fresh per payment rather than
        // trusted from mutable subscription state.
        LocalDate periodStart = payment.getCreatedAt().atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
        LocalDate periodEnd = billingCycle == null ? null
                : "YEARLY".equals(billingCycle) ? periodStart.plusYears(1) : periodStart.plusMonths(1);
        String periodValue = periodEnd == null ? null
                : INVOICE_DATE_FORMAT.format(periodStart) + " - " + INVOICE_DATE_FORMAT.format(periodEnd);

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            float margin = 50;
            float pageRight = PDRectangle.A4.getWidth() - margin;
            float pageWidth = pageRight - margin;
            float y = PDRectangle.A4.getHeight() - margin;
            float left = margin;
            float logoSize = 34;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                // --- Header: logo, title, PAID badge -------------------------------------------
                if (logoBytes != null) {
                    PDImageXObject logo = PDImageXObject.createFromByteArray(document, logoBytes, "fynora-mark");
                    cs.drawImage(logo, left, y - logoSize, logoSize, logoSize);
                }
                textRightAligned(cs, bold, 22, pageRight, y - 16, "INVOICE");
                float badgeWidth = 46, badgeHeight = 16;
                fillRect(cs, pageRight - badgeWidth, y - logoSize, badgeWidth, badgeHeight, SUCCESS_BG);
                setFillColor(cs, SUCCESS);
                textCentered(cs, bold, 8, pageRight - badgeWidth, pageRight, y - logoSize + 5, "PAID");
                setFillColor(cs, BLACK);
                y -= logoSize + 16;

                setStrokeColor(cs, BORDER);
                cs.setLineWidth(1);
                cs.moveTo(left, y);
                cs.lineTo(pageRight, y);
                cs.stroke();
                y -= 22;

                // --- Invoice meta strip (shaded) ------------------------------------------------
                float metaHeight = 30;
                fillRect(cs, left, y - metaHeight + 9, pageWidth, metaHeight, LIGHT_BG);
                float metaTextY = y - 6;
                setFillColor(cs, MUTED);
                text(cs, regular, 8, left + 12, metaTextY, "INVOICE NUMBER");
                text(cs, regular, 8, left + 190, metaTextY, "INVOICE DATE");
                if (payment.getProviderTransactionId() != null) {
                    text(cs, regular, 8, left + 330, metaTextY, "PAYMENT REFERENCE");
                }
                setFillColor(cs, BLACK);
                text(cs, bold, 10, left + 12, metaTextY - 13, invoiceNumber);
                text(cs, bold, 10, left + 190, metaTextY - 13, INVOICE_DATE_FORMAT.format(payment.getCreatedAt()));
                if (payment.getProviderTransactionId() != null) {
                    text(cs, bold, 10, left + 330, metaTextY - 13, payment.getProviderTransactionId());
                }
                y -= metaHeight + 24;

                // --- Billed From / Billed To columns --------------------------------------------
                float colRightX = left + 280;
                float fromY = y, toY = y;
                setFillColor(cs, MUTED);
                fromY = text(cs, bold, 8, left, fromY, "BILLED FROM");
                toY = text(cs, bold, 8, colRightX, toY, "BILLED TO");
                setFillColor(cs, BLACK);
                fromY = text(cs, bold, 11, left, fromY - 2, invoiceProperties.getEntityName());
                toY = text(cs, bold, 11, colRightX, toY - 2,
                        user.getFullName() != null ? user.getFullName() : user.getEmail());
                fromY = text(cs, regular, 9.5f, left, fromY, invoiceProperties.getAddress());
                toY = text(cs, regular, 9.5f, colRightX, toY, user.getEmail());
                String gstin = invoiceProperties.getGstin();
                fromY = text(cs, regular, 9.5f, left, fromY,
                        "GSTIN: " + (gstin == null || gstin.isBlank() ? "Not applicable" : gstin));
                y = Math.min(fromY, toY) - 20;

                // --- Membership period, its own clearly-labelled block ---------------------------
                // Deliberately its own visible strip (same weight as the invoice-meta strip above),
                // not a small caption folded into the line-item row -- that read as easy to miss.
                if (periodValue != null) {
                    float periodHeight = 34;
                    fillRect(cs, left, y - periodHeight + 10, pageWidth, periodHeight, LIGHT_BG);
                    setFillColor(cs, MUTED);
                    text(cs, bold, 8, left + 12, y - 5, "MEMBERSHIP PERIOD");
                    setFillColor(cs, BLACK);
                    text(cs, bold, 12, left + 12, y - 20, periodValue);
                    setFillColor(cs, BLACK);
                    y -= periodHeight + 14;
                }

                // --- Line-items table -------------------------------------------------------------
                float col1 = left, col3 = pageRight;
                float tableHeaderHeight = 24;
                fillRect(cs, left, y - tableHeaderHeight + 7, pageWidth, tableHeaderHeight, GRAPHITE);
                setFillColor(cs, PAPER);
                text(cs, bold, 9, col1 + 10, y - 4, "DESCRIPTION");
                textRightAligned(cs, bold, 9, col3 - 10, y - 4, "AMOUNT");
                setFillColor(cs, BLACK);
                y -= tableHeaderHeight + 10;

                String description = planName + (billingCycle != null ? " (" + billingCycle + ")" : "");
                y = tableRow(cs, regular, col1, col3, y, description, formatAmount(baseAmount), true);
                y = tableRow(cs, regular, col1, col3, y,
                        "GST (" + stripTrailingZeros(gstRate) + "%)", formatAmount(taxAmount), false);
                y -= 8;

                setStrokeColor(cs, BORDER);
                cs.setLineWidth(1);
                cs.moveTo(col1, y);
                cs.lineTo(pageRight, y);
                cs.stroke();
                y -= 8;

                // --- Total Paid, highlighted -----------------------------------------------------
                float totalBoxHeight = 34;
                fillRect(cs, left, y - totalBoxHeight + 10, pageWidth, totalBoxHeight, SUCCESS_BG);
                setFillColor(cs, GRAPHITE);
                text(cs, bold, 12, col1 + 10, y - 6, "Total Paid");
                setFillColor(cs, SUCCESS);
                textRightAligned(cs, bold, 14, col3 - 10, y - 8, formatAmount(total));
                setFillColor(cs, BLACK);
                y -= totalBoxHeight + 30;

                // --- Footer ------------------------------------------------------------------------
                setStrokeColor(cs, BORDER);
                cs.moveTo(left, y);
                cs.lineTo(pageRight, y);
                cs.stroke();
                y -= 20;
                setFillColor(cs, GRAPHITE);
                textCentered(cs, bold, 10, left, pageRight, y, "Thank you for choosing Fynora.");
                y -= 16;
                setFillColor(cs, MUTED);
                textCentered(cs, regular, 8, left, pageRight, y,
                        "This is a system-generated invoice and does not require a signature.");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render invoice PDF", e);
        }
    }

    /** One line-item row: label left, amount right, on an alternating (zebra) background so a
     *  multi-row table stays readable at a glance rather than as one undifferentiated block. */
    private float tableRow(PDPageContentStream cs, PDFont font, float col1, float col3, float y,
                            String label, String amount, boolean shaded) throws IOException {
        if (shaded) {
            fillRect(cs, col1, y - 8, col3 - col1, 20, LIGHT_BG);
            // fillRect leaves the fill colour set to the rect's own colour -- without resetting it
            // here, this row's text would be drawn near-white-on-white (and, since the colour
            // carries over between calls, the NEXT unshaded row's text would inherit it too).
            setFillColor(cs, BLACK);
        }
        text(cs, font, 10, col1 + 10, y, label);
        textRightAligned(cs, font, 10, col3 - 10, y, amount);
        return y - 22;
    }

    private float text(PDPageContentStream cs, PDFont font, float size, float x, float y, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(sanitizeForFont(font, value));
        cs.endText();
        return y - (size + 6);
    }

    /** Bug found on review, reproduced directly: Standard14 Helvetica only encodes
     *  WinAnsiEncoding's character set, and PDFBox throws {@code IllegalArgumentException}
     *  ("U+0928 ('nadeva') is not available in the font Helvetica") for anything outside it --
     *  Devanagari, other Indic scripts, emoji, CJK. {@code user.getFullName()} is drawn verbatim
     *  into "Billed To" and is entirely user-controlled, so without this every invoice for a user
     *  whose name uses a non-Latin-1 script would 500 on GET .../invoice AND silently fail the
     *  auto-sent purchase email (AfterCommit swallows the exception and just logs it -- see
     *  RazorpayWebhookDispatcher.sendInvoiceEmail). Every string this file draws goes through
     *  {@link #text} so this is the one place that needs to degrade instead of crash.
     *
     *  <p>The whole-string {@code encode} attempt first is the fast path: the overwhelming
     *  majority of calls (labels, amounts, dates, ASCII names) succeed immediately with no
     *  per-character cost. Only a string that actually contains an unsupported character pays for
     *  the character-by-character pass, replacing just that character with '?' rather than
     *  discarding the whole string. */
    private String sanitizeForFont(PDFont font, String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            font.encode(value);
            return value;
        } catch (IOException | IllegalArgumentException e) {
            StringBuilder safe = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                String ch = String.valueOf(value.charAt(i));
                try {
                    font.encode(ch);
                    safe.append(ch);
                } catch (IOException | IllegalArgumentException ex) {
                    safe.append('?');
                }
            }
            return safe.toString();
        }
    }

    /** rightEdge is where the text's right edge should land -- PDFBox only ever draws from a
     *  left-anchored offset, so the start x is back-computed from the string's own glyph width
     *  (getStringWidth returns 1000ths of a text-space unit, scaled by size to get points).
     *  Sanitize BEFORE measuring, not just before drawing: {@code getStringWidth} throws the exact
     *  same "not available in this font's encoding" exception {@code showText} does (confirmed
     *  directly) for a character outside WinAnsiEncoding, so an unsanitized call here would crash
     *  before {@link #text}'s own sanitization ever ran. */
    private void textRightAligned(PDPageContentStream cs, PDFont font, float size, float rightEdge, float y,
                                   String value) throws IOException {
        String safe = sanitizeForFont(font, value);
        float width = font.getStringWidth(safe) / 1000f * size;
        text(cs, font, size, rightEdge - width, y, safe);
    }

    private void textCentered(PDPageContentStream cs, PDFont font, float size, float x1, float x2, float y,
                               String value) throws IOException {
        String safe = sanitizeForFont(font, value);
        float width = font.getStringWidth(safe) / 1000f * size;
        text(cs, font, size, x1 + (x2 - x1 - width) / 2, y, safe);
    }

    private void fillRect(PDPageContentStream cs, float x, float y, float w, float h, int[] rgb) throws IOException {
        setFillColor(cs, rgb);
        cs.addRect(x, y, w, h);
        cs.fill();
    }

    /** {@code setNonStrokingColor(float,float,float)} takes 0..1 components, not 0..255 -- passing
     *  the raw int RGB triples straight through (as an int/float overload would suggest) throws
     *  IllegalArgumentException at every non-black fill. */
    private void setFillColor(PDPageContentStream cs, int[] rgb) throws IOException {
        cs.setNonStrokingColor(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f);
    }

    private void setStrokeColor(PDPageContentStream cs, int[] rgb) throws IOException {
        cs.setStrokingColor(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f);
    }

    private String formatAmount(BigDecimal amount) {
        return "Rs. " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String stripTrailingZeros(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }
}
