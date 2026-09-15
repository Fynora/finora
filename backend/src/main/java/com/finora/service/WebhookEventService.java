package com.finora.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7). {@link #claim} must run, and succeed or fail, BEFORE
 * any subscription state change — that ordering is what closes the race between two concurrent
 * deliveries of the same Razorpay event id.
 *
 * <p>Uses {@code WebhookEventRepository.insertIfAbsent} (a native {@code INSERT ... ON CONFLICT DO
 * NOTHING RETURNING}), not {@code save()} — {@code WebhookEvent.eventId} is a manually-assigned
 * natural key, and Hibernate's {@code save()} on an entity whose id is already non-null performs a
 * SELECT+UPDATE (a merge), never an INSERT, so a plain {@code saveAndFlush()} + catch
 * {@code DataIntegrityViolationException} never actually throws for a duplicate event id — it was
 * tried first and replaced after {@code firstClaimSucceedsSecondClaimOfSameEventIdIsRejected} failed
 * against a real Postgres instance (second claim returned {@code true}, not {@code false}).
 */
@Service
public class WebhookEventService {

    private final WebhookEventRepository webhookEventRepository;
    private final ObjectMapper objectMapper;

    public WebhookEventService(WebhookEventRepository webhookEventRepository, ObjectMapper objectMapper) {
        this.webhookEventRepository = webhookEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean claim(String eventId, String provider, String eventType, Map<String, Object> payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Webhook payload is not serializable to JSON.", e);
        }
        return webhookEventRepository.insertIfAbsent(eventId, provider, eventType, payloadJson).isPresent();
    }

    /** Claim-once, same discipline as {@link #claim} -- see {@code WebhookEventRepository
     *  .markStatusIfUnset}'s own doc for the concurrent-sweep-vs-still-in-flight-request race this
     *  guards against.
     *  @return true if this call set the status; false if the row was already PROCESSED/FAILED by
     *      another caller (a no-op, not an error). */
    @Transactional
    public boolean markProcessed(String eventId) {
        return webhookEventRepository.markStatusIfUnset(eventId, WebhookEvent.STATUS_PROCESSED) > 0;
    }

    /** @return true if this call set the status; false if the row was already PROCESSED/FAILED by
     *      another caller (a no-op, not an error) -- see {@link #markProcessed}. */
    @Transactional
    public boolean markFailed(String eventId) {
        return webhookEventRepository.markStatusIfUnset(eventId, WebhookEvent.STATUS_FAILED) > 0;
    }

    /** {@code @Transactional} for the same reason {@link #markProcessed}/{@link #markFailed} are --
     *  {@code reclaimFailed} is a {@code @Modifying} native query, which Spring Data refuses to run
     *  outside an active transaction. See {@code WebhookEventRepository.reclaimFailed}'s own doc for
     *  what this actually does.
     *  @return true if this call reclaimed the row, false if it was not (or no longer) FAILED. */
    @Transactional
    public boolean reclaimFailed(String eventId) {
        return webhookEventRepository.reclaimFailed(eventId) > 0;
    }
}
