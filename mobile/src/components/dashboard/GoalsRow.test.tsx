import { render, screen } from '@testing-library/react-native';
import { GoalsRow } from './GoalsRow';
import { ThemeProvider } from '../../theme';

describe('GoalsRow', () => {
  it('shows each goal name, amounts and percent', () => {
    render(
      <ThemeProvider>
        <GoalsRow goals={[
          { id: 'g1', name: 'Emergency Fund', currentAmount: 60000, targetAmount: 200000 },
          { id: 'g2', name: 'Europe Trip', currentAmount: 120000, targetAmount: 300000 },
        ]} />
      </ThemeProvider>
    );
    expect(screen.getByText('Emergency Fund')).toBeTruthy();
    expect(screen.getByText('₹60,000 of ₹2,00,000')).toBeTruthy();
    expect(screen.getByText('30%')).toBeTruthy();
    expect(screen.getByText('Europe Trip')).toBeTruthy();
    expect(screen.getByText('40%')).toBeTruthy();
  });

  it('renders nothing with no goals', () => {
    const { toJSON } = render(<ThemeProvider><GoalsRow goals={[]} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });
});
