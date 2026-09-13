package com.finora.util;

/**
 * Normalized Levenshtein similarity between two strings (1.0 = identical, 0.0 = completely
 * dissimilar). Extracted from {@code GmailReconciliationMatcher}'s own private implementation
 * (Plan 2 of the Account Aggregator sync feature, Task 7) so a second caller --
 * {@code ReconciliationService}'s AA-vs-manual fuzzy pass -- doesn't grow an independently
 * drifting copy of "how similar are two bank narrations." Both callers now share one
 * implementation; behavior is unchanged from what {@code GmailReconciliationMatcher} already did.
 */
public final class TextSimilarity {

    private TextSimilarity() {}

    public static double normalizedSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshteinDistance(a, b) / maxLen;
    }

    private static int levenshteinDistance(String a, String b) {
        int[] previousRow = new int[b.length() + 1];
        int[] currentRow = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previousRow[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            currentRow[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitutionCost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                currentRow[j] = Math.min(
                        Math.min(currentRow[j - 1] + 1, previousRow[j] + 1),
                        previousRow[j - 1] + substitutionCost);
            }
            int[] swap = previousRow;
            previousRow = currentRow;
            currentRow = swap;
        }
        return previousRow[b.length()];
    }
}
