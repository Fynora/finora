package com.finora.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every Fyn-callable tool, by name -- plan §4.1. Empty in Phase 1 (no tools exist yet); Phase 2-3
 * contribute their tools' descriptors as {@code @Bean List<FynToolDescriptor>}; Phase 4's chat
 * tools (plan §6) contribute a {@link FynChatTool} bean instead, which carries both the
 * descriptor's metadata and the actual execution -- {@link FynChatTool#toDescriptor()} is derived
 * from the same fields Anthropic is told about, so the two can never drift apart the way two
 * independent declarations of the same tool could.
 *
 * <p>Duplicate names fail at startup, not at call time: a silently-shadowed tool would be a
 * governance gap (the wrong entitlement/tier check winning) that's much cheaper to catch here.
 */
@Component
public class FynToolRegistry {

    private final Map<String, FynToolDescriptor> byName;

    public FynToolRegistry(List<FynToolDescriptor> descriptors, List<FynChatTool> chatTools) {
        List<FynToolDescriptor> all = new java.util.ArrayList<>(descriptors);
        chatTools.forEach(t -> all.add(t.toDescriptor()));
        this.byName = all.stream()
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
