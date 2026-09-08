package com.finora.controller;

import com.finora.dto.ApiResponse;
import com.finora.dto.BillingDtos.BillingHistoryEntryDto;
import com.finora.security.CurrentUser;
import com.finora.service.BillingHistoryService;
import com.finora.service.InvoiceService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** D-28 PR4-B. The user's own billing history (proposal §3.4) -- real Payment rows once the
 *  Razorpay webhook dispatcher has written at least one (see RazorpayWebhookDispatcher.
 *  handleCharged), not the permanently-empty list the original PR4-B comment here described. */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingHistoryController {

    private final BillingHistoryService billingHistoryService;
    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    public BillingHistoryController(BillingHistoryService billingHistoryService, InvoiceService invoiceService,
                                     CurrentUser currentUser) {
        this.billingHistoryService = billingHistoryService;
        this.invoiceService = invoiceService;
        this.currentUser = currentUser;
    }

    @GetMapping("/history")
    public ApiResponse<List<BillingHistoryEntryDto>> history() {
        return ApiResponse.ok(billingHistoryService.history(currentUser.id()));
    }

    /** Scoped to the caller's own payment inside InvoiceService.generate (looked up by userId +
     *  paymentId together, not paymentId alone) -- a 404 for someone else's payment id, same
     *  ownership discipline as every other per-resource GET in this codebase. */
    @GetMapping("/history/{paymentId}/invoice")
    public ResponseEntity<byte[]> invoice(@PathVariable UUID paymentId) {
        InvoiceService.GeneratedInvoice invoice = invoiceService.generate(currentUser.id(), paymentId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(invoice.fileName()).build().toString())
                .body(invoice.pdfBytes());
    }
}
