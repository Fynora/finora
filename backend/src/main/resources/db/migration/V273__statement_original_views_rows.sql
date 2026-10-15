-- An opening of a trust hold's staged rows is an opening of the customer's statement too: since
-- 2026-10-15 the staged-rows screen takes the same reason, Super Admin email and customer notice
-- as the original view (owner's decision). It is recorded as a statement_original_views row with
-- format 'ROWS', so the customer's import page, the hold's History and the once-a-day notice see
-- one record of every opening. For a ROWS row, page_count holds the number of rows shown.
ALTER TABLE statement_original_views DROP CONSTRAINT statement_original_views_format_valid;
ALTER TABLE statement_original_views ADD CONSTRAINT statement_original_views_format_valid
    CHECK (format IN ('PDF', 'CSV', 'ROWS'));
