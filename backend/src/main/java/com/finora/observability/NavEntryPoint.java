package com.finora.observability;

import java.util.Arrays;
import java.util.Optional;

/**
 * How a destination was reached.
 *
 * <p>{@code GROUP} is its taxonomy entry; the rest are promoted shortcuts and contextual links.
 * This is the dimension that answers, empirically, whether a group entry is used at all once an
 * item also has a tab or a FAB -- the question the taxonomy's Shortcut rule currently answers only
 * by assertion.
 *
 * <p>See {@link NavDestination} for why {@link #fromWire} has no fallback constant.
 */
public enum NavEntryPoint {
    GROUP("group"),
    TAB("tab"),
    FAB("fab"),
    HEADER("header"),
    CONTEXTUAL("contextual"),
    SEARCH("search");

    private final String wire;

    NavEntryPoint(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static Optional<NavEntryPoint> fromWire(String value) {
        return Arrays.stream(values()).filter(e -> e.wire.equals(value)).findFirst();
    }
}
