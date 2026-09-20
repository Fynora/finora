package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.ContentDamage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whether the parser had to discard content, and so whether rows may be missing.
 *
 * <p>Every other rule here checks the rows that survived against each other or against what the
 * statement printed. None of them can see a row that never arrived: with the opening balance derived
 * from the first surviving row (as it is whenever the statement prints none), "opening + these rows =
 * closing" holds by construction, and a file damaged part-way was measured importing 1 of 6 rows as
 * CLEAN. The parser, though, saw the damage. This turns that into a finding.
 *
 * <p><b>Reports only when there is something to report</b> -- empty for an undamaged document, unlike
 * the other rules, which always contribute a finding. An extra "verified" line on every clean report
 * would render as "a check this version of the app does not know how to display" on every app build
 * that predates the rule; this way only a damaged file does, and those builds still get the right
 * verdict, because the status is computed here on the server.
 *
 * <p>FAILED rather than WARNING: rows may be missing, which is precisely the case the review step
 * exists to catch, and the user should be told to fetch a fresh copy, not to trust this one.
 */
public final class ContentIntegrityValidator {

    public static final String RULE = "CONTENT_INTEGRITY";

    private ContentIntegrityValidator() {}

    public static Optional<VerificationFinding> check(ContentDamage damage) {
        if (damage == null || !damage.isDamaged()) return Optional.empty();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("failedTextOperators", damage.failedTextOperators());
        details.put("corruptContentStreams", damage.corruptContentStreams());
        details.put("damagedPages", damage.pages());
        details.put("explanation",
                "Part of this file could not be read, so some transactions may be missing from this import. "
                        + "Download the statement again from your bank and import the new copy.");
        return Optional.of(new VerificationFinding(RULE, "FAILED", details));
    }
}
