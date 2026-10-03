package com.finora.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RecurringDto(
        String merchant,
        String label,       // Weekly | Biweekly | Monthly | Quarterly
        BigDecimal averageAmount,
        int occurrences,
        LocalDate lastDate,
        LocalDate nextEstimate,
        // The recurring-payment question (docs/superpowers/specs/2026-10-02-recurring-payment-answer-design.md).
        // Added after the six fields above, which older app versions read and which keep their meaning.
        String category,          // most common category among the group's rows; null if none
        BigDecimal latestAmount,  // the most recent payment the user did not file by hand (else the most recent)
        String answer,            // the saved answer's category (the user's PAYEE rule), or null
        QuestionState state
) {
    /** Whether, and how, the app asks about this payment. */
    public enum QuestionState {
        /** No saved answer, and every payment is still "Other" or a structural "Personal Transfer"
         *  guess, none set by hand. */
        NEEDS_ANSWER,
        /** A saved answer exists and its amount range covers the latest payment. */
        ANSWERED,
        /** A saved answer exists but the latest payment is outside its range. */
        AMOUNT_CHANGED,
        /** Nothing to ask. */
        NONE
    }

    /** The six original fields, for callers that predate the question; state NONE. */
    public RecurringDto(String merchant, String label, BigDecimal averageAmount, int occurrences,
                        LocalDate lastDate, LocalDate nextEstimate) {
        this(merchant, label, averageAmount, occurrences, lastDate, nextEstimate, null, null, null, QuestionState.NONE);
    }
}
