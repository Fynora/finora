import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QuickSortCta } from './QuickSortCta';
import { transactionsApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { quickSort: vi.fn() },
}));

function q(id: string) {
  return {
    id, anchorTransactionId: id, kind: 'SHOP' as const, payee: id, payments: 1, total: 10, latestDate: '2026-09-01',
    largeOneOff: false, currentCategory: 'Other', answers: [], samples: [],
  };
}

function renderCta() {
  return render(
    <MemoryRouter initialEntries={['/app/import']}>
      <Routes>
        <Route path="/app/import" element={<QuickSortCta />} />
        <Route path="/app/transactions" element={<p>Ledger page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('QuickSortCta', () => {
  beforeEach(() => vi.mocked(transactionsApi.quickSort).mockReset());

  it('offers to sort the questions waiting, counting them without recording a batch shown', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue({
      questions: [q('a'), q('b'), q('c')], waitingTotal: 30, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] },
    });
    renderCta();
    expect(await screen.findByRole('button', { name: 'Sort 3 questions' })).toBeInTheDocument();
    expect(transactionsApi.quickSort).toHaveBeenCalledWith(0, true);
  });

  it('says question for one', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue({
      questions: [q('a')], waitingTotal: 10, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] },
    });
    renderCta();
    expect(await screen.findByRole('button', { name: 'Sort 1 question' })).toBeInTheDocument();
  });

  it('opens the Ledger, where Quick sort is', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValue({
      questions: [q('a')], waitingTotal: 10, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] },
    });
    renderCta();
    await userEvent.click(await screen.findByRole('button', { name: 'Sort 1 question' }));
    expect(await screen.findByText('Ledger page')).toBeInTheDocument();
  });

  it('shows nothing when nothing is waiting or the count cannot be read', async () => {
    vi.mocked(transactionsApi.quickSort).mockResolvedValueOnce({
      questions: [], waitingTotal: 0, rest: { questions: 0, payments: 0, amount: 0, transactionIds: [] },
    });
    const { container, unmount } = renderCta();
    await waitFor(() => expect(transactionsApi.quickSort).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
    unmount();

    vi.mocked(transactionsApi.quickSort).mockRejectedValueOnce(new Error('down'));
    const second = renderCta();
    await waitFor(() => expect(transactionsApi.quickSort).toHaveBeenCalledTimes(2));
    expect(second.container).toBeEmptyDOMElement();
  });
});
