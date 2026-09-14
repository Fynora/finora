import { render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsAccountScreen } from './SettingsAccountScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({ userApi: { get: jest.fn() } }));
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ logout: jest.fn() }) }));
jest.mock('./settings/DeactivateAccountSheet', () => ({
  DeactivateAccountSheet: () => {
    const { Text } = require('react-native');
    return <Text>Deactivate sheet open</Text>;
  },
}));
jest.mock('./settings/DeleteAccountSheet', () => ({ DeleteAccountSheet: () => null }));

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SettingsAccountScreen
          route={{ key: 'k', name: 'SettingsAccount', params: undefined } as never}
          navigation={{ navigate: jest.fn() } as never}
        />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

test('opens the Deactivate Account sheet', async () => {
  (userApi.get as jest.Mock).mockResolvedValue({ signInMethod: 'PASSWORD' });
  renderScreen();
  await screen.findAllByText('Deactivate Account');
  fireEvent.press(screen.getAllByText('Deactivate Account')[1]); // [0] is the row title, [1] the button
  expect(await screen.findByText('Deactivate sheet open')).toBeTruthy();
});

test('shows an error message, not an infinite spinner, when the account fails to load', async () => {
  (userApi.get as jest.Mock).mockRejectedValue(new Error('network down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your settings — please try again later.")).toBeTruthy();
});
