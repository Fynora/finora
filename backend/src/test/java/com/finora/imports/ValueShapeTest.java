package com.finora.imports;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValueShapeTest {

    @Test
    void digitsBecomeNine_lettersBecomeX_andEverythingElseIsKept() {
        assertThat(ValueShape.of("17-Sept-2026 14:05")).isEqualTo("99-XXXX-9999 99:99");
        assertThat(ValueShape.of("(1,500.00)/-")).isEqualTo("(9,999.99)/-");
    }

    @Test
    void aValueAtTheLimitIsKeptWhole_andOneCharacterMoreIsCutAndMarked() {
        String atLimit = "a".repeat(ValueShape.MAX_LENGTH);

        assertThat(ValueShape.of(atLimit)).isEqualTo("X".repeat(ValueShape.MAX_LENGTH));
        assertThat(ValueShape.of(atLimit + "b")).isEqualTo("X".repeat(ValueShape.MAX_LENGTH) + "~");
    }

    @Test
    void anEmptyValueHasAnEmptyShape() {
        assertThat(ValueShape.of("")).isEmpty();
    }
}
