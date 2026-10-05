package com.finora.notification.campaign;

public enum ScheduleKind {
    /** Sent only when an admin presses "send now". */
    NOW_ONLY,
    /** Sent once, at an instant inside the 07:00-21:59 IST window. */
    ONCE_AT,
    /** Sent every IST day at a time of day inside the window, until stopped or past its end date. */
    DAILY_AT
}
