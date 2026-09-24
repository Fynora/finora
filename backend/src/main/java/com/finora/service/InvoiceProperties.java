package com.finora.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Billing-entity details and GST rate InvoiceService prints on a generated invoice PDF
 * (app.billing.invoice.*). Unlike RazorpayProperties/GoogleOAuthProperties, this is not an
 * "unconfigured is supported" integration -- entity-name/address are real Fynora business
 * details set as the default, overridable per environment. Fynora Technovation LLP is
 * GST-registered (2026-09); gstin is still blank by default because it is set per environment
 * (BILLING_INVOICE_GSTIN), and InvoiceService prints "Not applicable" for blank rather than treat
 * it as a missing-config error -- a dev or test deployment must never print production's GSTIN.
 * sacCode is the GST Services Accounting Code printed beside the line item (998315, hosting and
 * IT infrastructure provisioning -- chosen by the business, 2026-09-24); blank omits it.
 */
@Configuration
@ConfigurationProperties(prefix = "app.billing.invoice")
public class InvoiceProperties {

    private String entityName;
    private String address;
    private String gstin;
    private BigDecimal gstRatePercent;
    private String sacCode;

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public BigDecimal getGstRatePercent() { return gstRatePercent; }
    public void setGstRatePercent(BigDecimal gstRatePercent) { this.gstRatePercent = gstRatePercent; }
    public String getSacCode() { return sacCode; }
    public void setSacCode(String sacCode) { this.sacCode = sacCode; }
}
