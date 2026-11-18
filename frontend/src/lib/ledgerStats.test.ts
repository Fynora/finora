import { describe, it, expect, vi } from 'vitest';
import { fetchLedgerStatsRows, STATS_MAX_ROWS, STATS_PAGE_SIZE } from './ledgerStats';
import type { TransactionFilters } from '../api/endpoints';
import type { Transaction } from '../types';

const rowsOf = (n: number) => Array.from({ length: n }, (_, i) => ({ id: `t${i}` }) as Transaction);

/** Answers like the server: at most a page of rows, and after a named row only what follows it. */
function serverOver(all: Transaction[]) {
  return vi.fn((f: TransactionFilters) => {
    const start = f.after ? all.findIndex((t) => t.id === f.after) + 1 : 0;
    const rest = all.slice(start);
    return Promise.resolve({ content: rest.slice(0, STATS_PAGE_SIZE), page: 0, size: STATS_PAGE_SIZE, totalElements: rest.length, totalPages: 1 });
  });
}

describe('fetchLedgerStatsRows', () => {
  it.each([0, 1, 99, 100, 101, 200, 250])('reads all %i rows', async (n) => {
    const result = await fetchLedgerStatsRows({}, serverOver(rowsOf(n)));
    expect(result.content).toHaveLength(n);
    expect(new Set(result.content.map((t) => t.id)).size).toBe(n);
    expect(result.totalElements).toBe(n);
    expect(result.complete).toBe(true);
  });

  it('makes one request when one page holds everything', async () => {
    const search = serverOver(rowsOf(100));
    await fetchLedgerStatsRows({}, search);
    expect(search).toHaveBeenCalledTimes(1);
  });

  it('keeps the filters on every request', async () => {
    const search = serverOver(rowsOf(150));
    await fetchLedgerStatsRows({ categoryId: 'cat-1', dateFrom: '2026-11-01' }, search);
    expect(search).toHaveBeenCalledTimes(2);
    for (const [f] of search.mock.calls) expect(f).toMatchObject({ categoryId: 'cat-1', dateFrom: '2026-11-01', size: STATS_PAGE_SIZE });
    expect(search.mock.calls[1][0].after).toBe('t99');
  });

  it('stops at the limit, keeps the exact count, and says the rows are not all of them', async () => {
    const total = STATS_MAX_ROWS + 250;
    const result = await fetchLedgerStatsRows({}, serverOver(rowsOf(total)));
    expect(result.content).toHaveLength(STATS_MAX_ROWS);
    expect(result.totalElements).toBe(total);
    expect(result.complete).toBe(false);
  });

  it('is complete at exactly the limit', async () => {
    const result = await fetchLedgerStatsRows({}, serverOver(rowsOf(STATS_MAX_ROWS)));
    expect(result.complete).toBe(true);
  });

  it('stops, and reports incomplete, when the server does not continue from the named row', async () => {
    const page = rowsOf(100);
    const search = vi.fn(() => Promise.resolve({ content: page, page: 0, size: 100, totalElements: 300, totalPages: 3 }));
    const result = await fetchLedgerStatsRows({}, search);
    expect(search).toHaveBeenCalledTimes(2);
    expect(result.content).toHaveLength(100);
    expect(result.complete).toBe(false);
  });

  it('fails as a whole when a later request fails, rather than returning a short sum', async () => {
    const search = vi.fn()
      .mockResolvedValueOnce({ content: rowsOf(100), page: 0, size: 100, totalElements: 150, totalPages: 2 })
      .mockRejectedValueOnce(new Error('down'));
    await expect(fetchLedgerStatsRows({}, search)).rejects.toThrow('down');
  });
});
