import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { DashboardEmptyState } from './DashboardEmptyState';
import { ThemeProvider } from '../../theme';

describe('DashboardEmptyState', () => {
  it('shows the message and no button when there is nothing to do about it', () => {
    render(<ThemeProvider><DashboardEmptyState icon="wallet-outline" message="No budgets set. Create one to track your spending." /></ThemeProvider>);
    expect(screen.getByText('No budgets set. Create one to track your spending.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('offers its action by its visible text, with a 44 point target', () => {
    const onAction = jest.fn();
    render(<ThemeProvider><DashboardEmptyState icon="receipt-outline" message="No transactions yet." actionLabel="Import a statement" onAction={onAction} /></ThemeProvider>);
    fireEvent.press(screen.getByText('Import a statement'));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(StyleSheet.flatten(screen.getByRole('button').props.style).minHeight).toBe(44);
  });

  // A label without a handler would be a button that does nothing.
  it('shows no button for a label that has no action behind it', () => {
    render(<ThemeProvider><DashboardEmptyState icon="receipt-outline" message="No transactions yet." actionLabel="Import a statement" /></ThemeProvider>);
    expect(screen.queryByText('Import a statement')).toBeNull();
  });
});
