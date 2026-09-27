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
        Map<UUID, String> accountNames = new HashMap<>();
        for (Account a : unresolved.accounts()) accountNames.put(a.getId(), a.getName());

        Map<String, List<Transaction>> bySender = new LinkedHashMap<>();
        for (Transaction t : unresolved.rows()) {
            String key = t.getCounterpartyKey();
            // A row with no sender key cannot share a rule, so it is its own group.
            String group = key == null || key.isBlank() ? "row:" + t.getId() : key;
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
