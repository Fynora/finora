package com.finora.imports;

import com.finora.imports.pdf.PdfPreviewGenerator;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
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

        var fromBytes = generator.generateSectionsWithContext(user, "statement.pdf", pdf).sections();
        var fromAcquired = generator.generateSectionsFromAcquired(user, "statement.pdf",
                generator.acquire(pdf, null)).sections();

        assertThat(fromAcquired).isEqualTo(fromBytes);
    }
}
