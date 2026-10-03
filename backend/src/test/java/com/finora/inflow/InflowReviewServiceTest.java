package com.finora.inflow;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.repository.TransactionRepository;
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

        TransactionRepository transactions = mock(TransactionRepository.class);
        // The sender also sent 3 credits that already count as income; a sender choice reaches all 5.
        when(transactions.countLiveCreditsBySender(userId, "vpa:asha")).thenReturn(5L);

        List<InflowDtos.UnresolvedSenderDto> groups = new InflowReviewService(reports, transactions).groups(userId, from, to);

        assertThat(groups).extracting(InflowDtos.UnresolvedSenderDto::count).containsExactly(2, 1);
        InflowDtos.UnresolvedSenderDto ashaGroup = groups.get(0);
        assertThat(ashaGroup.total()).isEqualByComparingTo("7000.00");
        assertThat(ashaGroup.latestDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(ashaGroup.sampleTransactionId()).isEqualTo(late.getId());
        assertThat(ashaGroup.label()).isEqualTo("ASHA VERMA");
        assertThat(ashaGroup.senderKnown()).isTrue();
        assertThat(ashaGroup.senderPaymentCount()).isEqualTo(5L);
        assertThat(ashaGroup.accountName()).isEqualTo("Savings One");
        assertThat(ashaGroup.rows()).extracting(InflowDtos.UnresolvedRowDto::id).containsExactly(late.getId(), early.getId());
        InflowDtos.UnresolvedSenderDto cashGroup = groups.get(1);
        assertThat(cashGroup.senderKnown()).isFalse();
        assertThat(cashGroup.senderPaymentCount()).isEqualTo(1L);
        assertThat(cashGroup.label()).isEqualTo("CASH DEPOSIT BRANCH");
    }

    @Test
    void creditsOnAKeyThatNamesNoOneAreNeverGroupedAsOneSender() {
        // Two strangers whose UPI ids the bank printed only the tail of, and two shops' refunds under
        // the gateway's own id: each row stands alone, so no sender-wide choice is offered for them.
        Account savings = new Account();
        ReflectionTestUtils.setField(savings, "id", UUID.randomUUID());
        savings.setName("Savings One");
        Transaction strangerA = credit(savings, "300.00", LocalDate.of(2026, 8, 3), "UPI/CR/1/A/SBIN/**1111@ybl/X", "masked:1111@ybl");
        Transaction strangerB = credit(savings, "400.00", LocalDate.of(2026, 8, 4), "UPI/CR/2/B/SBIN/**1111@ybl/X", "masked:1111@ybl");
        Transaction refundA = credit(savings, "500.00", LocalDate.of(2026, 8, 5), "UPI-RAZORPAY-PG.RAZORPAY@SAMPLEBANK-A", "vpa:pg.razorpay");
        Transaction refundB = credit(savings, "600.00", LocalDate.of(2026, 8, 6), "UPI-RAZORPAY-PG.RAZORPAY@SAMPLEBANK-B", "vpa:pg.razorpay");
        ReportService reports = mock(ReportService.class);
        when(reports.unresolvedInflows(userId, from, to)).thenReturn(new ReportService.UnresolvedRows(
                List.of(strangerA, strangerB, refundA, refundB), List.of(savings)));

        List<InflowDtos.UnresolvedSenderDto> groups =
                new InflowReviewService(reports, mock(TransactionRepository.class)).groups(userId, from, to);

        assertThat(groups).hasSize(4);
        assertThat(groups).allSatisfy(g -> {
            assertThat(g.count()).isEqualTo(1);
            assertThat(g.senderKnown()).isFalse();
            assertThat(g.senderPaymentCount()).isEqualTo(1L);
        });
    }
}
