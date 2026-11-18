import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerFilteredCard } from './LedgerFilteredCard';
import { ThemeProvider } from '../../theme';
import type { LedgerFilterTotals } from '../../lib/ledgerFilterTotals';

const TOTALS: LedgerFilterTotals = {
  moneyIn: 1500, moneyOut: 8400, notCounted: 0, rowsRead: 2, totalElements: 2, complete: true,
};

function show(over: Partial<Parameters<typeof LedgerFilteredCard>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerFilteredCard totals={TOTALS} failed={false} onRetry={jest.fn()} stacked={false} {...over} />
    </ThemeProvider>
  );
}

describe('LedgerFilteredCard', () => {
  it('shows what came in and went out across the matching rows', () => {
    show();
    expect(screen.getByText('Matching transactions')).toBeTruthy();
    expect(screen.getByText('Money in')).toBeTruthy();
    expect(screen.getByText('Money out')).toBeTruthy();
    expect(screen.getByTestId('filtered-money-in')).toHaveTextContent('₹1,500');
    expect(screen.getByTestId('filtered-money-out')).toHaveTextContent('₹8,400');
  });

  it('never claims the figures are income or expenses, which the server decides', () => {
    show();
    expect(screen.queryByText('Income')).toBeNull();
    expect(screen.queryByText('Expenses')).toBeNull();
  });

  it('shows zero as a real answer when nothing matched', () => {
    show({ totals: { ...TOTALS, moneyIn: 0, moneyOut: 0, rowsRead: 0, totalElements: 0 } });
    expect(screen.getByTestId('filtered-money-out')).toHaveTextContent('₹0');
    expect(screen.queryByTestId('filtered-note')).toBeNull();
  });

  it('shows a placeholder and no figure while loading', () => {
    show({ totals: undefined });
    expect(screen.getByLabelText('Loading')).toBeTruthy();
    expect(screen.queryByTestId('filtered-money-out')).toBeNull();
  });

  it('says it could not add up instead of showing zero, and offers a retry', () => {
    const onRetry = jest.fn();
    show({ totals: undefined, failed: true, onRetry });
    expect(screen.getByText("Couldn't add these up.")).toBeTruthy();
    expect(screen.queryByText('₹0')).toBeNull();
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('prefers figures it already has over a failed refresh', () => {
    show({ failed: true });
    expect(screen.getByTestId('filtered-money-out')).toHaveTextContent('₹8,400');
    expect(screen.queryByText("Couldn't add these up.")).toBeNull();
  });

  it.each([
    [1, '1 duplicate or replaced row is not counted.'],
    [3, '3 duplicate or replaced rows are not counted.'],
  ])('says when %i rows were left out', (notCounted, note) => {
    show({ totals: { ...TOTALS, notCounted } });
    expect(screen.getByTestId('filtered-note')).toHaveTextContent(note);
  });

  it('says the figures cover only the newest rows when the list is too long to read', () => {
    show({ totals: { ...TOTALS, rowsRead: 5000, totalElements: 5100, complete: false } });
    expect(screen.getByTestId('filtered-note')).toHaveTextContent('Covers the newest 5,000 of 5,100 transactions.');
  });

  it('stacks the two columns at large text', () => {
    show({ stacked: true });
    expect(screen.getByTestId('ledger-filtered-columns')).toHaveStyle({ flexDirection: 'column' });
  });
});
