import { toCsv } from '../../lib/download';
import { xlsx, type Cell, type Sheet } from '../../lib/xlsx';

/** One transaction as the converter API returns it (ConvertDto.Row). Exactly one of debit/credit. */
export interface ConvertedRow {
  date: string; // yyyy-mm-dd
  description: string | null;
  debit: number | null;
  credit: number | null;
  balance: number | null;
  reference: string | null;
}

/** One account section of a converted statement (ConvertDto.Section). */
export interface ConvertedSection {
  bankName: string | null;
  accountType: string | null;
  accountNumberMasked: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  openingBalance: number | null;
  closingBalance: number | null;
  rows: ConvertedRow[];
  checks: string[];
}

export interface ConvertedStatement {
  pageCount: number;
  sections: ConvertedSection[];
}

export const COLUMNS = ['Date', 'Description', 'Debit', 'Credit', 'Balance', 'Reference'] as const;

/** A name for a section, for an Account column or a sheet: "HDFC Bank SAVINGS XX1234", or "Account 2". */
export function sectionLabel(section: ConvertedSection, index: number): string {
  const parts = [section.bankName, section.accountType?.replace(/_/g, ' ').toLowerCase(), section.accountNumberMasked]
    .filter((part): part is string => Boolean(part && part.trim()));
  return parts.length > 0 ? parts.join(' ') : `Account ${index + 1}`;
}

/** dd-mm-yyyy, the way Indian statements print dates, from yyyy-mm-dd. */
export function indianDate(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}-${m}-${y}`;
}

/**
 * The CSV: one table, every section's rows in the statement's order. A statement with more than one
 * account gets an Account column first, so no row's account is ambiguous. Dates are yyyy-mm-dd, the
 * one written form no spreadsheet reads the wrong way round. Amounts are plain numbers.
 */
export function csvOf(statement: ConvertedStatement): string {
  const multi = statement.sections.length > 1;
  const header = multi ? ['Account', ...COLUMNS] : [...COLUMNS];
  const rows: (string | number)[][] = [header];
  statement.sections.forEach((section, i) => {
    for (const row of section.rows) {
      const cells: (string | number)[] = [
        row.date,
        row.description ?? '',
        row.debit ?? '',
        row.credit ?? '',
        row.balance ?? '',
        row.reference ?? '',
      ];
      rows.push(multi ? [sectionLabel(section, i), ...cells] : cells);
    }
  });
  return toCsv(rows);
}

/** The Excel workbook: one sheet per account, real dates and numbers, and a sheet saying what was checked. */
export function xlsxOf(statement: ConvertedStatement, checkedNote: string): Uint8Array<ArrayBuffer> {
  const sheets: Sheet[] = statement.sections.map((section, i) => ({
    name: statement.sections.length > 1 ? sectionLabel(section, i) : 'Transactions',
    rows: [
      [...COLUMNS],
      ...section.rows.map((row): Cell[] => [
        { date: row.date },
        row.description ?? '',
        row.debit,
        row.credit,
        row.balance,
        row.reference ?? '',
      ]),
    ],
  }));
  sheets.push({
    name: 'About this file',
    rows: [
      ['Made with Fynora’s free statement converter (app.fynora.net).'],
      [checkedNote],
      ...statement.sections.map((section, i): Cell[] => [
        `${sectionLabel(section, i)}: ${section.rows.length} transactions` +
          (section.periodStart && section.periodEnd
            ? `, ${indianDate(section.periodStart)} to ${indianDate(section.periodEnd)}`
            : ''),
      ]),
    ],
  });
  return xlsx(sheets);
}

/** "statement-2026-09-01-to-2026-09-30", or "statement" when no period was read. */
export function fileBaseName(statement: ConvertedStatement): string {
  const starts = statement.sections.map((s) => s.periodStart).filter((d): d is string => Boolean(d)).sort();
  const ends = statement.sections.map((s) => s.periodEnd).filter((d): d is string => Boolean(d)).sort();
  if (starts.length === 0 || ends.length === 0) return 'statement';
  return `statement-${starts[0]}-to-${ends[ends.length - 1]}`;
}

export function rowCount(statement: ConvertedStatement): number {
  return statement.sections.reduce((sum, section) => sum + section.rows.length, 0);
}
