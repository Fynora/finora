import { fireEvent, render, screen } from '@testing-library/react-native';
import { PaidRegularlySection } from './PaidRegularlySection';
import type { RecurringItem } from '../../api/endpoints';

const item = (merchant: string, label: string, averageAmount: number, latestAmount?: number): RecurringItem => ({
  merchant, label, averageAmount, latestAmount, occurrences: 6, lastDate: '2026-09-01', nextEstimate: '2026-10-01',
});

describe('PaidRegularlySection', () => {
  it('shows a shared bill at the user\'s share, and how many people share it', () => {
    render(
      <PaidRegularlySection
        rows={[{ item: { ...item('sample owner', 'Monthly', 36000), yourShare: 9000, sharedWith: 3 }, monthly: 9000, rising: false }]}
        monthlyTotal={9000}
        yearlyTotal={108000}
        olderMonthPicked={false}
        onDismiss={jest.fn()}
      />,
    );
    expect(screen.getByText('Monthly · split with 3')).toBeTruthy();
    expect(screen.getByText('₹9,000')).toBeTruthy();
    expect(screen.getByLabelText('sample owner, Monthly, your share ₹9,000 of ₹36,000, split with 3')).toBeTruthy();
  });

  function show(olderMonthPicked = false) {
    const onDismiss = jest.fn();
    render(
      <PaidRegularlySection
        rows={[
          { item: item('rent', 'Monthly', 15000, 15000), monthly: 15000, rising: false },
          { item: item('tv', 'Monthly', 549, 649), monthly: 549, rising: true },
        ]}
        monthlyTotal={15549}
        yearlyTotal={186588}
        olderMonthPicked={olderMonthPicked}
        onDismiss={onDismiss}
      />,
    );
    return { onDismiss };
  }

  it('totals the month and the year in Indian grouping', () => {
    show();
    expect(screen.getByText('Paid regularly')).toBeTruthy();
    expect(screen.getByText('₹15,549 a month · ₹1,86,588 a year')).toBeTruthy();
  });

  it('lists each payment with its cadence and how often it was seen, marking a rise', () => {
    show();
    expect(screen.getAllByText('Monthly · seen 6×')).toHaveLength(2);
    expect(screen.getByLabelText('tv, Monthly, ₹549, latest payment went up')).toBeTruthy();
    expect(screen.getByLabelText('rent, Monthly, ₹15,000')).toBeTruthy();
  });

  it('says the list comes from all statements when an older month is picked', () => {
    show(true);
    expect(screen.getByText('Based on all your statements')).toBeTruthy();
  });

  it('lets a wrongly found payment be dismissed', () => {
    const { onDismiss } = show();
    fireEvent.press(screen.getByRole('button', { name: 'Not recurring: dismiss tv' }));
    expect(onDismiss).toHaveBeenCalledWith('tv');
  });

  it('renders nothing with nothing repeating', () => {
    const { toJSON } = render(<PaidRegularlySection rows={[]} monthlyTotal={0} yearlyTotal={0} olderMonthPicked={false} onDismiss={jest.fn()} />);
    expect(toJSON()).toBeNull();
  });
});
