import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerFiltersSheet, STATUS_FILTERS, statusFilterLabel } from './LedgerFiltersSheet';
import { ThemeProvider } from '../../theme';
import { expectHaptics } from '../../test/haptics';

const handlers = {
  onStatus: jest.fn(), onDateFrom: jest.fn(), onDateTo: jest.fn(), onInternational: jest.fn(),
  onClearAll: jest.fn(), onClose: jest.fn(),
};
function show(over: Partial<Parameters<typeof LedgerFiltersSheet>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerFiltersSheet
        status="ALL"
        dateFrom={null}
        dateTo={null}
        internationalOnly={false}
        resultCount={21}
        {...handlers}
        {...over}
      />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('statusFilterLabel', () => {
  it('names the eight choices as the chips did', () => {
    expect((['ALL', 'OK', ...STATUS_FILTERS] as const).map(statusFilterLabel)).toEqual([
      'Any status', 'OK', 'Duplicate', 'Transfer', 'Refund', 'Reversed', 'Investment', 'Superseded',
    ]);
  });
});

describe('LedgerFiltersSheet', () => {
  it('offers the eight statuses, one selected, with their old spoken names', () => {
    show({ status: 'TRANSFER' });
    expect(screen.getByLabelText('Filter by status: Transfer')).toBeSelected();
    expect(screen.getByLabelText('Filter by status: Any status')).not.toBeSelected();
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));
    expect(handlers.onStatus).toHaveBeenCalledWith('OK');
    expect(screen.getAllByLabelText(/^Filter by status: /)).toHaveLength(8);
  });

  it('holds the From and To date fields', () => {
    show({ dateFrom: '2026-09-01' });
    expect(screen.getByLabelText(/^From: .*\. Change$/)).toBeTruthy();
    expect(screen.getByLabelText('To: not set. Choose a date')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Clear From'));
    expect(handlers.onDateFrom).toHaveBeenCalledWith(null);
  });

  it('turns International on from one switch row', () => {
    show();
    const row = screen.getByLabelText('Show only international transactions');
    expect(row).not.toBeChecked();
    fireEvent.press(row);
    expect(handlers.onInternational).toHaveBeenCalledWith(true);
  });

  it('says the switch is on when it is, and turns it off', () => {
    show({ internationalOnly: true });
    const row = screen.getByLabelText('Show only international transactions');
    expect(row).toBeChecked();
    fireEvent.press(row);
    expect(handlers.onInternational).toHaveBeenCalledWith(false);
  });

  it('ticks only when the status changes', () => {
    show({ status: 'TRANSFER' });
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));
    expectHaptics({});
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));
    expectHaptics({ selection: 1 });
  });

  it('the International only row fires one selection per toggle', () => {
    show({ internationalOnly: false });
    fireEvent.press(screen.getByLabelText('Show only international transactions'));
    expectHaptics({ selection: 1 });
  });

  it('clears everything from one control', () => {
    show({ status: 'REFUND' });
    fireEvent.press(screen.getByLabelText('Clear all filters'));
    expect(handlers.onClearAll).toHaveBeenCalledTimes(1);
  });

  it.each([
    [21, 'Show 21 transactions'], [1, 'Show 1 transaction'], [0, 'Show 0 transactions'], [1234567, 'Show 12,34,567 transactions'],
  ])('closes from a button that says how many match: %i', (count, text) => {
    show({ resultCount: count });
    expect(screen.getByTestId('filters-done')).toHaveTextContent(text);
    fireEvent.press(screen.getByTestId('filters-done'));
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });

  it('says Done while the count is not known', () => {
    show({ resultCount: null });
    expect(screen.getByTestId('filters-done')).toHaveTextContent('Done');
  });

  it('closes from the area outside the sheet', () => {
    show();
    fireEvent.press(screen.getByLabelText('Close filters'));
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });
});
