import { fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsSecurityScreen } from './SettingsSecurityScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';

jest.mock('../api/endpoints', () => ({ userApi: { get: jest.fn() } }));
jest.mock('./settings/AppLockSection', () => ({ AppLockSection: () => null }));
jest.mock('./settings/DeviceSessionsSection', () => ({ DeviceSessionsSection: () => null }));
// ChangeEmailSheet (rendered by this screen) now imports useAuth for its "Log in instead" nudge
// (#1645), which pulls in AuthContext -> revenueCat.ts -> the real react-native-purchases native
// module -- an ESM-only package Jest's CommonJS transform can't require, crashing this whole
// suite at import time with no test in it ever exercising the sheet. Same mock shape
// ChangeEmailSheet.test.tsx itself already uses.
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ logout: jest.fn() }) }));

const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsSecurityScreen /></ThemeProvider>
    </QueryClientProvider>
  );
}

test('shows the masked phone number once loaded', async () => {
  (userApi.get as jest.Mock).mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', phoneNumber: PHONE, phoneVerified: true,
    passwordChangedAt: null, signInMethod: 'PASSWORD', lowBalanceThreshold: 2000, theme: 'system',
    timezone: 'Asia/Kolkata', createdAt: '2026-01-01T00:00:00Z',
  });
  renderScreen();
  expect(await screen.findByText(MASKED_PHONE)).toBeTruthy();
});

test('shows an error message, not an infinite spinner, when the account fails to load', async () => {
  (userApi.get as jest.Mock).mockRejectedValue(new Error('network down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your settings — please try again later.")).toBeTruthy();
});

describe('SettingsSecurityScreen first load', () => {
  beforeEach(() => jest.mocked(userApi.get).mockReset());

  it('shows the security skeleton, not a spinner, on first load', () => {
    jest.mocked(userApi.get).mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('settings-security-skeleton')).toBeTruthy();
  });

  it('after a failed first load, Try again refetches', async () => {
    jest.mocked(userApi.get).mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

    jest.mocked(userApi.get).mockReturnValueOnce(never());
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('settings-security-skeleton')).toBeTruthy();
  });
});
