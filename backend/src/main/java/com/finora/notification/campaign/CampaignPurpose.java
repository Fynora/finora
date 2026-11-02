package com.finora.notification.campaign;

import com.finora.notification.domain.NotificationCategory;

/**
 * What a push campaign is for, which decides its notification category and so who may receive it
 * (V305).
 *
 * <ul>
 *   <li>{@link #OPERATIONAL}: about the user's own use of the app ("upload your statement", "your
 *       report is ready"). Sent as FINANCIAL, which is on unless the user switched it off.</li>
 *   <li>{@link #PROMOTIONAL}: offers, sales and news. Sent as MARKETING, which is off unless the
 *       user turned it on, so it reaches only users who did.</li>
 * </ul>
 */
public enum CampaignPurpose {
    OPERATIONAL(NotificationCategory.FINANCIAL),
    PROMOTIONAL(NotificationCategory.MARKETING);

    private final NotificationCategory category;

    CampaignPurpose(NotificationCategory category) {
        this.category = category;
    }

    public NotificationCategory category() {
        return category;
    }
}
