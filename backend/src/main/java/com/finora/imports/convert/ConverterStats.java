package com.finora.imports.convert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The converter's only database write: one counter per day, outcome, bank and row band (see
 * V301__converter_daily_stats.sql). Never anything about the visitor or the statement's content.
 *
 * <p>A failure to count never fails the conversion: the visitor's file matters more than our tally.
 */
@Component
public class ConverterStats {

    private static final Logger log = LoggerFactory.getLogger(ConverterStats.class);
    static final String NO_BANK = "-";

    private final JdbcTemplate jdbc;

    public ConverterStats(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void count(String outcome, String bank, int rows) {
        try {
            jdbc.update("""
                    INSERT INTO converter_daily_stats (day, outcome, bank, row_band, conversions)
                    VALUES ((now() AT TIME ZONE 'Asia/Kolkata')::date, ?, ?, ?, 1)
                    ON CONFLICT (day, outcome, bank, row_band)
                    DO UPDATE SET conversions = converter_daily_stats.conversions + 1
                    """, cap(outcome), cap(bank == null || bank.isBlank() ? NO_BANK : bank), rowBand(rows));
        } catch (RuntimeException e) {
            log.warn("Statement converter: could not count an outcome ({})", e.getClass().getSimpleName());
        }
    }

    static String rowBand(int rows) {
        if (rows <= 0) return "0";
        if (rows <= 50) return "1-50";
        if (rows <= 200) return "51-200";
        return "201+";
    }

    private static String cap(String value) {
        return value.length() > 40 ? value.substring(0, 40) : value;
    }
}
