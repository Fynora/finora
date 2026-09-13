package com.finora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Fyn (the AI assistant) configuration -- see docs/superpowers/specs/
 * 2026-09-13-fino-ai-implementation-plan.md §4.4 (cost governance) and §4.5 (kill switch).
 *
 * <p>Two independent ways Fyn can be inert, not one: {@code anthropicApiKey} blank is the
 * "not set up yet" no-op (same blank-default pattern as {@link EmailProperties#getApiKey()} /
 * {@link SmsProperties}), while {@code enabled}/the per-feature flags are a deliberate operator
 * toggle for a vendor outage or a cost spike -- flipping one doesn't require removing a secret.
 *
 * <p>Cost cap defaults are deliberately conservative placeholders, not a pricing decision: at the
 * usage estimated in the implementation plan's cost comparison (~20 messages/user/day on Haiku
 * 4.5), a real user costs roughly $0.03/day. {@code dailyUserCostCapUsd} defaults far above that
 * so it only trips on a genuine loop or abuse pattern, not normal use -- tune once real usage data
 * exists in {@code ai_audit_log}.
 */
@Configuration
@ConfigurationProperties(prefix = "app.fyn")
public class FynProperties {

    private boolean enabled = true;
    private boolean chatEnabled = true;
    private boolean insightsEnabled = true;
    private boolean importAssistEnabled = true;

    private String anthropicApiKey;
    private String model = "claude-haiku-4-5-20251001";
    // Overridable so tests can point AnthropicClient at a local stub server, same reasoning as
    // GoogleOAuthProperties.gmailApiBaseUrl.
    private String anthropicBaseUrl = "https://api.anthropic.com";

    private BigDecimal dailyUserCostCapUsd = new BigDecimal("1.00");
    private BigDecimal monthlyBudgetUsd = new BigDecimal("50.00");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isChatEnabled() { return chatEnabled; }
    public void setChatEnabled(boolean chatEnabled) { this.chatEnabled = chatEnabled; }
    public boolean isInsightsEnabled() { return insightsEnabled; }
    public void setInsightsEnabled(boolean insightsEnabled) { this.insightsEnabled = insightsEnabled; }
    public boolean isImportAssistEnabled() { return importAssistEnabled; }
    public void setImportAssistEnabled(boolean importAssistEnabled) { this.importAssistEnabled = importAssistEnabled; }
    public String getAnthropicApiKey() { return anthropicApiKey; }
    public void setAnthropicApiKey(String anthropicApiKey) { this.anthropicApiKey = anthropicApiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getAnthropicBaseUrl() { return anthropicBaseUrl; }
    public void setAnthropicBaseUrl(String anthropicBaseUrl) { this.anthropicBaseUrl = anthropicBaseUrl; }
    public BigDecimal getDailyUserCostCapUsd() { return dailyUserCostCapUsd; }
    public void setDailyUserCostCapUsd(BigDecimal dailyUserCostCapUsd) { this.dailyUserCostCapUsd = dailyUserCostCapUsd; }
    public BigDecimal getMonthlyBudgetUsd() { return monthlyBudgetUsd; }
    public void setMonthlyBudgetUsd(BigDecimal monthlyBudgetUsd) { this.monthlyBudgetUsd = monthlyBudgetUsd; }

    /** True only when the API key is actually configured -- distinct from {@link #isEnabled()},
     *  which is the deliberate operator kill switch. Both must be true for any Fyn call to
     *  proceed; see {@code FynAvailabilityGuard}. */
    public boolean hasApiKey() {
        return anthropicApiKey != null && !anthropicApiKey.isBlank();
    }
}
