import { Alert } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AccountsScreen } from './AccountsScreen';
import { accountsApi } from '../api/endpoints';
import type { Account } from '../types';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn(), create: jest.fn(), update: jest.fn(), remove: jest.fn() },
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

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

/** Confirms the last Alert.alert(...) call and runs its destructive button's onPress. */
async function confirmLastAlert(alertSpy: jest.SpyInstance, label: string) {
  const buttons = alertSpy.mock.calls.at(-1)?.[2] as { text: string; onPress?: () => void }[];
  await act(async () => { buttons.find((b) => b.text === label)!.onPress!(); });
}

beforeEach(() => {
  api.list.mockReset().mockResolvedValue([account()]);
  api.remove.mockReset().mockResolvedValue(undefined as never);
});

describe('AccountsScreen', () => {
  it('shows an empty state that mentions both ways to get an account', async () => {
    api.list.mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/Import a statement or add one manually/)).toBeTruthy();
  });

  it('renders an account with Edit and Delete actions', async () => {
    renderScreen();

    expect(await screen.findByText('HDFC Savings')).toBeTruthy();
    expect(screen.getByLabelText('Edit HDFC Savings')).toBeTruthy();
    expect(screen.getByLabelText('Delete HDFC Savings')).toBeTruthy();
  });

  it('opens the form sheet in add mode from the header button', async () => {
    renderScreen();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByRole('button', { name: 'Add Account' }));

    expect(await screen.findByLabelText('Account name')).toBeTruthy();
    // Add mode: the name field is empty, not prefilled from the existing account.
    expect(screen.getByLabelText('Account name').props.value).toBe('');
  });

  it("opens the form sheet in edit mode, prefilled, from a row's Edit action", async () => {
    renderScreen();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByLabelText('Edit HDFC Savings'));

    expect(await screen.findByLabelText('Account name')).toBeTruthy();
    expect(screen.getByLabelText('Account name').props.value).toBe('HDFC Savings');
  });

  it('refetches the account list once the form sheet reports a save', async () => {
    renderScreen();
    await screen.findByText('HDFC Savings');
    fireEvent.press(screen.getByRole('button', { name: 'Add Account' }));
    await screen.findByLabelText('Account name');

    api.list.mockResolvedValue([account(), account({ id: 'a-2', name: 'New Wallet', accountType: 'WALLET' })]);
    fireEvent.changeText(screen.getByLabelText('Account name'), 'New Wallet');
    // Two "Add Account" buttons are on screen at once now -- the header's own (still mounted
    // underneath the sheet) and the sheet's submit button. The sheet renders later in the tree.
    fireEvent.press(screen.getAllByRole('button', { name: /Add Account/ }).at(-1)!);
    await settle();

    expect(await screen.findByText('New Wallet')).toBeTruthy();
    // The sheet closes itself once its own onSaved fires.
    expect(screen.queryByLabelText('Account name')).toBeNull();
  });

  it('asks for confirmation before deleting, naming the account', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    renderScreen();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByLabelText('Delete HDFC Savings'));

    expect(alertSpy).toHaveBeenCalledWith(
      'Delete this account?',
      expect.stringContaining('HDFC Savings'),
      expect.anything()
    );
    expect(api.remove).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('removes the account once the destructive confirmation is pressed', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    renderScreen();
    await screen.findByText('HDFC Savings');
    api.list.mockResolvedValue([]);

    fireEvent.press(screen.getByLabelText('Delete HDFC Savings'));
    await confirmLastAlert(alertSpy, 'Delete');
    await settle();

    expect(api.remove).toHaveBeenCalledWith('a-1');
    expect(await screen.findByText(/Import a statement or add one manually/)).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('shows an error and keeps the account listed when deletion fails', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    api.remove.mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'This account still has open transfers.' } },
      })
    );
    renderScreen();
    await screen.findByText('HDFC Savings');

    fireEvent.press(screen.getByLabelText('Delete HDFC Savings'));
    await confirmLastAlert(alertSpy, 'Delete');
    await settle();

    expect(await screen.findByText('This account still has open transfers.')).toBeTruthy();
    expect(screen.getByText('HDFC Savings')).toBeTruthy();
    alertSpy.mockRestore();
  });
});
