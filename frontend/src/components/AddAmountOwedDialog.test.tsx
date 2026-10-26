import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AddAmountOwedDialog, AmountOwedNotKnown } from './AddAmountOwedDialog';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';

vi.mock('../api/endpoints', () => ({ accountsApi: { update: vi.fn() } }));

const card = { id: 'card-1', name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: -100396.53 } as Account;

describe('AddAmountOwedDialog', () => {
  beforeEach(() => { vi.mocked(accountsApi.update).mockReset(); });

  it('saves the amount as the card balance, keeping its name and type', async () => {
    vi.mocked(accountsApi.update).mockResolvedValue({ ...card, balance: 12345.67, amountOwedKnown: true });
    const onSaved = vi.fn();
    const user = userEvent.setup();
    render(<AddAmountOwedDialog account={card} onClose={vi.fn()} onSaved={onSaved} />);

    expect(screen.getByRole('dialog', { name: 'How much do you owe on ICICI Card today?' })).toBeInTheDocument();
    await user.type(screen.getByLabelText('Amount owed today (₹)'), '12,345.67');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(accountsApi.update).toHaveBeenCalledWith('card-1', { name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: 12345.67 });
  });

  it('will not save a blank or unreadable amount', async () => {
    const user = userEvent.setup();
    render(<AddAmountOwedDialog account={card} onClose={vi.fn()} onSaved={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    await user.type(screen.getByLabelText('Amount owed today (₹)'), '-5');
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Enter the amount in rupees, like 12345.67');
  });

  it('says so when saving fails, and keeps the dialog open', async () => {
    vi.mocked(accountsApi.update).mockImplementation(async () => {
      throw Object.assign(new Error('Request failed'), { response: { data: { message: 'Something went wrong' } } });
    });
    const onSaved = vi.fn();
    const user = userEvent.setup();
    render(<AddAmountOwedDialog account={card} onClose={vi.fn()} onSaved={onSaved} />);
    await user.type(screen.getByLabelText('Amount owed today (₹)'), '100');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('Something went wrong')).toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
  });
});

describe('AmountOwedNotKnown', () => {
  it('shows no figure, and offers to add it', async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    render(<AmountOwedNotKnown onAdd={onAdd} />);
    expect(screen.getByText('Amount owed not known')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add it' }));
    expect(onAdd).toHaveBeenCalled();
  });
});
