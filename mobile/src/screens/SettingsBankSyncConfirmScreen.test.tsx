import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsBankSyncConfirmScreen } from './SettingsBankSyncConfirmScreen';
import { accountsApi, accountAggregatorApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  accountAggregatorApi: { confirmExistingAccount: jest.fn(), confirmNewAccount: jest.fn() },
}));

const mockGoBack = jest.fn();
const routeProp = { key: 'k', name: 'SettingsBankSyncConfirm' as const, params: { linkId: 'l1' } };

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidateSpy = jest.spyOn(queryClient, 'invalidateQueries');
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SettingsBankSyncConfirmScreen
          route={routeProp as never}
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
});
