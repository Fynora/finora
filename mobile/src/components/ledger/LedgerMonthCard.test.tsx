import { render, screen } from '@testing-library/react-native';
import { LedgerMonthCard } from './LedgerMonthCard';
import { ThemeProvider } from '../../theme';
import type { KpiItem } from '../dashboard/LedgerSnapshotCard';

const kpi = (label: string, value: number, delta: number | null, invert: boolean): KpiItem =>
  ({ label, value, delta, invert, caption: null, isPercent: false });
const KPIS = [kpi('Income', 145000, 12, false), kpi('Expenses', 18672, 8, true), kpi('Net Savings', 126328, 15, false)];

function show(over: Partial<Parameters<typeof LedgerMonthCard>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerMonthCard
        kpis={KPIS}
        title="Aug 26"
        deltaLabel="vs the month before Aug 26"
        deltaSpokenLabel="versus the month before Aug 26"
        stacked={false}
        {...over}
      />
    </ThemeProvider>
  );
}

describe('LedgerMonthCard', () => {
  it('shows the period it is given, the comparison, and the two figures', () => {
    show();
    expect(screen.getByText('Aug 26')).toBeTruthy();
    expect(screen.getByText('vs the month before Aug 26')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
    expect(screen.getByTestId('kpi-Expenses')).toBeTruthy();
    expect(screen.queryByText('Net Savings')).toBeNull();
  });

  it('speaks a change with the comparison in words, and a rise in expenses as a rise', () => {
    show();
    expect(screen.getByLabelText('Income up 12.0 percent versus the month before Aug 26')).toBeTruthy();
    expect(screen.getByLabelText('Expenses up 8.0 percent versus the month before Aug 26')).toBeTruthy();
  });

  it('shows no chip and no comparison when there is no change figure', () => {
    show({ kpis: [kpi('Income', 1, null, false), kpi('Expenses', 2, null, true)] });
    expect(screen.queryByText(/%/)).toBeNull();
    expect(screen.queryByText('vs the month before Aug 26')).toBeNull();
  });

  it('renders nothing without both figures', () => {
    show({ kpis: [kpi('Income', 1, null, false)] });
    expect(screen.queryByTestId('ledger-month-card')).toBeNull();
  });

  it('stacks the two columns at large text', () => {
    show({ stacked: true });
    expect(screen.getByTestId('ledger-month-columns')).toHaveStyle({ flexDirection: 'column' });
  });

  it('puts them side by side otherwise', () => {
    show();
    expect(screen.getByTestId('ledger-month-columns')).toHaveStyle({ flexDirection: 'row' });
  });
});
