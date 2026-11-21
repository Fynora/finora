package com.finora.controller;

import com.finora.integrations.revenuecat.RevenueCatProperties;
import com.finora.integrations.revenuecat.RevenueCatSignatureVerifier;
import com.finora.service.RevenueCatWebhookDispatcher;
import com.finora.service.WebhookEventService;
import com.finora.util.LogSanitizer;
import com.finora.util.TokenHasher;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

/**
 * Subscription billing V4 (design spec §3/§4.3/§7). Unauthenticated by necessity, same posture as
 * RazorpayWebhookController -- what replaces authentication is the HMAC signature, verified before
 * anything else runs, over the RAW body (never a re-parsed object).
 *
 * <p>Event ids are prefixed ("revenuecat:...") before being handed to the shared webhook_events
 * ledger -- see this design's own §4.3: a composite (provider, event_id) primary key would need a
 * composite JPA key and new WebhookEventService signatures; a prefix gets the identical
 * collision-safety with neither. An event with no id, or an empty one, is refused (audit F-06,
 * 2026-11-20): see {@link #receive}.
 */
@RestController
@RequestMapping("/api/v1/webhooks/revenuecat")
public class RevenueCatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookController.class);
    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "revenuecat:";
    /** {@code webhook_events.event_id} is VARCHAR(50). */
    private static final int LEDGER_KEY_LENGTH = 50;

    private final RevenueCatProperties properties;
    private final WebhookEventService webhookEventService;
    private final RevenueCatWebhookDispatcher dispatcher;

    public RevenueCatWebhookController(RevenueCatProperties properties, WebhookEventService webhookEventService,
                                        RevenueCatWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-RevenueCat-Webhook-Signature") String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!RevenueCatSignatureVerifier.verify(rawBody, signature, properties.getWebhookSigningSecret(), SIGNATURE_TOLERANCE)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        JSONObject event = json.optJSONObject("event");
        if (event == null) {
            // Signed by RevenueCat but with nothing in it to process. Answered 200 because a
            // retry would carry the same nothing; logged because it should never happen.
            log.warn("A signed RevenueCat webhook carried no event object; nothing was processed.");
            return ResponseEntity.ok().build();
        }
        String eventType = event.optString("type", "unknown");
        Map<String, Object> eventPayload = event.toMap();

        // RevenueCat sends an id on every event, the dashboard's test event included, and a
        // retry reuses it (its webhook docs, "Common fields"). It is the only thing that tells a
        // second delivery from a new event. An event without one used to be given a random id,
        // so its redelivery was processed again; an empty one was stored under one shared key,
        // so the next such event was dropped as a duplicate of an unrelated one. Neither is
        // something RevenueCat produces, so both are refused rather than processed unkeyed --
        // the same answer RazorpayWebhookController gives a delivery with no event id.
        String rawEventId = event.optString("id", null);
        if (rawEventId == null || rawEventId.isBlank()) {
            log.warn("Refusing a RevenueCat webhook ({}) that carries no event id.",
                    LogSanitizer.sanitize(eventType));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String eventId = ledgerKey(rawEventId);

        if (!webhookEventService.claim(eventId, "REVENUECAT", eventType, eventPayload)) {
            log.info("Duplicate RevenueCat webhook event {} ({}), ignoring.",
                    LogSanitizer.sanitize(eventId), LogSanitizer.sanitize(eventType));
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, eventPayload);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process RevenueCat webhook event {} ({}).",
                    LogSanitizer.sanitize(eventId), LogSanitizer.sanitize(eventType), e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    /**
     * The key an event is recorded under in {@code webhook_events}: the prefix and the id, as
     * always, when that fits the column ({@link #LEDGER_KEY_LENGTH}).
     *
     * <p>RevenueCat's ids are 36-character UUIDs, which fit. Nothing in its documentation
     * promises that length, and an id that did not fit failed to store, so the request answered
     * 500, RevenueCat retried, and every retry failed the same way: that event could never be
     * processed. A longer id is recorded under a hash of itself instead. The same id always
     * gives the same key, so its redelivery is still recognised, and two different ids give
     * different keys. The id itself is still in the stored payload.
     */
    static String ledgerKey(String rawEventId) {
        String plain = KEY_PREFIX + rawEventId;
        if (plain.length() <= LEDGER_KEY_LENGTH) return plain;
        return KEY_PREFIX + TokenHasher.sha256(rawEventId).substring(0, LEDGER_KEY_LENGTH - KEY_PREFIX.length());
    }
}
