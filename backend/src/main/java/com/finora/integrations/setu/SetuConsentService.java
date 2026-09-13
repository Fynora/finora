package com.finora.integrations.setu;

import com.finora.entity.FeatureEntitlement;
import com.finora.exception.ApiException;
import com.finora.service.AuditService;
import com.finora.service.EntitlementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SetuConsentService {

    private final AccountAggregatorLinkRepository links;
    private final SetuConsentGateway gateway;
    private final EntitlementService entitlementService;
    private final AuditService auditService;
    private final int linkCap;
    private final long relinkThrottleHours;

    public SetuConsentService(AccountAggregatorLinkRepository links, SetuConsentGateway gateway,
                               EntitlementService entitlementService, AuditService auditService,
                               @Value("${app.integrations.setu.link-cap:5}") int linkCap,
                               @Value("${app.integrations.setu.relink-throttle-hours:24}") long relinkThrottleHours) {
        this.links = links;
        this.gateway = gateway;
        this.entitlementService = entitlementService;
        this.auditService = auditService;
        this.linkCap = linkCap;
        this.relinkThrottleHours = relinkThrottleHours;
    }

    /** @param idempotencyKey client-minted, unique per (user, attempt) -- see
     *                        AccountAggregatorLink's own doc comment. */
    public InitiateLinkResult initiateLink(UUID userId, FiType fiType, String idempotencyKey) {
        // Bug fix (found during post-implementation review): neither argument was validated before
        // use. A null fiType or a null/blank idempotencyKey used to fall all the way through to a
        // NOT NULL database constraint violation -- an opaque 500 instead of a clean 400, and for
        // fiType specifically, only AFTER already calling gateway.createConsent (a real, billable
        // Setu call once a real gateway exists) for a request that was malformed from the start.
        if (fiType == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fiType is required.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "idempotencyKey is required.");
        }
        if (!entitlementService.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Account Aggregator sync is a Premium feature.");
        }

        // Bootstrap values only -- both are open product decisions (Plan 5 scope doc: link cap
        // number, and whether PAUSED counts toward it). Named config, not hardcoded, so a later
        // decision changes a property, not this logic.
        List<AccountAggregatorLinkStatus> statusesCountedTowardCap =
                List.of(AccountAggregatorLinkStatus.CONSENT_PENDING,
                        AccountAggregatorLinkStatus.PENDING_ACCOUNT_CONFIRMATION,
                        AccountAggregatorLinkStatus.ACTIVE);
        if (links.countByUserIdAndStatusIn(userId, statusesCountedTowardCap) >= linkCap) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You've reached the maximum number of linked bank accounts.");
        }
        if (links.existsByUserIdAndFiTypeAndCreatedAtAfter(
                userId, fiType, Instant.now().minus(Duration.ofHours(relinkThrottleHours)))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Please wait before starting another bank connection of this type.");
        }

        Optional<AccountAggregatorLink> existing =
                links.findByUserIdAndLinkIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            // A retried or double-submitted request for the SAME attempt -- return the row already
            // created, redirectUrl null because there is nothing new to redirect to (the caller
            // already holds one from the original response, or is retrying after losing it, in
            // which case they need to start a fresh attempt with a new key, not reuse a dead one).
            return new InitiateLinkResult(existing.get(), null);
        }

        if (!gateway.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Account Aggregator sync is not available right now.");
        }

        AccountAggregatorLink link = new AccountAggregatorLink();
        link.setUserId(userId);
        link.setFiType(fiType);
        link.setLinkIdempotencyKey(idempotencyKey);

        SetuConsentInitiation initiation;
        try {
            initiation = gateway.createConsent(userId.toString(), fiType);
        } catch (RuntimeException e) {
            link.setStatus(AccountAggregatorLinkStatus.LINK_FAILED);
            links.save(link);
            auditService.record(userId, "ACCOUNT_AGGREGATOR_LINK_FAILED", "AccountAggregatorLink", link.getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start linking your account. Please try again.");
        }

        link.setConsentHandleId(initiation.consentHandleId());
        link.setStatus(AccountAggregatorLinkStatus.CONSENT_PENDING);
        try {
            link = links.save(link);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent requests for the same (user, idempotencyKey) both passed the
            // empty-check above before either committed -- the unique index on
            // account_aggregator_links(user_id, link_idempotency_key) is the real guarantee (see
            // that migration's own comment), and this is the second request losing the race. The
            // Setu consent this request just created is an orphan (no link row references it) --
            // acceptable: it costs one extra consent creation on the rare concurrent-double-submit
            // case, which is far cheaper than either a 500 or a duplicate link row would be. Return
            // whichever row actually won, exactly like the existing-key branch above.
            return new InitiateLinkResult(
                    links.findByUserIdAndLinkIdempotencyKey(userId, idempotencyKey).orElseThrow(() -> e), null);
        }
        auditService.record(userId, "ACCOUNT_AGGREGATOR_CONSENT_CREATED", "AccountAggregatorLink", link.getId());

        return new InitiateLinkResult(link, initiation.redirectUrl());
    }

    public record InitiateLinkResult(AccountAggregatorLink link, String redirectUrl) {}
}
