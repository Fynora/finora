import { describe, expect, it, vi, beforeEach } from 'vitest';
import { StrictMode, useState } from 'react';
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

  it('opens with the amount field focused, so typing goes straight into it', async () => {
    const user = userEvent.setup();
    render(<AddAmountOwedDialog account={card} onClose={vi.fn()} onSaved={vi.fn()} />);
    expect(screen.getByLabelText('Amount owed today (₹)')).toHaveFocus();
    await user.keyboard('500');
    expect(screen.getByLabelText('Amount owed today (₹)')).toHaveValue('500');
  });

  // Seen in the running app: opened by a real click, focus ended on Close. React's StrictMode runs
  // the dialog helper's focus effect, its cleanup (back to the opener) and the effect again, which
  // then lands on the first control -- Close -- because the field no longer has focus.
  it('keeps the amount field focused when opened from a button under StrictMode', async () => {
    const user = userEvent.setup();
    function Opener() {
      const [open, setOpen] = useState(false);
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>Add it</button>
          {open && <AddAmountOwedDialog account={card} onClose={() => setOpen(false)} onSaved={vi.fn()} />}
        </>
      );
    }
    render(<StrictMode><Opener /></StrictMode>);
    await user.click(screen.getByRole('button', { name: 'Add it' }));
    expect(screen.getByLabelText('Amount owed today (₹)')).toHaveFocus();
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
