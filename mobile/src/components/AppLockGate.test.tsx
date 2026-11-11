import { useEffect, useState } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AppState, BackHandler, Keyboard, Text, type AppStateStatus } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as appLock from '../lib/appLock';
import * as pinLock from '../lib/pinLock';
import { AppLockGate, RELOCK_AFTER_MS } from './AppLockGate';
import { AppModal, LaunchCoveredProvider } from './AppModal';
import { AppAlert, getCurrentAppAlert, __resetAppAlertForTests } from '../lib/appAlert';
import { AppBanner, __resetAppBannerForTests } from '../lib/appBanner';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { ThemeProvider } from '../theme';
import App from '../../App';
import { setLaunchAnimationPlayedForTests } from './LaunchAnimation';

// Real SHA-256 for the PIN -- see test/expoCryptoNodeMock.ts.
jest.mock('expo-crypto', () => require('../test/expoCryptoNodeMock'));

// Isolates the App mount test to App's own composition rather than the whole navigation tree.
jest.mock('../navigation/RootNavigator', () => {
  const { Text: RNText } = require('react-native');
  return { RootNavigator: () => <RNText>navigator</RNText> };
});

// AuthProvider calls configureRevenueCat() for a restored session; the real package cannot load
// under Jest and has nothing to do with the lock.
jest.mock('../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.MockedFunction<
  typeof LocalAuthentication.isEnrolledAsync
>;

const LOCK_TEXT = 'Enter your Fynora PIN';
const PIN = '4821';

/** A saved session, the way AuthProvider's bootstrap reads it. */
async function signIn() {
  await SecureStore.setItemAsync('finora_token', 'access-token');
  await SecureStore.setItemAsync('finora_refresh_token', 'refresh-token');
  await SecureStore.setItemAsync('finora_email', 'someone@example.com');
}

async function signInWithPin({ biometrics = false } = {}) {
  await signIn();
  await pinLock.setPin(PIN);
  if (biometrics) await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
}

/**
 * What the server says to the cold-start session check (verifySession, one /auth/refresh). Driven
 * through the real client by replacing the refresh instance's transport, so the real 'ended' path
 * -- clearing the session through AuthProvider's callback -- runs as it would on a phone.
 */
function serverSays(answer: 'alive' | 'ended' | 'offline') {
  const { rawApi, __setRenewRetryDelayForTests } = require('../api/client') as typeof import('../api/client');
  __setRenewRetryDelayForTests(0);
  // 'offline' is the phone having no internet (NetInfo), so the refresh is known not to have reached
  // the server and the session is kept -- see renewSession in api/client.ts.
  const netInfo = require('@react-native-community/netinfo').default as { fetch?: () => Promise<unknown> };
  netInfo.fetch = async () => ({ isConnected: answer !== 'offline', isInternetReachable: answer !== 'offline' });
  rawApi.defaults.adapter = ((config: unknown) => {
    if (answer === 'alive') {
      return Promise.resolve({
        config, status: 200, statusText: 'OK', headers: {},
        data: { success: true, data: { token: 'access-token-2', refreshToken: 'refresh-token-2' } },
      });
    }
    if (answer === 'ended') {
      return Promise.reject(Object.assign(new Error('HTTP 401'), {
        config, response: { status: 401, data: { message: 'x', errorCode: 'AUTH_005' } },
      }));
    }
    return Promise.reject(Object.assign(new Error('Network Error'), { config }));
  }) as never;
}

async function typePin(pin: string) {
  for (const d of pin) {
    await act(async () => {
      fireEvent.press(screen.getByLabelText(d));
    });
  }
}

let appStateListeners: ((status: AppStateStatus) => void)[] = [];
let now = 0;

async function fireAppState(status: AppStateStatus) {
  await act(async () => {
    appStateListeners.forEach((listener) => listener(status));
  });
}

/** Leaves the app for `ms`, then comes back, through the same listeners a real AppState fans out to. */
async function awayFor(ms: number) {
  await fireAppState('background');
  now += ms;
  await fireAppState('active');
}

let signOutFromOutside: (() => void) | undefined;
let signInFromLogin: ((identifier: string, password: string) => Promise<boolean>) | undefined;
/** Sits beside the gate inside AuthProvider, the way a failed refresh reaches logout from outside,
 *  and the way the Login screen signs in. */
function LogoutHandle() {
  const { logout, login } = useAuth();
  useEffect(() => {
    signOutFromOutside = logout;
    signInFromLogin = login;
  }, [logout, login]);
  return null;
}

/** A password sign-in in this run of the app, answered by a fake server. */
async function signInNow() {
  const { api } = require('../api/client') as typeof import('../api/client');
  api.defaults.adapter = ((config: unknown) => Promise.resolve({
    config, status: 200, statusText: 'OK', headers: {},
    data: {
      success: true,
      data: {
        token: 'fresh-token', refreshToken: 'fresh-refresh', email: 'someone@example.com',
        fullName: 'Someone', phoneVerified: true, id: 'user-1', onboardingCompleted: true,
      },
    },
  })) as never;
  await act(async () => {
    await signInFromLogin!('someone@example.com', 'password');
  });
}

function renderGate(children = <Text>protected content</Text>, { launching = false } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const tree = (isLaunching: boolean) => (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <LaunchCoveredProvider value={isLaunching}>
          <AuthProvider>
            <LogoutHandle />
            <AppLockGate>{children}</AppLockGate>
          </AuthProvider>
        </LaunchCoveredProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
  const result = render(tree(launching));
  return { ...result, setLaunching: (value: boolean) => result.rerender(tree(value)) };
}

let mounts = 0;
function Counter() {
  const [n, setN] = useState(0);
  useEffect(() => {
    mounts += 1;
  }, []);
  return <Text onPress={() => setN(n + 1)}>{`typed ${n}`}</Text>;
}

beforeEach(() => {
  appStateListeners = [];
  AppState.currentState = 'active';
  jest.spyOn(AppState, 'addEventListener').mockImplementation((event, listener) => {
    if (event !== 'change') return { remove: jest.fn() };
    const entry = listener as (status: AppStateStatus) => void;
    appStateListeners.push(entry);
    return {
      remove: jest.fn(() => {
        appStateListeners = appStateListeners.filter((l) => l !== entry);
      }),
    };
  });
  // A realistic clock (not a small number): appLock's grace windows compare against real stamps.
  now = Date.now() + 10 * 60_000;
  jest.spyOn(Date, 'now').mockImplementation(() => now);
  appLock.__resetAuthenticatingStateForTests();
  appLock.__resetSharingStateForTests();
  appLock.__resetLockedFlagForTests();
  appLock.__resetEnabledCacheForTests();
  __resetAppAlertForTests();
  __resetAppBannerForTests();
  signOutFromOutside = undefined;
  serverSays('alive');
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('AppLockGate', () => {
  it('shows the app with no lock while signed out', async () => {
    renderGate();
    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();
  });

  it('requires setting a PIN before showing the app', async () => {
    await signIn();
    renderGate();

    expect(await screen.findByText('Set a 4-digit PIN')).toBeTruthy();
    expect(screen.queryByText('protected content', { includeHiddenElements: true })).toBeNull();
    expect(appLock.isLocked()).toBe(true);

    await typePin(PIN);
    await screen.findByText('Enter it again');
    await typePin(PIN);

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(appLock.isLocked()).toBe(false);
    expect(await pinLock.checkPin(PIN)).toEqual({ ok: true });
  });

  // Signing out clears the PIN, but fire-and-forget: if that clear failed, the PIN left behind is
  // the previous session's -- maybe another account's. A sign-in always sets its own.
  it('asks a fresh sign-in to set a PIN even when an old one was left behind', async () => {
    await pinLock.setPin('1111');
    renderGate();
    expect(await screen.findByText('protected content')).toBeTruthy();

    await signInNow();

    expect(await screen.findByText('Set a 4-digit PIN')).toBeTruthy();
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();
    await typePin(PIN);
    await screen.findByText('Enter it again');
    await typePin(PIN);
    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(await pinLock.checkPin(PIN)).toEqual({ ok: true });
  });

  it('locks a saved session and opens with the right PIN', async () => {
    await signInWithPin();
    renderGate();

    expect(await screen.findByText(LOCK_TEXT)).toBeTruthy();
    expect(screen.queryByText('protected content', { includeHiddenElements: true })).toBeNull();
    expect(appLock.isLocked()).toBe(true);

    await typePin(PIN);

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(appLock.isLocked()).toBe(false);
  });

  it('auto-prompts for Face ID when it is on, and opens on success', async () => {
    await signInWithPin({ biometrics: true });
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    renderGate();

    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('protected content')).toBeTruthy();
  });

  it('stays on the PIN pad when Face ID fails, and the biometric key retries', async () => {
    await signInWithPin({ biometrics: true });
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'user_cancel' });
    renderGate();

    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    expect(screen.getByText(LOCK_TEXT)).toBeTruthy();

    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await act(async () => {
      fireEvent.press(screen.getByLabelText('Unlock with Face ID or fingerprint'));
    });
    expect(await screen.findByText('protected content')).toBeTruthy();
  });

  it('has no biometric key and never prompts when biometrics are off', async () => {
    await signInWithPin();
    renderGate();

    await screen.findByText(LOCK_TEXT);
    await act(async () => {});
    expect(mockedAuthenticateAsync).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Unlock with Face ID or fingerprint')).toBeNull();
  });

  describe('the cold-start session check', () => {
    it('goes to sign-in without prompting when the server says the session ended', async () => {
      await signInWithPin({ biometrics: true });
      serverSays('ended');
      renderGate();

      // Signed out: the app tree (here, the sign-in screen's stand-in) with no lock and no prompt.
      expect(await screen.findByText('protected content')).toBeTruthy();
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      await act(async () => {});
      expect(mockedAuthenticateAsync).not.toHaveBeenCalled();
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });

    it('asks for the PIN when the session check cannot reach the server', async () => {
      await signInWithPin({ biometrics: true });
      serverSays('offline');
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'user_cancel' });
      renderGate();

      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      expect(screen.getByText(LOCK_TEXT)).toBeTruthy();
      await typePin(PIN);
      expect(await screen.findByText('protected content')).toBeTruthy();
      expect(await SecureStore.getItemAsync('finora_refresh_token')).toBe('refresh-token');
    });

    it('holds the Face ID prompt until the server has answered', async () => {
      await signInWithPin({ biometrics: true });
      let answer: (() => void) | undefined;
      const { rawApi } = require('../api/client') as typeof import('../api/client');
      rawApi.defaults.adapter = ((config: unknown) => new Promise((resolve) => {
        answer = () => resolve({
          config, status: 200, statusText: 'OK', headers: {},
          data: { success: true, data: { token: 'a2', refreshToken: 'r2' } },
        });
      })) as never;
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate();

      await screen.findByText(LOCK_TEXT);
      await act(async () => {});
      expect(mockedAuthenticateAsync).not.toHaveBeenCalled();

      await act(async () => answer?.());
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    });
  });

  describe('the launch animation', () => {
    it('locks at once under the launch animation but holds the prompt until it has gone', async () => {
      await signInWithPin({ biometrics: true });
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      const { setLaunching } = renderGate(undefined, { launching: true });

      await screen.findByText(LOCK_TEXT, { includeHiddenElements: true });
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
      });
      expect(mockedAuthenticateAsync).not.toHaveBeenCalled();

      setLaunching(false);
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      expect(await screen.findByText('protected content')).toBeTruthy();
    });

    // The bug Sid reported (2026-11-10): Face ID asked for over the sign-in screen after the
    // session had ended while the launch animation was still up.
    it('does not prompt when the session ends while the launch animation is up', async () => {
      await signInWithPin({ biometrics: true });
      const { setLaunching } = renderGate(undefined, { launching: true });
      await screen.findByText(LOCK_TEXT, { includeHiddenElements: true });

      await act(async () => {
        signOutFromOutside?.();
      });
      setLaunching(false);
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 50));
      });

      expect(mockedAuthenticateAsync).not.toHaveBeenCalled();
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(screen.getByText('protected content')).toBeTruthy();
    });
  });

  describe('locking again after time away', () => {
    async function openApp() {
      await signInWithPin();
      renderGate(<Counter />);
      await screen.findByText(LOCK_TEXT);
      await typePin(PIN);
      await screen.findByText('typed 0');
    }

    it('does not lock after 59 seconds away', async () => {
      await openApp();
      await awayFor(59_000);
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(screen.getByText('typed 0')).toBeTruthy();
    });

    it('locks after 61 seconds away', async () => {
      await openApp();
      await awayFor(61_000);
      expect(await screen.findByText(LOCK_TEXT)).toBeTruthy();
      expect(appLock.isLocked()).toBe(true);
      expect(RELOCK_AFTER_MS).toBe(60_000);
    });

    it('counts from the first step out of the foreground (inactive, then background)', async () => {
      await openApp();
      await fireAppState('inactive');
      now += 40_000;
      await fireAppState('background');
      now += 30_000;
      await fireAppState('active');
      expect(await screen.findByText(LOCK_TEXT)).toBeTruthy();
    });

    it('prompts for Face ID on the relock if it was turned on mid-session', async () => {
      await openApp();
      await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });

      await awayFor(61_000);

      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
    });

    it('does not prompt for Face ID on the relock after it was turned off mid-session', async () => {
      await signInWithPin({ biometrics: true });
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate(<Counter />);
      // Waits for the Face ID unlock itself: while the saved session is still loading, the app tree
      // is briefly mounted signed-out, so finding its text alone proves nothing.
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(appLock.isLocked()).toBe(false));
      await screen.findByText('typed 0');
      await SecureStore.deleteItemAsync('finora_app_lock_enabled');
      // A real Keychain/Keystore read takes longer than one tick; the fake answers at once, which
      // would hide the window where the lock still believes Face ID is on.
      const real = (SecureStore.getItemAsync as jest.Mock).getMockImplementation()!;
      (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (k: string) => {
        if (k === 'finora_app_lock_enabled') await new Promise((r) => setTimeout(r, 10));
        return real(k);
      });

      try {
        await awayFor(61_000);
        await screen.findByText(LOCK_TEXT);
        await act(async () => {
          await new Promise((resolve) => setTimeout(resolve, 40));
        });

        expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
        expect(screen.queryByLabelText('Unlock with Face ID or fingerprint')).toBeNull();
      } finally {
        (SecureStore.getItemAsync as jest.Mock).mockImplementation(real);
      }
    });

    it('does not lock while a share sheet is open, however long it stays open', async () => {
      await openApp();
      let finishShare: (() => void) | undefined;
      const share = appLock.withShareSuppression(() => new Promise<void>((r) => { finishShare = r; }));

      await awayFor(5 * 60_000);
      finishShare?.();
      await share;

      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(screen.getByText('typed 0')).toBeTruthy();
    });

    it('does not lock when the Face ID sheet itself sends the app to the background', async () => {
      await signInWithPin({ biometrics: true });
      let finishAuth: ((r: LocalAuthentication.LocalAuthenticationResult) => void) | undefined;
      mockedAuthenticateAsync.mockImplementationOnce(() => new Promise((r) => { finishAuth = r; }));
      renderGate();
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));

      await fireAppState('inactive');
      await act(async () => finishAuth?.({ success: true }));
      now += 2 * 60_000;
      await fireAppState('active');

      expect(await screen.findByText('protected content')).toBeTruthy();
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
    });
  });

  describe('wrong PINs', () => {
    it('says the PIN was wrong, and shows tries left once two or fewer remain', async () => {
      await signInWithPin();
      renderGate();
      await screen.findByText(LOCK_TEXT);

      await typePin('0000');
      expect(await screen.findByText('Wrong PIN. Try again.')).toBeTruthy();
      await typePin('0000');
      expect(await screen.findByText('Wrong PIN. Try again.')).toBeTruthy();
      await typePin('0000');
      expect(await screen.findByText('Wrong PIN. 2 tries left.')).toBeTruthy();
      await typePin('0000');
      expect(await screen.findByText('Wrong PIN. 1 try left.')).toBeTruthy();
    });

    it('signs out after the fifth wrong PIN, clearing the PIN and biometric choice', async () => {
      await signInWithPin({ biometrics: true });
      mockedAuthenticateAsync.mockResolvedValue({ success: false, error: 'user_cancel' });
      renderGate();
      await screen.findByText(LOCK_TEXT);

      for (let i = 0; i < 5; i++) await typePin('0000');

      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
      await waitFor(async () => expect(await pinLock.hasPin()).toBe(false));
      expect(await appLock.isEnabled()).toBe(false);
    });

    it('stays locked and says so when the PIN cannot be checked', async () => {
      await signInWithPin();
      renderGate();
      await screen.findByText(LOCK_TEXT);
      const real = (SecureStore.getItemAsync as jest.Mock).getMockImplementation()!;
      (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (k: string) => {
        if (k === 'finora_pin_hash') throw new Error('keystore busy');
        return real(k);
      });

      try {
        await typePin(PIN);

        expect(await screen.findByText("Couldn't check your PIN. Try again.")).toBeTruthy();
        expect(screen.getByText(LOCK_TEXT)).toBeTruthy();
      } finally {
        (SecureStore.getItemAsync as jest.Mock).mockImplementation(real);
      }
      await typePin(PIN);
      expect(await screen.findByText('protected content')).toBeTruthy();
    });

    it('Forgot PIN signs out after a confirm', async () => {
      await signInWithPin();
      renderGate();
      await screen.findByText(LOCK_TEXT);

      fireEvent.press(screen.getByText('Forgot PIN?'));
      expect(screen.getByText("You'll be signed out on this phone. Sign in again, then set a new PIN.")).toBeTruthy();
      fireEvent.press(screen.getByText('Cancel'));
      expect(screen.getByText(LOCK_TEXT)).toBeTruthy();

      fireEvent.press(screen.getByText('Forgot PIN?'));
      await act(async () => {
        fireEvent.press(screen.getByText('Sign out'));
      });

      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });
  });

  describe('once opened, the lock screen covers the app instead of replacing it', () => {
    async function openWithState() {
      await signInWithPin();
      mounts = 0;
      renderGate(<Counter />);
      await screen.findByText(LOCK_TEXT);
      await typePin(PIN);
      await screen.findByText('typed 0');
      fireEvent.press(screen.getByText('typed 0'));
      expect(screen.getByText('typed 1')).toBeTruthy();
    }

    async function relock() {
      await awayFor(RELOCK_AFTER_MS + 1000);
      await screen.findByText(LOCK_TEXT);
    }

    it('keeps the screen and its state through a relock and unlock', async () => {
      await openWithState();
      const mountsBefore = mounts;

      await relock();
      expect(screen.queryByText('typed 1')).toBeNull();
      expect(screen.getByText('typed 1', { includeHiddenElements: true })).toBeTruthy();

      await typePin(PIN);
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
      expect(screen.getByText('typed 1')).toBeTruthy();
      expect(mounts).toBe(mountsBefore);
    });

    it('does not leave an open sheet visible over the lock screen, and restores it after unlock', async () => {
      function SheetOwner() {
        return (
          <AppModal visible>
            <Text>sheet body</Text>
          </AppModal>
        );
      }
      await signInWithPin();
      renderGate(<SheetOwner />);
      await screen.findByText(LOCK_TEXT);
      await typePin(PIN);
      await screen.findByText('sheet body');

      await relock();
      expect(screen.queryByText('sheet body', { includeHiddenElements: true })).toBeNull();

      await typePin(PIN);
      expect(await screen.findByText('sheet body')).toBeTruthy();
    });

    it('hides an open alert while locked and shows it again after unlock', async () => {
      await openWithState();
      act(() => {
        AppAlert.alert('Delete this account?', '"Savings" will be removed.');
      });
      expect(screen.getByText('Delete this account?')).toBeTruthy();

      await relock();
      expect(screen.queryByText('Delete this account?')).toBeNull();

      await typePin(PIN);
      expect(await screen.findByText('Delete this account?')).toBeTruthy();
    });

    it('hides a campaign banner behind the lock screen', async () => {
      await openWithState();
      act(() => {
        AppBanner.show('Welcome to Fynora', 'Welcome to Fynora body.');
      });
      expect(screen.getByText('Welcome to Fynora body.')).toBeTruthy();

      await relock();
      expect(screen.queryByText('Welcome to Fynora body.')).toBeNull();
    });

    it('drops the keyboard when it locks', async () => {
      await openWithState();
      const dismiss = jest.spyOn(Keyboard, 'dismiss');
      await relock();
      expect(dismiss).toHaveBeenCalled();
    });

    it('takes over the Android back button while locked, and leaves an alert underneath untouched', async () => {
      const exitApp = jest.spyOn(BackHandler, 'exitApp').mockImplementation(() => {});
      const handlers: (() => boolean)[] = [];
      jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, listener) => {
        handlers.push(listener as () => boolean);
        return { remove: jest.fn(() => handlers.splice(handlers.indexOf(listener as () => boolean), 1)) };
      });
      const pressBack = () => [...handlers].reverse().some((handler) => handler());
      await openWithState();
      expect(handlers).toHaveLength(0);
      await relock();

      act(() => {
        AppAlert.alert('Delete this account?', undefined, [{ text: 'Cancel' }]);
      });
      act(() => {
        pressBack();
      });

      expect(exitApp).toHaveBeenCalled();
      expect(getCurrentAppAlert()?.title).toBe('Delete this account?');
    });
  });

  it('holds an alert raised before the first unlock until the app is opened', async () => {
    await signInWithPin();
    renderGate();
    await screen.findByText(LOCK_TEXT);

    act(() => {
      AppAlert.alert('Email verified', "You're all set.");
    });
    expect(screen.queryByText('Email verified', { includeHiddenElements: true })).toBeNull();

    await typePin(PIN);
    expect(await screen.findByText('Email verified')).toBeTruthy();
  });

  it('offers Face ID during setup on a phone that has it', async () => {
    mockedIsEnrolled.mockResolvedValue(true);
    await signIn();
    renderGate();
    await screen.findByText('Set a 4-digit PIN');
    await typePin(PIN);
    await screen.findByText('Enter it again');
    await typePin(PIN);

    expect(await screen.findByText('Unlock with Face ID or fingerprint?')).toBeTruthy();
    fireEvent.press(screen.getByText('Not now'));
    expect(await screen.findByText('protected content')).toBeTruthy();
  });
});

describe('the app actually mounts it', () => {
  beforeEach(() => setLaunchAnimationPlayedForTests(true));
  afterEach(() => setLaunchAnimationPlayedForTests(false));

  it('locks the real App tree for a signed-in session', async () => {
    await signInWithPin();
    render(<App />);

    await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    expect(screen.queryByText('navigator')).toBeNull();
  });
});
