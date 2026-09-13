package com.finora.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class TextSimilarityTest {

    @Test
    void identicalStringsAreFullySimilar() {
        assertThat(TextSimilarity.normalizedSimilarity("amazon", "amazon")).isEqualTo(1.0);
    }

    @Test
    void emptyStringsAreFullySimilar() {
        assertThat(TextSimilarity.normalizedSimilarity("", "")).isEqualTo(1.0);
    }

    @Test
    void closeButNotIdenticalTokensScoreAboveTheGmailThreshold() {
        // "amazon" vs "amzn" -- 2 edits over 6 chars ~ 0.67 -- the exact case
        // GmailReconciliationMatcher's own SIMILARITY_THRESHOLD (0.6) comment cites.
        assertThat(TextSimilarity.normalizedSimilarity("amazon", "amzn")).isCloseTo(0.67, offset(0.01));
    }

    @Test
    void completelyDifferentTokensScoreLow() {
        assertThat(TextSimilarity.normalizedSimilarity("swiggy", "amazon")).isLessThan(0.3);
    }
}
