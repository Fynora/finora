package com.finora.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount as the apps print it in a sentence: Indian grouping (₹1,20,000), whole rupees. Built by
 * hand because the JDK's en-IN number format groups in thousands (measured: 120,000), so a
 * server-worded sentence would disagree with the figure beside it.
 *
 * <p>The sign is dropped: a sentence says "₹74,200 more" or "₹74,200 less", never a minus.
 */
public final class Rupees {

    private Rupees() {}

    public static String format(BigDecimal value) {
        String whole = value.abs().setScale(0, RoundingMode.HALF_UP).toPlainString();
        StringBuilder grouped = new StringBuilder();
        int firstGroup = Math.min(whole.length(), 3);
        grouped.insert(0, whole.substring(whole.length() - firstGroup));
        for (int end = whole.length() - firstGroup; end > 0; end -= 2) {
            grouped.insert(0, whole.substring(Math.max(0, end - 2), end) + ",");
        }
        return "₹" + grouped;
    }
}
