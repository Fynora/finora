import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  PUSH_DETACH_PENDING_KEY, clearPendingDetach, detachDevice, registerDeviceToken, resumePendingDetach,
  subscribeToForegroundMessages, type RemoteMessage,
} from './pushRegistration';

// '@react-native-firebase/messaging' is mocked globally in src/test/setup.ts (same posture as
// '@react-native-firebase/auth' there -- no native app registered under the runner, plus its real
// entry point is ESM source that a bare automock can't introspect without throwing). Nothing below
// exercises that default module mock anyway: every call here passes its own `messaging` fake
// through registerDeviceToken()/detachDevice()'s dependency-injection parameter instead.

// Mirrors @react-native-firebase/messaging's AuthorizationStatus enum -- see pushRegistration.ts's
// own comment on why that enum is duplicated as plain numbers rather than imported.
const AUTHORIZED = 1;
const DENIED = 0;

/**
 * Builds a fake messaging module shaped like PushMessaging (requestPermission/getToken/
 * onTokenRefresh/onMessage), plus test-only __emitTokenRefresh/__emitMessage helpers to simulate
 * Firebase rotating the token or delivering a foreground push. Both listeners are invoked
 * synchronously by their __emit helper, matching how the real native event emitter delivers them
 * -- so a test can assert immediately after calling __emit*, with no extra await.
 */
function requestPermissionMock(outcome: 'granted' | 'denied', token = 'fcm-token-default') {
  let refreshListener: ((nextToken: string) => void) | null = null;
  let messageListener: ((message: RemoteMessage) => void) | null = null;
  return {
    requestPermission: jest.fn(async () => (outcome === 'granted' ? AUTHORIZED : DENIED)),
    getToken: jest.fn(async () => token),
    deleteToken: jest.fn(async () => {}),
    onTokenRefresh: jest.fn((listener: (nextToken: string) => void) => {
      refreshListener = listener;
      return jest.fn();
    }),
    onMessage: jest.fn((listener: (message: RemoteMessage) => void) => {
      messageListener = listener;
      return jest.fn();
    }),
    // Not exercised by anything in this file -- registerDeviceToken/detachDevice/
    // subscribeToForegroundMessages never call either. Present only so this fixture satisfies the
    // full PushMessaging shape; usePushNotificationNavigation.test.ts covers these two for real.
    onNotificationOpenedApp: jest.fn(() => jest.fn()),
    getInitialNotification: jest.fn(async () => null),
    __emitTokenRefresh(nextToken: string) {
      refreshListener?.(nextToken);
    },
    __emitMessage(message: RemoteMessage) {
      messageListener?.(message);
    },
  };
}

describe('pushRegistration', () => {
  const postDeviceToken = jest.fn();

  beforeEach(async () => {
    jest.clearAllMocks();
    await AsyncStorage.removeItem(PUSH_DETACH_PENDING_KEY);
  });

  it('does not call the backend when the user denies permission', async () => {
    const messaging = requestPermissionMock('denied');

    await registerDeviceToken({ postDeviceToken, messaging });

    // A denied prompt is a normal outcome, not an error -- and must not send a token.
    expect(postDeviceToken).not.toHaveBeenCalled();
  });

  it('registers the token with its platform when permission is granted', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');

    await registerDeviceToken({ postDeviceToken, messaging });

    expect(postDeviceToken).toHaveBeenCalledWith({
      token: 'fcm-token-abc',
      platform: expect.stringMatching(/^(ANDROID|IOS)$/),
    });
  });

  it('re-registers when Firebase rotates the token', async () => {
    const messaging = requestPermissionMock('granted', 'token-1');

    await registerDeviceToken({ postDeviceToken, messaging });
    messaging.__emitTokenRefresh('token-2');

    expect(postDeviceToken).toHaveBeenLastCalledWith(
      expect.objectContaining({ token: 'token-2' }),
    );
  });

  it('never throws when the backend registration call fails', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');
    postDeviceToken.mockRejectedValueOnce(new Error('network'));

    // Failing to register a push token must never block the user from using the app.
    await expect(registerDeviceToken({ postDeviceToken, messaging })).resolves.not.toThrow();
  });

  describe('detachDevice (sign-out)', () => {
    it('deletes the token with Firebase and returns it for the server-side revoke', async () => {
      const messaging = requestPermissionMock('granted', 'fcm-token-abc');

      await expect(detachDevice({ messaging })).resolves.toBe('fcm-token-abc');

      expect(messaging.deleteToken).toHaveBeenCalledTimes(1);
      // Read before deleting: reading after would mint (and return) a brand-new token instead.
      expect(messaging.getToken.mock.invocationCallOrder[0])
        .toBeLessThan(messaging.deleteToken.mock.invocationCallOrder[0]);
      expect(await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)).toBeNull();
    });

    it('stops listening for token rotations before deleting, so the next token is never posted', async () => {
      const unsubscribe = jest.fn();
      const messaging = requestPermissionMock('granted', 'fcm-token-abc');
      messaging.onTokenRefresh.mockReturnValueOnce(unsubscribe);
      await registerDeviceToken({ postDeviceToken, messaging });
      postDeviceToken.mockClear();

      await detachDevice({ messaging });

      expect(unsubscribe.mock.invocationCallOrder[0]).toBeLessThan(messaging.deleteToken.mock.invocationCallOrder[0]);
    });

    it('leaves the pending flag set when Firebase could not delete the token (offline)', async () => {
      const messaging = requestPermissionMock('granted', 'fcm-token-abc');
      messaging.deleteToken.mockRejectedValueOnce(new Error('SERVICE_NOT_AVAILABLE'));

      await expect(detachDevice({ messaging })).resolves.toBe('fcm-token-abc');

      expect(await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)).toBe('1');
    });

    it('sets the pending flag before touching Firebase, so a close mid-way is finished next launch', async () => {
      const messaging = requestPermissionMock('granted', 'fcm-token-abc');
      let flagWhenDeleting: string | null = null;
      messaging.deleteToken.mockImplementationOnce(async () => {
        flagWhenDeleting = await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY);
      });

      await detachDevice({ messaging });

      expect(flagWhenDeleting).toBe('1');
    });

    it('never throws, even when the stored unsubscribe throws', async () => {
      const throwingUnsubscribe = jest.fn(() => {
        throw new Error('native removeListener failed');
      });
      const messaging = requestPermissionMock('granted', 'fcm-token-abc');
      messaging.onTokenRefresh.mockReturnValueOnce(throwingUnsubscribe);
      await registerDeviceToken({ postDeviceToken, messaging });

      await expect(detachDevice({ messaging })).resolves.toBe('fcm-token-abc');
      // A broken listener removal is no reason to keep delivering this account's notifications.
      expect(messaging.deleteToken).toHaveBeenCalledTimes(1);
      // And the broken closure is not kept around to be retried.
      await detachDevice({ messaging });
      expect(throwingUnsubscribe).toHaveBeenCalledTimes(1);
    });
  });

  describe('resumePendingDetach (next launch)', () => {
    it('does nothing when no sign-out left a detach unfinished', async () => {
      const messaging = requestPermissionMock('granted');

      await resumePendingDetach(async () => true, { messaging });

      expect(messaging.deleteToken).not.toHaveBeenCalled();
    });

    it('finishes an unfinished detach while still signed out, then clears the flag', async () => {
      await AsyncStorage.setItem(PUSH_DETACH_PENDING_KEY, '1');
      const messaging = requestPermissionMock('granted');

      await resumePendingDetach(async () => true, { messaging });

      expect(messaging.deleteToken).toHaveBeenCalledTimes(1);
      expect(await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)).toBeNull();
    });

    it('never deletes the token a sign-in has meanwhile registered', async () => {
      await AsyncStorage.setItem(PUSH_DETACH_PENDING_KEY, '1');
      const messaging = requestPermissionMock('granted');

      await resumePendingDetach(async () => false, { messaging });

      expect(messaging.deleteToken).not.toHaveBeenCalled();
    });

    it('keeps the flag for the launch after when Firebase fails again', async () => {
      await AsyncStorage.setItem(PUSH_DETACH_PENDING_KEY, '1');
      const messaging = requestPermissionMock('granted');
      messaging.deleteToken.mockRejectedValueOnce(new Error('SERVICE_NOT_AVAILABLE'));

      await expect(resumePendingDetach(async () => true, { messaging })).resolves.toBeUndefined();

      expect(await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)).toBe('1');
    });

    it('a sign-in voids an unfinished detach', async () => {
      await AsyncStorage.setItem(PUSH_DETACH_PENDING_KEY, '1');

      await clearPendingDetach();

      expect(await AsyncStorage.getItem(PUSH_DETACH_PENDING_KEY)).toBeNull();
    });
  });

  describe('subscribeToForegroundMessages', () => {
    it('delivers a foreground message to the caller', () => {
      const messaging = requestPermissionMock('granted');
      const onMessage = jest.fn();

      subscribeToForegroundMessages(onMessage, { messaging });
      messaging.__emitMessage(
        { notification: { title: 'Fynora', body: 'Your Visa payment is due tomorrow.' } } as RemoteMessage,
      );

      expect(onMessage).toHaveBeenCalledWith(
        expect.objectContaining({ notification: { title: 'Fynora', body: 'Your Visa payment is due tomorrow.' } }),
      );
    });

    it('returns the unsubscribe function messaging.onMessage hands back', () => {
      const messaging = requestPermissionMock('granted');
      const unsubscribe = jest.fn();
      messaging.onMessage.mockReturnValueOnce(unsubscribe);

      const returned = subscribeToForegroundMessages(jest.fn(), { messaging });
      returned();

      expect(unsubscribe).toHaveBeenCalledTimes(1);
    });

    it('never throws, and returns a no-op unsubscribe, when messaging cannot be resolved', () => {
      const messaging = {
        onMessage: jest.fn(() => {
          throw new Error('no native Firebase app registered');
        }),
      } as unknown as import('./pushRegistration').PushMessaging;

      let returned: (() => void) | undefined;
      expect(() => { returned = subscribeToForegroundMessages(jest.fn(), { messaging }); }).not.toThrow();
      expect(() => returned?.()).not.toThrow();
    });
  });
});
