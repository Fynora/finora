package com.finora.health;

/** UP: operating normally. DEGRADED: operating, but a real signal worth an admin's attention
 *  (e.g. an elevated skip rate). DOWN: not operating / a real integrity failure detected.
 *  OFF: switched off on purpose by configuration, so not a problem: it never lowers the overall
 *  status and raises no dashboard alert (a paused Gmail sync used to keep the dashboard on "Some
 *  systems are degraded" indefinitely). Missing configuration is not OFF -- that stays DEGRADED. */
public enum HealthStatus {
    UP, DEGRADED, DOWN, OFF
}
