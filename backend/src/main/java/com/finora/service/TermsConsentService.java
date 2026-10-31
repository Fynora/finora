package com.finora.service;

import com.finora.entity.ClientPlatform;
import com.finora.entity.User;
import com.finora.entity.UserTermsAcceptance;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AiAuditLogRepository;
import com.finora.repository.AiInsightTextRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.UserTermsAcceptanceRepository;
import com.finora.support.ClientIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Acceptance of the Terms and Privacy Policy, and the "AI features" choice -- privacy workstreams 7
 * and 8 (docs/superpowers/specs/2026-10-31-ai-consent-and-terms-acceptance-design.md).
 *
 * <p>Every acceptance is a new {@code user_terms_acceptances} row (append-only), and the latest is
 * mirrored onto {@code users.terms_*}. The AI choice lives on {@code users.ai_processing_consent};
 * {@link FynAvailabilityGuard} refuses every per-user AI call unless it is {@code true}.
 */
@Service
public class TermsConsentService {

    private final UserRepository users;
    private final UserTermsAcceptanceRepository acceptances;
    private final AiInsightTextRepository aiInsightTexts;
    private final AiAuditLogRepository aiAuditLog;
    private final AuditService auditService;
    private final ClientIdentity clientIdentity;

    public TermsConsentService(UserRepository users, UserTermsAcceptanceRepository acceptances,
                               AiInsightTextRepository aiInsightTexts, AiAuditLogRepository aiAuditLog,
                               AuditService auditService, ClientIdentity clientIdentity) {
        this.users = users;
        this.acceptances = acceptances;
        this.aiInsightTexts = aiInsightTexts;
        this.aiAuditLog = aiAuditLog;
        this.auditService = auditService;
        this.clientIdentity = clientIdentity;
    }

    /** True when the user's latest acceptance is not the current Terms version (or there is none). */
    public static boolean termsAcceptanceRequired(User user) {
        return !LegalTerms.CURRENT_VERSION.equals(user.getTermsVersion());
    }

    /**
     * The sign-up form's two boxes, applied to an account that has just been saved.
     *
     * @param acceptedTerms null from an app released before the required box existed: that user saw
     *                      only the passive notice, so nothing is recorded and the consent step asks
     *                      them later. False is rejected -- the forms never send it.
     * @param aiProcessing  null from the same older apps (not asked yet); otherwise the user's answer
     */
    @Transactional
    public void applySignUpChoices(User user, Boolean acceptedTerms, Boolean aiProcessing) {
        if (Boolean.FALSE.equals(acceptedTerms)) {
            throw new ApiException(ErrorCode.TERMS_NOT_ACCEPTED);
        }
        ClientPlatform channel = clientIdentity.platform();
        Instant now = Instant.now();
        if (Boolean.TRUE.equals(acceptedTerms)) {
            accept(user, LegalTerms.CURRENT_VERSION, channel, now);
        }
        if (aiProcessing != null) {
            setAi(user, aiProcessing, channel, now);
        }
        users.save(user);
    }

    /**
     * The consent step and the Settings switch.
     *
     * @param acceptTermsVersion the version the user just ticked the box for, or null when this call
     *                           only changes the AI choice. Must be the current version: anything else
     *                           means the app showed a document that has since changed.
     * @param aiProcessing       the AI choice, or null to leave it as it is
     */
    @Transactional
    public User submit(UUID userId, String acceptTermsVersion, Boolean aiProcessing) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.AUTH_FORBIDDEN));
        if (acceptTermsVersion != null && !LegalTerms.CURRENT_VERSION.equals(acceptTermsVersion)) {
            throw new ApiException(ErrorCode.TERMS_VERSION_OUTDATED);
        }
        ClientPlatform channel = clientIdentity.platform();
        Instant now = Instant.now();
        if (acceptTermsVersion != null) {
            accept(user, acceptTermsVersion, channel, now);
        }
        if (aiProcessing != null && !aiProcessing.equals(user.getAiProcessingConsent())) {
            boolean withdrawing = Boolean.TRUE.equals(user.getAiProcessingConsent()) && !aiProcessing;
            setAi(user, aiProcessing, channel, now);
            if (withdrawing) {
                // The deletes below clear the persistence context (their @Modifying settings), so
                // the user's changes are flushed first and the returned entity is the merged one.
                users.saveAndFlush(user);
                eraseAiOutput(userId);
            }
        }
        return users.save(user);
    }

    private void accept(User user, String version, ClientPlatform channel, Instant at) {
        acceptances.save(new UserTermsAcceptance(user.getId(), version, at, channel));
        user.recordTermsAcceptance(version, at);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("version", version);
        metadata.put("channel", channel.name());
        auditService.record(user.getId(), "TERMS_ACCEPTED", "User", user.getId(), metadata);
    }

    private void setAi(User user, boolean enabled, ClientPlatform channel, Instant at) {
        user.recordAiProcessingConsent(enabled, at);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enabled", enabled);
        metadata.put("channel", channel.name());
        auditService.record(user.getId(), "AI_PROCESSING_CONSENT_CHANGED", "User", user.getId(), metadata);
    }

    /**
     * Withdrawal: the AI insight texts written under the consent are deleted, and the facts sent to
     * and returned from Anthropic are cleared from {@code ai_audit_log} (its rows stay as the record
     * of calls and cost). Ask Fyn conversations stay: they are the user's own record, shown only to
     * them, and the Free plan's daily question count is read from them.
     */
    private void eraseAiOutput(UUID userId) {
        int texts = aiInsightTexts.deleteByUserId(userId);
        int payloads = aiAuditLog.clearToolPayloadsByUserId(userId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("aiInsightTextsDeleted", texts);
        metadata.put("aiAuditPayloadsCleared", payloads);
        auditService.record(userId, "AI_OUTPUT_ERASED", "User", userId, metadata);
    }
}
