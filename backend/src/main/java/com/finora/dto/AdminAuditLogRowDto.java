package com.finora.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of the admin portal's Audit Log, with the people involved named so an admin can read the
 * trail without looking every id up by hand.
 *
 * <p>{@code userId} is the account the entry is about (for a customer's own action, also who did
 * it). {@code actorId} is the admin who acted on that account, read from whichever of the keys in
 * {@code AuditLogRepository.ADMIN_ACTOR_KEYS} the writing service used; null when the account acted
 * for itself or no admin was recorded. {@code systemActor} names a background job that acted
 * ({@code metadata.actor}), when one did.
 *
 * <p>Many admin-side writes put the acting admin in the row's account slot and name no actor. Such a
 * row (a staff account, no actor key, no job) is read as that admin's act: {@code actorId} is the
 * admin and {@code userId} is null -- the row is about its entity -- or, for a read audit
 * (ADMIN_USER_DATA_VIEWED), the customer whose data was opened (AdminAuditLogService.toRow).
 *
 * <p>Names and emails are null when the account no longer exists, and the customer's are also null
 * when the caller lacks USER_VIEW: AUDIT_VIEW alone (the FINANCE and OPS presets) never revealed who
 * a customer is, and naming them here must not change that. Staff accounts are always named.
 *
 * <p>{@code redactedAt} is set once the retention sweep has replaced {@code metadata} (BH-044), so
 * the portal can say the detail was removed rather than show an empty payload as if nothing was
 * recorded.
 */
public record AdminAuditLogRowDto(
        UUID id,
        UUID userId,
        String userName,
        String userEmail,
        UUID actorId,
        String actorName,
        String actorEmail,
        String systemActor,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> metadata,
        String requestId,
        Instant createdAt,
        Instant redactedAt
) {}
