package com.finora.notification.domain;

/**
 * Preference grouping. SECURITY notifications default on and are the ones a user should not be
 * able to silence on their only verified channel. MARKETING is opt-in (off unless the user turns
 * it on, push only): PROMOTIONAL push campaigns (V305) are its only sender.
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
