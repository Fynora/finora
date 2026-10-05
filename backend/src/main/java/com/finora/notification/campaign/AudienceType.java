package com.finora.notification.campaign;

/** Who a campaign goes to. One {@link AudienceResolver} per value; add a value and its resolver
 *  together (V261's CHECK constraint on {@code push_campaigns.audience_type} lists them too). */
public enum AudienceType {
    /** Every active account with a live device that has not switched push off. */
    ALL_WITH_DEVICE,
    /** The above, who have never uploaded a statement (no import and no import job in any state). */
    NO_STATEMENT_UPLOADED
}
