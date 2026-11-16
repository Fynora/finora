package com.finora.aisort;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** The AI sorting card's API (spec docs/superpowers/specs/2026-11-14-ai-sort-after-import-design.md). */
public final class AiSortDto {

    private AiSortDto() {}

    /** SORT: the customer said yes to AI. ASK: they said no and are asked one more time. */
    public enum Mode { SORT, ASK }

    /**
     * Whether the Done step shows the card, and for how many of the import's shop payments. {@code mode}
     * is null when not shown; {@code amount} is in rupees, unrounded (the apps round).
     */
    public record Offer(boolean show, Mode mode, int payments, BigDecimal amount, int payees) {
        public static Offer none() {
            return new Offer(false, null, 0, BigDecimal.ZERO, 0);
        }
    }

    /** SORT: a yes customer's tap. TURN_ON_AND_SORT / NO_THANKS: the one-more question's two answers. */
    public enum Choice { SORT, TURN_ON_AND_SORT, NO_THANKS }

    public record StartRequest(@NotEmpty @Size(max = AiSortService.MAX_IDS) List<@NotNull UUID> statementImportIds,
                               @NotNull Choice choice) {}

    /**
     * A run's progress, in this import's payments: {@code paymentsDone} looked at, {@code paymentsSorted}
     * filed with the AI's category. {@code paymentsLeft} is what is still waiting for the customer once
     * finished -- the payments the AI could not place, not the ones the customer answered themselves
     * meanwhile (0 while running; for a run finished before V325, total less sorted).
     */
    public record Run(UUID id, AiSortRun.RunStatus status, int paymentsTotal, int paymentsDone, int paymentsSorted,
                      int paymentsLeft) {
        static Run of(AiSortRun r) {
            boolean finished = r.getStatus() != AiSortRun.RunStatus.RUNNING;
            int left = !finished ? 0
                    : r.getPaymentsLeft() != null ? r.getPaymentsLeft()
                    : Math.max(0, r.getPaymentsTotal() - r.getPaymentsSorted());
            return new Run(r.getId(), r.getStatus(), r.getPaymentsTotal(), r.getPaymentsDone(), r.getPaymentsSorted(), left);
        }
    }
}
