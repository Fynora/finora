package com.finora.dto;

import com.finora.imports.original.OriginalView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the original-view endpoints take and return: a reason in, a view's id and shape out, and a
 *  CSV's lines. A PDF's pages are PNG images, not JSON. Each record names its own OpenAPI schema, as
 *  {@link BlankedCopyDto}'s do, so the contract carries no bare {@code Opened} or {@code CsvLines}. */
public final class OriginalViewDto {
    private OriginalViewDto() {}

    @Schema(name = "OriginalViewOpenRequest")
    public record OpenRequest(String reason) {}

    @Schema(name = "OriginalViewOpened")
    public record Opened(UUID viewId, String format, int pageCount, Instant expiresAt) {
        public static Opened of(OriginalView v) {
            return new Opened(v.viewId(), v.format(), v.pageCount(), v.expiresAt());
        }
    }

    @Schema(name = "OriginalViewCsvLines")
    public record CsvLines(List<String> lines) {}
}
