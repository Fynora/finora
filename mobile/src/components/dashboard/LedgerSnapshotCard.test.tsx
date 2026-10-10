import { render, screen } from '@testing-library/react-native';
import { Dimensions, StyleSheet } from 'react-native';
import { LedgerSnapshotCard, type KpiItem } from './LedgerSnapshotCard';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';

// useLargeFontScale reads fontScale through useWindowDimensions, which takes its value from
// Dimensions.get('window') on mount. Spied the same way DashboardScreen.test.tsx does it, and
// reset before every test so a large scale cannot leak into the next one.
const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');
beforeEach(() => {
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
});

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
  it('renders one tile per KPI', () => {
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

  it('shows a delta chip for a KPI that has one, and names the comparison once for the section', () => {
    renderCard();
    expect(screen.getByText('▲ 12.5%')).toBeTruthy();
    expect(screen.getByText('▼ 3.2%')).toBeTruthy();
    expect(screen.getAllByText('vs last month')).toHaveLength(1);
  });

  it('colours a rise in expenses as bad and a fall as good', () => {
    renderCard([{ label: 'Expenses', value: 100, delta: 8, invert: true, caption: null, isPercent: false }]);
    expect(screen.getByText('▲ 8.0%')).toHaveStyle({ color: light.dangerInk });
    renderCard([{ label: 'Expenses', value: 100, delta: -8, invert: true, caption: null, isPercent: false }]);
    expect(screen.getByText('▼ 8.0%')).toHaveStyle({ color: light.successInk });
  });

  it('names no comparison when no KPI has a delta', () => {
    renderCard([{ label: 'Total Balance', value: 100000, delta: null, invert: false, caption: 'As of today', isPercent: false }]);
    expect(screen.queryByText('vs last month')).toBeNull();
  });

  it('still reads each tile out as one sentence', () => {
    renderCard();
    expect(screen.getByLabelText('Income: ₹1,45,000, up 12.5 percent versus last month')).toBeTruthy();
    expect(screen.getByLabelText('Expenses: ₹12,831, down 3.2 percent versus last month')).toBeTruthy();
  });

  const basis = (label: string) => StyleSheet.flatten(screen.getByTestId(`kpi-tile-${label}`).props.style).flexBasis;

  it('lays tiles out two per row for ordinary amounts', () => {
    renderCard();
    expect(basis('Income')).toBe('47%');
    expect(basis('Expenses')).toBe('47%');
  });

  // Ten characters ("₹12,48,320") is the longest amount that fits half a row on a 360 point
  // phone; this is the boundary, one character either side of it.
  it('keeps two per row at ten characters and drops to one per row at eleven', () => {
    renderCard([{ label: 'Income', value: 1248320, delta: null, invert: false, caption: null, isPercent: false }]);
    expect(basis('Income')).toBe('47%');
    renderCard([{ label: 'Expenses', value: 12483200, delta: null, invert: false, caption: null, isPercent: false }]);
    expect(basis('Expenses')).toBe('100%');
  });

  it('puts every tile on its own row once any one amount is too long, so the grid stays even', () => {
    renderCard([
      { label: 'Income', value: 12483200, delta: null, invert: false, caption: null, isPercent: false },
      { label: 'Expenses', value: 500, delta: null, invert: false, caption: null, isPercent: false },
    ]);
    expect(basis('Income')).toBe('100%');
    expect(basis('Expenses')).toBe('100%');
  });

  it('falls back to one tile per row under large Dynamic Type', () => {
    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    renderCard();
    expect(basis('Income')).toBe('100%');
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
