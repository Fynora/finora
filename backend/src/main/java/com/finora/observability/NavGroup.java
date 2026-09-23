package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five taxonomy groups plus the ungrouped root.
 *
 * <p>See {@link NavDestination} for why {@link #fromWire} has no fallback constant.
 */
public enum NavGroup {
    ROOT("root"),
    MONEY("money"),
    STATEMENTS("statements"),
    PLANNING("planning"),
    ANALYSIS("analysis"),
    YOUR_ACCOUNT("your-account");

    private final String wire;

    NavGroup(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<NavGroup> fromWire(String value) {
        return Arrays.stream(values()).filter(g -> g.wire.equals(value)).findFirst();
    }
}
