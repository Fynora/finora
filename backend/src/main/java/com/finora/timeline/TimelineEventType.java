package com.finora.timeline;

import java.util.Map;

/** Single source of truth for which (bucket, importance, permanent) triple a given event type
 *  carries -- see design spec §4.1-§4.3. Callers pass only the event type constant;
 *  TimelineEventService looks up the rest here so a caller can never mismatch a type against
 *  the wrong bucket/importance. */
public final class TimelineEventType {

    public static final String FIRST_GOAL_CREATED = "FIRST_GOAL_CREATED";
    public static final String FIRST_BUDGET_CREATED = "FIRST_BUDGET_CREATED";
    public static final String FIRST_IMPORT = "FIRST_IMPORT";
    public static final String GOAL_COMPLETED = "GOAL_COMPLETED";
    public static final String GOAL_PROGRESS_50 = "GOAL_PROGRESS_50";
    public static final String NET_WORTH_10K = "NET_WORTH_10K";
    public static final String NET_WORTH_100K = "NET_WORTH_100K";

    public record Definition(String bucket, String importance, boolean permanent) {}

    private static final Map<String, Definition> DEFINITIONS = Map.of(
            FIRST_GOAL_CREATED, new Definition("STARTING", "LANDMARK", true),
            FIRST_BUDGET_CREATED, new Definition("STARTING", "MAJOR", true),
            FIRST_IMPORT, new Definition("STARTING", "MAJOR", true),
            GOAL_COMPLETED, new Definition("TRANSFORMATION", "LANDMARK", true),
            GOAL_PROGRESS_50, new Definition("PROGRESS", "MINOR", false),
            NET_WORTH_10K, new Definition("TRANSFORMATION", "LANDMARK", true),
            NET_WORTH_100K, new Definition("TRANSFORMATION", "LANDMARK", true)
    );

    public static Definition definitionOf(String eventType) {
        Definition d = DEFINITIONS.get(eventType);
        if (d == null) throw new IllegalArgumentException("Unknown timeline event type: " + eventType);
        return d;
    }

    private TimelineEventType() {}
}
