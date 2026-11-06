package com.finora.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts only: layout-review alerts sent, by tier and by whether the layout was already flagged. */
@Component
public class LayoutAlertMetrics {

    private final MeterRegistry registry;

    public LayoutAlertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param tier      the tier's name (IMMEDIATE or DIGEST); a name rather than the imports
     *                  package's own type, so this package does not depend on that one
     * @param escalated true when the layout was already flagged and this alert is the urgent one
     *                  it had not yet had; false for the alert sent when a flag is first raised
     */
    public void sent(String tier, boolean escalated) {
        Counter.builder("finora.layout_review.alert")
                .description("A layout-review alert email was handed off, by tier")
                .tag("tier", tier)
                .tag("escalated", Boolean.toString(escalated))
                .register(registry)
                .increment();
    }
}
