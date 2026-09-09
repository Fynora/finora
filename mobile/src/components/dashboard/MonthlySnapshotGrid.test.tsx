import { render, screen } from '@testing-library/react-native';
import { MonthlySnapshotGrid } from './MonthlySnapshotGrid';
import { ThemeProvider } from '../../theme';

describe('MonthlySnapshotGrid', () => {
  it('renders each KPI value, with testID, and its delta', () => {
    render(
      <ThemeProvider>
        <MonthlySnapshotGrid
          deltaLabel="vs last month"
          deltaSpokenLabel="versus last month"
          kpis={[
            { label: 'Income', value: 145000, delta: 12, invert: false, caption: null, isPercent: false },
            { label: 'Expenses', value: 12831, delta: -24, invert: true, caption: null, isPercent: false },
            { label: 'Net Savings', value: 132169, delta: 18, invert: false, caption: null, isPercent: false },
            { label: 'Savings Rate', value: 91, delta: null, invert: false, caption: null, isPercent: true },
          ]}
        />
      </ThemeProvider>
    );

    expect(screen.getByTestId('kpi-Income')).toHaveProp('defaultValue', '₹1,45,000');
    expect(screen.getByText('▲ 12.0% vs last month')).toBeTruthy();
    expect(screen.getByText('91%')).toBeTruthy();
  });
});
