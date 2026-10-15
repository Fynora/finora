package com.finora.dto;

import com.finora.imports.blanked.BlankedCopy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the blanked-copy endpoints return. Carries the copy, the check's verdict and, when the copy
 *  reads differently, the fields and rows that differ with only the copy's own masked tokens --
 *  never a value from the real statement, and never its file name.
 *
 *  <p>Each record names its own OpenAPI schema. springdoc names a schema after the simple class
 *  name, so without these the contract carried a bare {@code Response}, {@code Difference} and
 *  {@code SwitchStatus}: one schema per name, which any other record called the same would have
 *  to share. The names change only the contract, not the JSON. */
public final class BlankedCopyDto {
    private BlankedCopyDto() {}

    @Schema(name = "BlankedCopySwitchStatus")
    public record SwitchStatus(boolean enabled) {}

    @Schema(name = "BlankedCopyDifference")
    public record Difference(int section, Integer row, String field, List<String> maskedTokens) {}

    /** A row of the real reading worth a look, by position and counts only: {@code kind} is
     *  TWO_PAGES, MANY_LINES or FAR_LINE (see {@code UnusualRows}); section and row are 0-based, as
     *  a {@link Difference} counts them. */
    @Schema(name = "BlankedCopyUnusualRow")
    public record UnusualRow(int section, int row, String kind, int lines, int pages) {}

    @Schema(name = "BlankedCopyResponse")
    public record Response(String fileName, String trace, String check, String notCheckedReason,
                           List<Difference> differences, List<UnusualRow> unusualRows) {
        public static Response of(BlankedCopy copy) {
            return new Response(copy.fileName(), copy.trace(), copy.check().name(), copy.notCheckedReason(),
                    copy.differences().stream()
                            .map(d -> new Difference(d.section(), d.row(), d.field().name(), d.maskedTokens()))
                            .toList(),
                    copy.unusualRows().stream()
                            .map(f -> new UnusualRow(f.section(), f.row(), f.kind().name(), f.lines(), f.pages()))
                            .toList());
        }
    }
}
