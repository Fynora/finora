package com.finora.inflow;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InflowReviewServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 8, 31);

    private static Transaction credit(Account on, String amount, LocalDate date, String description, String key) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setAccountId(on.getId());
        t.setTxnType(Transaction.Type.INCOME);
        t.setAmount(new BigDecimal(amount));
        t.setTxnDate(date);
        t.setDescription(description);
        t.setCounterpartyKey(key);
        return t;
    }

    @Test
    void groupsBySenderLargestFirstAndKeepsKeylessRowsApart() {
        Account savings = new Account();
        ReflectionTestUtils.setField(savings, "id", UUID.randomUUID());
        savings.setName("Savings One");
        String asha = "UPI-ASHA VERMA-asha@okbank-HDFC0XXXXXX-111111111111-UPI";
        Transaction early = credit(savings, "5000.00", LocalDate.of(2026, 8, 3), asha, "vpa:asha");
        Transaction late = credit(savings, "2000.00", LocalDate.of(2026, 8, 20), asha, "vpa:asha");
        Transaction cash = credit(savings, "700.00", LocalDate.of(2026, 8, 10), "CASH DEPOSIT BRANCH", "");
        ReportService reports = mock(ReportService.class);
        when(reports.unresolvedInflows(userId, from, to))
                .thenReturn(new ReportService.UnresolvedRows(List.of(early, cash, late), List.of(savings)));

        List<InflowDtos.UnresolvedSenderDto> groups = new InflowReviewService(reports).groups(userId, from, to);

        assertThat(groups).extracting(InflowDtos.UnresolvedSenderDto::count).containsExactly(2, 1);
        InflowDtos.UnresolvedSenderDto ashaGroup = groups.get(0);
        assertThat(ashaGroup.total()).isEqualByComparingTo("7000.00");
        assertThat(ashaGroup.latestDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(ashaGroup.sampleTransactionId()).isEqualTo(late.getId());
        assertThat(ashaGroup.label()).isEqualTo("ASHA VERMA");
        assertThat(ashaGroup.senderKnown()).isTrue();
        assertThat(ashaGroup.accountName()).isEqualTo("Savings One");
        assertThat(ashaGroup.rows()).extracting(InflowDtos.UnresolvedRowDto::id).containsExactly(late.getId(), early.getId());
        InflowDtos.UnresolvedSenderDto cashGroup = groups.get(1);
        assertThat(cashGroup.senderKnown()).isFalse();
        assertThat(cashGroup.label()).isEqualTo("CASH DEPOSIT BRANCH");
    }
}
