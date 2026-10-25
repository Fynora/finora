package com.finora.validation;

import com.finora.accounts.AccountDto;
import com.finora.budgets.BudgetDto;
import com.finora.dto.UserSettingsDto;
import com.finora.entity.Account;
import com.finora.entity.LoanEntry;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.people.PeopleDtos;
import com.finora.transactions.TransactionDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyLimitsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static boolean violates(Set<? extends ConstraintViolation<?>> violations, String field) {
        return violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    // ------------------------------------------------------------------ request bounds

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "999999999999.99", "100.500", "1E+2", "0.000000000000000000000000000001",
            "999999999999999999999999999999", "-999999999999999999999999999999.999999999999999999999999999999"})
    void numbersWithin30DigitsEitherSideOfThePoint_areWithinRequestBounds(String value) {
        assertThat(MoneyLimits.withinRequestBounds(new BigDecimal(value))).isTrue();
    }

    @ParameterizedTest
    // 1E+131072 is the first value the Postgres driver (42.7.11, measured) sends as 0;
    // 1E-999999999 is one it throws on.
    @ValueSource(strings = {"1E+30", "1E+131072", "1E+999999999", "-1E+999999999", "1E-31", "1E-999999999",
            "9999999999999999999999999999999"})
    void numbersPast30DigitsEitherSideOfThePoint_areOutsideRequestBounds(String value) {
        assertThat(MoneyLimits.withinRequestBounds(new BigDecimal(value))).isFalse();
    }

    @Test
    void wholePaise_ignoresTrailingZeros_andRefusesAThirdDecimalPlace() {
        assertThat(MoneyLimits.hasWholePaise(new BigDecimal("100.500"))).isTrue();
        assertThat(MoneyLimits.hasWholePaise(new BigDecimal("1E+2"))).isTrue();
        assertThat(MoneyLimits.hasWholePaise(new BigDecimal("0.01"))).isTrue();
        assertThat(MoneyLimits.hasWholePaise(new BigDecimal("0.015"))).isFalse();
        assertThat(MoneyLimits.hasWholePaise(new BigDecimal("1E-999999999"))).isFalse();
    }

    // ------------------------------------------------------------------ Account.setBalance

    @Test
    void anAccountBalance_canReachTheColumnLimitEitherSideOfZero_butNotPassIt() {
        Account account = new Account();
        account.setBalance(MoneyLimits.MAX_AMOUNT);
        account.setBalance(MoneyLimits.MAX_AMOUNT.negate());
        assertThat(account.getBalance()).isEqualByComparingTo("-999999999999.99");

        assertThatThrownBy(() -> account.setBalance(new BigDecimal("1000000000000.00")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo(ErrorCode.ACCOUNT_BALANCE_TOO_LARGE));
        assertThatThrownBy(() -> account.setBalance(new BigDecimal("-1000000000000.00")))
                .isInstanceOf(ApiException.class);
        // A refused write leaves the balance as it was.
        assertThat(account.getBalance()).isEqualByComparingTo("-999999999999.99");
    }

    // ------------------------------------------------------------------ request fields

    private static AccountDto.CreateRequest account(BigDecimal balance, BigDecimal creditLimit, BigDecimal principal,
                                                    BigDecimal rate, BigDecimal maturity, BigDecimal installment) {
        return new AccountDto.CreateRequest("Savings", "SAVINGS", balance, creditLimit, null, null, null, null, null,
                null, null, principal, rate, null, maturity, installment, null, null);
    }

    @Test
    void accountAmounts_acceptTheColumnLimitEitherSideOfZero() {
        BigDecimal max = MoneyLimits.MAX_AMOUNT;
        assertThat(validator.validate(account(max, max, max, new BigDecimal("9999.99"), max, max))).isEmpty();
        assertThat(validator.validate(account(max.negate(), null, null, new BigDecimal("-9999.99"), null, null))).isEmpty();
    }

    @Test
    void accountAmounts_refuseAnythingPastTheColumnOrFinerThanOnePaisa() {
        BigDecimal over = new BigDecimal("1000000000000");
        var violations = validator.validate(account(over, over, over, new BigDecimal("10000"), over, over));
        for (String field : new String[]{"balance", "creditLimit", "principalAmount", "interestRate", "maturityAmount", "installmentAmount"}) {
            assertThat(violates(violations, field)).as(field).isTrue();
        }
        BigDecimal frac = new BigDecimal("0.015");
        var fine = validator.validate(account(frac, frac, frac, new BigDecimal("7.125"), frac, frac));
        for (String field : new String[]{"balance", "creditLimit", "principalAmount", "interestRate", "maturityAmount", "installmentAmount"}) {
            assertThat(violates(fine, field)).as(field).isTrue();
        }
        assertThat(violates(validator.validate(account(new BigDecimal("-1000000000000"), null, null, null, null, null)), "balance")).isTrue();
    }

    @Test
    void budgetLimit_refusesPastTheColumnAndFinerThanOnePaisa_acceptsTrailingZeros() {
        assertThat(violates(validator.validate(new BudgetDto.UpsertRequest("Food", new BigDecimal("1000000000000"))), "monthlyLimit")).isTrue();
        assertThat(violates(validator.validate(new BudgetDto.UpsertRequest("Food", new BigDecimal("0.015"))), "monthlyLimit")).isTrue();
        assertThat(validator.validate(new BudgetDto.UpsertRequest("Food", new BigDecimal("100.500")))).isEmpty();
        assertThat(validator.validate(new BudgetDto.UpsertRequest("Food", MoneyLimits.MAX_AMOUNT))).isEmpty();
    }

    @Test
    void peopleAmounts_refusePastTheColumnAndFinerThanOnePaisa() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(violates(validator.validate(new PeopleDtos.EntryRequest(a, LoanEntry.Direction.LENT, new BigDecimal("0.015"))), "amount")).isTrue();
        assertThat(violates(validator.validate(new PeopleDtos.EntryRequest(a, LoanEntry.Direction.LENT, new BigDecimal("1000000000000"))), "amount")).isTrue();
        assertThat(validator.validate(new PeopleDtos.EntryRequest(a, LoanEntry.Direction.LENT, new BigDecimal("400.50")))).isEmpty();
        assertThat(violates(validator.validate(new PeopleDtos.LinkShareRequest(a, b, new BigDecimal("0.015"))), "amount")).isTrue();
        assertThat(validator.validate(new PeopleDtos.LinkShareRequest(a, b, new BigDecimal("400.50")))).isEmpty();
    }

    @Test
    void lowBalanceThreshold_acceptsTrailingZeros_andRefusesPastTheColumn() {
        assertThat(validator.validate(new UserSettingsDto.UpdateRequest(new BigDecimal("100.500"), null, null, null))).isEmpty();
        assertThat(violates(validator.validate(new UserSettingsDto.UpdateRequest(new BigDecimal("1000000000000"), null, null, null)), "lowBalanceThreshold")).isTrue();
        assertThat(violates(validator.validate(new UserSettingsDto.UpdateRequest(new BigDecimal("0.015"), null, null, null)), "lowBalanceThreshold")).isTrue();
    }

    @Test
    void typedTransactionCategoryNames_areCappedAtTheColumn() {
        String eighty = "c".repeat(80), eightyOne = "c".repeat(81);
        var ok = new TransactionDto.CreateRequest(UUID.randomUUID(), eighty, LocalDate.of(2026, 10, 1), "d", BigDecimal.TEN, "EXPENSE", null);
        var tooLong = new TransactionDto.CreateRequest(UUID.randomUUID(), eightyOne, LocalDate.of(2026, 10, 1), "d", BigDecimal.TEN, "EXPENSE", null);
        assertThat(violates(validator.validate(ok), "categoryName")).isFalse();
        assertThat(violates(validator.validate(tooLong), "categoryName")).isTrue();
        var update = new TransactionDto.UpdateRequest(LocalDate.of(2026, 10, 1), "d", null, BigDecimal.TEN, "EXPENSE", eightyOne, null, null);
        assertThat(violates(validator.validate(update), "categoryName")).isTrue();
    }
}
