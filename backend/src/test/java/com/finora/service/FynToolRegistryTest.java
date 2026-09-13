package com.finora.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FynToolRegistryTest {

    private static FynToolDescriptor descriptor(String name, FynDataTier tier) {
        return new FynToolDescriptor(name, "test tool", "FYN_CHAT", tier, true);
    }

    @Test
    void emptyByDefault() {
        FynToolRegistry registry = new FynToolRegistry(List.of());

        assertThat(registry.all()).isEmpty();
        assertThat(registry.find("GET_BALANCE")).isEmpty();
    }

    @Test
    void findsARegisteredTool() {
        FynToolDescriptor balance = descriptor("GET_BALANCE", FynDataTier.TIER_1_AGGREGATE);
        FynToolRegistry registry = new FynToolRegistry(List.of(balance));

        assertThat(registry.find("GET_BALANCE")).contains(balance);
        assertThat(registry.all()).containsExactly(balance);
    }

    @Test
    void rejectsAConstructorArgumentWithNoName() {
        assertThatThrownBy(() -> new FynToolDescriptor("", "d", null, FynDataTier.TIER_1_AGGREGATE, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsATierlessDescriptor() {
        assertThatThrownBy(() -> new FynToolDescriptor("GET_BALANCE", "d", null, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesTwoToolsRegisteredUnderTheSameName() {
        FynToolDescriptor a = descriptor("GET_BALANCE", FynDataTier.TIER_1_AGGREGATE);
        FynToolDescriptor b = descriptor("GET_BALANCE", FynDataTier.TIER_0_PUBLIC);

        assertThatThrownBy(() -> new FynToolRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET_BALANCE");
    }
}
