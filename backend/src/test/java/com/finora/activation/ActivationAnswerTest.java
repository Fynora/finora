package com.finora.activation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ActivationAnswerTest {

    @Test
    void everyAnswerButSkippedBelongsToExactlyOneMoment() {
        for (ActivationAnswer answer : ActivationAnswer.values()) {
            if (answer == ActivationAnswer.SKIPPED) continue;
            long moments = Arrays.stream(ActivationMoment.values()).filter(answer::allowedFor).count();
            assertThat(moments).as(answer.name()).isEqualTo(1);
        }
    }

    @Test
    void skippedIsAllowedForEveryMoment() {
        for (ActivationMoment moment : ActivationMoment.values()) {
            assertThat(ActivationAnswer.SKIPPED.allowedFor(moment)).as(moment.name()).isTrue();
        }
    }

    /** Spec §4.1: the lists, in the order the clients show them. */
    @Test
    void eachMomentHasTheSpecifiedAnswers() {
        assertThat(answersFor(ActivationMoment.NO_FILE)).containsExactly(
                ActivationAnswer.NO_STATEMENT, ActivationAnswer.NOT_COMFORTABLE, ActivationAnswer.DIDNT_KNOW_HOW,
                ActivationAnswer.NO_POINT_YET, ActivationAnswer.NOT_NOW);
        assertThat(answersFor(ActivationMoment.REFUSED)).containsExactly(
                ActivationAnswer.TRY_ANOTHER_FILE, ActivationAnswer.NO_OTHER_STATEMENT,
                ActivationAnswer.DONT_UNDERSTAND_FAILURE, ActivationAnswer.LEAVE_FOR_NOW);
        assertThat(answersFor(ActivationMoment.NOT_CONFIRMED)).containsExactly(
                ActivationAnswer.ROWS_LOOKED_WRONG, ActivationAnswer.WRONG_FILE,
                ActivationAnswer.NOT_READY_TO_SAVE, ActivationAnswer.COME_BACK_LATER);
    }

    private static ActivationAnswer[] answersFor(ActivationMoment moment) {
        return Arrays.stream(ActivationAnswer.values())
                .filter(a -> a != ActivationAnswer.SKIPPED && a.allowedFor(moment))
                .toArray(ActivationAnswer[]::new);
    }
}
