package com.finora.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LayoutAlertMetricsTest {

    @Test
    void countsAlertsByTierAndByWhetherTheLayoutWasAlreadyFlagged() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LayoutAlertMetrics metrics = new LayoutAlertMetrics(registry);

        metrics.sent("IMMEDIATE", false);
        metrics.sent("IMMEDIATE", false);
        metrics.sent("IMMEDIATE", true);
        metrics.sent("DIGEST", false);

        assertThat(registry.get("finora.layout_review.alert").tag("tier", "IMMEDIATE").tag("escalated", "false")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("finora.layout_review.alert").tag("tier", "IMMEDIATE").tag("escalated", "true")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finora.layout_review.alert").tag("tier", "DIGEST").tag("escalated", "false")
                .counter().count()).isEqualTo(1.0);
    }
}
