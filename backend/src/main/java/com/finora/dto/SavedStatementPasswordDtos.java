package com.finora.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Settings -> Saved statement passwords. Names which statements have a saved password; never the password. */
public final class SavedStatementPasswordDtos {

    private SavedStatementPasswordDtos() {}

    public record SavedStatementPassword(UUID statementImportId, String fileName, String accountName,
                                         LocalDate periodStart, LocalDate periodEnd, Instant savedAt) {}

    /** @param saveAvailable whether new passwords can be saved on this deployment; existing ones can always be removed */
    public record SavedStatementPasswordList(boolean saveAvailable, List<SavedStatementPassword> items) {}

    public record SavedStatementPasswordsRemoved(int removed) {}
}
