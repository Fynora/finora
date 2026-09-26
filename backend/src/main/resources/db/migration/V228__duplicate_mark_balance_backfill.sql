-- Duplicate marks and Account.balance: record what each mark did, and correct the marks written
-- before anything recorded it. Written on the repository owner's explicit instruction ("write
-- V228", PR #1765).
--
-- The rule (AccountBalanceConvention.netEffectIsInBalance): Account.balance moves with the rows
-- Finora counts. A row marked DUPLICATE is not counted, so the net effect it put into the balance
-- when it arrived comes back off when the mark is written, and goes back on when the mark is
-- cleared. Until 2026-09-25 only one site did the first half -- ImportService.summarise, for the
-- rows of the import it had just confirmed -- and it never touched manual rows. Every other mark
-- (a later reconciliation run after an edit, a delete or a later import; every mark on a manual
-- row) left the row's amount in the balance with the row hidden from the ledger.
--
-- Two columns, written by the site that writes the mark and read by every site that clears it:
--
--   duplicate_balance_reversed   The mark took this row's net effect off Account.balance. Clearing
--                                the mark puts it back; deleting or editing the row while marked
--                                moves nothing. False for a row whose effect was never in the
--                                balance, or was out of reach behind an absolute SET (below).
--   duplicate_balance_anchor_id  The absolute SET (statement_imports.id, accounts
--                                .last_absolute_set_statement_id at the time) that stood between
--                                the mark and the balance: the row's effect was in the balance the
--                                SET replaced, so it sits in that SET's pre-set snapshot and comes
--                                back with it if the SET is reversed. StatementImportService
--                                .reverseAbsoluteContribution reverses these rows at that moment;
--                                a manual balance edit (a fresh baseline) rebases them instead.
--
-- Recording the fact, rather than re-deriving it from the account's live anchor at un-mark time,
-- is what makes the un-mark exact after the anchor has gone: the earlier runtime read the anchor
-- as it was NOW, so a mark held behind a SET that was later deleted answered "in the balance" and
-- an un-mark added the amount a second time.
--
-- Classification per live, marked row (the buckets are recorded per account in audit_logs):
--
--   NEVER_IN_BALANCE   The row's own net effect never went into Account.balance: an account-
--                      aggregator row (nothing on that path writes the balance), or a statement
--                      row whose import was not ADDITIVE (ABSOLUTE set a stated figure; NONE and
--                      UNKNOWN_LEGACY never moved it by this row). Nothing to correct.
--   ASSUMED_REVERSED   An ADDITIVE-import row whose DUPLICATE edge was written within an hour of
--                      the row: a mark from its own confirm, already reversed then. A retroactive
--                      mark that quick is indistinguishable from it and is deliberately left as it
--                      is -- reversing twice would be the worse error. Reported.
--   ANCHORED           The account's balance was later SET from a stated closing figure
--                      (accounts.last_absolute_set_statement_id still live) and the row predates
--                      that SET. The figure replaced the history the row was part of, so its
--                      effect is not separately in the balance any more. Left alone, reported.
--                      A manual row, or an ADDITIVE row marked at least an hour after it arrived,
--                      was never reversed, so its effect is in that SET's pre-set snapshot: it is
--                      recorded as held by the anchor. An ADDITIVE row with no dated edge is not.
--   REVERSE            Still in the balance for certain: a manual row (never reversed by any
--                      site), or an ADDITIVE-import row whose DUPLICATE edge was written at least
--                      one hour after the row itself -- a mark that cannot have come from the
--                      row's own confirm, which is the only site that ever reversed. Corrected.
--   UNCLASSIFIED       An ADDITIVE-import row with no runtime DUPLICATE edge for its current mark.
--                      Edges exist since V114; the ones V114 itself backfilled carry no
--                      explanation and say nothing about when the mark was written. Left alone,
--                      reported, and recorded as reversed: the runtime answered that for these
--                      rows before this migration, and it is the answer under which an un-mark
--                      and a delete of the row cancel out.
--
-- The edge is matched on the CURRENT pointer (to_transaction_id = is_duplicate_of): a row marked,
-- cleared and marked again against another row has one edge per pointer. A repeat mark against
-- the same row reuses the first edge (TransactionGraphService.linkAll dedups live edges), and a
-- repeat mark can only ever be retroactive -- confirm-time marks are written on freshly inserted
-- rows -- so an older-than-the-mark edge can only push a row into REVERSE when it belongs there
-- anyway, or into ASSUMED_REVERSED when a confirm-time first mark was later repeated; that second
-- case leaves the balance as it was, never double-reversed.
--
-- Arithmetic matches AccountBalanceConvention.balanceDelta: on a credit card an EXPENSE increases
-- what is owed and an INCOME reduces it; every other type is the plain ledger convention. The
-- reversal is the negation. Amounts are stored unsigned; abs() guards a defensively-signed value.
--
-- DuplicateMarkBalanceBackfillIT executes this file a second time against service-built fixtures,
-- which is why the DDL is IF NOT EXISTS.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS duplicate_balance_reversed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS duplicate_balance_anchor_id UUID NULL;
-- Read only when an absolute SET is reversed, for the handful of rows it held; partial so the
-- index costs nothing for every other row.
CREATE INDEX IF NOT EXISTS idx_transactions_duplicate_balance_anchor
    ON transactions (duplicate_balance_anchor_id) WHERE duplicate_balance_anchor_id IS NOT NULL;

CREATE TEMP TABLE dup_mark_balance_backfill AS
WITH live_anchor AS (
    SELECT a.id AS account_id, si.id AS anchor_id, si.imported_at AS anchored_at
    FROM accounts a
    JOIN statement_imports si ON si.id = a.last_absolute_set_statement_id AND si.deleted_at IS NULL
),
marked AS (
    SELECT t.id,
           t.user_id,
           t.account_id,
           COALESCE(t.source, 'MANUAL') AS source,
           t.txn_type,
           t.amount,
           t.created_at,
           a.account_type,
           si.balance_application_mode AS import_mode,
           an.anchor_id,
           an.anchored_at,
           (SELECT MIN(r.created_at)
              FROM transaction_relationships r
             WHERE r.from_transaction_id = t.id
               AND r.to_transaction_id = t.is_duplicate_of
               AND r.relationship_type = 'DUPLICATE'
               AND r.explanation IS NOT NULL) AS marked_at
    FROM transactions t
    JOIN accounts a ON a.id = t.account_id AND a.deleted_at IS NULL
    LEFT JOIN statement_imports si ON si.id = t.statement_import_id
    LEFT JOIN live_anchor an ON an.account_id = t.account_id
    WHERE t.deleted_at IS NULL
      AND t.is_duplicate_of IS NOT NULL
)
SELECT m.*,
       CASE
           WHEN m.source = 'ACCOUNT_AGGREGATOR' THEN 'NEVER_IN_BALANCE'
           WHEN m.source IN ('CSV_IMPORT', 'GMAIL_IMPORT')
                AND (m.import_mode IS NULL OR m.import_mode <> 'ADDITIVE') THEN 'NEVER_IN_BALANCE'
           WHEN m.source <> 'MANUAL' AND m.marked_at IS NOT NULL
                AND m.marked_at < m.created_at + INTERVAL '1 hour' THEN 'ASSUMED_REVERSED'
           WHEN m.anchored_at IS NOT NULL AND m.created_at < m.anchored_at THEN 'ANCHORED'
           WHEN m.source = 'MANUAL' THEN 'REVERSE'
           WHEN m.marked_at IS NULL THEN 'UNCLASSIFIED'
           ELSE 'REVERSE'
       END AS bucket,
       CASE
           WHEN m.account_type = 'CREDIT_CARD'
               THEN CASE WHEN m.txn_type = 'EXPENSE' THEN -abs(m.amount) ELSE abs(m.amount) END
           ELSE CASE WHEN m.txn_type = 'INCOME' THEN -abs(m.amount) ELSE abs(m.amount) END
       END AS reversal
FROM marked m;

UPDATE accounts a
SET balance = a.balance + agg.total
FROM (
    SELECT account_id, SUM(reversal) AS total
    FROM dup_mark_balance_backfill
    WHERE bucket = 'REVERSE'
    GROUP BY account_id
) agg
WHERE a.id = agg.account_id
  AND agg.total <> 0;

-- What each standing mark did, as the runtime now records it at mark time. REVERSE rows were just
-- reversed above; ASSUMED_REVERSED rows were reversed at their confirm; UNCLASSIFIED rows are
-- recorded as reversed for the reason in the header. An ANCHORED row whose effect is known to be
-- in the live SET's pre-set snapshot (never reversed: manual, or marked an hour or more after it
-- arrived) is held by that SET.
UPDATE transactions t
SET duplicate_balance_reversed = b.bucket IN ('REVERSE', 'ASSUMED_REVERSED', 'UNCLASSIFIED'),
    duplicate_balance_anchor_id = CASE
        WHEN b.bucket = 'ANCHORED' AND (b.source = 'MANUAL' OR b.marked_at IS NOT NULL) THEN b.anchor_id
    END
FROM dup_mark_balance_backfill b
WHERE b.id = t.id;

-- One audit row per account that had anything to correct or anything left alone on purpose, so
-- the ASSUMED_REVERSED / UNCLASSIFIED / ANCHORED residue is visible to an operator without
-- re-deriving it. Accounts whose marked rows are all NEVER_IN_BALANCE get no row: there was
-- nothing to decide.
INSERT INTO audit_logs (user_id, action, entity_type, entity_id, metadata)
SELECT user_id,
       'DUPLICATE_MARK_BALANCE_BACKFILL',
       'Account',
       account_id,
       jsonb_build_object(
           'migration', 'V228',
           'reversedRows', COUNT(*) FILTER (WHERE bucket = 'REVERSE'),
           'balanceMovedBy', COALESCE(SUM(reversal) FILTER (WHERE bucket = 'REVERSE'), 0),
           'assumedReversedRows', COUNT(*) FILTER (WHERE bucket = 'ASSUMED_REVERSED'),
           'assumedReversedNet', COALESCE(SUM(reversal) FILTER (WHERE bucket = 'ASSUMED_REVERSED'), 0),
           'unclassifiedRows', COUNT(*) FILTER (WHERE bucket = 'UNCLASSIFIED'),
           'unclassifiedNet', COALESCE(SUM(reversal) FILTER (WHERE bucket = 'UNCLASSIFIED'), 0),
           'anchoredRows', COUNT(*) FILTER (WHERE bucket = 'ANCHORED'),
           'anchoredNet', COALESCE(SUM(reversal) FILTER (WHERE bucket = 'ANCHORED'), 0),
           'anchoredHeldRows', COUNT(*) FILTER (WHERE bucket = 'ANCHORED' AND (source = 'MANUAL' OR marked_at IS NOT NULL)),
           'neverInBalanceRows', COUNT(*) FILTER (WHERE bucket = 'NEVER_IN_BALANCE')
       )
FROM dup_mark_balance_backfill
GROUP BY user_id, account_id
HAVING COUNT(*) FILTER (WHERE bucket IN ('REVERSE', 'ASSUMED_REVERSED', 'UNCLASSIFIED', 'ANCHORED')) > 0;

DROP TABLE dup_mark_balance_backfill;
