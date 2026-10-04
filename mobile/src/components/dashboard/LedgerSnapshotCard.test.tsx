import { render, screen } from '@testing-library/react-native';
import { LedgerSnapshotCard, type KpiItem } from './LedgerSnapshotCard';
import { ThemeProvider } from '../../theme';

const KPIS: KpiItem[] = [
  { label: 'Income', value: 145000, delta: 12.5, invert: false, caption: null, isPercent: false },
  { label: 'Expenses', value: 12831, delta: -3.2, invert: true, caption: null, isPercent: false },
];

function renderCard(kpis: KpiItem[] = KPIS, title = 'This Month') {
  return render(
    <ThemeProvider>
      <LedgerSnapshotCard
        kpis={kpis} title={title} deltaLabel="vs last month" deltaSpokenLabel="versus last month"
      />
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

  // The KPIs are the summary's reporting month -- routinely an earlier one for statements
  // imported in arrears -- so the heading is whatever period the caller says they describe.
  it('titles the card with the period it is given, not a hard-coded "This Month"', () => {
    renderCard(KPIS, 'June 2026');
    expect(screen.getByText('June 2026')).toBeTruthy();
    expect(screen.queryByText('This Month')).toBeNull();
  });

  it('shows a delta for a KPI that has one', () => {
    renderCard();
    expect(screen.getByText(/12\.5% vs last month/)).toBeTruthy();
  });

  it('shows a percent value without AnimatedNumber', () => {
    renderCard([{ label: 'Savings Rate', value: 42, delta: null, invert: false, caption: null, isPercent: true }]);
    expect(screen.getByText('42%')).toBeTruthy();
  });

  it('shows a dash and the reason for a withheld savings rate', () => {
    renderCard([{ label: 'Savings Rate', value: null, delta: null, invert: false,
      caption: 'Classify money received to see this', isPercent: true }]);
    expect(screen.getByTestId('kpi-Savings Rate')).toHaveTextContent('—');
    expect(screen.getByText('Classify money received to see this')).toBeTruthy();
    expect(screen.getByLabelText('Savings Rate: —, Classify money received to see this')).toBeTruthy();
  });

  it('shows a caption instead of a delta when there is no delta', () => {
    renderCard([{ label: 'Total Balance', value: 100000, delta: null, invert: false, caption: 'As of today', isPercent: false }]);
    expect(screen.getByText('As of today')).toBeTruthy();
  });
});
