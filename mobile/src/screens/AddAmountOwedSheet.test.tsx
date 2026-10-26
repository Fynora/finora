import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AddAmountOwedSheet, AmountOwedNotKnown } from './AddAmountOwedSheet';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';

jest.mock('../api/endpoints', () => ({
  accountsApi: { update: jest.fn() },
}));

const api = accountsApi as jest.Mocked<typeof accountsApi>;

const card = {
  id: 'card-1', name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: -100396.53,
  amountOwedKnown: false, balanceStated: false,
} as Account;

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  api.update.mockReset().mockResolvedValue({ ...card, balance: 12345.67, amountOwedKnown: true });
});

describe('AddAmountOwedSheet', () => {
  it('asks the question and saves the amount as the card balance, keeping its name and type', async () => {
    const onSaved = jest.fn();
    render(<AddAmountOwedSheet account={card} onClose={jest.fn()} onSaved={onSaved} />);

    expect(screen.getByText('How much do you owe on ICICI Card today?')).toBeOnTheScreen();
    fireEvent.changeText(screen.getByLabelText('Amount owed today (₹)'), '12,345.67');
    fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('card-1', { name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: 12345.67 });
    expect(onSaved).toHaveBeenCalled();
  });

  it('will not save a blank or unreadable amount, and says what it needs', () => {
    render(<AddAmountOwedSheet account={card} onClose={jest.fn()} onSaved={jest.fn()} />);
    expect(screen.getByRole('button', { name: 'Save' }).props.accessibilityState.disabled).toBe(true);
    fireEvent.changeText(screen.getByLabelText('Amount owed today (₹)'), '-5');
    expect(screen.getByRole('button', { name: 'Save' }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText('Enter the amount in rupees, like 12345.67')).toBeOnTheScreen();
  });

  it('says so when saving fails, and does not report it saved', async () => {
    api.update.mockReset().mockRejectedValue(new Error('offline'));
    const onSaved = jest.fn();
    render(<AddAmountOwedSheet account={card} onClose={jest.fn()} onSaved={onSaved} />);
    fireEvent.changeText(screen.getByLabelText('Amount owed today (₹)'), '100');
    fireEvent.press(screen.getByRole('button', { name: 'Save' }));
    await settle();
    expect(screen.getByText(/Could not save the amount/)).toBeOnTheScreen();
    expect(onSaved).not.toHaveBeenCalled();
  });
});

describe('AmountOwedNotKnown', () => {
  it('shows no figure, and offers to add it', () => {
    const onAdd = jest.fn();
    render(<AmountOwedNotKnown cardName="ICICI Card" onAdd={onAdd} />);
    expect(screen.getByText('Amount owed not known')).toBeOnTheScreen();
    const addIt = screen.getByRole('button', { name: 'Add it' });
    // Several cards can each have one; the hint says which card a screen reader is on.
    expect(addIt.props.accessibilityHint).toBe('Asks how much you owe on ICICI Card today');
    fireEvent.press(addIt);
    expect(onAdd).toHaveBeenCalled();
  });
});
