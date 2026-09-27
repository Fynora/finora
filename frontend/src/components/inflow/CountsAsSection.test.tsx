import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CountsAsSection } from './CountsAsSection';
import { inflowApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  inflowApi: { countsAs: vi.fn(), kinds: vi.fn(), setChoice: vi.fn(), clearChoice: vi.fn(), createKind: vi.fn() },
}));

const unresolved = {
  flowClass: 'UNRESOLVED', flowReason: 'PERSON_INFLOW', kind: null, appliedBy: null, choosable: true,
  notChoosableReason: null, senderAvailable: true, senderLabel: 'ASHA VERMA', senderRowCount: 3,
  summary: 'Not counted yet · from a person',
};
const family = { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' as const };

describe('CountsAsSection', () => {
  beforeEach(() => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue(unresolved);
    vi.mocked(inflowApi.kinds).mockResolvedValue([family]);
  });

  it('sets a kind for every payment from the sender by default', async () => {
    vi.mocked(inflowApi.setChoice).mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', flowReason: 'FAMILY_SUPPORT', kind: family, appliedBy: 'SENDER',
      summary: 'You marked payments from this sender as Family support',
    });
    const onChanged = vi.fn();
    render(<CountsAsSection transactionId="t1" onChanged={onChanged} />);
    expect(await screen.findByText('Not counted yet · from a person')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    await userEvent.click(screen.getByRole('button', { name: 'Every payment from ASHA VERMA (3)' }));
    await waitFor(() => expect(inflowApi.setChoice).toHaveBeenCalledWith('t1', 'k2', 'SENDER'));
    expect(await screen.findByText('You marked payments from this sender as Family support')).toBeInTheDocument();
    expect(onChanged).toHaveBeenCalled();
  });

  it('offers only this payment when the sender is unknown', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({ ...unresolved, senderAvailable: false, senderLabel: null, senderRowCount: 0 });
    render(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: /Family support/ }));
    expect(screen.queryByRole('button', { name: /Every payment from/ })).toBeNull();
    expect(screen.getByRole('button', { name: 'Just this one' })).toBeInTheDocument();
  });

  it('shows the reason and no Change button when the row cannot take a kind', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({
      ...unresolved, flowClass: 'TRANSFER', flowReason: 'OWN_ACCOUNT_TRANSFER', choosable: false,
      notChoosableReason: 'This payment is matched as a transfer between your accounts. Use "Not a transfer" first.',
      summary: 'Transfer between your accounts',
    });
    render(<CountsAsSection transactionId="t1" />);
    expect(await screen.findByText(/Use "Not a transfer" first/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Change' })).toBeNull();
  });

  it('says a sender-wide choice is cleared for every payment from the sender', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', kind: family, appliedBy: 'SENDER',
      summary: 'You marked payments from this sender as Family support',
    });
    vi.mocked(inflowApi.clearChoice).mockResolvedValue(unresolved);
    render(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Clear for every payment from ASHA VERMA (3)' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'SENDER'));
  });

  it('clears the user choice', async () => {
    vi.mocked(inflowApi.countsAs).mockResolvedValue({
      ...unresolved, flowClass: 'INCOME', kind: family, appliedBy: 'ROW', summary: 'You marked this payment as Family support',
    });
    vi.mocked(inflowApi.clearChoice).mockResolvedValue(unresolved);
    render(<CountsAsSection transactionId="t1" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Clear my choice' }));
    await waitFor(() => expect(inflowApi.clearChoice).toHaveBeenCalledWith('t1', 'ROW'));
    expect(await screen.findByText('Not counted yet · from a person')).toBeInTheDocument();
  });
});
