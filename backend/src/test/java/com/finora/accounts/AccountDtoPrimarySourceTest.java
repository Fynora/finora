package com.finora.accounts;

import com.finora.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDtoPrimarySourceTest {

    @Test
    void defaultsToManualWhenTheAccountIsManuallySourced() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
        account.setAccountType(Account.Type.SAVINGS);

        AccountDto dto = AccountDto.from(account);

        assertThat(dto.primarySource()).isEqualTo("MANUAL");
    }

    @Test
    void surfacesAccountAggregatorWhenTheAccountIsAaLinked() {
        Account account = new Account();
        ReflectionTestUtils.setField(account, "id", java.util.UUID.randomUUID());
        account.setAccountType(Account.Type.SAVINGS);
        account.setPrimarySource(Account.PrimarySource.ACCOUNT_AGGREGATOR);

        AccountDto dto = AccountDto.from(account);

        assertThat(dto.primarySource()).isEqualTo("ACCOUNT_AGGREGATOR");
    }
}
