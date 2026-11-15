package com.finora.observability;

import com.finora.aisort.AiSortDto;
import com.finora.aisort.AiSortRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * AI sorting after an import (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md,
 * "Measuring"): the card shown, the taps, how runs end, and payees sorted or left. Whether customers
 * keep the AI's guesses is counted by {@link QuickSortMetrics#aiGuessAnswered}.
 */
@Component
public class AiSortMetrics {

    private final MeterRegistry registry;

    public AiSortMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void offerShown(AiSortDto.Mode mode) {
        Counter.builder("finora.ai_sort.offer").description("The AI sorting card was shown")
                .tag("mode", tag(mode)).register(registry).increment();
    }

    public void choice(AiSortDto.Choice choice) {
        Counter.builder("finora.ai_sort.choice").description("A tap on the AI sorting card")
                .tag("choice", tag(choice)).register(registry).increment();
    }

    public void runFinished(AiSortRun.RunStatus status) {
        Counter.builder("finora.ai_sort.run").description("An AI sorting run ended")
                .tag("status", tag(status)).register(registry).increment();
    }

    /** One payee worked: sorted when the AI's answer filed at least one of the import's payments. */
    public void payee(boolean sorted) {
        Counter.builder("finora.ai_sort.payees").description("Payees an AI sorting run worked")
                .tag("outcome", sorted ? "sorted" : "left").register(registry).increment();
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
