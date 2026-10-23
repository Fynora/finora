package com.finora.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Caps how many alert emails one kind of event may trigger per window, and counts what it held back
 * so the next email can say so.
 *
 * <p>Needed because ticket and feedback submission are not rate limited ({@code RateLimitFilter}
 * deliberately covers only a handful of endpoints), and each submission emails every admin. A
 * customer's script, a client retry loop or an abusive account would otherwise turn one unthrottled
 * endpoint into an unbounded stream of email: a flooded inbox for every admin, a burnt provider
 * quota, and a sender address that starts to look like spam. The submissions themselves are
 * untouched; only the email is held back, and the items are all in the portal either way.
 *
 * <p>A fixed window per kind. In-memory and per instance, which is the right size: the held-back
 * emails lose nothing durable, and a second replica would at worst double the cap.
 */
final class AlertThrottle {

    /**
     * What to do with one event. When {@code send}, {@code heldBackBefore} is how many earlier
     * events of this kind were suppressed since the last email, for the email to mention.
     */
    record Admission(boolean send, int heldBackBefore, boolean firstHeldBackInWindow) {}

    private static final class Window {
        Instant start;
        int sent;
        int heldBack;
    }

    private final Clock clock;
    private final int maxPerWindow;
    private final Duration window;
    private final Map<String, Window> windows = new HashMap<>();

    AlertThrottle(Clock clock, int maxPerWindow, Duration window) {
        this.clock = clock;
        this.maxPerWindow = maxPerWindow;
        this.window = window;
    }

    synchronized Admission admit(String kind) {
        Instant now = clock.instant();
        Window w = windows.computeIfAbsent(kind, k -> {
            Window fresh = new Window();
            fresh.start = now;
            return fresh;
        });
        if (!now.isBefore(w.start.plus(window))) {
            w.start = now;
            w.sent = 0;
        }
        if (w.sent < maxPerWindow) {
            w.sent++;
            int before = w.heldBack;
            w.heldBack = 0;
            return new Admission(true, before, false);
        }
        w.heldBack++;
        return new Admission(false, 0, w.heldBack == 1);
    }
}
