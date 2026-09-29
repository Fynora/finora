package com.finora.imports;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class LayoutIdentityTest {

    private static DetectedAccountInfo detected(String bankId, String bankName, String product, boolean needsReview) {
        DetectedAccountInfo info = Mockito.mock(DetectedAccountInfo.class);
        AccountDto.BankDto bank = Mockito.mock(AccountDto.BankDto.class);
        when(bank.id()).thenReturn(bankId);
        when(bank.officialName()).thenReturn(bankName);
        when(info.bank()).thenReturn(bank);
        when(info.detectedProduct()).thenReturn(product);
        when(info.productNeedsReview()).thenReturn(needsReview);
        return info;
    }

    @Test
    void aConfidentCreditCardStatementKeysToBankAndFamily() {
        LayoutIdentity identity = LayoutIdentity.of(List.of(detected("KOTAK", "Kotak Mahindra Bank", "CREDIT_CARD", false)));

        assertThat(identity.key()).isEqualTo("KOTAK|CREDIT_CARD");
        assertThat(identity.profileName()).isEqualTo("Kotak Mahindra Bank — Credit Card");
    }

    @Test
    void anUnprovenButIdentifiedProductStillGroups_andCurrentAccountsShareTheSavingsFamily() {
        LayoutIdentity savings = LayoutIdentity.of(List.of(detected("HDFC", "HDFC Bank", "SAVINGS", true)));
        LayoutIdentity current = LayoutIdentity.of(List.of(detected("HDFC", "HDFC Bank", "CURRENT", true)));

        assertThat(savings.key()).isEqualTo("HDFC|SAVINGS").isEqualTo(current.key());
    }

    @Test
    void aCompositeStatementJoinsItsFamiliesInAStableOrder() {
        LayoutIdentity identity = LayoutIdentity.of(List.of(
                detected("HDFC", "HDFC Bank", "SAVINGS", false),
                detected("HDFC", "HDFC Bank", "FIXED_DEPOSIT", false),
                detected("HDFC", "HDFC Bank", "RECURRING_DEPOSIT", false)));

        assertThat(identity.key()).isEqualTo("HDFC|INVESTMENT,SAVINGS");
        assertThat(identity.profileName()).isEqualTo("HDFC Bank — Investments + Savings / Current");
    }

    @Test
    void noIdentityWithoutAKnownBankOneBankAndAProductWithAFamily() {
        assertThat(LayoutIdentity.of(List.of(detected("OTHER", null, "SAVINGS", false)))).isNull();
        assertThat(LayoutIdentity.of(List.of(detected("HDFC", "HDFC Bank", "UNKNOWN", false)))).isNull();
        assertThat(LayoutIdentity.of(List.of(detected("HDFC", "HDFC Bank", "LOAN", false)))).isNull();
        assertThat(LayoutIdentity.of(List.of(
                detected("HDFC", "HDFC Bank", "SAVINGS", false), detected("SBI", "State Bank of India", "SAVINGS", false)))).isNull();
        assertThat(LayoutIdentity.of(List.of(
                detected("HDFC", "HDFC Bank", "SAVINGS", false), detected("HDFC", "HDFC Bank", "UNKNOWN", false)))).isNull();
        assertThat(LayoutIdentity.of(Arrays.asList((DetectedAccountInfo) null))).isNull();
        assertThat(LayoutIdentity.of(List.of())).isNull();
        assertThat(LayoutIdentity.of(null)).isNull();
    }

    @Test
    void accountTypesMapToTheSameFamilies() {
        assertThat(LayoutIdentity.familyOfAccountType("CREDIT_CARD")).isEqualTo("CREDIT_CARD");
        assertThat(LayoutIdentity.familyOfAccountType("savings")).isEqualTo("SAVINGS");
        assertThat(LayoutIdentity.familyOfAccountType("LOAN")).isNull();
        assertThat(LayoutIdentity.familyOfAccountType(null)).isNull();
        assertThat(new LayoutIdentity("KOTAK", "Kotak Mahindra Bank", Set.of("CREDIT_CARD")).key())
                .isEqualTo(new LayoutIdentity("KOTAK", "x", Set.of(LayoutIdentity.familyOfProduct("CREDIT_CARD"))).key());
    }
}
