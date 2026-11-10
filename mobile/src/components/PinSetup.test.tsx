import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import { PinSetup } from './PinSetup';
import * as pinLock from '../lib/pinLock';
import * as appLock from '../lib/appLock';
import { ThemeProvider } from '../theme';

jest.mock('expo-crypto', () => require('../test/expoCryptoNodeMock'));

const mocked = LocalAuthentication as jest.Mocked<typeof LocalAuthentication>;

function renderSetup() {
  const onDone = jest.fn();
  render(
    <ThemeProvider>
      <PinSetup onDone={onDone} />
    </ThemeProvider>
  );
  return { onDone };
}

async function type(pin: string) {
  for (const d of pin) {
    await act(async () => {
      fireEvent.press(screen.getByLabelText(d));
    });
  }
}

describe('PinSetup', () => {
  it('asks for the PIN twice and saves it', async () => {
    const { onDone } = renderSetup();

    expect(screen.getByText('Set a 4-digit PIN')).toBeTruthy();
    await type('4821');
    expect(await screen.findByText('Enter it again')).toBeTruthy();
    await type('4821');

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(await pinLock.checkPin('4821')).toEqual({ ok: true });
  });

  it('starts over when the two PINs differ, and saves nothing', async () => {
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4822');

    expect(await screen.findByText("The PINs didn't match. Try again.")).toBeTruthy();
    expect(screen.getByText('Set a 4-digit PIN')).toBeTruthy();
    expect(await pinLock.hasPin()).toBe(false);
    expect(onDone).not.toHaveBeenCalled();
  });

  it('offers Face ID or fingerprint when the phone has it, and turns it on after a scan', async () => {
    mocked.isEnrolledAsync.mockResolvedValue(true);
    mocked.authenticateAsync.mockResolvedValueOnce({ success: true } as never);
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4821');

    expect(await screen.findByText('Unlock with Face ID or fingerprint?')).toBeTruthy();
    expect(onDone).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.press(screen.getByText('Turn on'));
    });

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(await appLock.isEnabled()).toBe(true);
  });

  it('leaves biometrics off with Not now', async () => {
    mocked.isEnrolledAsync.mockResolvedValue(true);
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4821');
    await screen.findByText('Unlock with Face ID or fingerprint?');
    await act(async () => {
      fireEvent.press(screen.getByText('Not now'));
    });

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(await appLock.isEnabled()).toBe(false);
  });

  it('leaves biometrics off when the scan fails, and still finishes', async () => {
    mocked.isEnrolledAsync.mockResolvedValue(true);
    mocked.authenticateAsync.mockResolvedValueOnce({ success: false } as never);
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4821');
    await screen.findByText('Unlock with Face ID or fingerprint?');
    await act(async () => {
      fireEvent.press(screen.getByText('Turn on'));
    });

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(await appLock.isEnabled()).toBe(false);
  });

  it('skips the offer for someone who already had the biometric App Lock on', async () => {
    mocked.isEnrolledAsync.mockResolvedValue(true);
    await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4821');

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Unlock with Face ID or fingerprint?')).toBeNull();
    expect(await appLock.isEnabled()).toBe(true);
  });

  it('skips the offer on a phone with no biometrics', async () => {
    mocked.isEnrolledAsync.mockResolvedValue(false);
    const { onDone } = renderSetup();

    await type('4821');
    await screen.findByText('Enter it again');
    await type('4821');

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Unlock with Face ID or fingerprint?')).toBeNull();
  });
});
