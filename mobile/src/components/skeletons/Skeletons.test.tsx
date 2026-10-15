import { render, screen } from '@testing-library/react-native';
import {
  SkeletonBarRow, SkeletonBox, SkeletonBudgetCard, SkeletonCard, SkeletonChart, SkeletonFormField,
  SkeletonGroup, SkeletonKpiGrid, SkeletonLines, SkeletonListRow, SkeletonProfileHeader,
  SkeletonSettingsRow, SkeletonText, SkeletonTransactionRow,
} from './Skeletons';
import { ThemeProvider } from '../../theme';

function withTheme(node: React.ReactElement) {
  return render(<ThemeProvider>{node}</ThemeProvider>);
}

// See Shimmer.test.tsx's own note: shimmer-block/skeleton-* elements are deliberately hidden from
// accessibility, so every query here needs { hidden: true } to find them.

describe('SkeletonCard', () => {
  it('renders a heading placeholder plus the requested number of line placeholders', () => {
    withTheme(<SkeletonCard lines={2} />);
    // heading + 2 lines = 3 shimmer blocks
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });

  it('defaults to 3 lines when none is given', () => {
    withTheme(<SkeletonCard />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });
});

describe('SkeletonTransactionRow', () => {
  it('renders the row shape: description, meta and amount placeholders', () => {
    withTheme(<SkeletonTransactionRow />);
    expect(screen.getByTestId('skeleton-transaction-row', { hidden: true })).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });
});

describe('SkeletonBudgetCard', () => {
  it('renders the budget card shape: header, progress bar and footer placeholders', () => {
    withTheme(<SkeletonBudgetCard />);
    expect(screen.getByTestId('skeleton-budget-card', { hidden: true })).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });
});

describe('SkeletonChart', () => {
  it('renders a bar-shaped placeholder matching CashFlowChart height by default', () => {
    withTheme(<SkeletonChart width={280} />);
    expect(screen.getByTestId('skeleton-chart-bar', { hidden: true })).toBeTruthy();
  });

  it('renders a circular placeholder matching DonutChart when variant="donut"', () => {
    withTheme(<SkeletonChart variant="donut" />);
    expect(screen.getByTestId('skeleton-chart-donut', { hidden: true })).toBeTruthy();
  });
});

describe('SkeletonKpiGrid', () => {
  // Stands in for the month's four tiles, so the grid does not jump when the figures land.
  it('stands in for four tiles, two to a row', () => {
    withTheme(<SkeletonKpiGrid />);
    const grid = screen.getByTestId('skeleton-kpi-grid', { hidden: true });
    expect(grid.children).toHaveLength(4);
    // label, number and chip placeholders per tile
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(12);
  });
});

describe('SkeletonGroup', () => {
  it('is one element a screen reader reads as "Loading", with its pieces hidden', () => {
    withTheme(
      <SkeletonGroup>
        <SkeletonLines count={2} />
      </SkeletonGroup>
    );
    expect(screen.getAllByLabelText('Loading')).toHaveLength(1);
    // The pieces exist but only as hidden placeholders.
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
    expect(screen.queryAllByTestId('shimmer-block')).toHaveLength(0);
  });
});

describe('sized blocks', () => {
  function heightOf(testID = 'shimmer-block') {
    const style = screen.getByTestId(testID, { hidden: true }).props.style;
    return (Array.isArray(style) ? Object.assign({}, ...style.flat(Infinity).filter(Boolean)) : style).height;
  }

  it('a text placeholder is as tall as the font size it stands in for', () => {
    withTheme(<SkeletonText size="title" />);
    expect(heightOf()).toBe(22);
  });

  it('a field placeholder is as tall as a TextField input row', () => {
    withTheme(<SkeletonBox size="field" />);
    expect(heightOf()).toBe(48);
  });
});

describe('row and form blocks', () => {
  it('SkeletonListRow: icon, two lines and an amount', () => {
    withTheme(<SkeletonListRow icon="md" />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonListRow without an icon or amount is just the two lines', () => {
    withTheme(<SkeletonListRow amount={false} />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
  });

  it('SkeletonFormField: a label over a field', () => {
    withTheme(<SkeletonFormField />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2);
  });

  it('SkeletonProfileHeader: a circle and three lines', () => {
    withTheme(<SkeletonProfileHeader />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonSettingsRow with a switch: two lines and the switch', () => {
    withTheme(<SkeletonSettingsRow trailing="switch" />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });

  it('SkeletonLines draws the requested number of lines', () => {
    withTheme(<SkeletonLines count={4} />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });

  it('SkeletonBarRow: a label, a value and the bar track', () => {
    withTheme(<SkeletonBarRow />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });
});

// Measured on the simulator (iPhone 17 Pro, 2026-10-16): a line of the system font is 1.19 times
// its font size tall (12 -> 14.0, 14 -> 16.6, 11 -> 13.1, 17 -> 20.1). A placeholder only as tall
// as the font size made the loaded Profile about 60 pt taller than its skeleton, so content jumped.
describe('placeholders take the room the real text takes', () => {
  function flat(style: unknown): Record<string, unknown> {
    return Array.isArray(style) ? Object.assign({}, ...(style as unknown[]).flat(Infinity).filter(Boolean)) : ((style ?? {}) as Record<string, unknown>);
  }

  it('a text line occupies a line of text, with the bar at the font size inside it', () => {
    withTheme(<SkeletonText size="title" />);
    const bar = screen.getByTestId('shimmer-block', { hidden: true });
    expect(flat(bar.props.style).height).toBe(22);
    expect(flat(screen.getByTestId('skeleton-line', { hidden: true }).props.style).height).toBe(Math.round(22 * 1.19));
  });

  it('a paragraph is one line of text per line, with no extra gap between them', () => {
    withTheme(<SkeletonLines count={3} size="body" />);
    const lines = screen.getAllByTestId('skeleton-line', { hidden: true });
    expect(lines).toHaveLength(3);
    lines.forEach((l) => expect(flat(l.props.style).height).toBe(Math.round(14 * 1.19)));
    expect(flat(screen.getByTestId('skeleton-lines', { hidden: true }).props.style).gap).toBeUndefined();
  });

  it('a form field keeps the slot TextField reserves under its input for an error', () => {
    withTheme(<SkeletonFormField />);
    expect(screen.getByTestId('skeleton-field-message', { hidden: true })).toBeTruthy();
  });
});
