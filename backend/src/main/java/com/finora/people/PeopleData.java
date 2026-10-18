package com.finora.people;

import com.finora.entity.LoanEntry;
import com.finora.entity.PeoplePreference;
import com.finora.repository.LoanEntryRepository;
import com.finora.repository.PeoplePreferenceRepository;
import com.finora.repository.PersonSettingRepository;
import com.finora.repository.ShareLinkRepository;
import com.finora.repository.SplitRuleRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The five shared-payments tables (V279) as one unit for the account purge and the data export, so
 * neither has to know them one by one. DataExportServiceTest's purge-to-export guard matches
 * {@code peopleData.deleteByUserId} by name, so this call is held to an export entry like a repository's.
 */
@Component
public class PeopleData {

    public record ShareExport(UUID creditTransactionId, UUID billTransactionId, BigDecimal amount, Instant createdAt) {}
    public record SplitRuleExport(String senderKey, UUID billCategoryId, String billPayeeKey, String billMerchant,
                                  Instant createdAt, Instant removedAt) {}
    public record LoanEntryExport(String personKey, UUID transactionId, LoanEntry.Direction direction,
                                  BigDecimal amount, Instant createdAt) {}
    public record PersonExport(String personKey, boolean family) {}

    /** shared_payments.json. */
    public record PeopleExport(List<ShareExport> shares, List<SplitRuleExport> splitRules,
                               List<LoanEntryExport> loanEntries, List<PersonExport> people, String ownUpiId) {
        public static final PeopleExport EMPTY = new PeopleExport(List.of(), List.of(), List.of(), List.of(), null);

        /** The manifest's row count. */
        public int size() {
            return shares.size() + splitRules.size() + loanEntries.size() + people.size() + (ownUpiId == null ? 0 : 1);
        }
    }

    private final ShareLinkRepository shares;
    private final SplitRuleRepository splitRules;
    private final LoanEntryRepository loanEntries;
    private final PersonSettingRepository personSettings;
    private final PeoplePreferenceRepository preferences;

    public PeopleData(ShareLinkRepository shares, SplitRuleRepository splitRules, LoanEntryRepository loanEntries,
                            PersonSettingRepository personSettings, PeoplePreferenceRepository preferences) {
        this.shares = shares;
        this.splitRules = splitRules;
        this.loanEntries = loanEntries;
        this.personSettings = personSettings;
        this.preferences = preferences;
    }

    public void deleteByUserId(UUID userId) {
        shares.deleteByUserId(userId);
        splitRules.deleteByUserId(userId);
        loanEntries.deleteByUserId(userId);
        personSettings.deleteByUserId(userId);
        preferences.deleteByUserId(userId);
    }

    public PeopleExport exportFor(UUID userId) {
        return new PeopleExport(
                shares.findByUserId(userId).stream()
                        .map(s -> new ShareExport(s.getCreditTxnId(), s.getBillTxnId(), s.getAmount(), s.getCreatedAt())).toList(),
                splitRules.findByUserId(userId).stream()
                        .map(r -> new SplitRuleExport(r.getSenderKey(), r.getBillCategoryId(), r.getBillPayeeKey(),
                                r.getBillMerchant(), r.getCreatedAt(), r.getRemovedAt())).toList(),
                loanEntries.findByUserId(userId).stream()
                        .map(e -> new LoanEntryExport(e.getPersonKey(), e.getTxnId(), e.getDirection(), e.getAmount(),
                                e.getCreatedAt())).toList(),
                personSettings.findByUserId(userId).stream()
                        .map(p -> new PersonExport(p.getPersonKey(), p.isFamily())).toList(),
                preferences.findById(userId).map(PeoplePreference::getOwnUpiId).orElse(null));
    }
}
