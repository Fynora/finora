import { Alert, Text } from 'react-native';
import { act, render, waitFor, type RenderAPI } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from './AuthContext';
import { authApi } from '../api/endpoints';
import * as appLock from '../lib/appLock';
import { registerDeviceToken, revokeDeviceToken, subscribeToForegroundMessages } from '../lib/pushRegistration';
import { configureRevenueCat } from '../lib/revenueCat';
import { reportHandledError } from '../lib/monitoring';

jest.mock('../api/endpoints', () => ({
  authApi: {
    login: jest.fn(),
    reactivate: jest.fn(),
    register: jest.fn(),
    google: jest.fn(),
    apple: jest.fn(),
    logout: jest.fn(async () => ({ message: 'ok' })),
  },
}));

// Task 14. Pins the wiring in AuthContext.tsx itself (which hooks/setPhoneVerified()/persist()/
// logout()) call registerDeviceToken()/revokeDeviceToken(), and in what order relative to
// storage -- without this, dropping the phoneVerified gate or moving the revoke call after the
// stored token is cleared would both ship green: the former is a guaranteed 403
// PHONE_VERIFICATION_REQUIRED on every app open (/api/v1/device-tokens is not exempt in the
// backend's PhoneVerificationFilter), the latter a guaranteed 401 that silently leaves the token
// registered server-side. See pushRegistration.test.ts for that module's own behavior in
// isolation; this file only needs to know AuthContext calls it, and when.
jest.mock('../lib/pushRegistration', () => ({
  registerDeviceToken: jest.fn(),
  revokeDeviceToken: jest.fn(),
  // Defaults to a no-op unsubscribe, matching the real function's own "never throws" contract --
  // see the "AuthContext foreground push wiring" describe block below for tests that override this.
  subscribeToForegroundMessages: jest.fn(() => jest.fn()),
}));

// Subscription billing V4 (design spec §2/§6.1 step 1): configureRevenueCat() must run once the
// real Fynora user id is known, whether that's a fresh login/register/etc. or a cold-start
// restore of an already-persisted session -- see AuthContext bootstrap/configureRevenueCat below.
jest.mock('../lib/revenueCat', () => ({
  configureRevenueCat: jest.fn(),
}));

// A missing EXPO_PUBLIC_REVENUECAT_API_KEY makes the real configureRevenueCat() throw
// synchronously -- both call sites in AuthContext.tsx must catch that and report it, not let it
// interrupt session restore or persist(). See the "reports and survives a throwing
// configureRevenueCat" tests below for the regression this guards.
jest.mock('../lib/monitoring', () => ({
  reportHandledError: jest.fn(),
}));

const mockedAuthApi = authApi as jest.Mocked<typeof authApi>;
const mockedRegisterDeviceToken = registerDeviceToken as jest.MockedFunction<typeof registerDeviceToken>;
const mockedRevokeDeviceToken = revokeDeviceToken as jest.MockedFunction<typeof revokeDeviceToken>;
const mockedSubscribeToForegroundMessages = subscribeToForegroundMessages as jest.MockedFunction<typeof subscribeToForegroundMessages>;
const mockedConfigureRevenueCat = configureRevenueCat as jest.MockedFunction<typeof configureRevenueCat>;
const mockedReportHandledError = reportHandledError as jest.MockedFunction<typeof reportHandledError>;

const SESSION = {
  id: 'user-abc-123',
  token: 'access-token',
  refreshToken: 'refresh-token',
  email: 'someone@example.com',
  fullName: 'Some One',
  phoneVerified: true,
  maskedPhone: '+•••••••••210',
  onboardingCompleted: true,
};

/** Renders context state so assertions read against what a screen would actually see. */
function Probe() {
  const { bootstrapping, token, email, phoneVerified, onboardingCompleted } = useAuth();
  return (
    <>
      <Text testID="bootstrapping">{String(bootstrapping)}</Text>
      <Text testID="token">{token ?? 'none'}</Text>
      <Text testID="email">{email ?? 'none'}</Text>
      <Text testID="phoneVerified">{String(phoneVerified)}</Text>
      <Text testID="onboardingCompleted">{String(onboardingCompleted)}</Text>
    </>
  );
}

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return null;
}

function renderAuth(): RenderAPI {
  // AuthProvider reads the QueryClient so logout can clear cached financial data -- see its own
  // comment, and logoutCacheIsolation.test.tsx. App.tsx already nests it this way; wrapping here
  // keeps the harness matching the real composition rather than testing a shape that never ships.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
        <Capture />
      </AuthProvider>
    </QueryClientProvider>
  );
}

/** Waits for the async SecureStore restore to finish. */
async function settle(view: RenderAPI) {
  await waitFor(() => expect(view.getByTestId('bootstrapping')).toHaveTextContent('false'));
}

describe('AuthContext bootstrap', () => {
  /**
   * The reason mobile diverges from web here at all: localStorage reads are synchronous, so the
   * web version seeds state in useState initializers. SecureStore's are not. Without the
   * bootstrapping flag, a cold start renders token === null for a frame and RootNavigator shows
   * Login to an already-signed-in user.
   */
  it('starts in a bootstrapping state rather than reporting signed-out', async () => {
    const view = renderAuth();
    expect(view.getByTestId('bootstrapping')).toHaveTextContent('true');
    await settle(view); // avoid an unawaited state update after the test ends
  });

  it('restores a persisted session', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_email', 'stored@example.com');
    await SecureStore.setItemAsync('finora_phone_verified', 'true');

    const view = renderAuth();
    await settle(view);

    expect(view.getByTestId('token')).toHaveTextContent('stored-token');
    expect(view.getByTestId('email')).toHaveTextContent('stored@example.com');
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('true');
  });

  it('finishes bootstrapping with no stored session', async () => {
    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('token')).toHaveTextContent('none');
    // Opposite default from phoneVerified: a missing value means "not onboarded", not "onboarded".
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');
  });

  it('restores a persisted onboardingCompleted=true', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_onboarding_completed', 'true');

    const view = renderAuth();
    await settle(view);

    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
  });

  it('configures RevenueCat with the restored user id -- a cold start on an already-signed-in device', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_user_id', 'user-restored-456');

    const view = renderAuth();
    await settle(view);

    expect(mockedConfigureRevenueCat).toHaveBeenCalledWith('user-restored-456');
  });

  it('does not configure RevenueCat when there is no stored session to restore', async () => {
    const view = renderAuth();
    await settle(view);

    expect(mockedConfigureRevenueCat).not.toHaveBeenCalled();
  });

  it('reports and survives a throwing configureRevenueCat during session restore', async () => {
    mockedConfigureRevenueCat.mockImplementationOnce(() => {
      throw new Error('EXPO_PUBLIC_REVENUECAT_API_KEY is not set.');
    });
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_user_id', 'user-restored-456');

    const view = renderAuth();
    await settle(view);

    // The restored session itself must not be lost just because billing config is broken.
    expect(view.getByTestId('token')).toHaveTextContent('stored-token');
    expect(mockedReportHandledError).toHaveBeenCalledWith(expect.any(Error), 'auth-bootstrap-revenuecat');
  });

  // Stored as the string 'true'/'false'; anything else must not read as verified.
  it('treats a non-"true" verified flag as unverified', async () => {
    await SecureStore.setItemAsync('finora_token', 't');
    await SecureStore.setItemAsync('finora_phone_verified', 'false');

    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
  });
});

describe('AuthContext login', () => {
  it('persists every session key and reports the verified flag', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.login('someone@example.com', 'pw');
    });

    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
    expect(await SecureStore.getItemAsync('finora_refresh_token')).toBe('refresh-token');
    expect(await SecureStore.getItemAsync('finora_phone_verified')).toBe('true');
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
    expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBe('true');
  });

  it('reports an unverified account so the navigator can route to verification', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.login('someone@example.com', 'pw');
    });

    expect(verified).toBe(false);
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
  });

  it('leaves state and storage untouched when the call fails', async () => {
    mockedAuthApi.login.mockRejectedValue(new Error('bad credentials'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.login('someone@example.com', 'wrong')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
  });

  it('configures RevenueCat with the signed-in user id', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    expect(mockedConfigureRevenueCat).toHaveBeenCalledWith('user-abc-123');
  });

  it('reports and survives a throwing configureRevenueCat during login -- persist() must still complete', async () => {
    mockedConfigureRevenueCat.mockImplementationOnce(() => {
      throw new Error('EXPO_PUBLIC_REVENUECAT_API_KEY is not set.');
    });
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.login('someone@example.com', 'pw');
    });

    // login() itself must not reject, and everything persist() does after the RevenueCat call
    // (session storage, device-token registration) must still run.
    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
    expect(mockedRegisterDeviceToken).toHaveBeenCalled();
    expect(mockedReportHandledError).toHaveBeenCalledWith(expect.any(Error), 'auth-persist-revenuecat');
  });
});

describe('AuthContext reactivate', () => {
  it('persists the session and reports the verified flag, same as login', async () => {
    mockedAuthApi.reactivate.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.reactivate('reactivation-token');
    });

    expect(mockedAuthApi.reactivate).toHaveBeenCalledWith('reactivation-token');
    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
    expect(await SecureStore.getItemAsync('finora_refresh_token')).toBe('refresh-token');
  });

  it('leaves state and storage untouched when the token is stale or already used', async () => {
    mockedAuthApi.reactivate.mockRejectedValue(new Error('expired token'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.reactivate('stale-token')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
  });
});

describe('AuthContext loginWithGoogle', () => {
  it('persists the session and reports the verified flag, same as login()', async () => {
    mockedAuthApi.google.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.loginWithGoogle('a-google-id-token');
    });

    expect(mockedAuthApi.google).toHaveBeenCalledWith('a-google-id-token');
    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
  });

  it('propagates a rejection (e.g. an invalid/expired credential) without touching state', async () => {
    mockedAuthApi.google.mockRejectedValue(new Error('invalid token'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.loginWithGoogle('a-bad-id-token')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
  });
});

describe('AuthContext loginWithApple', () => {
  it('forwards the client-captured fullName straight through to authApi.apple', async () => {
    mockedAuthApi.apple.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.loginWithApple('an-apple-id-token', 'Amy Santiago');
    });

    expect(mockedAuthApi.apple).toHaveBeenCalledWith('an-apple-id-token', 'Amy Santiago');
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
  });

  it('works with no fullName -- every sign-in after the first, when Apple gives none', async () => {
    mockedAuthApi.apple.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.loginWithApple('an-apple-id-token', undefined);
    });

    expect(mockedAuthApi.apple).toHaveBeenCalledWith('an-apple-id-token', undefined);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
  });
});

describe('AuthContext logout', () => {
  it('clears state and storage, and revokes the refresh token server-side', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
      expect(await SecureStore.getItemAsync('finora_refresh_token')).toBeNull();
      expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBeNull();
    });
    // Best-effort revoke -- and it must read the refresh token before deletion races it.
    expect(mockedAuthApi.logout).toHaveBeenCalledWith('refresh-token');
  });

  it('still signs the user out locally when the revoke call fails', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    mockedAuthApi.logout.mockRejectedValue(new Error('offline'));
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });
  });
});

describe('AuthContext setPhoneVerified', () => {
  it('flips the flag and persists it -- this is what moves the navigator into the app', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.setPhoneVerified(true);
    });

    expect(view.getByTestId('phoneVerified')).toHaveTextContent('true');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_phone_verified')).toBe('true');
    });
  });
});

describe('AuthContext setOnboardingCompleted', () => {
  it('flips the flag and persists it', async () => {
    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');

    await act(async () => {
      auth.setOnboardingCompleted(true);
    });

    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBe('true');
    });
  });
});

describe('AuthContext push registration wiring (Task 14)', () => {
  it('does not register a device token when login returns an unverified phone', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    // /api/v1/device-tokens is not exempt in PhoneVerificationFilter -- calling this before
    // verification actually completes is a guaranteed 403. setPhoneVerified() below is where a
    // brand-new session's very first registration attempt belongs instead.
    expect(mockedRegisterDeviceToken).not.toHaveBeenCalled();
  });

  it('registers a device token once phone verification completes', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.setPhoneVerified(true);
    });

    expect(mockedRegisterDeviceToken).toHaveBeenCalledTimes(1);
  });

  it('registers a device token for a returning, already-verified login', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never); // SESSION.phoneVerified === true
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    expect(mockedRegisterDeviceToken).toHaveBeenCalledTimes(1);
  });

  it('revokes the device token before the stored auth token is cleared on logout', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    // Records whether the bearer token was still readable from storage at the moment
    // revokeDeviceToken() was actually invoked -- the real function's own POST needs it there to
    // authenticate itself (see pushRegistration.ts), so this pins the ORDERING logout() depends
    // on, not merely that both things eventually happened.
    let tokenPresentAtRevokeTime: string | null = null;
    mockedRevokeDeviceToken.mockImplementation(async () => {
      tokenPresentAtRevokeTime = await SecureStore.getItemAsync('finora_token');
    });
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });
    expect(mockedRevokeDeviceToken).toHaveBeenCalledTimes(1);
    expect(tokenPresentAtRevokeTime).toBe('access-token');
  });
});

// Mobile audit Phase 2: without this wiring, subscribeToForegroundMessages (pushRegistration.ts)
// exists but nothing ever calls it, and a push stays exactly as silent while the app is open as
// it was before that function existed at all.
describe('AuthContext foreground push wiring', () => {
  let alertSpy: jest.SpyInstance;

  beforeEach(() => {
    alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  });

  afterEach(() => {
    alertSpy.mockRestore();
    // Module-level state (see appLock.ts's own comment) -- a prior test leaving this true would
    // make a later, unrelated test's "shows an alert" assertions fail for the wrong reason.
    appLock.__resetLockedFlagForTests();
  });

  /** The listener AuthContext registered on its most recent subscribeToForegroundMessages call. */
  function latestHandler() {
    const call = mockedSubscribeToForegroundMessages.mock.calls.at(-1);
    if (!call) throw new Error('subscribeToForegroundMessages was never called');
    return call[0];
  }

  /** Presses the "OK" button of the nth (0-indexed) Alert.alert call, advancing the queue. */
  function pressOk(callIndex: number) {
    const call = alertSpy.mock.calls[callIndex];
    if (!call) throw new Error(`Alert.alert was not called at index ${callIndex}`);
    const buttons = call[2] as { onPress?: () => void }[];
    buttons[0].onPress?.();
  }

  it('does not subscribe while signed out', async () => {
    const view = renderAuth();
    await settle(view);

    expect(mockedSubscribeToForegroundMessages).not.toHaveBeenCalled();
  });

  it('subscribes once signed in with a verified phone', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never); // SESSION.phoneVerified === true
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    expect(mockedSubscribeToForegroundMessages).toHaveBeenCalledTimes(1);
  });

  it("shows an alert with the message's title and body", async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    latestHandler()({ notification: { title: 'Fynora', body: 'Your Visa payment is due tomorrow.' } } as never);

    expect(alertSpy).toHaveBeenCalledWith(
      'Fynora',
      'Your Visa payment is due tomorrow.',
      expect.any(Array),
      expect.objectContaining({ cancelable: false })
    );
  });

  // Regression test: Alert.alert is a native modal that floats above the entire app, including
  // AppLockGate's own lock screen -- without this check, a push arriving while the device is
  // locked would show its title/body on top of the lock screen before the user has authenticated.
  it('does not show an alert while the app is locked', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    appLock.setLockedFlag(true);
    latestHandler()({ notification: { title: 'Fynora', body: 'Your Visa payment is due tomorrow.' } } as never);

    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('falls back to a default title when the message has none', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    latestHandler()({ notification: { body: 'Your balance is below ₹1,000.' } } as never);

    expect(alertSpy).toHaveBeenCalledWith(
      'Fynora',
      'Your balance is below ₹1,000.',
      expect.any(Array),
      expect.objectContaining({ cancelable: false })
    );
  });

  // Regression test: RN's Alert.alert has no JS-side queueing (confirmed against
  // react-native/Libraries/Alert/Alert.js -- it forwards straight to the native alert manager on
  // every call), so two pushes landing before the first is dismissed used to silently drop one.
  it('queues a second message that arrives while the first alert is still showing', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    const handler = latestHandler();
    handler({ notification: { title: 'Budget alert', body: 'You are over budget on Dining.' } } as never);
    handler({ notification: { title: 'Card due', body: 'Your credit card payment is due tomorrow.' } } as never);

    // Only the first shows immediately -- the second is held, not dropped and not shown early.
    expect(alertSpy).toHaveBeenCalledTimes(1);
    expect(alertSpy).toHaveBeenNthCalledWith(
      1,
      'Budget alert',
      'You are over budget on Dining.',
      expect.any(Array),
      expect.objectContaining({ cancelable: false })
    );

    pressOk(0);

    expect(alertSpy).toHaveBeenCalledTimes(2);
    expect(alertSpy).toHaveBeenNthCalledWith(
      2,
      'Card due',
      'Your credit card payment is due tomorrow.',
      expect.any(Array),
      expect.objectContaining({ cancelable: false })
    );
  });

  it('shows a third queued message only after the first two are each dismissed in order', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    const handler = latestHandler();
    handler({ notification: { title: 'First', body: 'first body' } } as never);
    handler({ notification: { title: 'Second', body: 'second body' } } as never);
    handler({ notification: { title: 'Third', body: 'third body' } } as never);

    expect(alertSpy).toHaveBeenCalledTimes(1);

    pressOk(0);
    expect(alertSpy).toHaveBeenCalledTimes(2);
    expect(alertSpy.mock.calls[1][0]).toBe('Second');

    pressOk(1);
    expect(alertSpy).toHaveBeenCalledTimes(3);
    expect(alertSpy.mock.calls[2][0]).toBe('Third');
  });

  // Regression test: the queue and its "currently showing" flag live in refs that outlive any
  // single effect run -- without clearing them on logout, a message queued right before signing
  // out would sit there and only surface once some later, unrelated session's own push happened
  // to get shown, since the flag would still (wrongly) read "already showing".
  it('does not carry a queued message over into the next session after logout', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    const handler = latestHandler();
    handler({ notification: { title: 'First', body: 'first body' } } as never);
    handler({ notification: { title: 'Second', body: 'second body' } } as never);
    expect(alertSpy).toHaveBeenCalledTimes(1);

    await act(async () => {
      auth.logout();
    });

    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    // A fresh push in the new session shows immediately -- not queued behind "Second" from the
    // session that just ended.
    latestHandler()({ notification: { title: 'Third', body: 'third body' } } as never);
    expect(alertSpy).toHaveBeenCalledTimes(2);
    expect(alertSpy.mock.calls[1][0]).toBe('Third');
  });

  it('does nothing for a message with no body', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    latestHandler()({ notification: { title: 'Fynora' } } as never);

    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('unsubscribes when the session ends', async () => {
    const unsubscribe = jest.fn();
    mockedSubscribeToForegroundMessages.mockReturnValueOnce(unsubscribe);
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    await waitFor(() => expect(unsubscribe).toHaveBeenCalledTimes(1));
  });
});
