package com.finora.people;

import com.finora.entity.Transaction;
import com.finora.service.RefundNetting;
import com.finora.service.TransactionGraphService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one place a report gets its rows and its netting, so a share or a loan the user confirmed
 * (PeopleLedger) reaches every figure the same way: Dashboard, Reports, Analytics, Insights and
 * Budgets. ReportRowsGuardTest fails the build for a caller that goes around it.
 */
@Component
public class ReportRows {

    private final TransactionGraphService transactionGraphService;
    private final PeopleLedgerService peopleLedgerService;

    public ReportRows(TransactionGraphService transactionGraphService, PeopleLedgerService peopleLedgerService) {
        this.transactionGraphService = transactionGraphService;
        this.peopleLedgerService = peopleLedgerService;
    }

    /** The rows and netting as before shares and loans existed -- the default a service built by hand
     *  in a unit test holds until Spring injects the real one. */
    public static ReportRows withoutPeople(TransactionGraphService transactionGraphService) {
        return new ReportRows(transactionGraphService, null);
    }

    private PeopleLedger ledger(UUID userId) {
        return peopleLedgerService == null ? PeopleLedger.EMPTY : peopleLedgerService.forUser(userId);
    }

    /** {@link RefundNetting#reportable(Collection, Set)}, also leaving out every credit linked as a
     *  share and every lent or repaid-a-borrowing payment not let go. */
    public List<Transaction> reportable(UUID userId, Collection<Transaction> rows) {
        Set<UUID> excluded = new HashSet<>(transactionGraphService.ccPaymentFromTransactionIds(rows));
        excluded.addAll(ledger(userId).droppedIds());
        return RefundNetting.reportable(rows, excluded);
    }

    /** {@link RefundNetting#from} over {@code refundSource}, with the shares netted off their bills. */
    public RefundNetting netting(UUID userId, Collection<Transaction> refundSource) {
        return RefundNetting.from(refundSource).withShares(ledger(userId).sharedByBillId());
    }
}
