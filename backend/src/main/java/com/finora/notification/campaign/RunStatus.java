package com.finora.notification.campaign;

public enum RunStatus {
    RUNNING,
    DONE,
    FAILED,
    /** The slot passed while nothing could send (outage, master switch off, outside the allowed
     *  window); recorded so the history shows it, and never sent late at a bad hour. */
    MISSED,
    /** An admin cancelled sending while the run was still queuing people; whatever was queued but
     *  not yet delivered was withdrawn too. */
    CANCELLED
}
