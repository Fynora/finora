package com.finora.notification.campaign;

public enum CampaignStatus {
    DRAFT, ACTIVE, PAUSED, STOPPED, COMPLETED;

    /** Terminal states: nothing more will ever run. Clone the campaign to send it again. */
    public boolean isTerminal() {
        return this == STOPPED || this == COMPLETED;
    }
}
