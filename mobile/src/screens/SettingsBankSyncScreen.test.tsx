import { render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsBankSyncScreen } from './SettingsBankSyncScreen';
import { accountAggregatorApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  accountAggregatorApi: { list: jest.fn(), initiate: jest.fn(), disconnect: jest.fn() },
}));
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsBankSyncScreen /></ThemeProvider>
    </QueryClientProvider>
  );
}

test('shows a linked account and pushes the confirm screen for a pending one', async () => {
  (accountAggregatorApi.list as jest.Mock).mockResolvedValue([
    { id: 'l1', fiType: 'DEPOSIT', status: 'PENDING_ACCOUNT_CONFIRMATION', consentExpiresAt: null, lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
  ]);
  renderScreen();
  await screen.findByText('Bank Account · PENDING ACCOUNT CONFIRMATION');
  fireEvent.press(screen.getByText('Confirm Account'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsBankSyncConfirm', { linkId: 'l1' });
});

test('empty state offers Connect a Bank Account', async () => {
  (accountAggregatorApi.list as jest.Mock).mockResolvedValue([]);
  renderScreen();
  expect(await screen.findByText('No bank accounts linked yet.')).toBeTruthy();
  expect(screen.getByText('Connect a Bank Account')).toBeTruthy();
});

test('a load failure shows an error, not the empty-state copy', async () => {
  (accountAggregatorApi.list as jest.Mock).mockRejectedValue(new Error('network down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your linked bank accounts — please try again later.")).toBeTruthy();
  expect(screen.queryByText('No bank accounts linked yet.')).toBeNull();
});
