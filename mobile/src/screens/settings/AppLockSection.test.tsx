import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLockSection } from './AppLockSection';
import { AuthProvider } from '../../context/AuthContext';
import * as pinLock from '../../lib/pinLock';
import { ThemeProvider } from '../../theme';
import { expectSkeletonNotSpinner, never } from '../../test/loadingStates';

const mockedHasHardware = LocalAuthentication.hasHardwareAsync as jest.MockedFunction<
  typeof LocalAuthentication.hasHardwareAsync
>;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.MockedFunction<
  typeof LocalAuthentication.isEnrolledAsync
>;
const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;

jest.mock('expo-crypto', () => require('../../test/expoCryptoNodeMock'));
jest.mock('../../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

const SWITCH = 'Unlock with Face ID or fingerprint';

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <AppLockSection />
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

async function typePin(pin: string) {
  for (const d of pin) {
    await act(async () => {
      fireEvent.press(screen.getByLabelText(d));
    });
  }
}

describe('AppLockSection', () => {
  // Bug found in review (Track D/D1/D6): isEnabled()'s fail-closed `true` is correct for
  // AppLockGate's own lock-screen decision, but this screen used to paint that same value
  // straight onto the Settings switch -- so a transient SecureStore read failure showed App Lock
  // as ON to a user who never turned it on, with no indication anything had gone wrong.
  it('shows an error instead of guessing at the toggle state when the current setting cannot be read', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    const mockedGetItemAsync = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    mockedGetItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));
    renderSection();

    expect(await screen.findByText(/Couldn.t check whether Face ID or fingerprint unlock is on/i)).toBeTruthy();
    expect(screen.queryByLabelText(SWITCH)).toBeNull();
  });

  it('explains why the toggle is unavailable on unsupported hardware', async () => {
    mockedHasHardware.mockResolvedValueOnce(false);
    mockedIsEnrolled.mockResolvedValueOnce(false);
    renderSection();

    expect(await screen.findByText(/Set up Face ID or a fingerprint on this phone/i)).toBeTruthy();
    expect(screen.queryByLabelText(SWITCH)).toBeNull();
  });

  it('requires a real successful authentication before turning on', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    renderSection();

    const toggle = await screen.findByLabelText(SWITCH);
    expect(toggle.props.value).toBe(false);

    await act(async () => fireEvent(toggle, 'valueChange', true));

    // A failed confirmation must not have persisted the setting.
    expect(toggle.props.value).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
    expect(screen.getByText(/Face ID or fingerprint unlock was not turned on/i)).toBeTruthy();
  });

  it('turns on only after a successful confirmation', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    renderSection();

    const toggle = await screen.findByLabelText(SWITCH);
    await act(async () => fireEvent(toggle, 'valueChange', true));

    await waitFor(async () =>
      expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBe('true')
    );
    expect(toggle.props.value).toBe(true);
  });

  it('turns off without requiring authentication', async () => {
    await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    renderSection();

    const toggle = await screen.findByLabelText(SWITCH);
    expect(toggle.props.value).toBe(true);

    await act(async () => fireEvent(toggle, 'valueChange', false));

    expect(mockedAuthenticateAsync).not.toHaveBeenCalled();
    expect(toggle.props.value).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
  });
});

describe('Change PIN', () => {
  async function openChangePin() {
    await SecureStore.setItemAsync('finora_token', 'access-token');
    await SecureStore.setItemAsync('finora_refresh_token', 'refresh-token');
    await pinLock.setPin('4821');
    renderSection();
    await act(async () => {
      fireEvent.press(await screen.findByText('Change PIN'));
    });
    await screen.findByText('Enter your current PIN');
  }

  it('needs the current PIN, then sets the new one', async () => {
    await openChangePin();

    await typePin('4821');
    await screen.findByText('Set a 4-digit PIN');
    await typePin('7350');
    await screen.findByText('Enter it again');
    await typePin('7350');

    expect(await screen.findByText('PIN changed.')).toBeTruthy();
    expect(await pinLock.checkPin('7350')).toEqual({ ok: true });
    expect(screen.queryByText('Unlock with Face ID or fingerprint?')).toBeNull();
  });

  it('does not change the PIN after a wrong current PIN', async () => {
    await openChangePin();

    await typePin('0000');

    expect(await screen.findByText('Wrong PIN. Try again.')).toBeTruthy();
    expect(screen.queryByText('Set a 4-digit PIN')).toBeNull();
    expect(await pinLock.checkPin('4821')).toEqual({ ok: true });
  });

  it('counts a wrong current PIN toward the 5-try limit, and signs out at the fifth', async () => {
    await openChangePin();

    for (let i = 0; i < 4; i++) await typePin('0000');
    expect(await screen.findByText('Wrong PIN. 1 try left.')).toBeTruthy();
    await typePin('0000');

    await waitFor(async () => expect(await SecureStore.getItemAsync('finora_token')).toBeNull());
  });

  it('can be cancelled', async () => {
    await openChangePin();
    await act(async () => {
      fireEvent.press(screen.getByText('Cancel'));
    });
    await waitFor(() => expect(screen.queryByText('Enter your current PIN')).toBeNull());
  });
});

describe('AppLockSection first load', () => {
  it('shows a skeleton row, not a spinner, while it reads the device', () => {
    mockedHasHardware.mockReturnValue(never());
    renderSection();
    expectSkeletonNotSpinner();
  });

  it('the skeleton row has the two description lines the real row wraps to', () => {
    mockedHasHardware.mockReturnValue(never());
    renderSection();
    // title + 2 description lines + switch
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });
});
