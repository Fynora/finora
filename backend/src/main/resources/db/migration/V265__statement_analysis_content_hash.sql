-- Which file a failed analysis was, so a customer's failure can be recognised once that same file
-- imports.
--
-- The customer's failure lists (mobile's "Recent failed imports" card, read from this table, and
-- the recent-imports lists read from import_jobs) kept showing a failure after the user had
-- uploaded the same statement again and imported it -- "Couldn't finish" over data they already
-- have. Those lists now leave out a failure once a confirmed statement_imports row of the same
-- user carries the same content_hash, imported after the failure.
--
-- Same value as import_jobs.content_hash, import_sessions.content_hash and
-- statement_imports.content_hash: hex SHA-256 of the file exactly as uploaded (for a locked PDF,
-- the still-locked bytes), so re-uploading that file with its password matches. Null for every
-- row written before this column, which therefore never hides -- the safe direction.
--
-- Personal-data handling matches file_name: StatementAnalysisSessionRepository.anonymizeByUserId
-- nulls it when the account is deleted.
ALTER TABLE statement_analysis_sessions ADD COLUMN content_hash VARCHAR(64);

COMMENT ON COLUMN statement_analysis_sessions.content_hash IS
    'Hex SHA-256 of the uploaded file, as statement_imports.content_hash. Lets the customer failure '
    'list drop a failure once the same file was confirmed. NULL before V265 and after account deletion.';
