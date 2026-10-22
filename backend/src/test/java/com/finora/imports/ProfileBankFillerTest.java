package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.service.BankManagementService;
import com.finora.util.BankRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The fill rule (spec 2026-10-22, section 2): the file's own bank always wins; a profile's bank is
 * used only when the file names none; anything else leaves the bank unknown for the user to choose.
 */
class ProfileBankFillerTest {

    private static final String FP = "FP-1-CA196976";

    private JdbcTemplate jdbc;
    private BankManagementService banks;
    private ProfileBankFiller filler;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        banks = mock(BankManagementService.class);
        when(banks.resolve(anyString())).thenAnswer(call -> AccountDto.BankDto.from(BankRegistry.get(call.getArgument(0))));
        filler = new ProfileBankFiller(jdbc, banks);
    }

    private void profileBank(String bankId) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(FP))).thenReturn(bankId == null ? List.of() : List.of(bankId));
    }

    /** A detected account carrying {@code bankId} and a product identity key, nothing else that matters here. */
    static DetectedAccountInfo detected(String bankId) {
        AccountDto.BankDto bank = AccountDto.BankDto.from(BankRegistry.get(bankId));
        String name = BankRegistry.UNKNOWN_ID.equals(bankId) ? "Bank Statement Import" : bank.officialName();
        return new DetectedAccountInfo(name, "SAVINGS", null, null, null, null, "XXXX0001", null, null, null, null,
                null, null, bank, "SAVINGS", 0.9, false, List.of(), "identity-key",
                null, null, null, null, null, null, null);
    }

    @Test
    void theOldConstructorDerivesTheSourceFromTheBank() {
        assertThat(detected("HDFC").bankSource()).isEqualTo("FILE");
        assertThat(detected("OTHER").bankSource()).isEqualTo("NONE");
    }

    @Test
    void fileBankIsKept_andNoConflictWhenProfileAgrees() {
        profileBank("HDFC");

        var filled = filler.fill(FP, detected("HDFC"));

        assertThat(filled.value().bank().id()).isEqualTo("HDFC");
        assertThat(filled.value().bankSource()).isEqualTo("FILE");
        assertThat(filled.bankConflict()).isFalse();
    }

    @Test
    void fileBankIsKept_andConflictWhenProfileDisagrees() {
        profileBank("HDFC");

        var filled = filler.fill(FP, detected("ICICI"));

        assertThat(filled.value().bank().id()).isEqualTo("ICICI");
        assertThat(filled.value().bankSource()).isEqualTo("FILE");
        assertThat(filled.bankConflict()).isTrue();
    }

    @Test
    void unknownBankIsFilledFromProfile() {
        profileBank("HDFC");

        var filled = filler.fill(FP, detected("OTHER"));

        assertThat(filled.value().bank().id()).isEqualTo("HDFC");
        assertThat(filled.value().bankSource()).isEqualTo("PROFILE");
        assertThat(filled.value().suggestedName()).isEqualTo("HDFC Bank");
        // The full account number is not on the DTO, so a key built for the unknown bank cannot be
        // rebuilt for the profile's; it is dropped rather than left pointing at the wrong bank.
        assertThat(filled.value().productIdentityHash()).isNull();
        assertThat(filled.value().accountNumberMasked()).isEqualTo("XXXX0001");
        assertThat(filled.bankConflict()).isFalse();
    }

    @Test
    void noProfileLeavesItUnknown() {
        profileBank(null);

        var filled = filler.fill(FP, detected("OTHER"));

        assertThat(filled.value().bank().id()).isEqualTo("OTHER");
        assertThat(filled.value().bankSource()).isEqualTo("NONE");
        assertThat(filled.value().productIdentityHash()).isEqualTo("identity-key");
    }

    @Test
    void profileBankThatNoLongerResolvesIsIgnored() {
        profileBank("CUSTOM_DELETED");

        var filled = filler.fill(FP, detected("OTHER"));

        assertThat(filled.value().bank().id()).isEqualTo("OTHER");
        assertThat(filled.value().bankSource()).isEqualTo("NONE");
    }

    @Test
    void profileBankThatNoLongerResolvesIsNoConflictEither() {
        profileBank("CUSTOM_DELETED");

        assertThat(filler.fill(FP, detected("HDFC")).bankConflict()).isFalse();
    }

    @Test
    void nullFingerprintIsANoOp() {
        var filled = filler.fill(null, detected("OTHER"));

        assertThat(filled.value().bank().id()).isEqualTo("OTHER");
        verifyNoInteractions(jdbc);
    }

    @Test
    void aStagingResponseIsFilledThroughItsDetectedAccount() {
        profileBank("HDFC");
        StagingResponse staged = new StagingResponse(List.of(), 0, 0, detected("OTHER"), List.of());

        var filled = filler.fill(FP, staged);

        assertThat(filled.value().detectedAccount().bank().id()).isEqualTo("HDFC");
        assertThat(filled.value().detectedAccount().bankSource()).isEqualTo("PROFILE");
    }

    @Test
    void eachSectionIsFilledOnItsOwn() {
        profileBank("HDFC");
        List<StagedAccountSection> sections = List.of(
                new StagedAccountSection(detected("HDFC"), List.of(), 0, 0, List.of()),
                new StagedAccountSection(detected("OTHER"), List.of(), 0, 0, List.of()));

        var filled = filler.fill(FP, sections);

        assertThat(filled.value()).extracting(s -> s.detectedAccount().bank().id()).containsExactly("HDFC", "HDFC");
        assertThat(filled.value()).extracting(s -> s.detectedAccount().bankSource()).containsExactly("FILE", "PROFILE");
        assertThat(filled.bankConflict()).isFalse();
    }

    @Test
    void legacyJsonWithoutBankSourceStillReads() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String current = mapper.writeValueAsString(detected("HDFC"));
        assertThat(mapper.readTree(current).has("bankSource")).isTrue();

        // A session staged before this field existed.
        String legacy = mapper.readTree(current).deepCopy().toString().replace(",\"bankSource\":\"FILE\"", "");
        assertThat(legacy).doesNotContain("bankSource");

        DetectedAccountInfo read = mapper.readValue(legacy, DetectedAccountInfo.class);
        assertThat(read.bank().id()).isEqualTo("HDFC");
        assertThat(read.bankSource()).isNull();
    }
}
