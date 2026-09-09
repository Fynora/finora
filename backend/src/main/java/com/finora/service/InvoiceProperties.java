package com.finora.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Billing-entity details and GST rate InvoiceService prints on a generated invoice PDF
 * (app.billing.invoice.*). Unlike RazorpayProperties/GoogleOAuthProperties, this is not an
 * "unconfigured is supported" integration -- entity-name/address are real Fynora business
 * details set as the default, overridable per environment. gstin defaults to blank because
 * Fynora is not GST-registered; InvoiceService prints "Not applicable" rather than treat blank
 * as a missing-config error.
 */
@Configuration
@ConfigurationProperties(prefix = "app.billing.invoice")
public class InvoiceProperties {

    private String entityName;
    private String address;
    private String gstin;
    private BigDecimal gstRatePercent;

    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public BigDecimal getGstRatePercent() { return gstRatePercent; }
    public void setGstRatePercent(BigDecimal gstRatePercent) { this.gstRatePercent = gstRatePercent; }
}
