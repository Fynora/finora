package com.finora.people;

import com.finora.entity.Transaction;
import com.finora.service.RefundNetting;
import com.finora.service.ReportableDates;
import com.finora.service.TransactionGraphService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one place a report gets its rows and its netting, so a share or a loan the user confirmed
 * (PeopleLedger) reaches every figure the same way: Dashboard, Reports, Analytics, Insights and
 * Budgets. ReportRowsGuardTest fails the build for a caller that goes around it.
 *
 * <p>It is also where a row dated in the future is left out (see {@link ReportableDates}), and
 * {@link #latestDate} is the same date for the newest-date queries a screen makes alongside it.
 */
@Component
public class ReportRows {

    private final TransactionGraphService transactionGraphService;
    private final PeopleLedgerService peopleLedgerService;
    private final ReportableDates reportableDates;

    public ReportRows(TransactionGraphService transactionGraphService, PeopleLedgerService peopleLedgerService,
                      ReportableDates reportableDates) {
        this.transactionGraphService = transactionGraphService;
        this.peopleLedgerService = peopleLedgerService;
        this.reportableDates = reportableDates;
    }

    /** The rows and netting as before shares and loans existed -- the default a service built by hand
     *  in a unit test holds until Spring injects the real one. */
    public static ReportRows withoutPeople(TransactionGraphService transactionGraphService) {
        return new ReportRows(transactionGraphService, null, ReportableDates.withDefaultZone());
    }

    private PeopleLedger ledger(UUID userId) {
        return peopleLedgerService == null ? PeopleLedger.EMPTY : peopleLedgerService.forUser(userId);
    }

    /** The latest transaction date any figure counts for this user: see {@link ReportableDates}. */
    public LocalDate latestDate(UUID userId) {
        return reportableDates.latestFor(userId);
    }

    /** {@link RefundNetting#reportable(Collection, Set)}, also leaving out every credit linked as a
     *  share, every lent or repaid-a-borrowing payment not let go, and every row dated after
     *  {@link #latestDate}. */
    public List<Transaction> reportable(UUID userId, Collection<Transaction> rows) {
        List<Transaction> dated = notInTheFuture(userId, rows);
        Set<UUID> excluded = new HashSet<>(transactionGraphService.ccPaymentFromTransactionIds(dated));
        excluded.addAll(ledger(userId).droppedIds());
        return RefundNetting.reportable(dated, excluded);
    }

    /** {@link RefundNetting#from} over {@code refundSource}, with the shares netted off their bills. A
     *  refund dated in the future offsets nothing yet: it is left out like any other such row. */
    public RefundNetting netting(UUID userId, Collection<Transaction> refundSource) {
        return RefundNetting.from(notInTheFuture(userId, refundSource)).withShares(ledger(userId).sharedByBillId());
    }

    private List<Transaction> notInTheFuture(UUID userId, Collection<Transaction> rows) {
        LocalDate latest = latestDate(userId);
        return rows.stream().filter(t -> !ReportableDates.isAfter(t.getTxnDate(), latest)).toList();
    }
}
