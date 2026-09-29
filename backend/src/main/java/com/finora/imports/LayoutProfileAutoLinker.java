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
 *   <li>The version is the profile's highest + 1, read under a lock on the profile row, so two
 *       layouts arriving together get consecutive versions.</li>
 * </ul>
 */
@Component
public class LayoutProfileAutoLinker {

    public enum Outcome { LINKED, ALREADY_IN_GROUP, OPERATOR_DECIDED, CONFLICT, NO_IDENTITY, NOT_REGISTERED }

    private final JdbcTemplate jdbc;

    public LayoutProfileAutoLinker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Outcome link(String fingerprint, LayoutIdentity identity) {
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
        jdbc.queryForObject("SELECT id FROM layout_profile WHERE id = ? FOR UPDATE", UUID.class, profileId);
        Integer max = jdbc.queryForObject(
                "SELECT max(profile_version) FROM layout_registry WHERE profile_id = ?", Integer.class, profileId);
        int version = max == null ? 1 : max + 1;
        jdbc.update("""
                UPDATE layout_registry
                SET profile_id = ?, profile_version = ?, profile_link_source = 'AUTO', updated_at = now()
                WHERE fingerprint = ? AND profile_link_source IS NULL
                """, profileId, version, fingerprint);
        return Outcome.LINKED;
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
