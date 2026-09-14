import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
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
  return render(
    <ThemeProvider>
      <SettingsBankSyncConfirmScreen
        route={routeProp as never}
        navigation={{ goBack: mockGoBack } as never}
      />
    </ThemeProvider>
  );
}

test('confirming an existing account calls confirmExistingAccount with the selected id', async () => {
  (accountsApi.list as jest.Mock).mockResolvedValue([
    { id: 'acc1', name: 'HDFC Savings', bank: { shortName: 'HDFC' }, accountNumberMasked: '••1234' },
  ]);
  (accountAggregatorApi.confirmExistingAccount as jest.Mock).mockResolvedValue(undefined);
  renderScreen();
  await screen.findByText('HDFC Savings · HDFC ••1234');
  fireEvent.press(screen.getByText('Yes, this is my account'));
  await waitFor(() => expect(accountAggregatorApi.confirmExistingAccount).toHaveBeenCalledWith('l1', 'acc1'));
});
