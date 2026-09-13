package com.finora.integrations.setu;

import java.time.Instant;
import java.util.UUID;

public record AccountAggregatorLinkDto(UUID id, FiType fiType, AccountAggregatorLinkStatus status,
                                        Instant consentExpiresAt, Instant lastSyncedAt,
                                        AccountAggregatorLink.SyncStatus lastSyncStatus,
                                        Instant statusChangedAt) {
    public static AccountAggregatorLinkDto from(AccountAggregatorLink link) {
        return new AccountAggregatorLinkDto(link.getId(), link.getFiType(), link.getStatus(),
                link.getConsentExpiresAt(), link.getLastSyncedAt(), link.getLastSyncStatus(),
                link.getStatusChangedAt());
    }
}
