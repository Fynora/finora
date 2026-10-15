import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsBankSyncConfirmScreen } from './SettingsBankSyncConfirmScreen';
import { accountsApi, accountAggregatorApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import { expectHaptics } from '../test/haptics';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  accountAggregatorApi: { confirmExistingAccount: jest.fn(), confirmNewAccount: jest.fn() },
}));

const mockGoBack = jest.fn();
const routeProp = { key: 'k', name: 'SettingsBankSyncConfirm' as const, params: { linkId: 'l1' } };

function renderScreen(params: { linkId?: string } = routeProp.params) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateSpy = jest.spyOn(queryClient, 'invalidateQueries');
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SettingsBankSyncConfirmScreen
          route={{ ...routeProp, params } as never}
          navigation={{ goBack: mockGoBack } as never}
        />
      </ThemeProvider>
    </QueryClientProvider>
  );
  return { ...rendered, invalidateSpy };
}

test('confirming an existing account calls confirmExistingAccount with the selected id and refreshes the linked-accounts list', async () => {
  (accountsApi.list as jest.Mock).mockResolvedValue([
    { id: 'acc1', name: 'HDFC Savings', bank: { shortName: 'HDFC' }, accountNumberMasked: '••1234' },
  ]);
  (accountAggregatorApi.confirmExistingAccount as jest.Mock).mockResolvedValue(undefined);
  const { invalidateSpy } = renderScreen();
  await screen.findByText('HDFC Savings · HDFC ••1234');
  fireEvent.press(screen.getByText('Yes, this is my account'));
  await waitFor(() => expect(accountAggregatorApi.confirmExistingAccount).toHaveBeenCalledWith('l1', 'acc1'));
  // Bug found in a fresh review pass: without this, SettingsBankSyncScreen (still mounted
  // underneath, per native-stack) kept showing the pre-confirmation status after goBack().
  expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['aa-links'] });
  expect(mockGoBack).toHaveBeenCalled();
  expectHaptics({ success: 1 });
});

test('a confirmation the server refuses gives one error, for either choice', async () => {
  (accountsApi.list as jest.Mock).mockResolvedValue([
    { id: 'acc1', name: 'HDFC Savings', bank: { shortName: 'HDFC' }, accountNumberMasked: '••1234' },
  ]);
  (accountAggregatorApi.confirmExistingAccount as jest.Mock).mockRejectedValueOnce(new Error('down'));
  (accountAggregatorApi.confirmNewAccount as jest.Mock).mockRejectedValueOnce(new Error('down'));
  renderScreen();
  await screen.findByText('HDFC Savings · HDFC ••1234');

  fireEvent.press(screen.getByText('Yes, this is my account'));
  expect(await screen.findByText("Couldn't confirm this account.")).toBeTruthy();
  expectHaptics({ error: 1 });

  fireEvent.press(screen.getByText('This is a different/new account'));
  expect(await screen.findByText("Couldn't set this up as a new account.")).toBeTruthy();
  expectHaptics({ error: 2 });
});

test('setting it up as a new account gives one success', async () => {
  (accountsApi.list as jest.Mock).mockResolvedValue([]);
  (accountAggregatorApi.confirmNewAccount as jest.Mock).mockResolvedValue(undefined);
  renderScreen();

  fireEvent.press(await screen.findByText('This is a different/new account'));
  await waitFor(() => expect(mockGoBack).toHaveBeenCalled());
  expectHaptics({ success: 1 });
});

test('shows a recoverable message instead of crashing when linkId is missing from route.params', () => {
  // Self-contained, not relying on the mock state a preceding test leaves behind -- this needs
  // to hold whether or not that test actually ran first (e.g. under a -t filter).
  (accountsApi.list as jest.Mock).mockResolvedValue([]);
  renderScreen({});

  expect(screen.getByText(/link no longer available/i)).toBeTruthy();
  fireEvent.press(screen.getByText('Go Back'));
  expect(mockGoBack).toHaveBeenCalled();
});
