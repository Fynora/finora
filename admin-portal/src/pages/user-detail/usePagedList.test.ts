import { describe, it, expect } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { usePagedList } from './usePagedList';

const items = (n: number) => Array.from({ length: n }, (_, i) => i);

describe('usePagedList', () => {
  it('pages a list 25 at a time', () => {
    const { result } = renderHook(() => usePagedList(items(60)));
    expect(result.current.pageItems).toEqual(items(25));
    expect(result.current.totalPages).toBe(3);

    act(() => result.current.setPage(2));
    expect(result.current.pageItems).toEqual([50, 51, 52, 53, 54, 55, 56, 57, 58, 59]);
  });

  it('falls back to the new last page when the list shrinks under it, never an empty page', () => {
    const { result, rerender } = renderHook(({ list }) => usePagedList(list), { initialProps: { list: items(60) } });
    act(() => result.current.setPage(2));

    rerender({ list: items(30) });

    expect(result.current.page).toBe(1);
    expect(result.current.pageItems).toEqual([25, 26, 27, 28, 29]);
  });

  it('treats a list not loaded yet as one empty page', () => {
    const { result } = renderHook(() => usePagedList<number>(undefined));
    expect(result.current.pageItems).toEqual([]);
    expect(result.current.totalPages).toBe(1);
    expect(result.current.totalElements).toBe(0);
  });
});
