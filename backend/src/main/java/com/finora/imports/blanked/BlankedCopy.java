package com.finora.imports.blanked;

import java.util.List;

/** @param unusualRows rows of the real reading's row map worth a look (see {@link UnusualRows});
 *                     empty when the real reading failed */
public record BlankedCopy(String fileName, String trace, Check check, String notCheckedReason,
                          List<BlankedCopyComparison.Difference> differences,
                          List<UnusualRows.Flag> unusualRows) {
    public enum Check { MATCHES, DIFFERS, NOT_CHECKED }

    public BlankedCopy {
        unusualRows = unusualRows == null ? List.of() : List.copyOf(unusualRows);
    }
}
