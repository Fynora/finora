import { QueryClient, type Query } from '@tanstack/react-query';
import { setChangeWatchActive } from './changeSync';
import { shouldRefetchOnFocus } from './changeWatch';

/**
 * While the change stamp is being watched it is the authority for what changed, so returning to the
 * app must not ALSO refetch the queries the stamp covers -- that made a change made elsewhere cost
 * two rounds of reads (the focus refetch, then the stamp's invalidation). Everything the stamp does
 * not cover, and anything from an earlier day, still refetches on focus.
 */

const client = new QueryClient();
afterEach(() => {
  setChangeWatchActive(false);
  client.clear();
});

function queryFor(key: string[], updatedAt: number = Date.now()): Query {
  client.setQueryData(key, 'data', { updatedAt });
  return client.getQueryCache().find({ queryKey: key })! as unknown as Query;
}

/** A query that exists but has never loaded anything. */
function unloadedQuery(key: string): Query {
  return client.getQueryCache().build(client, { queryKey: [key] }) as unknown as Query;
}

const YESTERDAY = () => Date.now() - 26 * 60 * 60 * 1000;

describe('shouldRefetchOnFocus', () => {
  it('leaves the queries the stamp covers to the stamp while it is being watched', () => {
    setChangeWatchActive(true);
    for (const key of ['dashboard-summary', 'transactions', 'accounts', 'budgets', 'user-settings', 'categories']) {
      expect(shouldRefetchOnFocus(queryFor([key]))).toBe(false);
    }
  });

  it('still refetches everything the stamp does not cover', () => {
    setChangeWatchActive(true);
    for (const key of ['support-tickets-mine', 'gmail-status', 'entitlements', 'referrals-mine', 'devices']) {
      expect(shouldRefetchOnFocus(queryFor([key]))).toBe(true);
    }
  });

  it('refetches everything when nothing is watching the stamp (signed out, onboarding, tabs not showing)', () => {
    setChangeWatchActive(false);
    expect(shouldRefetchOnFocus(queryFor(['dashboard-summary']))).toBe(true);
  });

  it('refetches a covered query fetched on an earlier day: a stamp cannot see the day roll over', () => {
    // "This month" totals and Today/Yesterday labels change at midnight with no data change at all.
    setChangeWatchActive(true);
    expect(shouldRefetchOnFocus(queryFor(['dashboard-summary'], YESTERDAY()))).toBe(true);
  });

  it('retries a covered query whose last fetch failed: the stamp proves nothing about a failure', () => {
    setChangeWatchActive(true);
    const query = unloadedQuery('accounts');
    query.setState({ status: 'error', error: new Error('boom'), dataUpdatedAt: Date.now() });
    expect(shouldRefetchOnFocus(query)).toBe(true);
  });

  it('does not treat a never-loaded query as stale by day', () => {
    setChangeWatchActive(true);
    expect(shouldRefetchOnFocus(unloadedQuery('accounts'))).toBe(false);
  });
});
