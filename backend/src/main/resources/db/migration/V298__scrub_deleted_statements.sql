-- Deleting a statement now clears what identifies its file and its holder (StatementImport's
-- @SQLDelete). This does the same for statements deleted before that change.
--
-- Until now a deleted statement was a soft delete that only stamped deleted_at. The row kept the
-- uploaded file's name, the account-holder name detected on it, the column headers read from it
-- (layout_metadata_json) and, for statements imported before files moved to object storage, the
-- whole file in file_content. Nothing reads any of those four columns on a deleted row: the entity
-- hides deleted rows, and the native queries that read them (the storage sweep, the balance anchor
-- chain, the activation funnel, the earlier-upload notice) use other columns.
--
-- The row itself stays. Its content_hash, object_key and balance columns are still read by the
-- storage sweep, the balance reversal chain and the activation funnel.
--
-- content_hash: the activation funnel tells files apart by
-- COALESCE(content_hash, encode(sha256(file_content), 'hex'), id::text). For a legacy row with no
-- hash, that same hash is recorded before the bytes go, so the funnel's grouping does not change.
-- PostgreSQL evaluates every SET expression against the row as it was, so the sha256 below reads the
-- bytes this same statement clears.
--
-- V76's check that a row has its file somewhere (file_content or object_key) now applies to live
-- rows only. A deleted legacy row with no object key would otherwise be unable to lose its bytes.
--
-- Applied twice to the same data it changes nothing the second time.

ALTER TABLE statement_imports DROP CONSTRAINT statement_imports_file_content_or_object_key;
ALTER TABLE statement_imports ADD CONSTRAINT statement_imports_file_content_or_object_key
    CHECK (deleted_at IS NOT NULL OR file_content IS NOT NULL OR object_key IS NOT NULL);

UPDATE statement_imports
   SET content_hash          = COALESCE(content_hash, encode(sha256(file_content), 'hex')),
       file_name             = '(deleted)',
       extracted_holder_name = NULL,
       file_content          = NULL,
       layout_metadata_json  = NULL
 WHERE deleted_at IS NOT NULL
   AND (file_name <> '(deleted)'
        OR extracted_holder_name IS NOT NULL
        OR file_content IS NOT NULL
        OR layout_metadata_json IS NOT NULL);
