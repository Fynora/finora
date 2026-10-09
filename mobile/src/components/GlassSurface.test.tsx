import { useEffect, type ReactNode } from 'react';
import { render, screen } from '@testing-library/react-native';
import { GlassSurface } from './GlassSurface';
import { Card } from './Card';
import { DashboardCard } from './dashboard/DashboardCard';
import { ThemeProvider, useThemeSetting } from '../theme';

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

it('caller style can set radius/padding but not the fill', () => {
  render(<ThemeProvider><GlassSurface testID="s" style={{ borderRadius: 4, padding: 3, backgroundColor: 'red' }} /></ThemeProvider>);
  expect(screen.getByTestId('s')).toHaveStyle({ borderRadius: 4, padding: 3, backgroundColor: 'rgba(255,255,255,0.72)' });
});

it('renders without a ThemeProvider, like every other screen test in this repo', () => {
  render(<GlassSurface testID="s" />);
  expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)' });
});

it('Card renders through GlassSurface and keeps its testID/children/1px-border contract', () => {
  render(<ThemeProvider><Card testID="c" style={{ marginTop: 5 }}>{null}</Card></ThemeProvider>);
  expect(screen.getByTestId('c')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)', borderWidth: 1, marginTop: 5 });
});

it('DashboardCard renders through GlassSurface and keeps its hairline border and padding', () => {
  render(<ThemeProvider><DashboardCard testID="d">{null}</DashboardCard></ThemeProvider>);
  expect(screen.getByTestId('d')).toHaveStyle({ backgroundColor: 'rgba(255,255,255,0.72)', padding: 24 });
});
