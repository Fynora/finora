import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsGeneralScreen } from './SettingsGeneralScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), update: jest.fn() },
  onboardingApi: { reset: jest.fn().mockResolvedValue(undefined) },
}));
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ setOnboardingCompleted: jest.fn() }) }));

const user = userApi as jest.Mocked<typeof userApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsGeneralScreen /></ThemeProvider>
    </QueryClientProvider>
  );
}

test('shows an error message, not a blank screen, when the account fails to load', async () => {
  user.get.mockRejectedValue(new Error('network down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your settings — please try again later.")).toBeTruthy();
});

test('saves the low balance threshold', async () => {
  user.get.mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
  user.update.mockResolvedValue({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' } as never);
  renderScreen();
  const input = await screen.findByLabelText('Low balance alert');
  fireEvent.changeText(input, '5000');
  fireEvent.press(screen.getByText('Save preferences'));
  await waitFor(() => expect(user.update).toHaveBeenCalledWith({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' }));
});
