package com.finora.imports.analysis;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentIdentityTest {

    private static AccountDto.BankDto bank(String id, String officialName) {
        return new AccountDto.BankDto(id, officialName, null, null, null, null, null, null, null, List.of());
    }

    private static DetectedAccountInfo detected(AccountDto.BankDto bank, String product) {
        DetectedAccountInfo info = mock(DetectedAccountInfo.class);
        when(info.bank()).thenReturn(bank);
        when(info.detectedProduct()).thenReturn(product);
        return info;
    }

    @Test
    void aRecognisedBankAndProductAreRecorded() {
        var identity = DocumentIdentity.of(detected(bank("HDFC", "HDFC Bank"), "SAVINGS"));

        assertThat(identity).isEqualTo(new DocumentIdentity(true, "HDFC Bank", "SAVINGS"));
    }

    @Test
    void anUnrecognisedBankIsNullNotTheDisplayFallback() {
        // BankRegistry returns id OTHER when nothing matched; its display name must never be
        // recorded as though it were a bank.
        var identity = DocumentIdentity.of(detected(bank("OTHER", "Other Bank"), "UNKNOWN"));

        assertThat(identity.checked()).isTrue();
        assertThat(identity.bankName()).isNull();
        assertThat(identity.statementType()).as("UNKNOWN is not an identification").isNull();
    }

    @Test
    void aCompositeStatementKeepsDistinctTypesInSectionOrderAndTheFirstRecognisedBank() {
        var identity = DocumentIdentity.of(List.of(
                detected(bank("OTHER", "Other Bank"), "SAVINGS"),
                detected(bank("HSBC", "HSBC"), "FIXED_DEPOSIT"),
                detected(bank("HSBC", "HSBC"), "SAVINGS")));

        assertThat(identity).isEqualTo(new DocumentIdentity(true, "HSBC", "SAVINGS,FIXED_DEPOSIT"));
    }

    @Test
    void nothingDetectedIsNotCheckedRatherThanUnrecognised() {
        // "never got that far" and "looked and found nothing" must stay distinct answers.
        assertThat(DocumentIdentity.of((DetectedAccountInfo) null)).isEqualTo(DocumentIdentity.NONE);
        assertThat(DocumentIdentity.of(List.of())).isEqualTo(DocumentIdentity.NONE);
        assertThat(DocumentIdentity.of(Arrays.asList((DetectedAccountInfo) null))).isEqualTo(DocumentIdentity.NONE);
        assertThat(DocumentIdentity.ofSections(null)).isEqualTo(DocumentIdentity.NONE);
        assertThat(DocumentIdentity.NONE.checked()).isFalse();
    }

    @Test
    void anOverlongValueIsTruncatedToItsColumnRatherThanFailingTheEvidenceWrite() {
        var identity = DocumentIdentity.of(detected(bank("X", "B".repeat(300)), "SAVINGS"));

        assertThat(identity.bankName()).hasSize(128);
    }

    @Test
    void diagnosticsWithoutAnIdentityCarryNone() {
        assertThat(ParseDiagnostics.NONE.identity()).isEqualTo(DocumentIdentity.NONE);
        assertThat(ParseDiagnostics.of(3, null).identity()).isEqualTo(DocumentIdentity.NONE);
        assertThat(ParseDiagnostics.of(3, null).withIdentity(null).identity()).isEqualTo(DocumentIdentity.NONE);
    }
}
