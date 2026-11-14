package com.finora.aisort;

import java.math.BigDecimal;

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
}
