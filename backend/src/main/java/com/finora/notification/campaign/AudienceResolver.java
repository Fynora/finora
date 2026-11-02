package com.finora.notification.campaign;

import java.util.List;
import java.util.UUID;

/**
 * Who a campaign reaches. The dry-run count, the test and the real send all use the same resolver,
 * so the number an admin sees is the number the send will try. A new audience is a new
 * implementation (and an {@link AudienceType} value), never a growing switch.
 *
 * <p>Every audience starts from {@link AudienceSql#base}: an ACTIVE end-user account with at least
 * one live device, allowed to receive a campaign of that {@link CampaignPurpose} (FINANCIAL push
 * not switched off for OPERATIONAL, MARKETING push turned on for PROMOTIONAL). {@code AudienceResolverAgreementIT} checks that
 * rule against {@code DatabaseNotificationPreferenceResolver}, so the SQL cannot quietly drift from
 * the resolver the rest of the notification system uses.
 */
public interface AudienceResolver {

    AudienceType type();

    /** How many people qualify right now for a campaign of this purpose. */
    long count(CampaignPurpose purpose);

    /** Up to {@code limit} qualifying user ids greater than {@code afterUserId}, in id order. */
    List<UUID> page(CampaignPurpose purpose, UUID afterUserId, int limit);

    /** An OPERATIONAL campaign's audience, the only kind before V305. */
    default long count() {
        return count(CampaignPurpose.OPERATIONAL);
    }

    default List<UUID> page(UUID afterUserId, int limit) {
        return page(CampaignPurpose.OPERATIONAL, afterUserId, limit);
    }
}
