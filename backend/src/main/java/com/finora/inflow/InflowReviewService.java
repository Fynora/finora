package com.finora.inflow;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
import com.finora.service.ReportService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "Money not counted yet": the unresolved credits in a period, one group per sender (Plan 2). */
@Service
public class InflowReviewService {

    private final ReportService reportService;
    private final TransactionRepository transactions;

    public InflowReviewService(ReportService reportService, TransactionRepository transactions) {
        this.reportService = reportService;
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<InflowDtos.UnresolvedSenderDto> groups(UUID userId, LocalDate from, LocalDate to) {
        ReportService.UnresolvedRows unresolved = reportService.unresolvedInflows(userId, from, to);
        return group(userId, unresolved.rows(), unresolved.accounts());
    }

    /**
     * The money not counted as income among the rows one statement import added -- what the import
     * Done step asks about. By import, not by dates: a CSV statement prints no period, and a date
     * range would also list credits other statements added on the same days. Another user's import
     * id lists nothing.
     */
    @Transactional(readOnly = true)
    public List<InflowDtos.UnresolvedSenderDto> groupsForImport(UUID userId, UUID statementImportId) {
        List<Transaction> importRows = transactions.findByStatementImportId(statementImportId).stream()
                .filter(t -> userId.equals(t.getUserId())).toList();
        if (importRows.isEmpty()) return List.of();
        LocalDate from = importRows.stream().map(Transaction::getTxnDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = importRows.stream().map(Transaction::getTxnDate).max(Comparator.naturalOrder()).orElseThrow();
        ReportService.UnresolvedRows unresolved = reportService.unresolvedInflows(userId, from, to);
        List<Transaction> fromThisImport = unresolved.rows().stream()
                .filter(t -> statementImportId.equals(t.getStatementImportId())).toList();
        return group(userId, fromThisImport, unresolved.accounts());
    }

    private List<InflowDtos.UnresolvedSenderDto> group(UUID userId, List<Transaction> unresolvedRows, List<Account> accounts) {
        Map<UUID, String> accountNames = new HashMap<>();
        for (Account a : accounts) accountNames.put(a.getId(), a.getName());

        Map<String, List<Transaction>> bySender = new LinkedHashMap<>();
        for (Transaction t : unresolvedRows) {
            String key = t.getCounterpartyKey();
            // A row whose key names no one sender -- none at all, or a masked, cut or gateway id
            // shared by different senders -- cannot share a rule, so it is its own group.
            String group = com.finora.util.CounterpartyIdentity.identifiesOnePayee(key) ? key : "row:" + t.getId();
            bySender.computeIfAbsent(group, g -> new ArrayList<>()).add(t);
        }

        List<InflowDtos.UnresolvedSenderDto> out = new ArrayList<>();
        for (Map.Entry<String, List<Transaction>> e : bySender.entrySet()) {
            List<Transaction> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparing(Transaction::getTxnDate).reversed());
            Transaction latest = rows.get(0);
            BigDecimal total = rows.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean senderKnown = !e.getKey().startsWith("row:");
            out.add(new InflowDtos.UnresolvedSenderDto(latest.getId(), SenderLabel.of(latest), senderKnown, rows.size(),
                    senderKnown ? transactions.countLiveCreditsBySender(userId, e.getKey()) : rows.size(),
                    total, latest.getTxnDate(),
                    accountNames.get(latest.getAccountId()),
                    rows.stream().map(t -> new InflowDtos.UnresolvedRowDto(t.getId(), t.getTxnDate(), t.getAmount(),
                            t.getDescription(), accountNames.get(t.getAccountId()))).toList()));
        }
        out.sort(Comparator.comparing(InflowDtos.UnresolvedSenderDto::total).reversed());
        return out;
    }
}
