package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TypedBalanceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TypedBalance typedBalance = new TypedBalance(userRepository, transactionRepository);

    private Account card(String zone) {
        User user = new User();
        user.setTimezone(zone);
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        a.setAccountType(Account.Type.CREDIT_CARD);
        a.setBalance(new BigDecimal("-100396.53"));
        a.setLastAbsoluteSetStatementId(UUID.randomUUID());
        return a;
    }

    @Test
    void stateSetsBalanceClearsAnchorStampsTypedAndBaseline_inTheUsersOwnDay() {
        for (String zone : List.of("Asia/Kolkata", "America/Los_Angeles")) {
            Account a = card(zone);
            Instant before = Instant.now();

            typedBalance.state(a, new BigDecimal("12345.67"));

            assertThat(a.getBalance()).isEqualByComparingTo("12345.67");
            assertThat(a.getLastAbsoluteSetStatementId()).isNull();
            assertThat(a.getBalanceTypedAt()).isBetween(before, Instant.now());
            assertThat(a.getBalanceBaselineDate()).as(zone)
                    .isEqualTo(LocalDate.now(ZoneId.of(zone)).minusDays(1));
        }
    }

    @Test
    void stateReleasesEveryDuplicateMarkTheOldFigureHeld() {
        Account a = card("Asia/Kolkata");
        Transaction held = new Transaction();
        held.setIsDuplicateOf(UUID.randomUUID());
        held.setDuplicateBalanceReversed(true);
        held.setDuplicateBalanceAnchorId(UUID.randomUUID());
        Transaction untouched = new Transaction();
        untouched.setIsDuplicateOf(UUID.randomUUID());
        when(transactionRepository.findByAccountIdAndIsDuplicateOfIsNotNull(a.getId()))
                .thenReturn(List.of(held, untouched));

        typedBalance.state(a, BigDecimal.ZERO);

        assertThat(held.isDuplicateBalanceReversed()).isFalse();
        assertThat(held.getDuplicateBalanceAnchorId()).isNull();
        verify(transactionRepository).saveAll(List.of(held));
    }

    @Test
    void markTypedOnlyStamps() {
        Account a = card("Asia/Kolkata");
        typedBalance.markTyped(a);
        assertThat(a.getBalance()).isEqualByComparingTo("-100396.53");
        assertThat(a.getBalanceTypedAt()).isNotNull();
        assertThat(a.getBalanceBaselineDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1));
        verify(transactionRepository, never()).saveAll(anyList());
    }
}
