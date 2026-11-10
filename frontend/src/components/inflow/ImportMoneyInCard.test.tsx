import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { ImportMoneyInCard } from './ImportMoneyInCard';
import { inflowApi, type UnresolvedSender } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  inflowApi: {
    unresolvedForImport: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn(),
  },
}));
const invalidate = vi.fn();
vi.mock('../../lib/invalidateMoneyFigures', () => ({ useInvalidateMoneyFigures: () => invalidate }));

function sender(id: string, label: string, total: number, date: string, count = 1): UnresolvedSender {
  return {
    sampleTransactionId: id, label, senderKnown: true, count, senderPaymentCount: count, total, latestDate: date,
    accountName: 'Savings One',
    rows: [{ id, date, amount: total, description: `UPI-${label}`, accountName: 'Savings One' }],
  };
}
const income = { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' as const };

function renderCard(ids: string[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><ImportMoneyInCard statementImportIds={ids} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ImportMoneyInCard', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.unresolvedForImport).mockReset();
    vi.mocked(inflowApi.kinds).mockResolvedValue([income]);
    vi.mocked(inflowApi.setChoice).mockReset().mockResolvedValue({} as never);
    vi.mocked(inflowApi.clearChoice).mockReset().mockResolvedValue({} as never);
    invalidate.mockReset();
  });

  it('asks about the payments this import added that are not counted as income', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([
      sender('t1', 'NEHA RAO', 6000, '2026-11-06'), sender('t2', 'RAVI SEN', 9000, '2026-11-07'),
    ]);
    renderCard(['imp-1']);

    expect(await screen.findByText("2 payments you received aren't counted as income")).toBeInTheDocument();
    expect(screen.getByText('What were they? This decides if they count as income.')).toBeInTheDocument();
    expect(inflowApi.unresolvedForImport).toHaveBeenCalledWith('imp-1');
  });

  it('shows the three biggest senders and links to the rest', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([
      sender('a', 'A ONE', 100, '2026-11-02'), sender('b', 'B TWO', 500, '2026-11-03'),
      sender('c', 'C THREE', 300, '2026-11-04'), sender('d', 'D FOUR', 900, '2026-11-05'),
      sender('e', 'E FIVE', 200, '2026-11-09'),
    ]);
    renderCard(['imp-1']);

    await screen.findByText('D FOUR');
    const shown = screen.getAllByTestId('unresolved-sender').map((el) => el.querySelector('span')?.textContent);
    expect(shown).toEqual(['D FOUR', 'B TWO', 'C THREE']);
    expect(screen.getByRole('link', { name: 'See all' }))
      .toHaveAttribute('href', '/app/money-review?start=2026-11-02&end=2026-11-09');
  });

  it('goes away on Skip for now without saving anything', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    renderCard(['imp-1']);

    await userEvent.click(await screen.findByRole('button', { name: 'Skip for now' }));

    expect(screen.queryByText(/aren't counted as income|isn't counted as income/)).not.toBeInTheDocument();
    expect(inflowApi.setChoice).not.toHaveBeenCalled();
  });

  it('saves a choice for one payment, refreshes the money figures, and offers undo', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValueOnce([sender('t1', 'NEHA RAO', 6000, '2026-11-06')])
      .mockResolvedValue([]);
    renderCard(['imp-1']);

    await userEvent.click(await screen.findByText('NEHA RAO'));
    await userEvent.click(await screen.findByRole('button', { name: /^Income/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Just this one' }));

    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t1', 'k1', 'ROW'));
    expect(await screen.findByText('Saved for NEHA RAO.')).toBeInTheDocument();
    expect(invalidate).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'ROW'));
  });

  it('renders nothing when the import has no such payments', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([]);
    const { container } = renderCard(['imp-1']);

    await waitFor(() => expect(inflowApi.unresolvedForImport).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing, and no error, when the request fails', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockRejectedValue(new Error('down'));
    const { container } = renderCard(['imp-1']);

    await waitFor(() => expect(inflowApi.unresolvedForImport).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('says so when the choices cannot be loaded, instead of rows that cannot be answered', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    vi.mocked(inflowApi.kinds).mockRejectedValue(new Error('down'));
    renderCard(['imp-1']);

    expect(await screen.findByText("Couldn't load the choices. See all on the Money not counted page.")).toBeInTheDocument();
  });

  it('merges several imports into one question', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockImplementation(async (id) =>
      id === 'imp-1' ? [sender('t1', 'NEHA RAO', 6000, '2026-11-06', 2)] : [sender('t2', 'RAVI SEN', 9000, '2026-11-07')]);
    renderCard(['imp-1', 'imp-2']);

    expect(await screen.findByText("3 payments you received aren't counted as income")).toBeInTheDocument();
  });

  it('says payment, not payments, for one', async () => {
    vi.mocked(inflowApi.unresolvedForImport).mockResolvedValue([sender('t1', 'NEHA RAO', 6000, '2026-11-06')]);
    renderCard(['imp-1']);

    expect(await screen.findByText("1 payment you received isn't counted as income")).toBeInTheDocument();
  });
});
