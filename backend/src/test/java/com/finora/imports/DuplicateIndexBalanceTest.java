package com.finora.imports;

import com.finora.dto.ImportDto;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The staging-time duplicate flag must agree with the balance-keyed pass in ReconciliationService:
 * the same posting arriving with a different narration (composite vs classic layout, CSV vs PDF)
 * is flagged on the review screen when its running balance matches an existing row.
 */
class DuplicateIndexBalanceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 6, 1);

    private Transaction existing(String description, BigDecimal balanceAfter) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setTxnDate(date);
        t.setAmount(new BigDecimal("1300.00"));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription(description);
        t.setBalanceAfter(balanceAfter);
        return t;
    }

    private DuplicateDetector detector(Transaction... ledger) {
        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(eq(userId), eq(date), eq(date), any()))
                .thenReturn(List.of(ledger));
        AccountRepository accountRepository = TestAccountRepositories.anyLive();
        return new DuplicateDetector(transactionRepository, accountRepository);
    }

    @Test
    void aDifferentNarrationWithTheSameRunningBalance_isFlagged_asABalanceMatch() {
        Transaction ledger = existing("ACH D- CLEARING HOUSE-0000ABCDEFGH", new BigDecimal("23518.22"));
        DuplicateDetector detector = detector(ledger);
        DuplicateIndex index = detector.indexFor(userId);

        Optional<ImportDto.DuplicateMatch> match = detector.findMatch(index, date, new BigDecimal("1300.00"),
                "ACH D- CLEARING HOUSE-0000ABCDEFGHValue Dt 01/06/2026 Ref 000001",
                Transaction.Type.EXPENSE, new BigDecimal("23518.22"));

        assertThat(match).isPresent();
        assertThat(match.get().existingTransactionId()).isEqualTo(ledger.getId());
        assertThat(match.get().confidence()).isEqualTo("BALANCE");
    }

    @Test
    void aDifferentNarrationWithADifferentRunningBalance_isNotFlagged() {
        DuplicateDetector detector = detector(existing("ACH D- CLEARING HOUSE-0000ABCDEFGH", new BigDecimal("23518.22")));
        DuplicateIndex index = detector.indexFor(userId);

        Optional<ImportDto.DuplicateMatch> match = detector.findMatch(index, date, new BigDecimal("1300.00"),
                "ACH D- CLEARING HOUSE-0000ZZZZZZZZ", Transaction.Type.EXPENSE, new BigDecimal("22218.22"));

        assertThat(match).isEmpty();
    }

    @Test
    void anExactNarrationMatch_stillReportsExact() {
        Transaction ledger = existing("ACH D- CLEARING HOUSE-0000ABCDEFGH", new BigDecimal("23518.22"));
        DuplicateDetector detector = detector(ledger);
        DuplicateIndex index = detector.indexFor(userId);

        Optional<ImportDto.DuplicateMatch> match = detector.findMatch(index, date, new BigDecimal("1300.00"),
                "ACH D- CLEARING HOUSE-0000ABCDEFGH", Transaction.Type.EXPENSE, new BigDecimal("23518.22"));

        assertThat(match).isPresent();
        assertThat(match.get().confidence()).isEqualTo("EXACT");
    }
}
