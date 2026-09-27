import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CountsAsSection } from '../components/inflow/CountsAsSection';
import MoneyReview from '../pages/MoneyReview';
import { InflowKindsSection } from '../pages/settings/InflowKindsSection';
import { inflowApi } from '../api/endpoints';
import { MONEY_FIGURE_KEYS } from './invalidateMoneyFigures';

vi.mock('../api/endpoints', () => ({
  inflowApi: {
    countsAs: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn(),
    unresolved: vi.fn(), updateKind: vi.fn(), deleteKind: vi.fn(), senderRules: vi.fn(), forgetSender: vi.fn(),
  },
}));

const unresolved = {
  flowClass: 'UNRESOLVED', flowReason: 'PERSON_INFLOW', kind: null, appliedBy: null, choosable: true,
  notChoosableReason: null, senderAvailable: true, senderLabel: 'ASHA VERMA', senderRowCount: 1,
  summary: 'Not counted yet · from a person',
};
const family = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' as const };
const rent = { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null };

/** Renders with a real QueryClient and reports every key it was asked to invalidate. */
function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient();
  const spy = vi.spyOn(client, 'invalidateQueries');
  render(<QueryClientProvider client={client}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
  return () => spy.mock.calls.map((c) => (c[0] as { queryKey: string[] }).queryKey[0]);
}

describe('money figures refresh after an inflow-kind change (web caches them for 30s)', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.kinds).mockResolvedValue([family, rent]);
    vi.mocked(inflowApi.countsAs).mockResolvedValue(unresolved);
    vi.mocked(inflowApi.senderRules).mockResolvedValue([]);
  });

  it('covers every figure an inflow kind moves', () => {
    expect(MONEY_FIGURE_KEYS).toEqual(expect.arrayContaining([
      'dashboard-summary', 'dashboard-range-summary', 'report', 'report-months', 'insights', 'budgets', 'transactions',
    ]));
  });

  it('after a choice in "Counts as"', async () => {
    vi.mocked(inflowApi.setChoice).mockResolvedValue({ ...unresolved, kind: family, appliedBy: 'ROW' });
    const keys = renderWithClient(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Just this one' }));
    await waitFor(() => expect(keys()).toEqual(expect.arrayContaining(['dashboard-range-summary', 'dashboard-summary'])));
  });

  it('after a choice on the review page', async () => {
    vi.mocked(inflowApi.unresolved).mockResolvedValue([{
      sampleTransactionId: 't1', label: 'ASHA VERMA', senderKnown: true, count: 1, senderPaymentCount: 1, total: 500,
      latestDate: '2026-08-20', accountName: null,
      rows: [{ id: 't1', date: '2026-08-20', amount: 500, description: null, accountName: null }],
    }]);
    vi.mocked(inflowApi.setChoice).mockResolvedValue({} as never);
    const keys = renderWithClient(<MoneyReview />);
    await userEvent.click(await screen.findByText('ASHA VERMA'));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Every payment from ASHA VERMA (1)' }));
    await waitFor(() => expect(keys()).toEqual(expect.arrayContaining(['dashboard-range-summary', 'insights'])));
  });

  it('after a kind stops counting as income in Settings', async () => {
    vi.mocked(inflowApi.updateKind).mockResolvedValue({ ...rent, countsAsIncome: false });
    const keys = renderWithClient(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Rent from tenant counts as income' }));
    await waitFor(() => expect(keys()).toEqual(expect.arrayContaining(['dashboard-summary', 'report'])));
  });
});
