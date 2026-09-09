import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccountFormSheet } from './AccountFormSheet';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';

jest.mock('../api/endpoints', () => ({
  accountsApi: { create: jest.fn(), update: jest.fn(), remove: jest.fn() },
}));

const api = accountsApi as jest.Mocked<typeof accountsApi>;

const bank = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

function account(over: Partial<Account> = {}): Account {
  return {
    id: 'a-1', name: 'HDFC Savings', accountType: 'SAVINGS', balance: 5000, bank,
    lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE', ...over,
  };
}

const onClose = jest.fn();
const onSaved = jest.fn();

function renderSheet(existing?: Account | null) {
  return render(<AccountFormSheet account={existing} onClose={onClose} onSaved={onSaved} />);
}

async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onClose.mockReset();
  onSaved.mockReset();
  api.create.mockReset().mockResolvedValue(account());
  api.update.mockReset().mockResolvedValue(account());
});

describe('AccountFormSheet -- add mode', () => {
  it('starts empty, with Savings selected and Save disabled', () => {
    renderSheet();

    // Not getByText('Add Account') -- that string renders twice (the sheet's own title, and the
    // submit button's label), same collision AddTransactionSheet's own tests already ran into.
    expect(screen.getByLabelText('Account name').props.value).toBe('');
    expect(screen.getByLabelText('Opening balance').props.value).toBe('');
    expect(screen.getByRole('button', { name: /Add Account/ }).props.accessibilityState.disabled).toBe(true);
  });

  it('enables Save once a name is entered', () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Account name'), 'New Wallet');

    expect(screen.getByRole('button', { name: /Add Account/ }).props.accessibilityState.disabled).toBe(false);
  });

  it('shows credit limit and due date only once Credit Card is chosen', () => {
    renderSheet();

    expect(screen.queryByLabelText('Credit limit')).toBeNull();

    fireEvent.press(screen.getByRole('button', { name: 'Credit Card' }));

    expect(screen.getByLabelText('Credit limit')).toBeTruthy();
    expect(screen.getByText('Payment due date')).toBeTruthy();
  });

  it('creates the account with the chosen type and a parsed opening balance', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Account name'), 'New Wallet');
    fireEvent.press(screen.getByRole('button', { name: 'Wallet' }));
    fireEvent.changeText(screen.getByLabelText('Opening balance'), '1500');

    fireEvent.press(screen.getByRole('button', { name: /Add Account/ }));
    await settle();

    expect(api.create).toHaveBeenCalledWith({ name: 'New Wallet', accountType: 'WALLET', balance: 1500 });
    expect(onSaved).toHaveBeenCalled();
  });

  it('sends no opening balance at all when left blank, rather than coercing it to zero', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Account name'), 'New Wallet');

    fireEvent.press(screen.getByRole('button', { name: /Add Account/ }));
    await settle();

    expect(api.create).toHaveBeenCalledWith({ name: 'New Wallet', accountType: 'SAVINGS', balance: undefined });
  });

  it('includes credit limit and due date for a Credit Card account', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Account name'), 'New Card');
    fireEvent.press(screen.getByRole('button', { name: 'Credit Card' }));
    fireEvent.changeText(screen.getByLabelText('Credit limit'), '50000');

    fireEvent.press(screen.getByRole('button', { name: /Add Account/ }));
    await settle();

    expect(api.create).toHaveBeenCalledWith({
      name: 'New Card', accountType: 'CREDIT_CARD', creditLimit: 50000, dueDate: undefined, balance: undefined,
    });
  });

  it('shows a server error and stays on the form when create() fails', async () => {
    api.create.mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 403, data: { message: "You've reached the account limit for your plan." } },
      })
    );
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Account name'), 'One Too Many');

    fireEvent.press(screen.getByRole('button', { name: /Add Account/ }));
    await settle();

    expect(await screen.findByText("You've reached the account limit for your plan.")).toBeTruthy();
    expect(onSaved).not.toHaveBeenCalled();
  });

  it('closes via Cancel without saving', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
    expect(api.create).not.toHaveBeenCalled();
  });
});

describe('AccountFormSheet -- edit mode', () => {
  it('prefills the existing account and shows the type as fixed, not a picker', () => {
    renderSheet(account({ name: 'HDFC Savings', balance: 5000, accountType: 'SAVINGS' }));

    expect(screen.getByText('Edit Account')).toBeTruthy();
    expect(screen.getByLabelText('Account name').props.value).toBe('HDFC Savings');
    expect(screen.getByLabelText('Balance').props.value).toBe('5000');
    expect(screen.getByText('Savings')).toBeTruthy();
    // No selectable type chips -- AccountService.update() never applies a changed accountType.
    expect(screen.queryByRole('button', { name: 'Wallet' })).toBeNull();
  });

  it('omits balance from the update when it was left unchanged', async () => {
    renderSheet(account({ id: 'a-9', name: 'HDFC Savings', balance: 5000 }));
    fireEvent.changeText(screen.getByLabelText('Account name'), 'HDFC Savings Account');

    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('a-9', { name: 'HDFC Savings Account', accountType: 'SAVINGS' });
  });

  it('includes balance in the update only when it actually changed', async () => {
    renderSheet(account({ id: 'a-9', name: 'HDFC Savings', balance: 5000 }));
    fireEvent.changeText(screen.getByLabelText('Balance'), '6200');

    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('a-9', { name: 'HDFC Savings', accountType: 'SAVINGS', balance: 6200 });
  });

  it('preserves the credit card fields for a Credit Card account being edited', () => {
    renderSheet(account({ accountType: 'CREDIT_CARD', creditLimit: 100000, dueDate: '2026-09-15' }));

    expect(screen.getByLabelText('Credit limit').props.value).toBe('100000');
    // fmtDate's exact locale rendering isn't pinned here (see format.test.ts's own convention) --
    // this only confirms the due date round-tripped into the field at all, not vanished.
    expect(screen.getByLabelText(/Payment due date: .*Change/)).toBeTruthy();
  });

  // AccountService.update() only ever applies a NON-null dueDate -- there's no way through that
  // endpoint to clear one that's already set. Submitting after "Clear" would look like it worked
  // (the sheet closes, no error) while the backend silently kept the old date.
  it('blocks saving after the due date is cleared, rather than silently keeping the old one', async () => {
    renderSheet(account({ id: 'a-9', accountType: 'CREDIT_CARD', creditLimit: 100000, dueDate: '2026-09-15' }));

    fireEvent.press(screen.getByLabelText('Clear Payment due date'));

    expect(screen.getByText(/A due date can.t be removed here/)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Save Changes/ }).props.accessibilityState.disabled).toBe(true);

    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();
    expect(api.update).not.toHaveBeenCalled();
  });
});
