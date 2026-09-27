import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MoneyReview from './MoneyReview';
import { inflowApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  inflowApi: { unresolved: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn() },
}));

const asha = {
  sampleTransactionId: 't1', label: 'ASHA VERMA', senderKnown: true, count: 2, senderPaymentCount: 5, total: 7000,
  latestDate: '2026-08-20', accountName: 'Savings One',
  rows: [
    { id: 't1', date: '2026-08-20', amount: 2000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
    { id: 't0', date: '2026-08-03', amount: 5000, description: 'UPI-ASHA VERMA', accountName: 'Savings One' },
  ],
};
const family = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' as const };

function renderAt(url: string) {
  return render(<MemoryRouter initialEntries={[url]}><MoneyReview /></MemoryRouter>);
}

describe('MoneyReview', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.kinds).mockResolvedValue([family]);
    vi.mocked(inflowApi.unresolved).mockReset();
  });

  it('loads the period from the URL and lists senders', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValue([asha]);
    renderAt('/app/money-review?start=2026-08-01&end=2026-08-31');
    expect(await screen.findByText('ASHA VERMA')).toBeInTheDocument();
    expect(inflowApi.unresolved).toHaveBeenCalledWith('2026-08-01', '2026-08-31');
  });

  it('removes a sender once every payment from them is set, and offers undo', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValueOnce([asha]).mockResolvedValue([]);
    vi.mocked(inflowApi.setChoice).mockResolvedValue({} as never);
    vi.mocked(inflowApi.clearChoice).mockResolvedValue({} as never);
    renderAt('/app/money-review?start=2026-08-01&end=2026-08-31');
    await userEvent.click(await screen.findByText('ASHA VERMA'));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Every payment from ASHA VERMA (5)' }));
    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText("Everything's sorted")).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'SENDER'));
  });

  it('sets one payment on its own', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValue([asha]);
    vi.mocked(inflowApi.setChoice).mockResolvedValue({} as never);
    renderAt('/app/money-review?start=2026-08-01&end=2026-08-31');
    await userEvent.click(await screen.findByText('ASHA VERMA'));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Just this one' }));
    await userEvent.click(screen.getByRole('button', { name: /2026-08-03/ }));
    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t0', 'k2', 'ROW'));
  });
});
