package com.finora.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every Fyn-callable tool, by name -- plan §4.1. Empty in Phase 1 (no tools exist yet); Phase 2-4
 * each contribute their tools' descriptors as {@code @Bean List<FynToolDescriptor>} (or a single
 * descriptor collected into Spring's list-of-beans injection), so this class never needs to change
 * as tools are added -- only the descriptor list Spring hands it grows.
 *
 * <p>Duplicate names fail at startup, not at call time: a silently-shadowed tool would be a
 * governance gap (the wrong entitlement/tier check winning) that's much cheaper to catch here.
 */
@Component
public class FynToolRegistry {

    private final Map<String, FynToolDescriptor> byName;

    public FynToolRegistry(List<FynToolDescriptor> descriptors) {
        this.byName = descriptors.stream()
                .collect(Collectors.toUnmodifiableMap(FynToolDescriptor::name, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Two Fyn tools registered under the same name: " + a.name());
                        }));
    }

    public Optional<FynToolDescriptor> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<FynToolDescriptor> all() {
        return List.copyOf(byName.values());
    }
}
