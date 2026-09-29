package com.finora.imports;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Places a layout into the profile its {@link LayoutIdentity} names, as that profile's next version
 * (V244) -- the automatic half of layout profiles. Shared by staging ({@link LayoutReviewService})
 * and the one-time backfill of existing layouts ({@link LayoutProfileBackfill}), so both follow the
 * same rules.
 *
 * <p>Must run inside the caller's transaction: it takes row locks (the layout, then the profile)
 * that have to be held until the caller commits.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>Only a layout nobody has decided about ({@code profile_link_source IS NULL}) is linked. An
 *       operator's link, and an operator's decision to take a layout out of a profile, both stay.</li>
 *   <li>A layout the engine already linked to a DIFFERENT group is not moved; that is reported as
 *       {@link Outcome#CONFLICT} so the caller can flag it -- one fingerprint seen as two banks or
 *       two account families is a detection problem a person should look at.</li>
 *   <li>The profile is found by its auto key, else adopted by name (an operator may have created
 *       "Kotak Mahindra Bank — Credit Card" by hand before this existed), else created.</li>
 *   <li>The version follows first appearance: the layout goes right after every member first seen
 *       before it, and later members move up one. Decided under a lock on the profile row (taken
 *       after the layout's own row, the order every writer uses), so concurrent layouts never get
 *       the same number.</li>
 * </ul>
 */
@Component
public class LayoutProfileAutoLinker {

    public enum Outcome { LINKED, ALREADY_IN_GROUP, OPERATOR_DECIDED, CONFLICT, NO_IDENTITY, NOT_REGISTERED }

    /** Larger than any real version, so a shifted block never collides with an unshifted one. */
    private static final int SHIFT_OFFSET = 1_000_000;

    private final JdbcTemplate jdbc;
    private final com.finora.service.AuditService auditService;

    public LayoutProfileAutoLinker(JdbcTemplate jdbc, com.finora.service.AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    public Outcome link(String fingerprint, LayoutIdentity identity) {
        return link(fingerprint, identity, null);
    }

    /** As above, attributed to the operator whose action caused it (returning a layout to
     *  automatic grouping), so any renumbering it causes is audited with that actor. */
    public Outcome link(String fingerprint, LayoutIdentity identity, UUID actingAdminId) {
        if (identity == null) return Outcome.NO_IDENTITY;
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.profile_id, r.profile_link_source, p.auto_key
                FROM layout_registry r LEFT JOIN layout_profile p ON p.id = r.profile_id
                WHERE r.fingerprint = ? FOR UPDATE OF r
                """, fingerprint);
        if (rows.isEmpty()) return Outcome.NOT_REGISTERED;
        Map<String, Object> row = rows.get(0);
        String source = (String) row.get("profile_link_source");
        if ("MANUAL".equals(source)) return Outcome.OPERATOR_DECIDED;
        if ("AUTO".equals(source)) {
            return identity.key().equals(row.get("auto_key")) ? Outcome.ALREADY_IN_GROUP : Outcome.CONFLICT;
        }

        UUID profileId = profileFor(identity);
        if (profileId == null) return Outcome.CONFLICT;
        place(fingerprint, profileId, "AUTO", actingAdminId);
        return Outcome.LINKED;
    }

    /**
     * Puts a layout into a profile at the version its first appearance earns, and records who
     * decided ("AUTO" or "MANUAL"). Shared with the operator's own "add to profile"
     * (LayoutCurationService), so every profile is ordered the same way however its members got
     * there. The caller must already hold the layout's row lock; this takes the profile's.
     *
     * <p>Any member that moves up is recorded: its previous number and the time are kept on the row
     * (V245) and one audit entry lists every move, so a renumbering is never silent.
     *
     * @param actingAdminId the operator who caused the placement, or null when the engine did
     * @return the version the layout now has
     */
    public int place(String fingerprint, UUID profileId, String source, UUID actingAdminId) {
        Object firstSeen = jdbc.queryForObject(
                "SELECT first_seen FROM layout_registry WHERE fingerprint = ?", Object.class, fingerprint);
        jdbc.queryForObject("SELECT id FROM layout_profile WHERE id = ? FOR UPDATE", UUID.class, profileId);

        // Placed by when the layout first appeared, not by when it happened to be grouped: an older
        // format grouped late (its first statement carried no bank evidence) must still read as the
        // earlier version. It goes right after every member first seen before it, and the members
        // after that move up one. Two statements for the move because the (profile, version) unique
        // index is checked row by row: shift clear of every real number first, then back down.
        Integer before = jdbc.queryForObject("""
                SELECT max(profile_version) FROM layout_registry
                WHERE profile_id = ? AND (first_seen < ? OR (first_seen = ? AND fingerprint < ?))
                """, Integer.class, profileId, firstSeen, firstSeen, fingerprint);
        int version = before == null ? 1 : before + 1;
        // Only when that number is taken do the later members move; a gap left by an earlier
        // removal is simply filled, so nobody is renumbered for nothing.
        Integer taken = jdbc.queryForObject(
                "SELECT count(*) FROM layout_registry WHERE profile_id = ? AND profile_version = ?",
                Integer.class, profileId, version);
        List<Map<String, Object>> moving = taken == null || taken == 0 ? List.of() : jdbc.queryForList(
                "SELECT fingerprint, profile_version FROM layout_registry WHERE profile_id = ? AND profile_version >= ? "
                        + "ORDER BY profile_version", profileId, version);
        if (!moving.isEmpty()) {
            jdbc.update("UPDATE layout_registry SET profile_version = profile_version + " + SHIFT_OFFSET
                    + " WHERE profile_id = ? AND profile_version >= ?", profileId, version);
            // Each moved layout keeps the number it had, so the admin screen can say "was v2" (V245).
            jdbc.update("UPDATE layout_registry SET previous_profile_version = profile_version - " + SHIFT_OFFSET
                    + ", profile_version = profile_version - " + (SHIFT_OFFSET - 1)
                    + ", profile_version_changed_at = now(), updated_at = now()"
                    + " WHERE profile_id = ? AND profile_version >= ?", profileId, SHIFT_OFFSET);
        }
        jdbc.update("""
                UPDATE layout_registry
                SET profile_id = ?, profile_version = ?, profile_link_source = ?,
                    previous_profile_version = NULL, profile_version_changed_at = NULL, updated_at = now()
                WHERE fingerprint = ?
                """, profileId, version, source, fingerprint);
        if (!moving.isEmpty()) {
            StringBuilder moves = new StringBuilder();
            for (Map<String, Object> m : moving) {
                if (!moves.isEmpty()) moves.append(", ");
                int old = ((Number) m.get("profile_version")).intValue();
                moves.append(m.get("fingerprint")).append(" v").append(old).append("->v").append(old + 1);
            }
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("insertedFingerprint", fingerprint);
            metadata.put("insertedVersion", version);
            metadata.put("moved", moves.toString());
            metadata.put("source", source);
            if (actingAdminId != null) metadata.put("actorId", actingAdminId.toString());
            auditService.record(actingAdminId, "LAYOUT_PROFILE_VERSIONS_SHIFTED", "LayoutProfile", profileId, metadata);
        }
        return version;
    }

    /** The profile answering to this identity's key: by key, else an unkeyed profile of the same
     *  name (adopted), else a new one. Null only if the name belongs to a profile keyed to
     *  something else, which the caller treats as a conflict rather than guessing. */
    private UUID profileFor(LayoutIdentity identity) {
        jdbc.update("""
                INSERT INTO layout_profile (id, name, auto_key, created_at, updated_at)
                SELECT gen_random_uuid(), ?, ?, now(), now()
                WHERE NOT EXISTS (SELECT 1 FROM layout_profile WHERE lower(name) = lower(?))
                ON CONFLICT DO NOTHING
                """, identity.profileName(), identity.key(), identity.profileName());
        List<UUID> byKey = jdbc.queryForList("SELECT id FROM layout_profile WHERE auto_key = ?", UUID.class, identity.key());
        if (!byKey.isEmpty()) return byKey.get(0);
        // Same name, created by an operator without a key: adopt it.
        int adopted = jdbc.update("UPDATE layout_profile SET auto_key = ?, updated_at = now() "
                + "WHERE lower(name) = lower(?) AND auto_key IS NULL", identity.key(), identity.profileName());
        if (adopted == 0) return null;
        return jdbc.queryForObject("SELECT id FROM layout_profile WHERE auto_key = ?", UUID.class, identity.key());
    }
}
