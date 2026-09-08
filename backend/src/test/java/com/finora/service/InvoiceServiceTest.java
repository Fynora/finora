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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/v1/billing/history/{id}/invoice. The GST split (18% by default) is a fixed product
 * decision, not a computed fact -- see InvoiceService's own class doc -- so this locks in the
 * exact arithmetic (826.00 charged -> 700.00 base + 126.00 GST, a round-number fixture chosen so
 * the assertion isn't itself hiding a rounding bug).
 */
class InvoiceServiceTest {

    private PaymentRepository paymentRepository;
    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private UserRepository userRepository;
    private InvoiceProperties invoiceProperties;
    private InvoiceService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        userRepository = mock(UserRepository.class);
        invoiceProperties = new InvoiceProperties();
        invoiceProperties.setEntityName("Fynora Technovation LLP");
        invoiceProperties.setAddress("Sipri Bazaar, Jhansi, Uttar Pradesh, 284003");
        invoiceProperties.setGstin(null);
        invoiceProperties.setGstRatePercent(BigDecimal.valueOf(18));

        service = new InvoiceService(paymentRepository, subscriptionRepository, planRepository,
                userRepository, invoiceProperties);

        User user = new User();
        user.setEmail("invoice-test@example.com");
        user.setFullName("Invoice Test User");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    private Payment payment(UUID owner, String status, BigDecimal amount) {
        Payment p = new Payment();
        ReflectionTestUtils.setField(p, "id", paymentId);
        p.setUserId(owner);
        p.setAmount(amount);
        p.setCurrency("INR");
        p.setProvider("RAZORPAY");
        p.setStatus(status);
        p.setProviderTransactionId("pay_test123");
        ReflectionTestUtils.setField(p, "createdAt", Instant.parse("2026-09-08T10:00:00Z"));
        return p;
    }

    @Test
    void generate_throwsNotFound_whenPaymentDoesNotExist() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate(userId, paymentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void generate_throwsNotFound_whenThePaymentBelongsToAnotherUser() {
        Payment p = payment(UUID.randomUUID(), Payment.STATUS_SUCCESS, BigDecimal.valueOf(826));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.generate(userId, paymentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void generate_throwsConflict_whenThePaymentIsNotSuccess() {
        Payment p = payment(userId, Payment.STATUS_PENDING, BigDecimal.valueOf(826));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.generate(userId, paymentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void generate_producesAWellFormedPdf_withTheEntityAndGstBreakupOnIt() throws IOException {
        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(826));
        p.setSubscriptionId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        Subscription subscription = new Subscription();
        UUID planId = UUID.randomUUID();
        subscription.setPlanId(planId);
        subscription.setBillingCycle("MONTHLY");
        when(subscriptionRepository.findById(p.getSubscriptionId())).thenReturn(Optional.of(subscription));

        Plan plan = new Plan();
        plan.setCode("PLUS");
        plan.setName("Plus");
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        InvoiceService.GeneratedInvoice invoice = service.generate(userId, paymentId);

        assertThat(invoice.fileName()).startsWith("INV-2026-").endsWith(".pdf");
        assertThat(invoice.pdfBytes()).isNotEmpty();
        assertThat(new String(invoice.pdfBytes(), 0, 4)).isEqualTo("%PDF");

        String text;
        try (PDDocument document = Loader.loadPDF(invoice.pdfBytes())) {
            text = new PDFTextStripper().getText(document);

            // Company logo (frontend/public/favicon.png, embedded via InvoiceService.LOGO_RESOURCE)
            // -- present as a real image XObject on the page, not just a claim in a comment.
            org.apache.pdfbox.pdmodel.PDResources resources = document.getPage(0).getResources();
            boolean hasEmbeddedImage = false;
            for (org.apache.pdfbox.cos.COSName xObjectName : resources.getXObjectNames()) {
                if (resources.getXObject(xObjectName) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    hasEmbeddedImage = true;
                }
            }
            assertThat(hasEmbeddedImage).as("invoice page should embed the Fynora logo image").isTrue();
        }
        assertThat(text).contains("Fynora Technovation LLP");
        assertThat(text).contains("Sipri Bazaar, Jhansi, Uttar Pradesh, 284003");
        assertThat(text).contains("GSTIN: Not applicable");
        assertThat(text).contains("Plus (MONTHLY)");
        assertThat(text).contains("Rs. 700.00"); // base
        assertThat(text).contains("Rs. 126.00"); // GST @ 18%
        assertThat(text).contains("Rs. 826.00"); // total paid
        assertThat(text).contains("GST (18%)");
        assertThat(text).contains("Invoice Test User");
        assertThat(text).contains("invoice-test@example.com");
        // Membership period is computed from payment.createdAt + billingCycle, NOT read off the
        // live Subscription row (see renderPdf's own comment for why that would be wrong for an
        // older invoice) -- fixture createdAt is 2026-09-08T10:00:00Z (15:30 IST, still 08 Sep),
        // MONTHLY billing cycle, so the period is exactly one calendar month.
        assertThat(text).contains("MEMBERSHIP PERIOD");
        assertThat(text).contains("08 Sep 2026 - 08 Oct 2026");
    }

    @Test
    void generate_usesThePlanFrozenOnThePaymentItself_notTheLiveSubscriptionsCurrentPlan() throws IOException {
        // Bug found on review: the live Subscription row is mutated in place on every
        // upgrade/downgrade (RazorpayWebhookDispatcher.handleActivated/handleCharged), so reading
        // planName/billingCycle off it at invoice-generation time -- rather than off what this
        // SPECIFIC payment actually paid for -- would silently relabel an old Plus payment as
        // Premium the moment the user upgrades. V171 freezes plan/cycle onto the Payment row at
        // charge time; this proves that frozen value wins even when the live subscription now
        // disagrees.
        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(399));
        UUID subscriptionId = UUID.randomUUID();
        p.setSubscriptionId(subscriptionId);
        UUID paidPlanId = UUID.randomUUID();
        p.setPlanId(paidPlanId);
        p.setBillingCycle("MONTHLY");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        Plan planAtChargeTime = new Plan();
        planAtChargeTime.setCode("PLUS");
        planAtChargeTime.setName("Plus");
        when(planRepository.findById(paidPlanId)).thenReturn(Optional.of(planAtChargeTime));

        // The live subscription has since been upgraded to Premium -- must NOT leak into this
        // already-completed Plus payment's invoice.
        Subscription currentSubscription = new Subscription();
        UUID currentPlanId = UUID.randomUUID();
        currentSubscription.setPlanId(currentPlanId);
        currentSubscription.setBillingCycle("YEARLY");
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(currentSubscription));
        Plan currentPlan = new Plan();
        currentPlan.setCode("PREMIUM");
        currentPlan.setName("Premium");
        when(planRepository.findById(currentPlanId)).thenReturn(Optional.of(currentPlan));

        InvoiceService.GeneratedInvoice invoice = service.generate(userId, paymentId);

        String text;
        try (PDDocument document = Loader.loadPDF(invoice.pdfBytes())) {
            text = new PDFTextStripper().getText(document);
        }
        assertThat(text).contains("Plus (MONTHLY)");
        assertThat(text).doesNotContain("Premium");
        assertThat(text).doesNotContain("YEARLY");
    }

    @Test
    void generate_computesAYearMembershipPeriod_whenBillingCycleIsYearly() throws IOException {
        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(8000));
        p.setSubscriptionId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        Subscription subscription = new Subscription();
        UUID planId = UUID.randomUUID();
        subscription.setPlanId(planId);
        subscription.setBillingCycle("YEARLY");
        when(subscriptionRepository.findById(p.getSubscriptionId())).thenReturn(Optional.of(subscription));
        Plan plan = new Plan();
        plan.setName("Premium");
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));

        InvoiceService.GeneratedInvoice invoice = service.generate(userId, paymentId);

        String text;
        try (PDDocument document = Loader.loadPDF(invoice.pdfBytes())) {
            text = new PDFTextStripper().getText(document);
        }
        assertThat(text).contains("08 Sep 2026 - 08 Sep 2027");
    }

    @Test
    void generate_doesNotCrash_whenTheUsersFullNameUsesANonLatin1Script() throws IOException {
        // Bug found on review, reproduced directly against PDFBox before this fix existed:
        // Standard14 Helvetica only encodes WinAnsiEncoding, so drawing a Devanagari name straight
        // into "Billed To" threw IllegalArgumentException ("U+0928 ('nadeva') is not available in
        // the font Helvetica") and 500'd the whole invoice -- both the View/Download buttons and
        // the auto-sent purchase email (silently, since AfterCommit swallows the exception there).
        User user = new User();
        user.setEmail("invoice-test@example.com");
        user.setFullName("नीरज मेहता");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(826));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        InvoiceService.GeneratedInvoice invoice = service.generate(userId, paymentId);

        assertThat(invoice.pdfBytes()).isNotEmpty();
        assertThat(new String(invoice.pdfBytes(), 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void generate_fallsBackToAGenericPlanName_whenThePaymentHasNoSubscription() throws IOException {
        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(826));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        InvoiceService.GeneratedInvoice invoice = service.generate(userId, paymentId);

        String text;
        try (PDDocument document = Loader.loadPDF(invoice.pdfBytes())) {
            text = new PDFTextStripper().getText(document);
        }
        assertThat(text).contains("Fynora Subscription");
        // No subscription/billingCycle to compute a period from -- must degrade cleanly (no
        // fabricated dates), not print a "Membership period" line built off a null cycle.
        assertThat(text).doesNotContain("MEMBERSHIP PERIOD");
    }

    @Test
    void generate_isDeterministic_forTheSamePaymentId() {
        Payment p = payment(userId, Payment.STATUS_SUCCESS, BigDecimal.valueOf(826));
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        InvoiceService.GeneratedInvoice first = service.generate(userId, paymentId);
        InvoiceService.GeneratedInvoice second = service.generate(userId, paymentId);

        assertThat(first.fileName()).isEqualTo(second.fileName());
    }
}
