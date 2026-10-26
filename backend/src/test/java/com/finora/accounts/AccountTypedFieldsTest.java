package com.finora.accounts;

import com.finora.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTypedFieldsTest {

    @Test
    void aFullAccountNumberKeepsOnlyItsLastFour() {
        assertThat(AccountService.maskTypedAccountNumber("100000009012")).isEqualTo("••••9012");
        assertThat(AccountService.maskTypedAccountNumber("5011 2233 4455 6677")).isEqualTo("••••6677");
        assertThat(AccountService.maskTypedAccountNumber("12345")).as("five digits is more than a last four").isEqualTo("••••2345");
    }

    @Test
    void anAlreadyMaskedNumberOrALastFourIsKept_andBlankClears() {
        assertThat(AccountService.maskTypedAccountNumber("XXXXXX4587")).isEqualTo("XXXXXX4587");
        assertThat(AccountService.maskTypedAccountNumber(" ••••4587 ")).isEqualTo("••••4587");
        assertThat(AccountService.maskTypedAccountNumber("4587")).isEqualTo("4587");
        assertThat(AccountService.maskTypedAccountNumber("   ")).isNull();
        assertThat(AccountService.maskTypedAccountNumber(null)).isNull();
    }

    @Test
    void anIfscIsCapitalisedAndChecked() {
        assertThat(AccountService.normalizeIfsc("hdfc0xxxxxx")).isEqualTo("HDFC0XXXXXX");
        assertThat(AccountService.normalizeIfsc("ABCD0111111")).isEqualTo("ABCD0111111");
        assertThat(AccountService.normalizeIfsc(" ")).isNull();
        assertThat(AccountService.normalizeIfsc(null)).isNull();
        for (String bad : new String[] {"not-ifsc!!", "HDFC1XXXXXX", "HDF00XXXXXX", "HDFC0XXXXX", "HDFC0XXXXXXX"}) {
            assertThatThrownBy(() -> AccountService.normalizeIfsc(bad)).as(bad)
                    .isInstanceOf(ApiException.class).hasMessageContaining("IFSC");
        }
    }
}
