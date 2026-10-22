package com.finora.dto;

import com.finora.people.PeopleSuggestions;

import java.math.BigDecimal;

/**
 * One row of Insights' "To look at", built on the server (AI insights foundation §8.2) exactly as
 * mobile/src/lib/insightsFeed.ts's buildToLookAt built it on the phone. {@code kind} is one of
 * {@code card-statement}, {@code people}, {@code new-recurring}, {@code amount-changed},
 * {@code running-high}; each kind fills only its own fields, the rest are null.
 *
 * <p>{@code key} names the row across requests: {@code ackKind|ackItemKey} for a row that can be
 * marked "Got it", {@code NEW_RECURRING|<payee, lower-cased>} for a new repeating payment (it is
 * answered, never dismissed). {@code whyAvailable} is true on a running-high row whose usual month
 * holds at least 2 months of history.
 */
public record LookAtRowDto(
        String kind,
        String key,
        String ackKind,
        String ackItemKey,
        // card-statement and running-high
        String month,
        // card-statement
        BigDecimal total,
        Integer count,
        String cardName,
        String cardLast4,
        // people
        PeopleSuggestions.Suggestion people,
        // new-recurring
        RecurringDto recurring,
        // amount-changed
        String merchant,
        String category,
        BigDecimal latestAmount,
        String latestDate,
        // running-high (category above too)
        BigDecimal current,
        BigDecimal priorAverage,
        Boolean whyAvailable
) {
    public static LookAtRowDto cardStatement(InsightsDto.UnlinkedCardBills b, String ackItemKey) {
        return new LookAtRowDto("card-statement", "CARD_STATEMENT_MISSING|" + ackItemKey, "CARD_STATEMENT_MISSING",
                ackItemKey, b.month(), b.total(), b.count(), b.cardName(), b.cardLast4(),
                null, null, null, null, null, null, null, null, null);
    }

    public static LookAtRowDto people(PeopleSuggestions.Suggestion s) {
        return new LookAtRowDto("people", s.kind() + "|" + s.ackKey(), s.kind(), s.ackKey(), null, null, null,
                null, null, s, null, null, null, null, null, null, null, null);
    }

    public static LookAtRowDto newRecurring(RecurringDto r, String normalizedMerchant) {
        return new LookAtRowDto("new-recurring", "NEW_RECURRING|" + normalizedMerchant, null, null, null, null, null,
                null, null, null, r, null, null, null, null, null, null, null);
    }

    public static LookAtRowDto amountChanged(String merchant, String category, BigDecimal latestAmount,
                                             String latestDate, String ackItemKey) {
        return new LookAtRowDto("amount-changed", "AMOUNT_CHANGED|" + ackItemKey, "AMOUNT_CHANGED", ackItemKey,
                null, null, null, null, null, null, null, merchant, category, latestAmount, latestDate, null, null, null);
    }

    public static LookAtRowDto runningHigh(String category, BigDecimal current, BigDecimal priorAverage, String month,
                                           String ackItemKey, boolean whyAvailable) {
        return new LookAtRowDto("running-high", "CATEGORY_HIGH|" + ackItemKey, "CATEGORY_HIGH", ackItemKey, month,
                null, null, null, null, null, null, null, category, null, null, current, priorAverage, whyAvailable);
    }
}
