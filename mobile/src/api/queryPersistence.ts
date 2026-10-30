import { defaultShouldDehydrateQuery, hashKey, type Query } from '@tanstack/react-query';
import type { PersistedClient } from '@tanstack/react-query-persist-client';
import { DEFAULT_LEDGER_FILTERS } from '../lib/ledgerDefaults';

/**
 * Locked-down allowlist for what's allowed to survive an app restart in plaintext AsyncStorage --
 * see startQueryPersistence's own doc comment in queryClient.ts. Built from every real queryKey in
 * this app (grep -rn "queryKey:" src) and limited to the brief's named domains: dashboard,
 * accounts, transactions, budgets, reports, categories.
 *
 * Deliberately excluded by simply not appearing here, rather than a denylist that would need
 * remembering: import workflow state ('statement-imports', 'statement-import-transactions'),
 * pending uploads, the auth/session domain ('devices' -- tokens themselves already live in
 * SecureStore via safeStorage.ts and are never duplicated here), and everything else this app
 * doesn't need instantly on cold start ('goals', 'insights', 'networth', 'recurring',
 * 'user-settings', 'workspace-settings', 'import-statistics'). An allowlist, not a denylist, on
 * purpose: the failure mode of forgetting to add a new sensitive key to a denylist is a leak; the
 * failure mode here is a new screen's data not warm-starting until someone adds it -- a slower
 * cold start, not a disclosure.
 */
export const PERSISTED_QUERY_KEY_PREFIXES = [
  'dashboard-summary',
  'accounts',
  'transactions',
  'recent-transactions',
  'budgets',
  'report',
  'report-months',
  'categories',
] as const;

/** The cache hash of the Ledger list as it opens: the one transactions query that is persisted. */
const DEFAULT_LEDGER_QUERY_HASH = hashKey(['transactions', DEFAULT_LEDGER_FILTERS]);

/**
 * Passed as dehydrateOptions.shouldDehydrateQuery to persistQueryClient (queryClient.ts). Starts
 * from the library's own default -- only ever persist a query that actually SUCCEEDED, since an
 * in-flight or errored fetch has nothing worth restoring -- and narrows further to the allowlist
 * above.
 */
export function shouldPersistQuery(query: Query): boolean {
  if (!defaultShouldDehydrateQuery(query)) return false;
  const [prefix] = query.queryKey;
  if (typeof prefix !== 'string' || !(PERSISTED_QUERY_KEY_PREFIXES as readonly string[]).includes(prefix)) return false;
  // Of the transactions queries, only the Ledger list as it opens. Every debounced search keyword
  // and every filter is its own cache entry; saving them all made the blob grow with each search
  // and each save slower (measured 2026-10-30). A cold start only needs the list a user lands on.
  // Compared by query hash, which ignores undefined fields the same way the cache itself does.
  if (prefix === 'transactions') return query.queryHash === DEFAULT_LEDGER_QUERY_HASH;
  return true;
}

/**
 * Keeps only the first page of the persisted Ledger list. Every page the user scrolled through
 * used to be written on every save -- about 14 KB a page, rewritten within a second of each new
 * page arriving -- for a list that only needs its first screen on a cold start. More pages load
 * as the user scrolls, as they do on a first visit. Returns a new object; the input is untouched.
 */
export function trimForPersistence(client: PersistedClient): PersistedClient {
  return {
    ...client,
    clientState: {
      ...client.clientState,
      queries: client.clientState.queries.map((query) => {
        if (query.queryHash !== DEFAULT_LEDGER_QUERY_HASH) return query;
        const data = query.state.data as { pages?: unknown[]; pageParams?: unknown[] } | undefined;
        if (!data?.pages || !data.pageParams || data.pages.length <= 1) return query;
        return {
          ...query,
          state: { ...query.state, data: { ...data, pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) } },
        };
      }),
    },
  };
}
