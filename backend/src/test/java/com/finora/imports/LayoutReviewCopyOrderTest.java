package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The review queue tries a flag's reason sources newest first; references order by day, then by sequence as a number. */
class LayoutReviewCopyOrderTest {

    private static List<String> sorted(String... references) {
        return Stream.of(references).sorted(LayoutCurationService.NEWEST_REFERENCE_FIRST).toList();
    }

    @Test
    void newerDaysComeFirst() {
        assertThat(sorted("SA-20261101-0009", "SA-20261112-0001", "SA-20261105-0500"))
                .containsExactly("SA-20261112-0001", "SA-20261105-0500", "SA-20261101-0009");
    }

    @Test
    void withinADayTheSequenceIsANumber_notText() {
        // As text "SA-20261112-9999" sorts after "SA-20261112-10000"; as a number it is older.
        assertThat(sorted("SA-20261112-9999", "SA-20261112-10000", "SA-20261112-0002"))
                .containsExactly("SA-20261112-10000", "SA-20261112-9999", "SA-20261112-0002");
    }

    @Test
    void aSequenceOfFiveDigitsDoesNotOutrankALaterDay() {
        assertThat(sorted("SA-20261112-10000", "SA-20261113-0001"))
                .containsExactly("SA-20261113-0001", "SA-20261112-10000");
    }

    @Test
    void otherShapesComeAfterAndOrderByText() {
        assertThat(sorted("legacy-b", "SA-20261101-0001", "legacy-a", "SA-2026-1"))
                .containsExactly("SA-20261101-0001", "legacy-b", "legacy-a", "SA-2026-1");
    }
}
