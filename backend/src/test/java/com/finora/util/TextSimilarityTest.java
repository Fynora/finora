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

    // Bug fix (found during Plan 3's post-implementation review): transactions.description is
    // nullable at the DB level (VARCHAR(500), no NOT NULL constraint -- V1__init_schema.sql), and
    // is genuinely possible to be null for an AA-sourced row specifically (Setu's narration field
    // is documented elsewhere as unverified against a real sandbox response). Both callers of this
    // method (ReconciliationService's AA-vs-manual pass, already merged, and its AA-vs-Gmail pass)
    // call it directly on raw descriptions with no null-guard of their own -- an unguarded .length()
    // here would throw a NullPointerException inside reconcileForUser, which has eight production
    // callers (transaction create/update/delete, every import path). Treating a missing description
    // as "no similarity" (0.0) is the safe default: never falsely match on absent data, consistent
    // with duplicateKey()'s own established treatment of a null description elsewhere in this file.
    @Test
    void aNullDescriptionOnEitherSideScoresZeroRatherThanThrowing() {
        assertThat(TextSimilarity.normalizedSimilarity(null, "amazon")).isEqualTo(0.0);
        assertThat(TextSimilarity.normalizedSimilarity("amazon", null)).isEqualTo(0.0);
        assertThat(TextSimilarity.normalizedSimilarity(null, null)).isEqualTo(0.0);
    }
}
