package com.finora.imports;

import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.imports.ownership.HolderNameSanity;
import com.finora.imports.pdf.PdfMetadataExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** When staging flags a layout because its holder could not be read (HOLDER_NAME_UNREADABLE). */
class HolderUnreadableFlagTest {

    private static DetectedAccountInfo withHolder(String holder) {
        DetectedAccountInfo info = Mockito.mock(DetectedAccountInfo.class);
        when(info.accountHolderName()).thenReturn(holder);
        return info;
    }

    private static DocumentContext refused() {
        DocumentContext ctx = new DocumentContext("PDF", "");
        ctx.recordDiagnostic(HolderNameSanity.REFUSED_DIAGNOSTIC);
        return ctx;
    }

    @Test
    void aRefusedLabelWithNoHolderAnywhereFlags() {
        assertThat(ImportService.holderUnreadable(refused(), List.of(withHolder(null)))).isTrue();
        assertThat(ImportService.holderUnreadable(refused(), Arrays.asList(withHolder(null), null))).isTrue();
    }

    @Test
    void aRefusedLabelFollowedByTheRealNameDoesNotFlag() {
        // The tester's Axis statements: the footer fragment was refused and the name found elsewhere.
        assertThat(ImportService.holderUnreadable(refused(), List.of(withHolder("RAVI KUMAR")))).isFalse(); // synthetic-ok
        assertThat(ImportService.holderUnreadable(refused(), List.of(withHolder(null), withHolder("RAVI KUMAR")))).isFalse(); // synthetic-ok
    }

    @Test
    void noHolderWithoutARefusalDoesNotFlag() {
        // Plenty of statements simply print no holder; that is not a layout problem.
        assertThat(ImportService.holderUnreadable(new DocumentContext("PDF", ""), List.of(withHolder(null)))).isFalse();
        assertThat(ImportService.holderUnreadable(null, List.of(withHolder(null)))).isFalse();
    }

    @Test
    void theExtractorRecordsARefusedLabel_andOnlyALabel() {
        DocumentContext labelled = new DocumentContext("PDF", "");
        new PdfMetadataExtractor().extract(List.of("Name: Previous Balance"), labelled);
        assertThat(labelled.diagnostics()).contains(HolderNameSanity.REFUSED_DIAGNOSTIC);

        DocumentContext clean = new DocumentContext("PDF", "");
        new PdfMetadataExtractor().extract(List.of("Name: RAVI KUMAR"), clean); // synthetic-ok
        assertThat(clean.diagnostics()).doesNotContain(HolderNameSanity.REFUSED_DIAGNOSTIC);
    }
}
