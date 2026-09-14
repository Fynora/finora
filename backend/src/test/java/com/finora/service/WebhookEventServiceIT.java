package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventServiceIT extends AbstractIntegrationTest {

    @Autowired private WebhookEventService webhookEventService;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Test
    void firstClaimSucceedsSecondClaimOfSameEventIdIsRejected() {
        String eventId = "evt_" + UUID.randomUUID();

        boolean first = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));
        boolean second = webhookEventService.claim(eventId, "RAZORPAY", "subscription.activated", Map.of("k", "v"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void markProcessedAndMarkFailedSetStatusAndTimestamp() {
        String eventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(eventId, "RAZORPAY", "subscription.charged", Map.of());

        webhookEventService.markProcessed(eventId);

        WebhookEvent processed = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();

        String failedEventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(failedEventId, "RAZORPAY", "subscription.halted", Map.of());
        webhookEventService.markFailed(failedEventId);

        WebhookEvent failed = webhookEventRepository.findById(failedEventId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(WebhookEvent.STATUS_FAILED);
    }

    /** Bug found in self-review of {@code WebhookEventRecoverySweepService}: a still-in-flight
     *  original request and the recovery sweep can race to resolve the same event, and a plain
     *  "find, set, implicitly save" has no defense against whichever one finishes last silently
     *  overwriting the other's terminal status -- including relabelling a real success FAILED. Both
     *  orderings are covered: the second call must be a no-op (return false, and leave the first
     *  call's status in place) regardless of which status came first. */
    @Test
    void markProcessedAndMarkFailedAreClaimOnceNeitherOverwritesAnEarlierTerminalStatus() {
        String processedFirstEventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(processedFirstEventId, "RAZORPAY", "subscription.activated", Map.of());
        assertThat(webhookEventService.markProcessed(processedFirstEventId)).isTrue();
        assertThat(webhookEventService.markFailed(processedFirstEventId)).isFalse();
        assertThat(webhookEventRepository.findById(processedFirstEventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_PROCESSED);

        String failedFirstEventId = "evt_" + UUID.randomUUID();
        webhookEventService.claim(failedFirstEventId, "RAZORPAY", "subscription.charged", Map.of());
        assertThat(webhookEventService.markFailed(failedFirstEventId)).isTrue();
        assertThat(webhookEventService.markProcessed(failedFirstEventId)).isFalse();
        assertThat(webhookEventRepository.findById(failedFirstEventId).orElseThrow().getStatus())
                .isEqualTo(WebhookEvent.STATUS_FAILED);
    }
}
