import { act, render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsAccountScreen } from './SettingsAccountScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';

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
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SettingsAccountScreen
          route={{ key: 'k', name: 'SettingsAccount', params: undefined } as never}
          navigation={{ navigate: jest.fn() } as never}
        />
      </ThemeProvider>
    </QueryClientProvider>
  );
  return { ...utils, queryClient };
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

describe('SettingsAccountScreen first load', () => {
  beforeEach(() => jest.mocked(userApi.get).mockReset());

  it('shows the account settings skeleton, not a spinner, on first load', () => {
    jest.mocked(userApi.get).mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('settings-account-skeleton')).toBeTruthy();
  });

  it('after a failed first load, Try again refetches', async () => {
    jest.mocked(userApi.get).mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

    jest.mocked(userApi.get).mockReturnValueOnce(never());
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('settings-account-skeleton')).toBeTruthy();
  });
});

test('a failed refetch keeps showing the account settings already loaded', async () => {
  jest.mocked(userApi.get).mockReset()
    .mockResolvedValueOnce({ signInMethod: 'PASSWORD' } as never)
    .mockRejectedValueOnce(new Error('down'));
  const { queryClient } = renderScreen();
  await screen.findAllByText('Deactivate Account');
  await act(() => queryClient.refetchQueries({ queryKey: ['user-settings'] }));
  // React Query delivers the result on a setTimeout(0) batch; let it land before asserting.
  await act(() => new Promise((resolve) => setTimeout(resolve, 10)));
  expect(queryClient.getQueryState(['user-settings'])?.status).toBe('error');
  expect(screen.getAllByText('Deactivate Account').length).toBeGreaterThan(0);
  expect(screen.queryByText(/Couldn.t load your settings/)).toBeNull();
});
