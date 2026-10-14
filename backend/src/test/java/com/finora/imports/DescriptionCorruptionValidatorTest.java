package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The line these tests defend is the one between "genuinely longer narration" and "absorbed text
 * that was never part of it" -- see the validator's own class doc for why both a length outlier
 * AND a sentence-break shape are required together, and why the threshold is relative to the
 * section's own median rather than a fixed byte count.
 *
 * <p>Fully synthetic fixtures -- no real document text quoted, per this codebase's Synthetic
 * Fixture Policy. Every merchant name, reference code and digit run below is invented.
 */
class DescriptionCorruptionValidatorTest {

    private final DescriptionCorruptionValidator validator = new DescriptionCorruptionValidator();

    private static StagedRow row(String description) {
        return new StagedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal("35.00"),
                "EXPENSE", "Other", "default", null, false, null, null);
    }

    /** Five ordinary, uniformly-short narrations -- the shape a UPI-column credit-card statement
     *  narration column commonly has, truncated to a fixed physical width. */
    private static List<StagedRow> ordinaryStatement() {
        List<StagedRow> rows = new ArrayList<>();
        rows.add(row("UPI-VMPL DEL 24 REF001"));
        rows.add(row("UPI-ZOMATO ORDER REF002"));
        rows.add(row("UPI-BLUE LEAF STORE REF3"));
        rows.add(row("UPI-CORNER SHOP NINE REF4"));
        rows.add(row("UPI-GREEN VALLEY MART R5"));
        return rows;
    }

    @Test
    void reportsNotApplicableBelowTheMinimumSampleSize() {
        var finding = validator.check(List.of(row("UPI-SHORT ONE"), row("UPI-SHORT TWO")));

        assertThat(finding.rule()).isEqualTo("DESCRIPTION_CORRUPTION");
        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void verifiesAnOrdinaryStatementWithUniformlyShortDescriptions() {
        var finding = validator.check(ordinaryStatement());

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void flagsADescriptionThatAbsorbedAFooterSentence() {
        // The shape this validator exists to catch: a normal short narration, immediately followed
        // by boilerplate prose that was never part of it -- long, and reading as more than one
        // sentence. Fully invented wording, mirroring the SHAPE of the real Axis Bank bug (a
        // page footer merging into the preceding transaction), not its content.
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        rows.add(row("UPI-CORNER SHOP TEN REF6 Please pay your card bill from any account by "
                + "registering for auto-debit at any branch. Visit bank.example.in to download "
                + "the form. Registered office reference number: EXAMPLE0000EXAMPLE."));

        var finding = validator.check(rows);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details()).containsKey("sectionMedianLength");
        assertThat(finding.details().get("outliers").toString())
                .contains("rowIndex=5");
    }

    @Test
    void doesNotFlagAGenuinelyLongButSinglePhraseNarration() {
        // A real long reference string has no sentence breaks at all -- length alone must not be
        // enough to flag it, or a document whose normal narrations vary a lot in length would cry
        // wolf on every one that happens to be verbose.
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        rows.add(row("NEFT-EXAMPLECORP-FROM SOME EXAMPLE PRIVATE LIMITED COMPANY ACCOUNT REFERENCE "
                + "EXAMPLE INVOICE NUMBER EXAMPLEINV DATED EXAMPLEDATE PROCESSED SUCCESSFULLY"));

        var finding = validator.check(rows);

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }

    @Test
    void doesNotFlagAShortSentenceLikeDescriptionWhenTheSectionsOwnNarrationsAreAlsoShort() {
        // A section whose OWN normal descriptions are short prose ("Cash withdrawal. ATM fee
        // applies.") must not be flagged just for having a sentence break -- the length must be a
        // real outlier relative to this document's own peers, not merely present.
        List<StagedRow> rows = List.of(
                row("Cash withdrawal. Fee applies."),
                row("Cash withdrawal. Fee applies."),
                row("Cash withdrawal. Fee applies."),
                row("Cash withdrawal. Fee applies."),
                row("Cash withdrawal. Fee applies."));

        var finding = validator.check(rows);

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }

    // ------------------------------------------------------------- where a flagged row is shown

    private static final String FOOTER = " Please pay your card bill from any account by registering for "
            + "auto-debit at any branch. Visit bank.example.in to download the form. Registered office "
            + "reference number: EXAMPLE0000EXAMPLE.";

    @SuppressWarnings("unchecked")
    private static List<java.util.Map<String, Object>> outliersOf(com.finora.dto.ImportDto.VerificationFinding finding) {
        return (List<java.util.Map<String, Object>>) finding.details().get("outliers");
    }

    @Test
    void anOutlierIsShownWhereItWasCheckedWhenTheCallerShowsTheRowsAsGiven() {
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        rows.add(2, row("UPI-CORNER SHOP TEN REF6" + FOOTER));

        assertThat(outliersOf(validator.check(rows))).singleElement().satisfies(outlier -> {
            assertThat(outlier.get("rowIndex")).isEqualTo(2);
            assertThat(outlier.get("displayIndex")).isEqualTo(2);
        });
    }

    @Test
    void anOutlierSaysWhereItsRowIsShownWhenTheCallerShowsADifferentOrder() {
        // Six rows checked in one order and shown reversed, the way a newest-first statement is.
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        rows.add(1, row("UPI-CORNER SHOP TEN REF6" + FOOTER));
        int[] shownReversed = {5, 4, 3, 2, 1, 0};

        assertThat(outliersOf(validator.check(rows, shownReversed))).singleElement().satisfies(outlier -> {
            assertThat(outlier.get("rowIndex")).as("its place in the list that was checked, unchanged").isEqualTo(1);
            assertThat(outlier.get("displayIndex")).as("its place in the list that is shown").isEqualTo(4);
        });
    }

    @Test
    void severalOutliersAreListedDownTheListThatIsShown() {
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        rows.add(0, row("UPI-CORNER SHOP TEN REF6" + FOOTER));
        rows.add(row("UPI-CORNER SHOP ELEVEN R7" + FOOTER));
        int[] shownReversed = {6, 5, 4, 3, 2, 1, 0};

        assertThat(outliersOf(validator.check(rows, shownReversed)))
                .extracting(outlier -> outlier.get("displayIndex"))
                .containsExactly(0, 6);
    }

    @Test
    void rowsThatAreEqualAsValuesAreStillToldApartByPosition() {
        // Two rows with the same date, amount and footer-carrying description are equal records. The
        // mapping is by position, so each still gets its own place.
        List<StagedRow> rows = new ArrayList<>(ordinaryStatement());
        StagedRow twin = row("UPI-CORNER SHOP TEN REF6" + FOOTER);
        rows.add(1, twin);
        rows.add(4, twin);
        int[] shown = {0, 5, 2, 3, 1, 4, 6};

        assertThat(outliersOf(validator.check(rows, shown)))
                .extracting(outlier -> outlier.get("rowIndex"), outlier -> outlier.get("displayIndex"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple(4, 1), org.assertj.core.groups.Tuple.tuple(1, 5));
    }

    @Test
    void aMappingThatIsNotARearrangementOfTheRowsIsRefusedEvenWhenNothingIsFlagged() {
        List<StagedRow> rows = ordinaryStatement();

        assertThatThrownBy(() -> validator.check(rows, new int[] {0, 1, 2, 3}))
                .as("one position short").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.check(rows, new int[] {0, 1, 2, 3, 3}))
                .as("two rows sent to one position").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.check(rows, new int[] {0, 1, 2, 3, 5}))
                .as("a position past the end").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.check(rows, new int[] {0, 1, 2, 3, -1}))
                .as("a negative position").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.check(null, new int[] {0}))
                .as("positions for rows that were not given").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noRowsAndNoMappingIsStillNotApplicable() {
        assertThat(validator.check(null, null).outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(validator.check(List.of(), new int[0]).outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
