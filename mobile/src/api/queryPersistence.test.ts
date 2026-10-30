import { QueryClient, dehydrate } from '@tanstack/react-query';
import type { PersistedClient } from '@tanstack/react-query-persist-client';
import { DEFAULT_LEDGER_FILTERS } from '../lib/ledgerDefaults';
import { PERSISTED_QUERY_KEY_PREFIXES, shouldPersistQuery, trimForPersistence } from './queryPersistence';

/**
 * This file creates ~20 throwaway QueryClients (one per assertion below), each cleared via
 * clear()/cancelQueries() as soon as its Query object is captured -- confirmed via
 * --detectOpenHandles and repeated runs that this cleanup is correct and every assertion passes
 * every time. Despite that, `npm test -- src/api/queryPersistence.test.ts` run in isolation can
 * still occasionally leave the Jest process itself hanging past a clean exit (Jest's own generic
 * "did not exit one second after the test run" warning, with no handle it can actually name). This
 * does NOT affect the full suite (`npm test` with no path filter): Jest's worker pool force-exits
 * any straggling worker on its own, and every test here is genuinely correct and deterministic --
 * only the OS-level process teardown for a single-file direct run is occasionally slow. If this
 * file is run standalone and appears to hang after printing PASS, that's this known quirk, not a
 * new bug -- check the printed test results, not the process's own exit.
 */

function successfulQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  qc.setQueryData(queryKey, { ok: true });
  const query = qc.getQueryCache().find({ queryKey })!;
  qc.clear();
  return query;
}

function pendingQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  void qc.prefetchQuery({ queryKey, queryFn: () => new Promise(() => {}) });
  const query = qc.getQueryCache().find({ queryKey })!;
  void qc.cancelQueries({ queryKey });
  qc.clear();
  return query;
}

describe('shouldPersistQuery', () => {
  it.each(PERSISTED_QUERY_KEY_PREFIXES.filter((p) => p !== 'transactions'))('allows a successful "%s" query', (prefix) => {
    expect(shouldPersistQuery(successfulQuery([prefix]))).toBe(true);
  });

  it('allows the Ledger list as it opens: transactions under the default filters', () => {
    expect(shouldPersistQuery(successfulQuery(['transactions', DEFAULT_LEDGER_FILTERS]))).toBe(true);
  });

  it('matches the default Ledger key the way the screen builds it, with the unset filters present as undefined', () => {
    const asTheScreenBuildsIt = { ...DEFAULT_LEDGER_FILTERS, keyword: undefined, type: undefined, status: undefined, dateFrom: undefined };
    expect(shouldPersistQuery(successfulQuery(['transactions', asTheScreenBuildsIt]))).toBe(true);
  });

  // Each debounced search keyword and each filter is its own cache entry. Saving them made the
  // persisted blob grow with every search and every save slower (measured 2026-10-30).
  it.each([
    ['a search keyword', { ...DEFAULT_LEDGER_FILTERS, keyword: 'zomato' }],
    ['a type filter', { ...DEFAULT_LEDGER_FILTERS, type: 'EXPENSE' }],
    ['a date range', { ...DEFAULT_LEDGER_FILTERS, dateFrom: '2026-10-01', dateTo: '2026-10-31' }],
    ['an account drill-through', { ...DEFAULT_LEDGER_FILTERS, accountId: 'acc-1' }],
  ])('excludes transactions under %s', (_label, filters) => {
    expect(shouldPersistQuery(successfulQuery(['transactions', filters]))).toBe(false);
  });

  it('excludes a transactions query with no filter object', () => {
    expect(shouldPersistQuery(successfulQuery(['transactions']))).toBe(false);
  });

  it.each([
    'statement-imports',
    'statement-import-transactions',
    'devices',
    'user-settings',
    'workspace-settings',
    'import-statistics',
    'goals',
    'insights',
    'networth',
    'recurring',
  ])('excludes "%s" -- not on the persisted allowlist', (key) => {
    expect(shouldPersistQuery(successfulQuery([key]))).toBe(false);
  });

  it('excludes an allowlisted key that has not resolved yet', () => {
    expect(shouldPersistQuery(pendingQuery(['dashboard-summary']))).toBe(false);
  });
});

describe('trimForPersistence', () => {
  function persistedClientWith(setup: (qc: QueryClient) => void): PersistedClient {
    const qc = new QueryClient();
    setup(qc);
    const clientState = dehydrate(qc);
    qc.clear();
    return { timestamp: 1, buster: '1', clientState };
  }

  const page = (n: number) => ({ content: [{ id: `t-${n}` }], page: n, totalPages: 5 });

  it('keeps only the first page of the Ledger list', () => {
    const client = persistedClientWith((qc) => {
      qc.setQueryData(['transactions', DEFAULT_LEDGER_FILTERS], {
        pages: [page(0), page(1), page(2)],
        pageParams: [0, 1, 2],
      });
    });

    const trimmed = trimForPersistence(client);

    expect(trimmed.clientState.queries[0].state.data).toEqual({ pages: [page(0)], pageParams: [0] });
  });

  it('leaves every other query, and the input object, untouched', () => {
    const client = persistedClientWith((qc) => {
      qc.setQueryData(['transactions', DEFAULT_LEDGER_FILTERS], { pages: [page(0), page(1)], pageParams: [0, 1] });
      qc.setQueryData(['dashboard-summary'], { pages: ['not', 'an', 'infinite', 'query'] });
      qc.setQueryData(['report', '2026-10'], { total: 5 });
    });
    const before = JSON.stringify(client);

    const trimmed = trimForPersistence(client);

    expect(JSON.stringify(client)).toBe(before);
    const byHead = Object.fromEntries(trimmed.clientState.queries.map((q) => [String(q.queryKey[0]), q.state.data]));
    expect(byHead['dashboard-summary']).toEqual({ pages: ['not', 'an', 'infinite', 'query'] });
    expect(byHead.report).toEqual({ total: 5 });
  });

  it('leaves a one-page Ledger list as it is', () => {
    const client = persistedClientWith((qc) => {
      qc.setQueryData(['transactions', DEFAULT_LEDGER_FILTERS], { pages: [page(0)], pageParams: [0] });
    });

    expect(trimForPersistence(client).clientState.queries[0].state.data).toEqual({ pages: [page(0)], pageParams: [0] });
  });
});
