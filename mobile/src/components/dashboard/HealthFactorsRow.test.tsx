import { fireEvent, render, screen } from '@testing-library/react-native';
import { HealthFactorsRow } from './HealthFactorsRow';
import { ThemeProvider } from '../../theme';

function renderRow(props: Partial<React.ComponentProps<typeof HealthFactorsRow>> = {}) {
  return render(
    <ThemeProvider>
      <HealthFactorsRow
        available
        breakdown={{ 'Debt Score': 100, 'Savings Rate': 65 }}
        breakdownDetail={{ 'Debt Score': 'No credit card balance carried over.' }}
        topOpportunityFactor={null}
        topOpportunityPotentialGain={null}
        {...props}
      />
    </ThemeProvider>
  );
}

describe('HealthFactorsRow', () => {
  it('renders nothing when not available', () => {
    const { toJSON } = renderRow({ available: false });
    expect(toJSON()).toBeNull();
  });

  it('shows each factor name, score and label', () => {
    renderRow();
    expect(screen.getByText('Debt Score')).toBeTruthy();
    expect(screen.getByText('100%')).toBeTruthy();
    expect(screen.getByText('Savings Rate')).toBeTruthy();
    expect(screen.getByText('65%')).toBeTruthy();
    expect(screen.getByText('Good')).toBeTruthy();
  });

  it('keeps the Why?/Hide detail disclosure, only for factors with a detail entry', async () => {
    renderRow();
    const whys = screen.getAllByText('Why?');
    expect(whys).toHaveLength(1);
    expect(screen.queryByText('No credit card balance carried over.')).toBeNull();

    fireEvent.press(whys[0]);
    expect(await screen.findByText('No credit card balance carried over.')).toBeTruthy();
    expect(screen.getByText('Hide')).toBeTruthy();
  });

  it('shows the improvement suggestion for every factor', () => {
    renderRow();
    expect(screen.getByText('Aim to save at least 24% of your income each month.')).toBeTruthy();
  });

  it('highlights the top-opportunity factor with its potential gain', () => {
    renderRow({ topOpportunityFactor: 'Savings Rate', topOpportunityPotentialGain: 14 });
    expect(screen.getByText('↑ +14 point opportunity')).toBeTruthy();
  });
});
