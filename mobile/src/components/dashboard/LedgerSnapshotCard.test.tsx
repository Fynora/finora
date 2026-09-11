import { render, screen } from '@testing-library/react-native';
import { LedgerSnapshotCard, type KpiItem } from './LedgerSnapshotCard';
import { ThemeProvider } from '../../theme';

const KPIS: KpiItem[] = [
  { label: 'Income', value: 145000, delta: 12.5, invert: false, caption: null, isPercent: false },
  { label: 'Expenses', value: 12831, delta: -3.2, invert: true, caption: null, isPercent: false },
];

function renderCard(kpis: KpiItem[] = KPIS) {
  return render(
    <ThemeProvider>
      <LedgerSnapshotCard kpis={kpis} deltaLabel="vs last month" deltaSpokenLabel="versus last month" />
    </ThemeProvider>
  );
}

describe('LedgerSnapshotCard', () => {
  it('renders one row per KPI, in a single list, not a grid of separate cards', () => {
    renderCard();
    expect(screen.getByText('Income')).toBeTruthy();
    expect(screen.getByText('Expenses')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
  });

  it('shows a delta for a KPI that has one', () => {
    renderCard();
    expect(screen.getByText(/12\.5% vs last month/)).toBeTruthy();
  });

  it('shows a percent value without AnimatedNumber', () => {
    renderCard([{ label: 'Savings Rate', value: 42, delta: null, invert: false, caption: null, isPercent: true }]);
    expect(screen.getByText('42%')).toBeTruthy();
  });

  it('shows a caption instead of a delta when there is no delta', () => {
    renderCard([{ label: 'Total Balance', value: 100000, delta: null, invert: false, caption: 'As of today', isPercent: false }]);
    expect(screen.getByText('As of today')).toBeTruthy();
  });
});
