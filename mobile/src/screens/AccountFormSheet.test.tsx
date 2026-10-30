import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AccountFormSheet } from './AccountFormSheet';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';
import { expectHaptics } from '../test/haptics';

jest.mock('../api/endpoints', () => ({
  accountsApi: { create: jest.fn(), update: jest.fn(), remove: jest.fn() },
  // The edit mode's Bank field (GET /banks).
  banksApi: {
    list: jest.fn().mockResolvedValue([
      { id: 'HDFC', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004c8f', initials: 'H', logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: 'HDFC', supportedAccountTypes: [] },
      { id: 'ICICI', officialName: 'ICICI Bank', shortName: 'ICICI', colorHex: '#b02a30', initials: 'I', logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: 'ICIC', supportedAccountTypes: [] },
    ]),
  },
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
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE', primarySource: 'MANUAL', ...over,
  };
}

const onClose = jest.fn();
const onSaved = jest.fn();

function renderSheet(existing?: Account | null, focusBank?: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <AccountFormSheet account={existing} focusBank={focusBank} onClose={onClose} onSaved={onSaved} />
    </QueryClientProvider>
  );
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
    // The type chip ticked; the save gives one success after the server confirms.
    expectHaptics({ selection: 1, success: 1 });
  });

  it('ticks only when the account type changes', () => {
    renderSheet();
    fireEvent.press(screen.getByRole('button', { name: 'Savings' }));
    expectHaptics({});
    fireEvent.press(screen.getByRole('button', { name: 'Wallet' }));
    expectHaptics({ selection: 1 });
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
    expectHaptics({ error: 1 });
  });

  it('closes via Cancel without saving', () => {
    renderSheet();

    fireEvent.press(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalled();
    expect(api.create).not.toHaveBeenCalled();
    expectHaptics({});
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
    expectHaptics({ success: 1 });
  });

  it('gives one error when the update fails', async () => {
    api.update.mockRejectedValueOnce(new Error('down'));
    renderSheet(account({ id: 'a-9', name: 'HDFC Savings', balance: 5000 }));
    fireEvent.changeText(screen.getByLabelText('Account name'), 'HDFC Savings Account');

    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(await screen.findByText('Could not update this account.')).toBeTruthy();
    expect(onSaved).not.toHaveBeenCalled();
    expectHaptics({ error: 1 });
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

describe('AccountFormSheet -- a card whose amount owed is not known', () => {
  it('does not put the summed figure in the balance field, and a rename sends no balance', async () => {
    renderSheet(account({ id: 'card-1', name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: -100396.53, amountOwedKnown: false }));

    expect(screen.getByLabelText('Balance').props.value).toBe('');
    fireEvent.changeText(screen.getByLabelText('Account name'), 'ICICI Rupay');
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('card-1', expect.not.objectContaining({ balance: expect.anything() }));
  });

  it('sends a typed balance even when it equals the summed figure: the user has now stated it', async () => {
    renderSheet(account({ id: 'card-1', name: 'ICICI Card', accountType: 'CREDIT_CARD', balance: -500, amountOwedKnown: false }));

    fireEvent.changeText(screen.getByLabelText('Balance'), '-500');
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('card-1', expect.objectContaining({ balance: -500 }));
  });
});

// Spec 2026-10-22-bank-from-profile-and-required-bank-design.md, section 4: the person can change
// an account's bank later. Edit mode only; add mode is unchanged.
describe('AccountFormSheet -- the bank, in edit mode', () => {
  const HDFC = { ...bank, id: 'HDFC', officialName: 'HDFC Bank', shortName: 'HDFC' };

  it('shows the current bank and changes it', async () => {
    renderSheet(account({ bank: HDFC }));
    await settle();

    expect(screen.getByTestId('account-form-bank-value')).toHaveTextContent('HDFC Bank');
    fireEvent.press(screen.getByTestId('account-form-bank-change'));
    fireEvent.press(await screen.findByTestId('account-form-bank-option-ICICI'));
    expect(screen.getByTestId('account-form-bank-value')).toHaveTextContent('ICICI Bank');

    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('a-1', expect.objectContaining({ bankId: 'ICICI' }));
    expect(onSaved).toHaveBeenCalledWith({ bankAnswered: true });
  });

  it('turns "Other bank" into a real one', async () => {
    renderSheet(account({ bank }));
    await settle();

    expect(screen.getByTestId('account-form-bank-value')).toHaveTextContent('Other bank');
    fireEvent.press(screen.getByTestId('account-form-bank-change'));
    fireEvent.changeText(screen.getByLabelText('Search banks'), 'hdfc');
    fireEvent.press(await screen.findByTestId('account-form-bank-option-HDFC'));
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update).toHaveBeenCalledWith('a-1', expect.objectContaining({ bankId: 'HDFC' }));
  });

  it('sends no bank on a plain rename', async () => {
    renderSheet(account({ bank: HDFC }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Account name'), 'Salary');
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update.mock.calls[0][1]).not.toHaveProperty('bankId');
    expect(onSaved).toHaveBeenCalledWith({ bankAnswered: false });
  });

  it('counts "Other bank" picked again as an answer, without sending a change', async () => {
    // Spec section 7: choosing "Other bank" again answers "Which bank is this?".
    renderSheet(account({ bank }), true);
    await settle();

    fireEvent.press(await screen.findByTestId('account-form-bank-option-OTHER'));
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update.mock.calls[0][1]).not.toHaveProperty('bankId');
    expect(onSaved).toHaveBeenCalledWith({ bankAnswered: true });
  });

  it('opens the bank list at once from the prompt', async () => {
    renderSheet(account({ bank }), true);
    await settle();

    expect(screen.getByLabelText('Search banks')).toBeOnTheScreen();
    expect(screen.getByTestId('account-form-bank-hint')).toHaveTextContent(/didn't say which bank/);
  });

  it('keeps an Account Aggregator account\'s bank read-only and never sends one', async () => {
    // AA sets the bank once from the bank's own IFSC; a re-link finds the account again by it.
    renderSheet(account({ bank: HDFC, primarySource: 'ACCOUNT_AGGREGATOR' }));
    await settle();

    expect(screen.getByTestId('account-form-bank-readonly')).toHaveTextContent(/HDFC Bank/);
    expect(screen.queryByTestId('account-form-bank-change')).toBeNull();
    fireEvent.changeText(screen.getByLabelText('Account name'), 'Synced');
    fireEvent.press(screen.getByRole('button', { name: /Save Changes/ }));
    await settle();

    expect(api.update.mock.calls[0][1]).not.toHaveProperty('bankId');
    expect(onSaved).toHaveBeenCalledWith({ bankAnswered: false });
  });

  it('offers no bank field when adding an account', () => {
    renderSheet();
    expect(screen.queryByTestId('account-form-bank-value')).toBeNull();
  });
});
