package com.finora.imports.send;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;

class StatementSendCodeTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void codeUsesOnlyTheSafeAlphabet() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(StatementSendService.newCode(random)).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]+");
        }
        assertThat(StatementSendService.ALPHABET).doesNotContain("I", "L", "O", "0", "1");
    }

    @Test
    void codeIsSixCharacters() {
        assertThat(StatementSendService.newCode(random)).hasSize(6);
    }

    @Test
    void typedCodeIgnoresCaseSpacesAndDashes() {
        assertThat(StatementSendService.normalise(" ab3-k7 m ")).isEqualTo("AB3K7M");
        assertThat(StatementSendService.normalise(null)).isEmpty();
    }

    @Test
    void hashIsHexSha256AndNeverTheCode() {
        String hash = StatementSendService.hash("AB3K7M");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+").doesNotContain("AB3K7M");
    }
}
