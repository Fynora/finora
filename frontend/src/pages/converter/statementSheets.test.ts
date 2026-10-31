import { describe, expect, it } from 'vitest';
import { csvOf, fileBaseName, indianDate, rowCount, sectionLabel, xlsxOf, type ConvertedSection, type ConvertedStatement } from './statementSheets';

// Synthetic values only.
const savings: ConvertedSection = {
  bankName: 'Example Bank',
  accountType: 'SAVINGS',
  accountNumberMasked: 'XX1234',
  periodStart: '2026-09-01',
  periodEnd: '2026-09-30',
  openingBalance: 1000,
  closingBalance: 1500,
  checks: ['BALANCE_CHAIN'],
  rows: [
    { date: '2026-09-03', description: 'UPI/GENERIC PAYEE', debit: 250.5, credit: null, balance: 749.5, reference: '100000000001' },
    { date: '2026-09-10', description: '=SUM(A1:A9)', debit: null, credit: 750.5, balance: 1500, reference: null },
  ],
};
const card: ConvertedSection = {
  ...savings,
  bankName: null,
  accountType: 'CREDIT_CARD',
  accountNumberMasked: null,
  periodStart: '2026-08-15',
  periodEnd: '2026-09-14',
  rows: [{ date: '2026-08-20', description: 'GENERIC STORE', debit: 99, credit: null, balance: null, reference: null }],
};

describe('csvOf', () => {
  it('is one table with a header, dates as yyyy-mm-dd and amounts as numbers (every cell quoted by toCsv)', () => {
    const csv = csvOf({ pageCount: 2, sections: [savings] });
    const lines = csv.replace(/^\uFEFF/, '').split('\n');
    expect(csv.startsWith('\uFEFF')).toBe(true);
    expect(lines[0]).toBe('"Date","Description","Debit","Credit","Balance","Reference"');
    expect(lines[1]).toBe('"2026-09-03","UPI/GENERIC PAYEE","250.5","","749.5","100000000001"');
  });

  it('guards a description that starts like a formula', () => {
    const lines = csvOf({ pageCount: 2, sections: [savings] }).split('\n');
    expect(lines[2]).toContain("'=SUM(A1:A9)");
  });

  it('adds an Account column only when the statement holds more than one account', () => {
    const lines = csvOf({ pageCount: 3, sections: [savings, card] }).replace(/^\uFEFF/, '').split('\n');
    expect(lines[0]).toBe('"Account","Date","Description","Debit","Credit","Balance","Reference"');
    expect(lines[1].startsWith('"Example Bank savings XX1234",')).toBe(true);
    expect(lines[3].startsWith('"credit card",')).toBe(true);
  });
});

describe('xlsxOf', () => {
  it('makes a workbook with the transactions and a sheet saying what was checked', () => {
    const bytes = xlsxOf({ pageCount: 2, sections: [savings] }, 'CHECKED NOTE');
    const text = new TextDecoder().decode(bytes);
    expect(text).toContain('<sheet name="Transactions"');
    expect(text).toContain('<sheet name="About this file"');
    expect(text).toContain('CHECKED NOTE');
    expect(text).toContain('01-09-2026 to 30-09-2026');
  });

  it('gives each account its own sheet when there are several', () => {
    const text = new TextDecoder().decode(xlsxOf({ pageCount: 3, sections: [savings, card] }, 'note'));
    expect(text).toContain('<sheet name="Example Bank savings XX1234"');
    expect(text).toContain('<sheet name="credit card"');
  });
});

describe('names and counts', () => {
  const statement: ConvertedStatement = { pageCount: 3, sections: [savings, card] };

  it('labels a section by bank, type and masked number, or by position', () => {
    expect(sectionLabel(savings, 0)).toBe('Example Bank savings XX1234');
    expect(sectionLabel({ ...card, accountType: null }, 1)).toBe('Account 2');
  });

  it('names the file after the whole period', () => {
    expect(fileBaseName(statement)).toBe('statement-2026-08-15-to-2026-09-30');
    expect(fileBaseName({ pageCount: 1, sections: [{ ...savings, periodStart: null, periodEnd: null }] })).toBe('statement');
  });

  it('counts every row and prints Indian dates', () => {
    expect(rowCount(statement)).toBe(3);
    expect(indianDate('2026-09-03')).toBe('03-09-2026');
  });
});
