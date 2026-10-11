import { render, screen } from '@testing-library/react-native';
import {
  SkeletonBudgetCard, SkeletonCard, SkeletonChart, SkeletonKpiGrid, SkeletonTransactionRow,
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
