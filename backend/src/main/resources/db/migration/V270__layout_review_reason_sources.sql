-- Which upload raised each review reason on a flagged layout.
--
-- review_reasons accumulates across every staging run while a layout stays flagged, but
-- review_analysis_reference names only the latest run that added a reason. On 2026-10-14 that sent
-- triage to a clean upload: a layout flagged "Staging failed" by three failed uploads pointed at a
-- later upload that had read every row and passed every check, and the failures could only be
-- found by searching Layout Studio for the fingerprint.
--
-- review_reason_sources maps each reason to the analysis reference of the run that first raised it
-- in the current flag (e.g. {"STAGING_FAILED": "SA-...", "IDENTITY_CONFLICT": "SA-..."}). A reason
-- raised again while still flagged keeps its first source; resolving clears the map with the flag.
-- Layouts flagged before this column existed have no sources (NULL): there is nothing to backfill
-- from, because the earlier runs' references were overwritten.

ALTER TABLE layout_registry ADD COLUMN review_reason_sources JSONB;
