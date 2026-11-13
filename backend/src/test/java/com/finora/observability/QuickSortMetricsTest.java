package com.finora.observability;

import com.finora.transactions.QuickSortDto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuickSortMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final QuickSortMetrics metrics = new QuickSortMetrics(registry);

    @Test
    void batchShownIsTaggedWithItsSource() {
        metrics.batchShown(3, QuickSortDto.Source.DASHBOARD);

        assertThat(registry.get("finora.quick_sort.batches_shown").tag("source", "dashboard")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finora.quick_sort.questions_shown").tag("source", "dashboard")
                .counter().count()).isEqualTo(3.0);
    }

    @Test
    void answeredIsTaggedWithKindAndSource() {
        metrics.answered("SHOP", QuickSortDto.Source.QUICK_SORT);

        assertThat(registry.get("finora.quick_sort.answered").tag("kind", "SHOP").tag("source", "quick_sort")
                .counter().count()).isEqualTo(1.0);
    }
}
