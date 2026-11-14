-- Indexes for the admin Audit Log's filters and the user page's history (AuditLogRepository.search,
-- findForAdminUserHistory), which read the whole table on every request: the people involved live in
-- metadata, and audit_logs had no index on it or on entity_id. On a 1,000,000-row local table the
-- account filter took ~430 ms and the admin filter ~600 ms (break-test of 2026-11-14).
--
-- Each filter matches a row in several ways (ORs); with an index for every way, Postgres combines
-- index scans (measured locally: about 30 ms for each filter, 7 ms for the user page):
--   * account filter / user page: metadata.subjectUserId (below), user_id (idx_audit_user, V1), and
--     entity_id for an admin's read audit of the account (ADMIN_USER_DATA_VIEWED's entity);
--   * admin filter: one index per actor key (AuditLogRepository.ADMIN_ACTOR_KEYS), and user_id.
-- The metadata indexes are partial (only rows carrying that key), so they stay small and most audit
-- writes never touch them. The expressions must match the queries' jsonb_extract_path_text(metadata,
-- '<key>') exactly to be used. entity_id also serves TransactionService's per-transaction history
-- (findByEntityIdOrderByCreatedAtAsc), which scanned the table too.
--
-- Plain CREATE INDEX, not CONCURRENTLY, as V89 decided for this same table: CONCURRENTLY hung
-- startup there behind an idle-in-transaction pool connection. The builds block audit writes while
-- they run: this migration took 0.7 s on a 1,000,000-row local table. Each audit write then costs
-- about 10 microseconds more (20,000 inserts: ~400 ms with these indexes, ~200 ms without).
CREATE INDEX IF NOT EXISTS idx_audit_logs_subject_user_id
    ON audit_logs (jsonb_extract_path_text(metadata, 'subjectUserId'))
    WHERE jsonb_extract_path_text(metadata, 'subjectUserId') IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_logs_entity_id
    ON audit_logs (entity_id);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_actor_id
    ON audit_logs (jsonb_extract_path_text(metadata, 'actorId'))
    WHERE jsonb_extract_path_text(metadata, 'actorId') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_suspended_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'suspendedBy'))
    WHERE jsonb_extract_path_text(metadata, 'suspendedBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_reactivated_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'reactivatedBy'))
    WHERE jsonb_extract_path_text(metadata, 'reactivatedBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_recorded_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'recordedBy'))
    WHERE jsonb_extract_path_text(metadata, 'recordedBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_updated_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'updatedBy'))
    WHERE jsonb_extract_path_text(metadata, 'updatedBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_set_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'setBy'))
    WHERE jsonb_extract_path_text(metadata, 'setBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_created_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'createdBy'))
    WHERE jsonb_extract_path_text(metadata, 'createdBy') IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_changed_by
    ON audit_logs (jsonb_extract_path_text(metadata, 'changedBy'))
    WHERE jsonb_extract_path_text(metadata, 'changedBy') IS NOT NULL;
