package com.finora.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FynToolRegistryTest {

    private static FynToolDescriptor descriptor(String name, FynDataTier tier) {
        return new FynToolDescriptor(name, "test tool", "FYN_CHAT", tier, true);
    }

    /** Minimal {@link FynChatTool} stub -- exercises the "derived descriptor" path Phase 4's real
     *  tools also go through, without depending on any of them. */
    private static FynChatTool chatTool(String name) {
        return new FynChatTool() {
            public String name() { return name; }
            public String description() { return "test chat tool"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            public String execute(UUID userId, Map<String, Object> input) { return "unused"; }
        };
    }

    @Test
    void emptyByDefault() {
        FynToolRegistry registry = new FynToolRegistry(List.of(), List.of());

        assertThat(registry.all()).isEmpty();
        assertThat(registry.find("GET_BALANCE")).isEmpty();
    }

    @Test
    void findsARegisteredTool() {
        FynToolDescriptor balance = descriptor("GET_BALANCE", FynDataTier.TIER_1_AGGREGATE);
        FynToolRegistry registry = new FynToolRegistry(List.of(balance), List.of());

        assertThat(registry.find("GET_BALANCE")).contains(balance);
        assertThat(registry.all()).containsExactly(balance);
    }

    @Test
    void derivesADescriptorFromAFynChatToolBean() {
        FynToolRegistry registry = new FynToolRegistry(List.of(), List.of(chatTool("GET_SPEND_BY_CATEGORY")));

        assertThat(registry.find("GET_SPEND_BY_CATEGORY")).isPresent();
        assertThat(registry.find("GET_SPEND_BY_CATEGORY").get().requiredEntitlement())
                .isEqualTo(com.finora.entity.FeatureEntitlement.FYN_CHAT);
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

        assertThatThrownBy(() -> new FynToolRegistry(List.of(a, b), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET_BALANCE");
    }

    @Test
    void refusesACollisionBetweenAPlainDescriptorAndAChatToolDerivedOne() {
        FynToolDescriptor a = descriptor("GET_BALANCE", FynDataTier.TIER_1_AGGREGATE);

        assertThatThrownBy(() -> new FynToolRegistry(List.of(a), List.of(chatTool("GET_BALANCE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GET_BALANCE");
    }
}
