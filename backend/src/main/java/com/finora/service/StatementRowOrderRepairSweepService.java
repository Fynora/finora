package com.finora.service;

import com.finora.imports.PrintedOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Puts back in true order the same-day transactions of PDF statements that were saved reversed.
 *
 * <p>A newest-first statement (Punjab National Bank, Bandhan) prints each day newest-first too.
 * Until the import learnt that ({@link PrintedOrder}), it sorted such a statement's rows by date
 * with a stable sort, which put the days in order but kept each day's rows backwards, and saved them
 * that way. The ledger orders a day's rows by when they were saved ({@code created_at}, then
 * {@code id}), so it showed them reversed, each beside a running balance that did not follow from
 * the one before. Measured in production before this change: five statements (four PNB, one
 * Bandhan) across four users, 422 rows out of place; in the order rebuilt here every consecutive
 * pair of all five chains.
 *
 * <p>What it changes, and only this: for a statement {@link PrintedOrder} reads as newest-first,
 * within each day that has more than one live row, the rows' existing {@code row_ordinal} and
 * {@code created_at} values are handed out again in reverse print order ({@code source_row_position}
 * descending) -- the same order a fresh import now saves them in. The set of values in each day is
 * unchanged, so nothing moves across days or past another statement's rows. A statement any of
 * whose live rows has no printed position is left alone: its print order cannot be rebuilt. Rows are
 * matched on the values read, so a row deleted or reordered in between is not overwritten.
 * {@code version} is bumped so the mobile app fetches the rows again.
 *
 * <p>Modes ({@code app.statement-row-order-repair.sweep.mode}), as the other sweeps: {@code dry-run},
 * the default, counts once per start and writes nothing; {@code apply} repairs every candidate
 * statement in one run, then rests until the next start; {@code off} does nothing. One audit record
 * per statement repaired, with the count of rows moved.
 */
@Service
public class StatementRowOrderRepairSweepService {

    private static final Logger log = LoggerFactory.getLogger(StatementRowOrderRepairSweepService.class);

    @Value("${app.statement-row-order-repair.sweep.mode:dry-run}")
    private String mode;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final AuditService auditService;

    private volatile boolean dryRunReported;
    private volatile boolean passComplete;

    public StatementRowOrderRepairSweepService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate,
                                               AuditService auditService) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${app.statement-row-order-repair.sweep.interval-ms:300000}",
            initialDelayString = "${app.statement-row-order-repair.sweep.initial-delay-ms:270000}")
    public void scheduledSweep() {
        if ("apply".equals(mode)) {
            if (passComplete) return;
            Result result = run(true);
            passComplete = true;
            log.info("Statement row order repair: {} statements repaired, {} rows moved, of {} statements read.",
                    result.statementsRepaired(), result.rowsMoved(), result.statementsRead());
        } else if ("dry-run".equals(mode) && !dryRunReported) {
            dryRunReported = true;
            Result result = run(false);
            log.info("Statement row order repair DRY RUN (nothing written): {} statements would be repaired, {} rows "
                            + "moved, of {} statements read. Set app.statement-row-order-repair.sweep.mode=apply to repair.",
                    result.statementsRepaired(), result.rowsMoved(), result.statementsRead());
        }
    }

    public record Result(int statementsRead, int statementsRepaired, int rowsMoved) {}

    private record SavedRow(UUID id, UUID userId, LocalDate date, Integer position, int ordinal, Timestamp createdAt,
                            BigDecimal signedAmount, BigDecimal balanceAfter) {}

    /** Every PDF statement with a day of more than one live row: repaired when {@code write}, else counted. */
    public Result run(boolean write) {
        List<UUID> statements = jdbc.queryForList("""
                SELECT DISTINCT t.statement_import_id FROM transactions t
                JOIN statement_imports si ON si.id = t.statement_import_id
                WHERE si.source_format = 'PDF' AND t.deleted_at IS NULL AND t.row_ordinal IS NOT NULL
                GROUP BY t.statement_import_id, t.txn_date HAVING count(*) > 1
                """, UUID.class);
        int repaired = 0, moved = 0;
        for (UUID statement : statements) {
            try {
                int rows = write ? transactionTemplate.execute(tx -> repair(statement, true)) : repair(statement, false);
                if (rows > 0) {
                    repaired++;
                    moved += rows;
                }
            } catch (RuntimeException e) {
                log.warn("Statement row order repair could not repair statement {}: {}", statement, e.toString());
            }
        }
        return new Result(statements.size(), repaired, moved);
    }

    /** The rows of {@code statement} that are out of place (written back when {@code write}). */
    private int repair(UUID statement, boolean write) {
        List<SavedRow> rows = jdbc.query("""
                SELECT id, user_id, txn_date, source_row_position, row_ordinal, created_at,
                       CASE WHEN txn_type = 'INCOME' THEN amount ELSE -amount END, balance_after
                FROM transactions
                WHERE statement_import_id = ? AND deleted_at IS NULL AND row_ordinal IS NOT NULL
                """, (rs, n) -> new SavedRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, LocalDate.class), (Integer) rs.getObject(4), rs.getInt(5), rs.getTimestamp(6),
                        rs.getBigDecimal(7), rs.getBigDecimal(8)), statement);
        if (rows.isEmpty() || rows.stream().anyMatch(r -> r.position() == null)) return 0;
        List<SavedRow> printed = rows.stream().sorted(Comparator.comparingInt(SavedRow::position)).toList();
        boolean newestFirst = PrintedOrder.newestFirst(printed.stream()
                .map(r -> new PrintedOrder.Row(r.date(), r.signedAmount(), r.balanceAfter())).toList());
        if (!newestFirst) return 0;

        Map<LocalDate, List<SavedRow>> days = new LinkedHashMap<>();
        for (SavedRow row : rows) days.computeIfAbsent(row.date(), d -> new ArrayList<>()).add(row);
        record Move(SavedRow row, int ordinal, Timestamp createdAt) {}
        List<Move> moves = new ArrayList<>();
        for (List<SavedRow> day : days.values()) {
            if (day.size() < 2) continue;
            List<SavedRow> target = day.stream().sorted(Comparator.comparingInt(SavedRow::position).reversed()).toList();
            List<Integer> ordinals = day.stream().map(SavedRow::ordinal).sorted().toList();
            List<Timestamp> createdAts = day.stream().map(SavedRow::createdAt).sorted().toList();
            for (int k = 0; k < target.size(); k++) {
                SavedRow row = target.get(k);
                if (row.ordinal() != ordinals.get(k) || !row.createdAt().equals(createdAts.get(k))) {
                    moves.add(new Move(row, ordinals.get(k), createdAts.get(k)));
                }
            }
        }
        if (moves.isEmpty() || !write) return moves.size();

        // Two steps, because (statement_import_id, row_ordinal) is unique (V67): park every moving row
        // on a negative ordinal no live row holds, then give each its new one.
        for (Move m : moves) {
            int parked = jdbc.update("UPDATE transactions SET row_ordinal = ? WHERE id = ? AND row_ordinal = ? AND deleted_at IS NULL",
                    -1 - m.row().ordinal(), m.row().id(), m.row().ordinal());
            if (parked != 1) throw new IllegalStateException("row " + m.row().id() + " changed while being repaired");
        }
        for (Move m : moves) {
            int placed = jdbc.update("""
                    UPDATE transactions SET row_ordinal = ?, created_at = ?, version = version + 1, updated_at = now()
                    WHERE id = ? AND row_ordinal = ?
                    """, m.ordinal(), m.createdAt(), m.row().id(), -1 - m.row().ordinal());
            if (placed != 1) throw new IllegalStateException("row " + m.row().id() + " changed while being repaired");
        }
        UUID userId = rows.get(0).userId();
        auditService.record(userId, "STATEMENT_ROW_ORDER_REPAIRED", "StatementImport", statement,
                Map.of("rowsMoved", moves.size(), "actor", "statement-row-order-repair"));
        return moves.size();
    }
}
