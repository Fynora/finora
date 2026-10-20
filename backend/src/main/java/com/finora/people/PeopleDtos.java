package com.finora.people;

import com.finora.entity.LoanEntry;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Requests and responses of /api/v1/people (spec §7). */
public final class PeopleDtos {

    private PeopleDtos() {}

    /** {@code amount} null: as much as fits (what is left of the credit and of the bill). */
    public record LinkShareRequest(@NotNull UUID creditId, @NotNull UUID billId, @DecimalMin("0.01") BigDecimal amount) {}

    public record ShareDto(UUID creditId, UUID billId, String label, LocalDate date, BigDecimal amount, boolean fromRule) {}

    /** LENT and REPAID on a payment out; PAID_BACK and BORROWED on money in. {@code amount} null: the
     *  payment's full amount, replacing any share links on it. Set: only that much -- the part of a
     *  credit left over after the bills it is linked to, which keeps its share links. */
    public record EntryRequest(@NotNull UUID txnId, @NotNull LoanEntry.Direction direction,
                               @DecimalMin("0.01") BigDecimal amount) {
        public EntryRequest(UUID txnId, LoanEntry.Direction direction) {
            this(txnId, direction, null);
        }
    }

    public record EntryDto(UUID id, String personKey, UUID txnId, LoanEntry.Direction direction, BigDecimal amount) {}

    public enum SettleHow { CASH, LET_GO }

    public record SettleRequest(@NotBlank @Size(max = 200) String personKey, @NotNull SettleHow how) {}

    /** The credit and the bill the rule is made from: its sender, its bill's category and payee. */
    public record SplitRuleRequest(@NotNull UUID creditId, @NotNull UUID billId) {}

    public record SplitRuleDto(UUID id, String senderKey, UUID billCategoryId, String billCategory,
                               String billPayeeKey, String billMerchant) {}

    /** Positive {@code owedToUser}: they owe the user; negative: the user owes them. */
    public record PersonDto(String personKey, String label, BigDecimal owedToUser, LocalDate since, boolean family) {}

    /** {@code kind}: a loan entry's direction, or SHARE for a share they paid toward a bill. */
    public record TimelineItem(LocalDate date, String kind, BigDecimal amount, UUID txnId) {}

    public record PersonDetailDto(String personKey, String label, BigDecimal owedToUser, LocalDate since,
                                  boolean family, List<TimelineItem> timeline, List<SplitRuleDto> rules) {}

    public record PersonSettingsRequest(@NotBlank @Size(max = 200) String personKey, boolean family) {}

    /** {@code ownUpiId}: name@bank, or null to forget it. It goes into a payment request the user
     *  shares, so nothing else is kept -- no spaces, no '&' to add to the request. */
    public record PreferencesRequest(
            @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$") String ownUpiId) {}

    public record PreferencesDto(String ownUpiId) {}

    /** The person on the other side of one transaction. */
    public record PersonRefDto(String personKey, String label) {}
}
