import { useMemo } from 'react';
import { useTheme } from '../context/ThemeContext';

/**
 * Decorative, multi-series Chart.js colors that react to the app's light/dark theme. Values
 * mirror the --color-accent-* family and --color-success / --color-danger tokens in index.css --
 * duplicated here (rather than read via getComputedStyle) because Chart.js needs a literal color per
 * render and jsdom doesn't resolve custom properties from a real stylesheet, same reasoning
 * COLOR_HEX (categoryIcons.ts) already documents for its own mirror of the backend palette.
 *
 * Do NOT use this for a category's actual color (COLOR_HEX) -- a user's chosen category color
 * must stay constant across theme toggles, unlike these purely decorative series colors.
 */
const PALETTE = {
  light: {
    success: '#16a34a', danger: '#dc2626',
    blue: '#2563eb', green: '#16a34a', red: '#dc2626', purple: '#9333ea', orange: '#ea580c', teal: '#0d9488',
  },
  dark: {
    success: '#22c55e', danger: '#f87171',
    blue: '#60a5fa', green: '#22c55e', red: '#f87171', purple: '#c084fc', orange: '#fb923c', teal: '#2dd4bf',
  },
} as const;

export function useChartColors() {
  const { resolvedTheme } = useTheme();
  return useMemo(() => {
    const p = PALETTE[resolvedTheme];
    // Fixed-order multi-series palette for charts that color N items by index (e.g. holdings),
    // not by a real per-item identity.
    return { ...p, series: [p.blue, p.green, p.red, p.purple, p.orange, p.teal] };
  }, [resolvedTheme]);
}
