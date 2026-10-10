import { useEffect } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { DashboardSectionHeader } from './DashboardSectionHeader';
import { DeltaChip } from './DeltaChip';
import { IconWell, NEUTRAL_WASH } from './IconWell';
import { ProgressBar } from './ProgressBar';
import { dark, light } from '../../theme/palette';
import { withAlpha } from '../../theme/glass';
import { ThemeProvider, useThemeSetting } from '../../theme';

// Pinned to "not known yet", which GlassSurface renders as the solid card: the same look Reduce
// Transparency gives. Left unmocked, the real hook starts at null and flips to false a tick after
// the first render in the file, so a surface's colours would depend on test order.
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

// The app has no ThemeProvider prop for forcing a theme, only the switch a person taps
// (useThemeSetting().setSetting); AccountsCard.test.tsx forces dark the same way. It does not leak
// into the next test: the choice is written to SecureStore, and src/test/setup.ts clears the
// mocked store before every test, so each ThemeProvider mounts on `system` (light in jest).
function ForceDarkTheme({ children }: { children: React.ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const lum = ([r, g, b]: number[]) => 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b);
const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
const hex = (h: string) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const over = (top: number[], a: number, bot: number[]) => top.map((v, i) => v * a + bot[i] * (1 - a));

// Bars and icon wells are decorative and hidden from screen readers, and RNTL's queries skip
// hidden elements unless asked.
const flat = (id: string) => StyleSheet.flatten(screen.getByTestId(id, { includeHiddenElements: true }).props.style);

describe('DashboardCard', () => {
  it('uses the redesign radius and regular padding by default', () => {
    render(<DashboardCard testID="card"><Text>x</Text></DashboardCard>);
    expect(flat('card').borderRadius).toBe(20);
    expect(flat('card').padding).toBe(20);
  });

  it('offers a compact padding for tiles', () => {
    render(<DashboardCard testID="card" padding="compact"><Text>x</Text></DashboardCard>);
    expect(flat('card').padding).toBe(16);
  });

  // The solid fallback is what Reduce Transparency users get. A tile must still have an edge there.
  it('keeps a visible edge on the solid fallback', () => {
    render(<DashboardCard testID="card" variant="row"><Text>x</Text></DashboardCard>);
    expect(flat('card').borderWidth).toBe(StyleSheet.hairlineWidth);
    expect(flat('card').borderColor).toBe(light.border);
    expect(flat('card').backgroundColor).toBe(light.card);
  });
});

describe('DeltaChip', () => {
  it('points up for a rise and down for a fall, with one decimal', () => {
    const { rerender } = render(<ThemeProvider><DeltaChip delta={4.25} good /></ThemeProvider>);
    expect(screen.getByText('▲ 4.3%')).toBeTruthy();
    rerender(<ThemeProvider><DeltaChip delta={-3.2} good={false} /></ThemeProvider>);
    expect(screen.getByText('▼ 3.2%')).toBeTruthy();
  });

  it('colours by whether the move is good, not by its direction (a rise in expenses is bad)', () => {
    render(<ThemeProvider><DeltaChip delta={8.1} good={false} testID="chip" /></ThemeProvider>);
    expect(flat('chip').backgroundColor).toBe(light.dangerBg);
    expect(screen.getByText('▲ 8.1%')).toHaveStyle({ color: light.dangerInk });
  });

  // The chip hugs the start of its row by default (under a tile's number). Beside a
  // right-aligned label it has to sit at the end instead, or the two visibly disagree.
  it('can sit at the end of its row', () => {
    const { rerender } = render(<ThemeProvider><DeltaChip delta={1} good testID="chip" /></ThemeProvider>);
    expect(flat('chip').alignSelf).toBe('flex-start');
    rerender(<ThemeProvider><DeltaChip delta={1} good align="end" testID="chip" /></ThemeProvider>);
    expect(flat('chip').alignSelf).toBe('flex-end');
  });

  it('treats zero as a rise of 0.0%, matching the wording the cards used before', () => {
    render(<ThemeProvider><DeltaChip delta={0} good /></ThemeProvider>);
    expect(screen.getByText('▲ 0.0%')).toBeTruthy();
  });
});

describe('ProgressBar', () => {
  const width = (pct: number) => {
    render(<ThemeProvider><ProgressBar percent={pct} color="#000000" testID="bar" /></ThemeProvider>);
    return flat('bar-fill').width;
  };
  it('draws the given percentage', () => { expect(width(68)).toBe('68%'); });
  it('never draws past its track for an over-budget value', () => { expect(width(150)).toBe('100%'); });
  it('never draws a negative width', () => { expect(width(-5)).toBe('0%'); });
  it('draws nothing for a value that is not a number', () => { expect(width(Number.NaN)).toBe('0%'); });
});

describe('DashboardSectionHeader', () => {
  it('shows the title as a heading', () => {
    render(<ThemeProvider><DashboardSectionHeader title="Goals" /></ThemeProvider>);
    expect(screen.getByRole('header', { name: 'Goals' })).toBeTruthy();
  });

  it('shows a plain caption when there is nothing to press', () => {
    render(<ThemeProvider><DashboardSectionHeader title="June 2026" caption="vs the month before Jun 26" /></ThemeProvider>);
    expect(screen.getByText('vs the month before Jun 26')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('offers an action with a 44 point target', () => {
    const onAction = jest.fn();
    render(<ThemeProvider><DashboardSectionHeader title="Budget Progress" actionLabel="Manage Budgets" onAction={onAction} /></ThemeProvider>);
    const button = screen.getByLabelText('Manage Budgets');
    expect(StyleSheet.flatten(button.props.style).minHeight).toBe(44);
    fireEvent.press(button);
    expect(onAction).toHaveBeenCalledTimes(1);
  });
});

describe('IconWell', () => {
  it('tints by tone', () => {
    render(<ThemeProvider><IconWell name="arrow-down-outline" tone="success" testID="well" /></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(light.successBg);
  });

  it('washes the neutral well with the light theme ink', () => {
    render(<ThemeProvider><IconWell name="card-outline" testID="well" /></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(withAlpha(light.ink, NEUTRAL_WASH));
    expect(flat('well').borderColor).toBe(light.border);
  });

  it('follows the dark theme', () => {
    render(<ThemeProvider><ForceDarkTheme><IconWell name="card-outline" testID="well" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('well').backgroundColor).toBe(withAlpha(dark.ink, NEUTRAL_WASH));
    expect(flat('well').borderColor).toBe(dark.border);
  });

  // The well this replaced (primaryLight) measured 1.01:1 on a dark card: it was not there.
  // 1.15 is below both measured values (1.17 light, 1.25 dark), so this fails if the wash is
  // weakened, and 3:1 is the floor for the icon drawn on it.
  it.each([['light', light], ['dark', dark]] as const)('stays visible on a %s card, with a readable icon', (_name, p) => {
    const card = hex(p.card);
    const well = over(hex(p.ink), NEUTRAL_WASH, card);
    expect(ratio(well, card)).toBeGreaterThanOrEqual(1.15);
    expect(ratio(hex(p.ink), well)).toBeGreaterThanOrEqual(3);
  });
});

describe('dark theme', () => {
  it('draws a delta chip from the dark washes and inks', () => {
    render(<ThemeProvider><ForceDarkTheme><DeltaChip delta={-2} good={false} testID="chip" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('chip').backgroundColor).toBe(dark.dangerBg);
    expect(screen.getByText('▼ 2.0%')).toHaveStyle({ color: dark.dangerInk });
  });

  it('draws a progress track from the dark border', () => {
    render(<ThemeProvider><ForceDarkTheme><ProgressBar percent={40} color={dark.primary} testID="bar" /></ForceDarkTheme></ThemeProvider>);
    expect(flat('bar').backgroundColor).toBe(dark.border);
  });

  it('gives a card the dark solid fallback, edge included', () => {
    render(<ThemeProvider><ForceDarkTheme><DashboardCard testID="card"><Text>x</Text></DashboardCard></ForceDarkTheme></ThemeProvider>);
    expect(flat('card').backgroundColor).toBe(dark.card);
    expect(flat('card').borderColor).toBe(dark.border);
  });
});
