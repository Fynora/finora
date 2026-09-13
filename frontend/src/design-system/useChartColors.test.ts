import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { useChartColors } from './useChartColors';
import { ThemeProvider, useTheme } from '../context/ThemeContext';
import { setAccessToken } from '../api/client';

// ThemeProvider's server-sync effect only fires when a token is present (see
// ThemeContext.test.tsx) -- every test here runs with no token, so this only guards against an
// unexpected call, matching that file's own mock.
vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  return createElement(ThemeProvider, null, children);
}

describe('useChartColors', () => {
  beforeEach(() => {
    localStorage.clear();
    setAccessToken(null);
    document.documentElement.classList.remove('dark');
  });

  it('returns the light-mode palette by default', () => {
    const { result } = renderHook(() => useChartColors(), { wrapper });
    expect(result.current.success).toBe('#16a34a');
    expect(result.current.danger).toBe('#dc2626');
    expect(result.current.blue).toBe('#2563eb');
    expect(result.current.series).toHaveLength(6);
  });

  it('switches to the dark-mode palette when the theme toggles, without remounting', () => {
    const { result } = renderHook(
      () => {
        const colors = useChartColors();
        const { setTheme } = useTheme();
        return { colors, setTheme };
      },
      { wrapper }
    );

    const lightSeries = result.current.colors.series;
    expect(result.current.colors.success).toBe('#16a34a');

    act(() => {
      result.current.setTheme('dark');
    });

    expect(result.current.colors.success).toBe('#22c55e');
    expect(result.current.colors.danger).toBe('#f87171');
    expect(result.current.colors.series).not.toEqual(lightSeries);
    expect(result.current.colors.series).toHaveLength(6);
  });

  it('gives every series entry a distinct color', () => {
    const { result } = renderHook(() => useChartColors(), { wrapper });
    expect(new Set(result.current.series).size).toBe(result.current.series.length);
  });
});
