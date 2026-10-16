package com.finora.imports.pdf.trace;

import com.finora.imports.pdf.PositionedText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The version in a trace file's first line is read as an integer. The pattern that finds it accepts
 * any run of digits, so a version too long for an int used to escape as an uncaught
 * NumberFormatException with no hint of which file or which line. It is now reported as what it is, a
 * malformed header, and a well-formed version is read exactly as before.
 */
class TraceFormatVersionTest {

    private static final String RUN = "0\t10.0\t20.0\t30.0\tXxxx";

    @Test
    void aVersionTooLongForAnIntIsAMalformedHeader_notAnUncaughtNumberFormatException() {
        String content = "# finora-pdf-trace v99999999999999999999\n" + RUN + "\n";

        assertThatThrownBy(() -> TraceFormat.parse(content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed trace header")
                .hasMessageContaining("format version");
    }

    @Test
    void theLargestVersionAnIntCanHoldIsStillRead() {
        String content = "# finora-pdf-trace v" + Integer.MAX_VALUE + "\n" + RUN + "\n";

        List<PositionedText> runs = TraceFormat.parse(content);

        assertThat(runs).extracting(PositionedText::text).containsExactly("Xxxx");
    }

    @Test
    void aVersionBelowFourIsReadTheOldWay_andFourOrMoreTheNewWay() {
        // v4 decodes the visible line-feed symbol; older files keep it as written.
        String symbol = String.valueOf(TraceFormat.LF_SYMBOL);
        String old = "# finora-pdf-trace v3\n0\t10.0\t20.0\t30.0\tAa" + symbol + "Bb\n";
        String current = "# finora-pdf-trace v4\n0\t10.0\t20.0\t30.0\tAa" + symbol + "Bb\n";

        assertThat(TraceFormat.parse(old).get(0).text()).as("v3 keeps the symbol").contains(symbol);
        assertThat(TraceFormat.parse(current).get(0).text()).as("v4 decodes it to a line feed").contains("\n");
    }
}
