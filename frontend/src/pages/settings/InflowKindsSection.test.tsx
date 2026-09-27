import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InflowKindsSection } from './InflowKindsSection';
import { inflowApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  inflowApi: {
    kinds: vi.fn(), createKind: vi.fn(), updateKind: vi.fn(), deleteKind: vi.fn(), senderRules: vi.fn(), forgetSender: vi.fn(),
  },
}));

const income = { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' as const };
const rent = { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null };

describe('InflowKindsSection', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.kinds).mockReset().mockResolvedValue([income, rent]);
    vi.mocked(inflowApi.senderRules).mockReset().mockResolvedValue([{ id: 'r1', label: 'ASHA VERMA', kind: income, rowCount: 3 }]);
  });

  it('lists kinds and remembered senders', async () => {
    render(<InflowKindsSection />);
    expect(await screen.findByText('Rent from tenant')).toBeInTheDocument();
    expect(screen.getByText('ASHA VERMA')).toBeInTheDocument();
    expect(screen.getByText('Income · 3 payments')).toBeInTheDocument();
  });

  it('built-ins have no delete button and no income toggle', async () => {
    render(<InflowKindsSection />);
    await screen.findByText('Rent from tenant');
    expect(screen.queryByRole('button', { name: 'Delete Income' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Delete Rent from tenant' })).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'Income counts as income' })).toBeNull();
    expect(screen.getByRole('checkbox', { name: 'Rent from tenant counts as income' })).toBeChecked();
  });

  it('flips a custom kind to not counting as income', async () => {
    vi.mocked(inflowApi.updateKind).mockResolvedValue({ ...rent, countsAsIncome: false });
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Rent from tenant counts as income' }));
    await waitFor(() => expect(inflowApi.updateKind).toHaveBeenCalledWith('k6', { countsAsIncome: false }));
  });

  it('renames a kind', async () => {
    vi.mocked(inflowApi.updateKind).mockResolvedValue({ ...income, name: 'Earnings' });
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('button', { name: 'Rename Income' }));
    const input = screen.getByLabelText('New name for Income');
    await userEvent.clear(input);
    await userEvent.type(input, 'Earnings');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(inflowApi.updateKind).toHaveBeenCalledWith('k1', { name: 'Earnings' }));
  });

  it('explains why a kind in use cannot be deleted', async () => {
    vi.mocked(inflowApi.deleteKind).mockRejectedValue({
      response: { data: { message: 'This kind is still used. Move those payments and senders to another kind first.', details: { rows: 2, senders: 1 } } },
    });
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('button', { name: 'Delete Rent from tenant' }));
    expect(await screen.findByText(/Used by 2 payments and 1 sender\./)).toBeInTheDocument();
  });

  it('forgets a sender', async () => {
    vi.mocked(inflowApi.forgetSender).mockResolvedValue({} as never);
    render(<InflowKindsSection />);
    await userEvent.click(await screen.findByRole('button', { name: 'Forget ASHA VERMA' }));
    await waitFor(() => expect(inflowApi.forgetSender).toHaveBeenCalledWith('r1'));
  });
});
