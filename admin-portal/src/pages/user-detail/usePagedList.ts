import { useState } from 'react';

/** Rows per page in the user page's lists (merchants, relationships, rules). */
export const USER_LIST_PAGE_SIZE = 25;

/**
 * One page of a list the server returns whole. A user can have hundreds of merchants (378 seen in
 * production, 2026-10-23); rendering every row made the user page slow. The page is clamped, not
 * stored as-is, so a list that shrinks under it (a merge, a delete) shows its new last page rather
 * than an empty one.
 */
export function usePagedList<T>(items: T[] | undefined, pageSize = USER_LIST_PAGE_SIZE) {
  const [page, setPage] = useState(0);
  const all = items ?? [];
  const totalPages = Math.max(1, Math.ceil(all.length / pageSize));
  const current = Math.min(page, totalPages - 1);
  return {
    page: current,
    setPage,
    totalPages,
    totalElements: all.length,
    pageSize,
    pageItems: all.slice(current * pageSize, (current + 1) * pageSize),
  };
}
