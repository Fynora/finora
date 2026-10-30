import type { TransactionFilters } from '../api/endpoints';

export const LEDGER_PAGE_SIZE = 20;

/**
 * The exact filters Ledger's useInfiniteQuery sends on a fresh mount (no search keyword typed, no
 * type filter chosen). Shared so Dashboard's prefetch-on-focus hook (usePrefetchAdjacentScreens)
 * can warm ['transactions', DEFAULT_LEDGER_FILTERS] under this EXACT key -- a prefetch built from a
 * near-identical object is a cache miss with extra network calls, not a warm cache -- and so the
 * query persister can tell this one list apart from every search and filter variant.
 *
 * Kept out of LedgerScreen.tsx so the api layer (queryPersistence.ts) can read it without
 * importing a screen. LedgerScreen re-exports both names.
 */
export const DEFAULT_LEDGER_FILTERS: TransactionFilters = {
  size: LEDGER_PAGE_SIZE,
  sortField: 'date',
  sortDir: 'desc',
};
