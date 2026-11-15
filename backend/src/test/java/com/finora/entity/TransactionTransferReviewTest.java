package com.finora.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A payment linked as a transfer between the user's own accounts stops waiting for a category, and
 * unlinking gives back exactly the flag the link took (spec
 * docs/superpowers/specs/2026-11-15-card-bill-issuer-list-design.md, fix 2).
 */
class TransactionTransferReviewTest {

    private static Transaction waiting() {
        Transaction t = new Transaction();
        t.setNeedsCategoryReview(true);
        return t;
    }

    @Test
    void linkingAWaitingPaymentClearsItsReview() {
        Transaction t = waiting();
        t.setTransfer(true);
        assertThat(t.isTransfer()).isTrue();
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isReviewClearedByTransfer()).isTrue();
    }

    @Test
    void unlinkingPutsTheReviewBack() {
        Transaction t = waiting();
        t.setTransfer(true);
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.isReviewClearedByTransfer()).isFalse();
    }

    @Test
    void unlinkKeepsACategoryChosenByHand() {
        Transaction t = waiting();
        t.setTransfer(true);
        t.setCategoryManuallySet(true);
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isReviewClearedByTransfer()).isFalse();
    }

    @Test
    void linkingAPaymentThatWasNotWaitingChangesNothingOnUnlink() {
        Transaction t = new Transaction();
        t.setTransfer(true);
        assertThat(t.isReviewClearedByTransfer()).isFalse();
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isFalse();
    }

    // A statement refresh re-decides an unreviewed row's category and review flag from corrected
    // text (StatementRefreshService.applyCategoryDecision) while the row stays linked: the flag must
    // not come back on a linked row, and must come back once the row is unlinked.
    @Test
    void flaggingALinkedRowWaitsUntilItIsUnlinked() {
        Transaction t = new Transaction();
        t.setTransfer(true);
        t.setNeedsCategoryReview(true);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isReviewClearedByTransfer()).isTrue();
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isTrue();
    }

    @Test
    void clearingTheFlagOnALinkedRowForgetsTheHeldOne() {
        Transaction t = waiting();
        t.setTransfer(true);
        t.setNeedsCategoryReview(false);
        assertThat(t.isReviewClearedByTransfer()).isFalse();
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isFalse();
    }

    @Test
    void repeatedCallsChangeNothing() {
        Transaction t = waiting();
        t.setTransfer(true);
        t.setTransfer(true);
        assertThat(t.isNeedsCategoryReview()).isFalse();
        assertThat(t.isReviewClearedByTransfer()).isTrue();
        t.setTransfer(false);
        t.setTransfer(false);
        assertThat(t.isNeedsCategoryReview()).isTrue();
        assertThat(t.isReviewClearedByTransfer()).isFalse();

        Transaction never = waiting();
        never.setTransfer(false);
        assertThat(never.isNeedsCategoryReview()).isTrue();
        assertThat(never.isReviewClearedByTransfer()).isFalse();
    }
}
