import type { PagedResponse, TransactionFilters } from '../api/endpoints';
import type { Transaction } from '../types';
import {
  FILTER_TOTALS_MAX_ROWS, FILTER_TOTALS_PAGE_SIZE, addUpLedgerRows, fetchLedgerFilterTotals, isNarrowedLedger,
} from './ledgerFilterTotals';
import { DEFAULT_LEDGER_FILTERS } from './ledgerDefaults';

const row = (over: Partial<Transaction> = {}) =>
  ({ id: 't', type: 'EXPENSE', amount: 100, reconciliationStatus: 'OK', ...over }) as Transaction;
const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => row({ id: `t${i}` }));

/** Answers like the server: at most a page of rows, and after a named row only what follows it. */
function serverOver(all: Transaction[]) {
  return jest.fn((f: TransactionFilters): Promise<PagedResponse<Transaction>> => {
    const start = f.after ? all.findIndex((t) => t.id === f.after) + 1 : 0;
    const rest = all.slice(start);
    return Promise.resolve({
      content: rest.slice(0, FILTER_TOTALS_PAGE_SIZE), page: 0, size: FILTER_TOTALS_PAGE_SIZE,
      totalElements: rest.length, totalPages: 1,
    });
  });
}

describe('isNarrowedLedger', () => {
  it('is false for the list as it opens', () => {
    expect(isNarrowedLedger(DEFAULT_LEDGER_FILTERS)).toBe(false);
  });

  it('is false when a filter is present but empty', () => {
    expect(isNarrowedLedger({ ...DEFAULT_LEDGER_FILTERS, keyword: undefined, categoryId: undefined })).toBe(false);
  });

  it.each<[string, TransactionFilters]>([
    ['category', { categoryId: 'c-1' }],
    ['account', { accountId: 'a-1' }],
    ['type', { type: 'EXPENSE' }],
    ['status', { status: 'TRANSFER' }],
    ['international', { international: true }],
    ['start date', { dateFrom: '2026-11-01' }],
    ['end date', { dateTo: '2026-11-30' }],
    ['search', { keyword: 'swiggy' }],
    ['payee', { payeeOf: 't-1' }],
  ])('is true for a %s', (_name, filter) => {
    expect(isNarrowedLedger({ ...DEFAULT_LEDGER_FILTERS, ...filter })).toBe(true);
  });
});

describe('addUpLedgerRows', () => {
  it('is zero for no rows', () => {
    expect(addUpLedgerRows([])).toEqual({ moneyIn: 0, moneyOut: 0, notCounted: 0 });
  });

  it('adds credits and debits separately', () => {
    expect(addUpLedgerRows([
      row({ type: 'INCOME', amount: 5000 }), row({ amount: 5000 }), row({ amount: 3400 }),
    ])).toEqual({ moneyIn: 5000, moneyOut: 8400, notCounted: 0 });
  });

  it('counts a debit the same whichever sign it arrives with', () => {
    expect(addUpLedgerRows([row({ amount: -1250 }), row({ amount: 1250 })]).moneyOut).toBe(2500);
  });

  it('adds paise without floating point drift', () => {
    expect(addUpLedgerRows([row({ amount: 0.1 }), row({ amount: 0.2 })]).moneyOut).toBe(0.3);
  });

  it.each(['DUPLICATE', 'SUPERSEDED'] as const)('leaves a %s row out and says so', (status) => {
    expect(addUpLedgerRows([row({ amount: 900, reconciliationStatus: status }), row({ amount: 100 })]))
      .toEqual({ moneyIn: 0, moneyOut: 100, notCounted: 1 });
  });

  // The rule the list's own day subtotals use, so the card and the days above each row agree.
  it.each(['TRANSFER', 'INVESTMENT_TRANSFER', 'REFUND', 'REVERSAL'] as const)('counts a %s row: the money moved', (status) => {
    expect(addUpLedgerRows([row({ amount: 900, reconciliationStatus: status })]).moneyOut).toBe(900);
  });
});

describe('fetchLedgerFilterTotals', () => {
  it.each([0, 1, 99, 100, 101, 200, 250])('adds up all %i rows', async (n) => {
    const result = await fetchLedgerFilterTotals({}, serverOver(rowsOf(n)));
    expect(result.moneyOut).toBe(n * 100);
    expect(result.totalElements).toBe(n);
    expect(result.complete).toBe(true);
  });

  it('makes one request when one page holds everything', async () => {
    const search = serverOver(rowsOf(FILTER_TOTALS_PAGE_SIZE));
    await fetchLedgerFilterTotals({}, search);
    expect(search).toHaveBeenCalledTimes(1);
  });

  it('keeps the filters on every request and continues from the last row held', async () => {
    const search = serverOver(rowsOf(150));
    await fetchLedgerFilterTotals({ categoryId: 'c-1', dateFrom: '2026-11-01', size: 20 }, search);
    expect(search).toHaveBeenCalledTimes(2);
    for (const [f] of search.mock.calls) {
      expect(f).toMatchObject({ categoryId: 'c-1', dateFrom: '2026-11-01', size: FILTER_TOTALS_PAGE_SIZE });
    }
    expect(search.mock.calls[1][0].after).toBe('t99');
  });

  it('counts a row the server repeats on two pages once', async () => {
    const search = jest.fn()
      .mockResolvedValueOnce({ content: [row({ id: 'a' }), row({ id: 'b' })], page: 0, size: 2, totalElements: 3, totalPages: 2 })
      .mockResolvedValueOnce({ content: [row({ id: 'b' }), row({ id: 'c' })], page: 0, size: 2, totalElements: 2, totalPages: 1 });
    const result = await fetchLedgerFilterTotals({}, search);
    expect(result.moneyOut).toBe(300);
  });

  it('stops at the limit, keeps the exact count, and says the rows are not all of them', async () => {
    const total = FILTER_TOTALS_MAX_ROWS + 250;
    const result = await fetchLedgerFilterTotals({}, serverOver(rowsOf(total)));
    expect(result.rowsRead).toBe(FILTER_TOTALS_MAX_ROWS);
    expect(result.totalElements).toBe(total);
    expect(result.complete).toBe(false);
  });

  it('is complete at exactly the limit', async () => {
    const result = await fetchLedgerFilterTotals({}, serverOver(rowsOf(FILTER_TOTALS_MAX_ROWS)));
    expect(result.complete).toBe(true);
  });

  it('stops, and reports incomplete, when the server does not continue from the named row', async () => {
    const content = rowsOf(100);
    const search = jest.fn(() => Promise.resolve({ content, page: 0, size: 100, totalElements: 300, totalPages: 3 }));
    const result = await fetchLedgerFilterTotals({}, search);
    expect(search).toHaveBeenCalledTimes(2);
    expect(result.rowsRead).toBe(100);
    expect(result.complete).toBe(false);
  });

  it('fails as a whole when a later request fails, rather than returning a short sum', async () => {
    const search = jest.fn()
      .mockResolvedValueOnce({ content: rowsOf(100), page: 0, size: 100, totalElements: 150, totalPages: 2 })
      .mockRejectedValueOnce(new Error('down'));
    await expect(fetchLedgerFilterTotals({}, search)).rejects.toThrow('down');
  });
});
