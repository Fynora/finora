package com.finora.aisort;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One tap on "Sort with AI" on an import's Done step ({@code ai_sort_runs}, V323): which imports it
 * was for, and how far it got. Counts only. See docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md.
 */
@Entity
@Table(name = "ai_sort_runs")
public class AiSortRun {

    public enum RunStatus {
        RUNNING,
        DONE,
        /** The customer's daily AI cap or the monthly budget was reached. */
        STOPPED_LIMIT,
        /** AI was turned off (Settings, or globally) while the run was going. */
        STOPPED_AI_OFF,
        /** Anything else: the AI could not be reached, a restart, a full queue. It can be tried again. */
        STOPPED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "imports_key", nullable = false, updatable = false)
    private String importsKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RunStatus status;

    @Column(name = "payments_total", nullable = false)
    private int paymentsTotal;

    @Column(name = "payments_done", nullable = false)
    private int paymentsDone;

    @Column(name = "payments_sorted", nullable = false)
    private int paymentsSorted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** V325: which try this is; a stopped run is tried again on the same row. */
    @Column(name = "attempt", nullable = false)
    private int attempt = 1;

    /** V325: this import's payments still waiting for the customer when the run finished. */
    @Column(name = "payments_left")
    private Integer paymentsLeft;

    protected AiSortRun() {}

    public static AiSortRun start(UUID userId, String importsKey, int paymentsTotal) {
        AiSortRun run = new AiSortRun();
        run.id = UUID.randomUUID();
        run.userId = userId;
        run.importsKey = importsKey;
        run.status = RunStatus.RUNNING;
        run.paymentsTotal = paymentsTotal;
        Instant now = Instant.now();
        run.createdAt = now;
        run.updatedAt = now;
        return run;
    }

    /** Whether a tap may try this run again: it stopped, rather than finishing or still going. */
    public boolean canTryAgain() {
        return status == RunStatus.STOPPED || status == RunStatus.STOPPED_LIMIT || status == RunStatus.STOPPED_AI_OFF;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getImportsKey() { return importsKey; }
    public RunStatus getStatus() { return status; }
    public int getPaymentsTotal() { return paymentsTotal; }
    public int getPaymentsDone() { return paymentsDone; }
    public int getPaymentsSorted() { return paymentsSorted; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getAttempt() { return attempt; }
    public Integer getPaymentsLeft() { return paymentsLeft; }
}
