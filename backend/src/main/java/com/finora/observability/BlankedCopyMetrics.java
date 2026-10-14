package com.finora.observability;

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

    /** @param check the check's result by name (MATCHES, DIFFERS or NOT_CHECKED). A name rather
     *  than the blanked-copy package's own type, so this package does not depend on that one. */
    public void produced(String check) {
        Counter.builder("finora.blanked_copy.produced")
                .description("A blanked copy of a held statement was produced")
                .tag("check", check)
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
