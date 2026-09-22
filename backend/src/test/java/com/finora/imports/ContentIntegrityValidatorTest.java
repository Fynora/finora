package com.finora.imports;

import com.finora.dto.ImportDto.VerificationFinding;
import com.finora.imports.pdf.ContentDamage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContentIntegrityValidatorTest {

    @Test
    void anUndamagedDocumentProducesNoFindingAtAll() {
        assertThat(ContentIntegrityValidator.check(ContentDamage.NONE)).isEmpty();
        assertThat(ContentIntegrityValidator.check(null)).isEmpty();
    }

    /** Only reported when there is something to report: an extra "verified" line on every clean report would
     *  render as an unknown rule on every app version that does not know it yet. */
    @Test
    void aDamagedDocumentFailsTheCheckAndSaysWhichPagesAndHowMuch() {
        Optional<VerificationFinding> finding = ContentIntegrityValidator.check(new ContentDamage(3, 0, List.of(1, 4)));

        assertThat(finding).isPresent();
        assertThat(finding.get().rule()).isEqualTo("CONTENT_INTEGRITY");
        assertThat(finding.get().outcome()).isEqualTo("FAILED");
        assertThat(finding.get().details()).containsEntry("failedTextOperators", 3)
                .containsEntry("damagedPages", List.of(1, 4)).containsEntry("corruptContentStreams", 0);
        assertThat(finding.get().details().get("explanation").toString()).contains("could not be read");
    }

    /** Either kind of damage is damage: a page can lose text with no operator ever failing. */
    @Test
    void aCorruptContentStreamAloneIsDamage() {
        var finding = ContentIntegrityValidator.check(new ContentDamage(0, 1, List.of(2))).orElseThrow();

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("corruptContentStreams", 1).containsEntry("damagedPages", List.of(2));
    }

    @Test
    void aFailedIntegrityCheckMakesTheReportNeedAttention() {
        var finding = ContentIntegrityValidator.check(new ContentDamage(0, 2, List.of(1))).orElseThrow();

        assertThat(ImportReliabilityStatusDeriver.derive(List.of(finding), false, null))
                .isEqualTo(ImportReliabilityStatus.NEEDS_ATTENTION);
    }
}
