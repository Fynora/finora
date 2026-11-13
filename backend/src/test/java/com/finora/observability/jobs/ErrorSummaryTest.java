package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorSummaryTest {

    @Test
    void keepsTypeAndFirstFinoraFrame_neverTheMessage() {
        IllegalStateException e = thrower();

        String summary = ErrorSummary.of(e);

        assertThat(summary).startsWith("IllegalStateException at ErrorSummaryTest:");
        assertThat(summary).matches("IllegalStateException at ErrorSummaryTest:\\d+");
        assertThat(summary).doesNotContain("@").doesNotContain("someone").doesNotContain("bad row");
    }

    @Test
    void anErrorWithNoFinoraFrame_isTypeOnly() {
        RuntimeException e = new RuntimeException("card 4111 for someone@example.com");
        e.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.postgresql.Driver", "connect", "Driver.java", 10)});

        assertThat(ErrorSummary.of(e)).isEqualTo("RuntimeException");
    }

    @Test
    void nullIsNull() {
        assertThat(ErrorSummary.of(null)).isNull();
    }

    @Test
    void anAnonymousOrNestedTypeStillHasAName() {
        RuntimeException anon = new RuntimeException() {};
        assertThat(ErrorSummary.of(anon)).startsWith("RuntimeException at ErrorSummaryTest:");
    }

    private static IllegalStateException thrower() {
        try {
            throw new IllegalStateException("bad row for someone@example.com");
        } catch (IllegalStateException e) {
            return e;
        }
    }
}
