package com.finora.validation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordFitsHashTest {

    private final PasswordFitsHash.Validator validator = new PasswordFitsHash.Validator();

    private boolean fits(String password) {
        return validator.isValid(password, null);
    }

    @Test
    void seventyTwoBytesFit_seventyThreeDoNot() {
        assertThat(fits("a".repeat(72))).isTrue();
        assertThat(fits("a".repeat(73))).isFalse();
    }

    @Test
    void itCountsBytes_notCharacters() {
        String hindi24 = "क".repeat(24);
        String hindi25 = "क".repeat(25);
        assertThat(hindi24.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(fits(hindi24)).isTrue();
        assertThat(fits(hindi25)).as("25 characters, 75 bytes").isFalse();
        assertThat(fits("😀".repeat(18))).as("72 bytes").isTrue();
        assertThat(fits("😀".repeat(19))).as("76 bytes").isFalse();
        assertThat(fits("Ab1!" + "क".repeat(22))).as("4 + 66 = 70 bytes").isTrue();
    }

    @Test
    void nullIsLeftToNotBlank() {
        assertThat(fits(null)).isTrue();
    }
}
