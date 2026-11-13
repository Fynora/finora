package com.finora.observability.jobs;

import com.finora.observability.AlertSeverity;
import com.finora.observability.WorkerExecution;
import com.finora.observability.WorkerObservability;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue workers already report their failures through {@link WorkerExecution}; those reports
 * reach the background job's run too. An expected, customer-caused give-up ({@link AlertSeverity#NONE},
 * for example a corrupt PDF) and a retry are not failures of the job.
 */
class WorkerExecutionReportTest {

    private WorkerObservability observability;

    @BeforeEach
    void setUp() {
        observability = new WorkerObservability(new SimpleMeterRegistry());
        BackgroundJobReport.begin();
    }

    @AfterEach
    void tearDown() {
        BackgroundJobReport.end();
    }

    private int failedItemsAfter(java.util.function.Consumer<WorkerExecution> event) {
        try (WorkerExecution execution = observability.beginScheduled("worker", "kind")) {
            event.accept(execution);
        }
        return BackgroundJobReport.end().failedItems();
    }

    @Test
    void deadLetteredWithErrorOrWarning_reportsAFailedItem() {
        assertThat(failedItemsAfter(e -> e.deadLettered(UUID.randomUUID(), 3, new RuntimeException("x")))).isEqualTo(1);
        BackgroundJobReport.begin();
        assertThat(failedItemsAfter(e -> e.deadLettered(UUID.randomUUID(), 3, new RuntimeException("x"),
                AlertSeverity.WARNING))).isEqualTo(1);
    }

    @Test
    void deadLetteredWithNone_reportsNothing() {
        assertThat(failedItemsAfter(e -> e.deadLettered(UUID.randomUUID(), 1, new RuntimeException("corrupt pdf"),
                AlertSeverity.NONE))).isZero();
    }

    @Test
    void retryScheduled_reportsNothing() {
        assertThat(failedItemsAfter(e -> e.retryScheduled(UUID.randomUUID(), 1))).isZero();
    }

    @Test
    void failureNotRecorded_reportsAFailedItem() {
        assertThat(failedItemsAfter(e -> e.failureNotRecorded(UUID.randomUUID(), new RuntimeException("x")))).isEqualTo(1);
    }

    @Test
    void heldWithoutReviewRecord_reportsAFailedItem() {
        assertThat(failedItemsAfter(e -> e.heldWithoutReviewRecord(UUID.randomUUID(), new RuntimeException("x"))))
                .isEqualTo(1);
    }

    @Test
    void deadLetteredAsExpected_reportsNothing() {
        assertThat(failedItemsAfter(e -> e.deadLetteredAsExpected(UUID.randomUUID(), 1,
                new IllegalStateException("no device to send to")))).isZero();
    }
}
