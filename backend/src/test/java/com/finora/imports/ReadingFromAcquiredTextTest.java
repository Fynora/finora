package com.finora.imports;

import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingFromAcquiredTextTest {

    static Stream<Callable<byte[]>> samples() {
        return Stream.of(
                PdfFixtureBuilder::buildReverseChronologicalRunningBalanceSample,
                PdfFixtureBuilder::buildReferenceNumberAndBalanceSample,
                PdfFixtureBuilder::buildWrappedDescriptionCreditCardSample,
                PdfFixtureBuilder::buildMultiSectionCompositeStatementSample,
                PdfFixtureBuilder::buildSavingsAndCardWithCardSummarySample);
    }

    @ParameterizedTest
    @MethodSource("samples")
    void readingFromAcquiredTextEqualsReadingFromBytes(Callable<byte[]> sample) throws Exception {
        byte[] pdf = sample.call();
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        UUID user = UUID.randomUUID();

        var fromBytes = generator.generateSectionsWithContext(user, "statement.pdf", pdf);
        var fromAcquired = generator.generateSectionsFromAcquired(user, "statement.pdf",
                generator.acquire(pdf, null));

        // Not vacuous: each sample reads to at least one section with rows.
        assertThat(fromBytes.sections()).isNotEmpty();
        assertThat(fromBytes.sections()).anySatisfy(s -> assertThat(s.rows()).isNotEmpty());
        assertThat(fromAcquired.sections()).isEqualTo(fromBytes.sections());

        // The document facts the split moved from one method into two.
        DocumentContext a = fromAcquired.documentContext();
        DocumentContext b = fromBytes.documentContext();
        assertThat(a.textSource()).isNotNull().isEqualTo(b.textSource());
        assertThat(a.contentDamage()).isEqualTo(b.contentDamage());
        assertThat(a.extractedRuns()).isNotNull().isPositive().isEqualTo(b.extractedRuns());
        assertThat(fromAcquired.creditCardSummary()).isEqualTo(fromBytes.creditCardSummary());
    }

    /** The card-summary comparison above is not only null against null. */
    @Test
    void theCardSummarySampleHasACardSummaryToCompare() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSavingsAndCardWithCardSummarySample();
        PdfPreviewGenerator generator = PdfGeneratorTestSupport.generator();
        var fromAcquired = generator.generateSectionsFromAcquired(UUID.randomUUID(), "statement.pdf",
                generator.acquire(pdf, null));
        assertThat(fromAcquired.creditCardSummary()).isNotNull();
    }
}
