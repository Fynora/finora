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
 *       {@code bankSource = PROFILE} -- straight away when an admin attached the layout, but for a
 *       layout grouped automatically only once {@link #MIN_AGREEING_UPLOADERS} different people's
 *       uploads named that bank in the file and no upload named another (Sid, 2026-10-23). An
 *       automatic group can rest on one person's file name, and one person's file name must never
 *       label everyone else's statements.</li>
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

    /** Different uploaders whose files named the profile's bank before an automatic group fills. */
    static final int MIN_AGREEING_UPLOADERS = 2;

    private final JdbcTemplate jdbc;
    private final BankManagementService banks;

    public ProfileBankFiller(JdbcTemplate jdbc, BankManagementService banks) {
        this.jdbc = jdbc;
        this.banks = banks;
    }

    public Filled<DetectedAccountInfo> fill(String fingerprint, DetectedAccountInfo detected) {
        if (detected == null || fingerprint == null) return new Filled<>(detected, false);
        return fill(detected, fileNamedABank(detected) ? declaredBank(fingerprint) : profileBank(fingerprint));
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
        boolean anyNamed = sections.stream().anyMatch(sec -> fileNamedABank(sec.detectedAccount()));
        boolean anyUnnamed = sections.stream().anyMatch(sec -> sec.detectedAccount() != null && !fileNamedABank(sec.detectedAccount()));
        Optional<AccountDto.BankDto> declared = anyNamed ? declaredBank(fingerprint) : Optional.empty();
        Optional<AccountDto.BankDto> usable = anyUnnamed ? profileBank(fingerprint) : Optional.empty();
        List<StagedAccountSection> filled = new ArrayList<>(sections.size());
        boolean conflict = false;
        for (StagedAccountSection section : sections) {
            Filled<DetectedAccountInfo> account = fill(section.detectedAccount(),
                    fileNamedABank(section.detectedAccount()) ? declared : usable);
            conflict |= account.bankConflict();
            filled.add(account.value() == section.detectedAccount() ? section
                    : new StagedAccountSection(account.value(), section.rows(), section.totalParsed(),
                            section.flaggedDuplicates(), section.unparseableRows(), section.verification()));
        }
        return new Filled<>(filled, conflict);
    }

    /** The profile's bank id for this layout, if the layout is in a profile that has one. Says
     *  nothing about whether it may be used; see {@link #profileBank}. */
    public Optional<String> profileBankId(String fingerprint) {
        return link(fingerprint).map(ProfileLink::bankId);
    }

    private record ProfileLink(String bankId, String source) {}

    private Optional<ProfileLink> link(String fingerprint) {
        if (fingerprint == null) return Optional.empty();
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.bank_id, r.profile_link_source FROM layout_registry r JOIN layout_profile p ON p.id = r.profile_id
                WHERE r.fingerprint = ? AND p.bank_id IS NOT NULL
                """, fingerprint);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(new ProfileLink((String) rows.get(0).get("bank_id"),
                (String) rows.get(0).get("profile_link_source")));
    }

    /** The profile's bank, only if it still resolves to that bank (a deleted custom bank resolves
     *  to OTHER, which is "no bank", not a bank to fill). */
    private Optional<AccountDto.BankDto> resolvedBank(ProfileLink link) {
        AccountDto.BankDto bank = banks.resolve(link.bankId());
        return bank != null && link.bankId().equals(bank.id()) && !BankRegistry.UNKNOWN_ID.equals(link.bankId())
                ? Optional.of(bank) : Optional.empty();
    }

    /** The bank this layout's profile may give a file that names none: an admin-attached layout's
     *  straight away, an automatically grouped layout's only when earlier uploads corroborate it. */
    private Optional<AccountDto.BankDto> profileBank(String fingerprint) {
        Optional<ProfileLink> link = link(fingerprint);
        if (link.isEmpty()) return Optional.empty();
        Optional<AccountDto.BankDto> bank = resolvedBank(link.get());
        if (bank.isEmpty() || "MANUAL".equals(link.get().source())) return bank;
        return corroborated(fingerprint, bank.get()) ? bank : Optional.empty();
    }

    /** The profile's bank for the conflict check only: whatever the profile says, trusted or not. */
    private Optional<AccountDto.BankDto> declaredBank(String fingerprint) {
        return link(fingerprint).flatMap(this::resolvedBank);
    }

    /** At least {@link #MIN_AGREEING_UPLOADERS} different uploaders' files named this bank (in the
     *  file itself: the analysis row records detection, never a filled bank), and no upload of the
     *  layout named another. Bank names on analysis rows exist from 2026-09-29 (V239). */
    private boolean corroborated(String fingerprint, AccountDto.BankDto bank) {
        String name = bank.officialName();
        if (name == null) return false;
        java.util.Map<String, Object> counts = jdbc.queryForMap("""
                SELECT count(DISTINCT coalesce(user_id::text, id::text))
                           FILTER (WHERE bank_name = ? AND outcome = 'PARSED') AS agree,
                       count(*) FILTER (WHERE bank_name IS NOT NULL AND bank_name <> ?) AS disagree
                FROM statement_analysis_sessions WHERE layout_fingerprint = ?
                """, name, name, fingerprint);
        long agree = ((Number) counts.get("agree")).longValue();
        long disagree = ((Number) counts.get("disagree")).longValue();
        return agree >= MIN_AGREEING_UPLOADERS && disagree == 0;
    }

    private static boolean fileNamedABank(DetectedAccountInfo detected) {
        return detected != null
                && DetectedAccountInfo.BANK_SOURCE_FILE.equals(DetectedAccountInfo.sourceOf(detected.bank()));
    }

    /** {@code profileBank}: for a file that named a bank, the profile's declared bank (conflict check
     *  only); for one that did not, the bank it may be given, if any. */
    private static Filled<DetectedAccountInfo> fill(DetectedAccountInfo detected, Optional<AccountDto.BankDto> profileBank) {
        if (detected == null || profileBank.isEmpty()) return new Filled<>(detected, false);
        AccountDto.BankDto bank = profileBank.get();
        if (fileNamedABank(detected)) return new Filled<>(detected, !bank.id().equals(detected.bank().id()));
        return new Filled<>(detected.withProfileBank(bank), false);
    }
}
