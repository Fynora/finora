package com.finora.inflow;

import com.finora.entity.InflowKind;
import com.finora.service.InflowChoices;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for the inflow-kind endpoints (Plan 2). */
public final class InflowDtos {

    private InflowDtos() {}

    public record InflowKindDto(UUID id, String name, boolean countsAsIncome, String builtIn) {
        static InflowKindDto from(InflowKind k) {
            return new InflowKindDto(k.getId(), k.getName(), k.isCountsAsIncome(),
                    k.getBuiltIn() == null ? null : k.getBuiltIn().name());
        }
    }

    public record CreateKindRequest(@NotBlank @Size(max = 60) String name, @NotNull Boolean countsAsIncome) {}

    public record UpdateKindRequest(@Size(min = 1, max = 60) String name, Boolean countsAsIncome) {}

    public record SetChoiceRequest(@NotNull UUID kindId, @NotNull InflowChoices.Scope scope) {}

    /**
     * What a credit counts as, for the "Counts as" row on the detail screen.
     *
     * @param appliedBy      ROW or SENDER when the user's kind decided the row, else null
     * @param senderLabel    the sender's name as printed on the payment -- never the raw key
     * @param senderRowCount live credits from the same sender (what a SENDER choice reaches)
     */
    public record CountsAsDto(String flowClass, String flowReason, InflowKindDto kind, String appliedBy,
                              boolean choosable, String notChoosableReason, boolean senderAvailable,
                              String senderLabel, long senderRowCount, String summary) {}

    public record SenderRuleDto(UUID id, String label, InflowKindDto kind, long rowCount) {}

    public record UnresolvedRowDto(UUID id, LocalDate date, BigDecimal amount, String description, String accountName) {}

    /**
     * One sender in the review list. Identified by any of its row ids (a SENDER choice on one of
     * them reaches all); the raw counterparty key never leaves the server.
     *
     * @param senderKnown false when the rows carry no sender key -- the client offers only
     *                    "Just this one" for such a group, which then holds exactly one row
     */
    public record UnresolvedSenderDto(UUID sampleTransactionId, String label, boolean senderKnown, int count,
                                      BigDecimal total, LocalDate latestDate, String accountName,
                                      List<UnresolvedRowDto> rows) {}
}
