import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MoneyReviewScreen } from './MoneyReviewScreen';
import { inflowApi } from '../api/endpoints';
import type { InflowKind, UnresolvedSender } from '../types';

jest.mock('../api/endpoints', () => ({
  inflowApi: { unresolved: jest.fn(), kinds: jest.fn(), setChoice: jest.fn(), clearChoice: jest.fn(), createKind: jest.fn() },
}));

jest.mock('../lib/invalidateFinancialData', () => ({
  invalidateFinancialData: jest.fn(),
}));

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

const inflow = inflowApi as jest.Mocked<typeof inflowApi>;

const asha: UnresolvedSender = {
  sampleTransactionId: 't1', label: 'ASHA VERMA', senderKnown: true, count: 2, senderPaymentCount: 5, total: 7000,
  latestDate: '2026-08-20', accountName: 'Savings One',
  rows: [
    { id: 't1', date: '2026-08-20', amount: 2000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
    { id: 't0', date: '2026-08-03', amount: 5000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
  ],
};
const family: InflowKind = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' };

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const route = { key: 'MoneyReview-1', name: 'MoneyReview' as const, params: { start: '2026-08-01', end: '2026-08-31' } };
  return render(
    <QueryClientProvider client={queryClient}>
      <MoneyReviewScreen route={route} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  inflow.kinds.mockResolvedValue([family]);
});

describe('MoneyReviewScreen', () => {
  it('lists senders for the period it was opened with', async () => {
    inflow.unresolved.mockResolvedValue([asha]);
    renderScreen();
    expect(await screen.findByText('ASHA VERMA')).toBeOnTheScreen();
    expect(inflow.unresolved).toHaveBeenCalledWith('2026-08-01', '2026-08-31');
  });

  it('removes a sender once every payment from them is set, and offers undo', async () => {
    inflow.unresolved.mockResolvedValueOnce([asha]).mockResolvedValue([]);
    inflow.setChoice.mockResolvedValue({} as never);
    inflow.clearChoice.mockResolvedValue({} as never);
    renderScreen();
    fireEvent.press(await screen.findByText('ASHA VERMA'));
    fireEvent.press(await screen.findByText('Family support'));
    fireEvent.press(screen.getByText('Every payment from ASHA VERMA (5)'));
    await waitFor(() => expect(inflow.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText("Everything's sorted")).toBeOnTheScreen();
    fireEvent.press(screen.getByText('Undo'));
    await waitFor(() => expect(inflow.clearChoice).toHaveBeenCalledWith('t1', 'SENDER'));
  });

  it('sets one payment on its own', async () => {
    inflow.unresolved.mockResolvedValue([asha]);
    inflow.setChoice.mockResolvedValue({} as never);
    renderScreen();
    fireEvent.press(await screen.findByText('ASHA VERMA'));
    fireEvent.press(await screen.findByText('Family support'));
    fireEvent.press(screen.getByText('Just this one'));
    fireEvent.press(screen.getByText('2026-08-03'));
    await waitFor(() => expect(inflow.setChoice).toHaveBeenCalledWith('t0', 'k2', 'ROW'));
  });
});
