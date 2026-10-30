package com.finora.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * The home-screen icons the mobile app can switch to. A phone can only show an icon that is already
 * bundled inside the installed app, so this list is fixed in code: adding a key here is a change
 * made together with the app build that bundles that icon, never something an admin types in.
 *
 * <p>{@link #key()} is the stable string the app receives and the schedule table stores.
 * {@link #DEFAULT} is what shows whenever no schedule window is active; it cannot be scheduled.
 */
public enum AppIcon {

    DEFAULT("default", "Standard Fynora icon"),
    DIWALI("diwali", "Diwali");

    private final String key;
    private final String label;

    AppIcon(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Whether an admin may schedule this icon. The default needs no window: it is what shows outside them. */
    public boolean schedulable() {
        return this != DEFAULT;
    }

    /** The icon for a stored or submitted key, or empty when the key is not one the app knows. */
    public static Optional<AppIcon> fromKey(String key) {
        if (key == null) return Optional.empty();
        return Arrays.stream(values()).filter(icon -> icon.key.equals(key)).findFirst();
    }
}
