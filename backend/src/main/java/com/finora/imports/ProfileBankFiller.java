package com.finora.imports;

import com.finora.accounts.AccountDto;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagingResponse;
import com.finora.service.BankManagementService;
import com.finora.util.BankRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gives an upload the bank of its layout's profile when the file itself names none (V285; design
 * docs/superpowers/specs/2026-10-22-bank-from-profile-and-required-bank-design.md, section 2).
 *
 * <p>The rule, per detected account:
 * <ol>
 *   <li>The file named a bank: keep it. If the profile names a different one, report a conflict so
 *       the layout goes back to the review queue -- the file always wins.</li>
 *   <li>The file named none and the layout's profile has a bank that still resolves: use it, marked
 *       {@code bankSource = PROFILE}.</li>
 *   <li>Otherwise the bank stays unknown and the user chooses it on the review screen.</li>
 * </ol>
 *
 * <p>Runs after detection, on the copy that is stored in the session and shown to the user. The
 * layout's own identity, the analysis row and the profile grouping keep using the unfilled
 * detection: a bank filled from a profile is not evidence about the file, and feeding it back
 * would let a layout confirm its own profile.
 */
@Component
public class ProfileBankFiller {

    /** The filled value, and whether the file's own bank disagreed with the profile's. */
    public record Filled<T>(T value, boolean bankConflict) {}

    private final JdbcTemplate jdbc;
    private final BankManagementService banks;

    public ProfileBankFiller(JdbcTemplate jdbc, BankManagementService banks) {
        this.jdbc = jdbc;
        this.banks = banks;
    }

    public Filled<DetectedAccountInfo> fill(String fingerprint, DetectedAccountInfo detected) {
        if (detected == null || fingerprint == null) return new Filled<>(detected, false);
        return fill(detected, profileBank(fingerprint));
    }

    public Filled<StagingResponse> fill(String fingerprint, StagingResponse staged) {
        if (staged == null) return new Filled<>(null, false);
        Filled<DetectedAccountInfo> account = fill(fingerprint, staged.detectedAccount());
        if (account.value() == staged.detectedAccount()) return new Filled<>(staged, account.bankConflict());
        return new Filled<>(new StagingResponse(staged.rows(), staged.totalParsed(), staged.flaggedDuplicates(),
                account.value(), staged.unparseableRows(), staged.verification()), account.bankConflict());
    }

    public Filled<List<StagedAccountSection>> fill(String fingerprint, List<StagedAccountSection> sections) {
        if (sections == null || sections.isEmpty() || fingerprint == null) return new Filled<>(sections, false);
        Optional<AccountDto.BankDto> profileBank = profileBank(fingerprint);
        List<StagedAccountSection> filled = new ArrayList<>(sections.size());
        boolean conflict = false;
        for (StagedAccountSection section : sections) {
            Filled<DetectedAccountInfo> account = fill(section.detectedAccount(), profileBank);
            conflict |= account.bankConflict();
            filled.add(account.value() == section.detectedAccount() ? section
                    : new StagedAccountSection(account.value(), section.rows(), section.totalParsed(),
                            section.flaggedDuplicates(), section.unparseableRows(), section.verification()));
        }
        return new Filled<>(filled, conflict);
    }

    /** The profile's bank id for this layout, if the layout is in a profile that has one. */
    public Optional<String> profileBankId(String fingerprint) {
        if (fingerprint == null) return Optional.empty();
        List<String> ids = jdbc.queryForList("""
                SELECT p.bank_id FROM layout_registry r JOIN layout_profile p ON p.id = r.profile_id
                WHERE r.fingerprint = ? AND p.bank_id IS NOT NULL
                """, String.class, fingerprint);
        return ids.isEmpty() ? Optional.empty() : Optional.ofNullable(ids.get(0));
    }

    /** The profile's bank, only if it still resolves to that bank (a deleted custom bank resolves
     *  to OTHER, which is "no bank", not a bank to fill). */
    private Optional<AccountDto.BankDto> profileBank(String fingerprint) {
        return profileBankId(fingerprint)
                .map(id -> {
                    AccountDto.BankDto bank = banks.resolve(id);
                    return bank != null && id.equals(bank.id()) && !BankRegistry.UNKNOWN_ID.equals(id) ? bank : null;
                });
    }

    private static Filled<DetectedAccountInfo> fill(DetectedAccountInfo detected, Optional<AccountDto.BankDto> profileBank) {
        if (detected == null || profileBank.isEmpty()) return new Filled<>(detected, false);
        AccountDto.BankDto bank = profileBank.get();
        boolean fileNamedABank = DetectedAccountInfo.BANK_SOURCE_FILE.equals(DetectedAccountInfo.sourceOf(detected.bank()));
        if (fileNamedABank) return new Filled<>(detected, !bank.id().equals(detected.bank().id()));
        return new Filled<>(detected.withProfileBank(bank), false);
    }
}
