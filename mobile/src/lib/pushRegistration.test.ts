import { registerDeviceToken, revokeDeviceToken, subscribeToForegroundMessages, type RemoteMessage } from './pushRegistration';

// '@react-native-firebase/messaging' is mocked globally in src/test/setup.ts (same posture as
// '@react-native-firebase/auth' there -- no native app registered under the runner, plus its real
// entry point is ESM source that a bare automock can't introspect without throwing). Nothing below
// exercises that default module mock anyway: every call here passes its own `messaging` fake
// through registerDeviceToken()/revokeDeviceToken()'s dependency-injection parameter instead.

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
    onTokenRefresh: jest.fn((listener: (nextToken: string) => void) => {
      refreshListener = listener;
      return jest.fn();
    }),
    onMessage: jest.fn((listener: (message: RemoteMessage) => void) => {
      messageListener = listener;
      return jest.fn();
    }),
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
  const deleteDeviceToken = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
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

  it('revokes the token on logout', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');

    await revokeDeviceToken({ deleteDeviceToken, messaging });

    expect(deleteDeviceToken).toHaveBeenCalledWith({ token: 'fcm-token-abc' });
  });

  it('never throws when the backend revoke call fails', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');
    deleteDeviceToken.mockRejectedValueOnce(new Error('network'));

    await expect(revokeDeviceToken({ deleteDeviceToken, messaging })).resolves.not.toThrow();
  });

  it('never throws when the stored onTokenRefresh unsubscribe itself throws, and does not retry it next time', async () => {
    // registerDeviceToken() stashes onTokenRefresh's returned unsubscribe in module-level state;
    // revokeDeviceToken() calls it on the way out. Overriding what THIS onTokenRefresh call
    // returns (rather than trusting requestPermissionMock's default no-op) is what lets this test
    // control that stashed value deterministically, regardless of what any earlier test in this
    // file left behind.
    const throwingUnsubscribe = jest.fn(() => {
      throw new Error('native removeListener failed');
    });
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');
    messaging.onTokenRefresh.mockReturnValueOnce(throwingUnsubscribe);
    await registerDeviceToken({ postDeviceToken, messaging });

    await expect(revokeDeviceToken({ deleteDeviceToken, messaging })).resolves.not.toThrow();
    expect(throwingUnsubscribe).toHaveBeenCalledTimes(1);
    // The backend revoke call must still happen even though the unsubscribe attempt above failed
    // -- a broken native listener removal is not a reason to skip telling the backend.
    expect(deleteDeviceToken).toHaveBeenCalledWith({ token: 'fcm-token-abc' });

    // Proves the stored reference was actually cleared (not left dangling for a later call to
    // retry): a second revoke must not invoke the same already-thrown closure again.
    await expect(revokeDeviceToken({ deleteDeviceToken, messaging })).resolves.not.toThrow();
    expect(throwingUnsubscribe).toHaveBeenCalledTimes(1);
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
