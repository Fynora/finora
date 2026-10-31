package com.finora.imports.convert;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The free statement converter's request and response. */
public final class ConvertDto {

    private ConvertDto() {}

    /**
     * One conversion. The whole request is one JSON body, read into memory with a hard cap: the PDF
     * as base64 and, for a locked statement, its password beside it -- the same place
     * {@code /import/pdf/stage} carries a password, never a URL or a header. No multipart, so the
     * file is never written to a temporary file on disk.
     *
     * @param file           the PDF, base64
     * @param password       the PDF's open password, or null; used once and never kept
     * @param turnstileToken Cloudflare Turnstile's token from the page
     * @param consent        the visitor ticked the consent box; nothing is read without it
     */
    public record Request(String file, String password, String turnstileToken, Boolean consent) {
        @Override
        public String toString() {
            // Never print the file or the password, whoever logs this record.
            return "ConvertDto.Request[file=" + (file == null ? "null" : file.length() + " chars")
                    + ", password=" + (password == null ? "none" : "given") + ", consent=" + consent + "]";
        }
    }

    /** Whether the page should offer the converter at all, and the limits it should state. */
    public record Availability(boolean available, long maxBytes, int maxPages) {}

    /**
     * A converted statement: one section per account the statement holds (most hold one; a
     * composite statement holds several), each confirmed against the statement's own figures.
     *
     * @param pageCount the PDF's pages
     */
    public record Result(int pageCount, List<Section> sections) {}

    /**
     * @param bankName           the bank as detected, or null when not recognised
     * @param accountType        SAVINGS, CREDIT_CARD... as detected
     * @param accountNumberMasked as printed on the statement, already masked by the parser, or null
     * @param checks             the checks that confirmed this section's amounts
     */
    public record Section(String bankName, String accountType, String accountNumberMasked,
                          LocalDate periodStart, LocalDate periodEnd,
                          BigDecimal openingBalance, BigDecimal closingBalance,
                          List<Row> rows, List<String> checks) {}

    /**
     * One transaction, in the statement's own order. Exactly one of {@code debit} and {@code credit}
     * is set: money out of the account (or spent on a card) is a debit.
     *
     * @param balance   the running balance printed on the row, or null
     * @param reference the cheque or reference number printed on the row, or null
     */
    public record Row(LocalDate date, String description, BigDecimal debit, BigDecimal credit,
                      BigDecimal balance, String reference) {}
}
