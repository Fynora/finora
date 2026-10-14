package com.finora.dto;

import com.finora.imports.blanked.BlankedCopy;

import java.util.List;

/** What the blanked-copy endpoints return. Carries the copy, the check's verdict and, when the copy
 *  reads differently, the fields and rows that differ with only the copy's own masked tokens --
 *  never a value from the real statement, and never its file name. */
public final class BlankedCopyDto {
    private BlankedCopyDto() {}

    public record SwitchStatus(boolean enabled) {}

    public record Difference(int section, Integer row, String field, List<String> maskedTokens) {}

    public record Response(String fileName, String trace, String check, String notCheckedReason,
                           List<Difference> differences) {
        public static Response of(BlankedCopy copy) {
            return new Response(copy.fileName(), copy.trace(), copy.check().name(), copy.notCheckedReason(),
                    copy.differences().stream()
                            .map(d -> new Difference(d.section(), d.row(), d.field().name(), d.maskedTokens()))
                            .toList());
        }
    }
}
