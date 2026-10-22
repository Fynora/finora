-- Removes statement cells from unparseable_summary_json rows written before PR #2299.
--
-- Until then UnparseableRowSummary keyed its reasons histogram by the review-screen reason verbatim,
-- and for a row the normaliser refused that reason quotes the cell it could not read:
--   Date value "<cell>" didn't match any known date format
--   Amount value "<cell>" didn't match any known numeric format
-- So these admin-read, indefinitely kept summaries held lines of customers' bank statements. New
-- summaries key by the cell's shape instead (UnparseableReasons.summaryKey).
--
-- For old rows the cell is replaced by a fixed marker rather than re-derived as a shape: a SQL copy
-- of ValueShape could not be proven to classify letters and digits as Java does for every script,
-- and a marker is value-free by construction. Counts that land on the same marker are summed, so
-- the summary's total still equals the sum of its reasons. Only the "reasons" object changes;
-- columnSignatures and total are kept.
--
-- Only a key whose quoted part is nothing but ASCII punctuation and spaces (a lone "-", say) is left
-- alone. A shape written after #2299 deployed ("99/99/9999") is redacted too: in an old row a 9 or
-- an X is a real character -- a cell of "999" is somebody's figure -- and the two cannot be told
-- apart here, so the newer rows give up their format detail rather than risk keeping a value. A
-- summary that is not valid JSON, has no reasons object, or has a non-numeric count is skipped --
-- it is not readable by CapabilityCoverageService either (which also re-masks every key it reports),
-- and failing boot over one malformed metrics row would be the wrong trade.
--
-- Applied twice to the same data it changes nothing the second time.

WITH parsed AS (
    SELECT id,
           CASE WHEN pg_input_is_valid(unparseable_summary_json, 'jsonb')
                THEN unparseable_summary_json::jsonb END AS summary
    FROM statement_imports
    WHERE position('value \"' IN unparseable_summary_json) > 0
),
entries AS (
    SELECT p.id, e.key, e.value,
           substring(e.key FROM '^(?:Date|Amount) value "(.*)" didn''t match any known (?:date|numeric) format$') AS cell
    FROM parsed p
    CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(p.summary -> 'reasons') = 'object'
                                       THEN p.summary -> 'reasons' ELSE '{}'::jsonb END) e
),
rekeyed AS (
    SELECT id, key, value,
           CASE WHEN cell IS NOT NULL
                 AND translate(cell, ' ~!"#$%&''()*+,-./:<=>?@[\]^_`{|}', '') <> ''
                THEN CASE WHEN key LIKE 'Date value %'
                          THEN 'Date value "(redacted)" didn''t match any known date format'
                          ELSE 'Amount value "(redacted)" didn''t match any known numeric format' END
                ELSE key END AS new_key
    FROM entries
),
eligible AS (
    SELECT id FROM rekeyed GROUP BY id
    HAVING bool_or(new_key <> key) AND bool_and(jsonb_typeof(value) = 'number')
),
merged AS (
    SELECT id, jsonb_object_agg(new_key, n) AS reasons
    FROM (SELECT r.id, r.new_key, sum((r.value)::numeric) AS n
          FROM rekeyed r JOIN eligible USING (id)
          GROUP BY r.id, r.new_key) grouped
    GROUP BY id
)
UPDATE statement_imports s
SET unparseable_summary_json = jsonb_set(p.summary, '{reasons}', m.reasons)::text
FROM merged m
JOIN parsed p ON p.id = m.id
WHERE s.id = m.id;

-- The same for staged sessions. They expire after 48 hours, but the sweep that removes them only
-- runs when someone imports, so an expired row can outlive its TTL.
WITH parsed AS (
    SELECT id,
           CASE WHEN pg_input_is_valid(unparseable_summary_json, 'jsonb')
                THEN unparseable_summary_json::jsonb END AS summary
    FROM import_sessions
    WHERE position('value \"' IN unparseable_summary_json) > 0
),
entries AS (
    SELECT p.id, e.key, e.value,
           substring(e.key FROM '^(?:Date|Amount) value "(.*)" didn''t match any known (?:date|numeric) format$') AS cell
    FROM parsed p
    CROSS JOIN LATERAL jsonb_each(CASE WHEN jsonb_typeof(p.summary -> 'reasons') = 'object'
                                       THEN p.summary -> 'reasons' ELSE '{}'::jsonb END) e
),
rekeyed AS (
    SELECT id, key, value,
           CASE WHEN cell IS NOT NULL
                 AND translate(cell, ' ~!"#$%&''()*+,-./:<=>?@[\]^_`{|}', '') <> ''
                THEN CASE WHEN key LIKE 'Date value %'
                          THEN 'Date value "(redacted)" didn''t match any known date format'
                          ELSE 'Amount value "(redacted)" didn''t match any known numeric format' END
                ELSE key END AS new_key
    FROM entries
),
eligible AS (
    SELECT id FROM rekeyed GROUP BY id
    HAVING bool_or(new_key <> key) AND bool_and(jsonb_typeof(value) = 'number')
),
merged AS (
    SELECT id, jsonb_object_agg(new_key, n) AS reasons
    FROM (SELECT r.id, r.new_key, sum((r.value)::numeric) AS n
          FROM rekeyed r JOIN eligible USING (id)
          GROUP BY r.id, r.new_key) grouped
    GROUP BY id
)
UPDATE import_sessions s
SET unparseable_summary_json = jsonb_set(p.summary, '{reasons}', m.reasons)::text
FROM merged m
JOIN parsed p ON p.id = m.id
WHERE s.id = m.id;
