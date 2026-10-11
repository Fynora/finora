import { useEffect, type ReactNode } from 'react';
import { Platform, Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { GlassSurface, useGlassSurfaceStyle } from './GlassSurface';

import { Card } from './Card';
import { DashboardCard } from './dashboard/DashboardCard';
import { ThemeProvider, useThemeSetting } from '../theme';

// This file specifies the TINTED path (Android, rows, and every surface before Phase 2): jest
// defaults Platform.OS to 'ios', where panels now render native glass with the fill on an inner
// layer -- GlassSurface.native.test.tsx covers that. Pin Android here so the fill/edge assertions
// read the outer element.
const originalOS = Platform.OS;
beforeEach(() => { (Platform as { OS: string }).OS = 'android'; });
afterEach(() => { (Platform as { OS: string }).OS = originalOS; });

jest.mock('../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../lib/useReduceTransparency');
afterEach(() => useReduceTransparency.mockReturnValue(false));

// Same approach as AccountsCard.test.tsx: dark via the real public setter, not a mocked
// useColorScheme.
function ForceDarkTheme({ children }: { children: ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

it.each(['panel', 'row'] as const)('%s paints the translucent glass fill and glass edge (light)', (variant) => {
  render(<ThemeProvider><GlassSurface testID="s" variant={variant} /></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({
    backgroundColor: 'rgba(255,255,255,0.72)',
    borderColor: 'rgba(255,255,255,0.85)',
  });
});

it('paints the dark glass fill and edge when the theme resolves dark', () => {
  render(<ThemeProvider><ForceDarkTheme><GlassSurface testID="s" /></ForceDarkTheme></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({
    backgroundColor: 'rgba(38,42,51,0.72)',
    borderColor: 'rgba(255,255,255,0.14)',
  });
});

it.each([true, null])('falls back to solid card + border when Reduce Transparency is %s', (v) => {
  useReduceTransparency.mockReturnValue(v);
  render(<ThemeProvider><GlassSurface testID="s" /></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#ffffff', borderColor: '#E6EAF2' });
});

it('caller style keeps radius/padding and a deliberate tint or border colour wins over the glass pair', () => {
  // Dashboard's coverage/limited-history banners tint a Card with warningBg, and Import/Statement
  // History error cards give it a danger border. Those are the signal; glass must not paint over
  // them. (Opaque c.card/c.bg as a caller tint is what glassMigration.test.ts guards against.)
  render(<ThemeProvider><GlassSurface testID="s" style={{ borderRadius: 4, padding: 3, backgroundColor: '#fef3c7', borderColor: '#dc2626' }} /></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({ borderRadius: 4, padding: 3, backgroundColor: '#fef3c7', borderColor: '#dc2626' });
});

it('a caller that sets neither colour gets the glass pair', () => {
  render(<ThemeProvider><GlassSurface testID="s" style={{ borderRadius: 4 }} /></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({ borderRadius: 4, backgroundColor: 'rgba(255,255,255,0.72)', borderColor: 'rgba(255,255,255,0.85)' });
});

it('Card keeps a warning tint passed through style (dashboard banners)', () => {
  render(<ThemeProvider><Card testID="c" style={{ backgroundColor: '#fef3c7' }}>{null}</Card></ThemeProvider>);
  expect(screen.getByTestId('c')).toHaveStyle({ backgroundColor: '#fef3c7' });
});

it('Card keeps a danger border passed through style (import / statement history error cards)', () => {
  render(<ThemeProvider><Card testID="c" style={{ borderColor: '#dc2626' }}>{null}</Card></ThemeProvider>);
  expect(screen.getByTestId('c')).toHaveStyle({ borderColor: '#dc2626', backgroundColor: 'rgba(255,255,255,0.72)' });
});

it('renders without a ThemeProvider, like every other screen test in this repo', () => {
  render(<GlassSurface testID="s" />);
  expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
});

it('Card renders through GlassSurface and keeps its testID/children/1px-border contract', () => {
  render(<ThemeProvider><Card testID="c" style={{ marginTop: 5 }}>{null}</Card></ThemeProvider>);
  expect(screen.getByTestId('c')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)', borderWidth: 1, marginTop: 5 });
});

// Pressable rows (Ledger's transaction rows) can't be a GlassSurface View, so they read the same
// fill/edge pair through this hook -- one source of truth, including the solid fallback.
function HookProbe() {
  const s = useGlassSurfaceStyle();
  return <Text testID="probe">{JSON.stringify(s)}</Text>;
}

it('useGlassSurfaceStyle returns the exact fill/edge pair GlassSurface paints', () => {
  render(<ThemeProvider><HookProbe /></ThemeProvider>);
  expect(JSON.parse(screen.getByTestId('probe').props.children)).toEqual({
    backgroundColor: 'rgba(255,255,255,0.72)', borderColor: 'rgba(255,255,255,0.85)',
  });
});

it.each([true, null])('useGlassSurfaceStyle falls back to solid card + border when Reduce Transparency is %s', (v) => {
  useReduceTransparency.mockReturnValue(v);
  render(<ThemeProvider><HookProbe /></ThemeProvider>);
  expect(JSON.parse(screen.getByTestId('probe').props.children)).toEqual({ backgroundColor: '#ffffff', borderColor: '#E6EAF2' });
});

it('DashboardCard renders through GlassSurface and keeps its hairline border and padding', () => {
  render(<ThemeProvider><DashboardCard testID="d">{null}</DashboardCard></ThemeProvider>);
  // 20 (spacing.ml) since the card redesign of 2026-10-10; it was 24 (spacing.lg) before.
  expect(screen.getByTestId('d')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)', padding: 20 });
});
