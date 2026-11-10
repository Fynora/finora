import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ImportMoneyInCard } from './ImportMoneyInCard';
import { inflowApi } from '../../api/endpoints';
import { invalidateFinancialData } from '../../lib/invalidateFinancialData';
import type { InflowKind, UnresolvedSender } from '../../types';

jest.mock('../../api/endpoints', () => ({
  inflowApi: {
    unresolvedForImport: jest.fn(), kinds: jest.fn(), setChoice: jest.fn(), clearChoice: jest.fn(), createKind: jest.fn(),
  },
}));
jest.mock('../../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));

const inflow = inflowApi as jest.Mocked<typeof inflowApi>;
const income: InflowKind = { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' };

function sender(id: string, label: string, total: number, date: string, count = 1): UnresolvedSender {
  return {
    sampleTransactionId: id, label, senderKnown: true, count, senderPaymentCount: count, total, latestDate: date,
    accountName: 'Savings One',
    rows: [{ id, date, amount: total, description: `UPI-${label}`, accountName: 'Savings One' }],
  };
}

function renderCard(ids: string[], onSeeAll = jest.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ImportMoneyInCard statementImportIds={ids} onSeeAll={onSeeAll} />
    </QueryClientProvider>,
  );
  return { ...view, onSeeAll };
}

beforeEach(() => {
  jest.clearAllMocks();
  inflow.kinds.mockResolvedValue([income]);
  inflow.setChoice.mockResolvedValue({} as never);
  inflow.clearChoice.mockResolvedValue({} as never);
});

describe('ImportMoneyInCard', () => {
  it('asks about the payments this import added that are not counted as income', async () => {
    inflow.unresolvedForImport.mockResolvedValue([
      sender('t1', 'NEHA RAO', 6000, '2026-11-06'), sender('t2', 'RAVI SEN', 9000, '2026-11-07'),
    ]);
    renderCard(['imp-1']);

    expect(await screen.findByText("2 payments you received aren't counted as income")).toBeOnTheScreen();
    expect(screen.getByText('What were they? This decides if they count as income.')).toBeOnTheScreen();
    expect(inflow.unresolvedForImport).toHaveBeenCalledWith('imp-1');
  });

  it('shows the three biggest senders and opens the rest for their days', async () => {
    inflow.unresolvedForImport.mockResolvedValue([
      sender('a', 'A ONE', 100, '2026-11-02'), sender('b', 'B TWO', 500, '2026-11-03'),
      sender('c', 'C THREE', 300, '2026-11-04'), sender('d', 'D FOUR', 900, '2026-11-05'),
      sender('e', 'E FIVE', 200, '2026-11-09'),
    ]);
    const { onSeeAll } = renderCard(['imp-1']);

    expect(await screen.findByText('D FOUR')).toBeOnTheScreen();
    expect(screen.getByText('B TWO')).toBeOnTheScreen();
    expect(screen.getByText('C THREE')).toBeOnTheScreen();
    expect(screen.queryByText('E FIVE')).not.toBeOnTheScreen();
    expect(screen.queryByText('A ONE')).not.toBeOnTheScreen();
    fireEvent.press(screen.getByText('See all'));
    expect(onSeeAll).toHaveBeenCalledWith('2026-11-02', '2026-11-09');
  });

  it('goes away on Skip for now without saving anything', async () => {
    inflow.unresolvedForImport.mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    renderCard(['imp-1']);

    fireEvent.press(await screen.findByText('Skip for now'));

    expect(screen.queryByText(/counted as income/)).not.toBeOnTheScreen();
    expect(inflow.setChoice).not.toHaveBeenCalled();
  });

  it('saves a choice for one payment, refreshes the money figures, and offers undo', async () => {
    inflow.unresolvedForImport.mockResolvedValueOnce([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]).mockResolvedValue([]);
    renderCard(['imp-1']);

    fireEvent.press(await screen.findByText('NEHA RAO'));
    fireEvent.press(await screen.findByText('Income'));
    fireEvent.press(screen.getByText('Just this one'));

    await waitFor(() => expect(inflow.setChoice).toHaveBeenCalledWith('t1', 'k1', 'ROW'));
    expect(await screen.findByText('Saved for NEHA RAO.')).toBeOnTheScreen();
    expect(invalidateFinancialData).toHaveBeenCalled();
    fireEvent.press(screen.getByText('Undo'));
    await waitFor(() => expect(inflow.clearChoice).toHaveBeenCalledWith('t1', 'ROW'));
  });

  it('renders nothing when the import has no such payments', async () => {
    inflow.unresolvedForImport.mockResolvedValue([]);
    renderCard(['imp-1']);

    await waitFor(() => expect(inflow.unresolvedForImport).toHaveBeenCalled());
    expect(screen.queryByTestId('import-money-in-card')).not.toBeOnTheScreen();
  });

  it('renders nothing, and no error, when the request fails', async () => {
    inflow.unresolvedForImport.mockRejectedValue(new Error('down'));
    renderCard(['imp-1']);

    await waitFor(() => expect(inflow.unresolvedForImport).toHaveBeenCalled());
    expect(screen.queryByTestId('import-money-in-card')).not.toBeOnTheScreen();
    expect(screen.queryByText(/down|Could not/)).not.toBeOnTheScreen();
  });

  it('renders nothing without an import id', () => {
    renderCard([]);

    expect(inflow.unresolvedForImport).not.toHaveBeenCalled();
    expect(screen.queryByTestId('import-money-in-card')).not.toBeOnTheScreen();
  });

  it('says so when the choices cannot be loaded, instead of rows that cannot be answered', async () => {
    inflow.unresolvedForImport.mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    inflow.kinds.mockRejectedValue(new Error('down'));
    renderCard(['imp-1']);

    expect(await screen.findByText("Couldn't load the choices. See all on the Money not counted page.")).toBeOnTheScreen();
  });

  it('merges several imports into one question', async () => {
    inflow.unresolvedForImport.mockImplementation(async (id) =>
      id === 'imp-1' ? [sender('t1', 'NEHA RAO', 6000, '2026-11-06', 2)] : [sender('t2', 'RAVI SEN', 9000, '2026-11-07')]);
    renderCard(['imp-1', 'imp-2']);

    expect(await screen.findByText("3 payments you received aren't counted as income")).toBeOnTheScreen();
  });

  it('says payment, not payments, for one, and keeps the heading whole at large text', async () => {
    inflow.unresolvedForImport.mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    renderCard(['imp-1']);

    const heading = await screen.findByText("1 payment you received isn't counted as income");
    expect(heading.props.numberOfLines).toBeUndefined();
  });
});
