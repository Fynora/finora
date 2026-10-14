package com.finora.observability;

import com.finora.imports.blanked.BlankedCopy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Counts only: how many blanked copies were made, by check result, and how many were refused. */
@Component
public class BlankedCopyMetrics {

    private final MeterRegistry registry;

    public BlankedCopyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void produced(BlankedCopy.Check check) {
        Counter.builder("finora.blanked_copy.produced")
                .description("A blanked copy of a held statement was produced")
                .tag("check", check.name())
                .register(registry)
                .increment();
    }

    public void refused() {
        Counter.builder("finora.blanked_copy.refused")
                .description("A blanked copy was refused by the privacy scan")
                .register(registry)
                .increment();
    }
}
