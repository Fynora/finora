package com.finora.notification.domain;

/**
 * Preference grouping. SECURITY notifications default on and are the ones a user should not be
 * able to silence on their only verified channel; MARKETING is a placeholder with no send logic
 * in v1 (proposal section 4, explicitly out of scope).
 */
public enum NotificationCategory {
    SECURITY,
    FINANCIAL,
    MARKETING,
    /**
     * Gate 2b (spec §6, Sid's decision (b), 16 Oct): nudges such as the month-start statement
     * reminder. Opt-out like FINANCIAL, with its own switch, so someone tired of reminders does not
     * also lose "your statement is ready". Push only.
     */
    REMINDERS
}
