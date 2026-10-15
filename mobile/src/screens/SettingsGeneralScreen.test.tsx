import { act, render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsGeneralScreen } from './SettingsGeneralScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';
import * as SecureStore from 'expo-secure-store';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), update: jest.fn() },
  onboardingApi: { reset: jest.fn().mockResolvedValue(undefined) },
}));
// Self-contained, with its own fetch -- covered by its own test file.
jest.mock('./settings/NotificationPreferencesSection', () => ({ NotificationPreferencesSection: () => null }));
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ setOnboardingCompleted: jest.fn() }) }));

const user = userApi as jest.Mocked<typeof userApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsGeneralScreen /></ThemeProvider>
    </QueryClientProvider>
  );
  return { ...utils, queryClient };
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
  await waitFor(() => expectHaptics({ success: 1 }));
});

test('a save the server refuses gives one error', async () => {
  user.get.mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
  user.update.mockRejectedValueOnce(new Error('down'));
  renderScreen();
  fireEvent.changeText(await screen.findByLabelText('Low balance alert'), '5000');
  fireEvent.press(screen.getByText('Save preferences'));
  expect(await screen.findByText('Could not save your preferences.')).toBeTruthy();
  expectHaptics({ error: 1 });
});

test('an alert amount that is not a number gives one warning and saves nothing', async () => {
  user.get.mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
  user.update.mockClear();
  renderScreen();
  fireEvent.changeText(await screen.findByLabelText('Low balance alert'), 'abc');
  fireEvent.press(screen.getByText('Save preferences'));
  expect(await screen.findByText('Low balance alert must be a number greater than zero.')).toBeTruthy();
  expect(user.update).not.toHaveBeenCalled();
  expectHaptics({ warning: 1 });
});

test('re-reads the profile after saving, which is how the change watch learns the edit was our own', async () => {
  user.get.mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
  user.update.mockResolvedValue({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' } as never);
  const { queryClient } = renderScreen();
  const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
  const input = await screen.findByLabelText('Low balance alert');
  fireEvent.changeText(input, '5000');
  fireEvent.press(screen.getByText('Save preferences'));
  await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['user-settings'] }));
});

describe('SettingsGeneralScreen first load', () => {
  beforeEach(() => user.get.mockReset());

  it('shows the general settings skeleton, not a spinner, on first load', () => {
    user.get.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('settings-general-skeleton')).toBeTruthy();
  });

  it('after a failed first load, Try again refetches the settings', async () => {
    user.get.mockRejectedValueOnce(new Error('down'));
    renderScreen();
    expect(await screen.findByText(/Couldn.t load your settings/)).toBeTruthy();

    user.get.mockReturnValueOnce(never());
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByTestId('settings-general-skeleton')).toBeTruthy();
  });
});

test('a failed refetch keeps showing the preferences already loaded', async () => {
  user.get.mockReset()
    .mockResolvedValueOnce({
      email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
      phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    } as never)
    .mockRejectedValueOnce(new Error('down'));
  const { queryClient } = renderScreen();
  await screen.findByText('Low balance alert');
  await act(() => queryClient.refetchQueries({ queryKey: ['user-settings'] }));
  // React Query delivers the result on a setTimeout(0) batch; let it land before asserting.
  await act(() => new Promise((resolve) => setTimeout(resolve, 10)));
  expect(queryClient.getQueryState(['user-settings'])?.status).toBe('error');
  expect(screen.getByText('Low balance alert')).toBeTruthy();
  expect(screen.queryByText(/Couldn.t load your settings/)).toBeNull();
});

function loaded() {
  user.get.mockReset().mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
}

describe('Haptics switch', () => {
  beforeEach(loaded);

  it('is on by default and turning it off fires nothing and saves "off"', async () => {
    renderScreen();
    const sw = await screen.findByLabelText('Haptics');
    expect(sw.props.value).toBe(true);
    fireEvent(sw, 'valueChange', false);
    expectHaptics({});
    await act(async () => {});
    expect((SecureStore as unknown as { __store: Map<string, string> }).__store.get('finora_haptics')).toBe('off');
  });

  it('turning it on fires exactly one selection', async () => {
    __setHapticsStateForTests('off');
    renderScreen();
    fireEvent(await screen.findByLabelText('Haptics'), 'valueChange', true);
    expectHaptics({ selection: 1 });
  });
});

describe('Theme segment', () => {
  beforeEach(loaded);

  it('fires one selection when the theme changes, none when the chosen one is tapped', async () => {
    renderScreen();
    fireEvent.press(await screen.findByLabelText('System theme'));
    expectHaptics({});
    fireEvent.press(screen.getByLabelText('Dark theme'));
    expectHaptics({ selection: 1 });
  });
});
