import { renderHook } from '@testing-library/react-native';
import { usePushNotificationNavigation } from './usePushNotificationNavigation';
import type { PushMessaging, RemoteMessage } from '../lib/pushRegistration';

function fakeMessaging(overrides: Partial<PushMessaging> = {}) {
  let openedListener: ((message: RemoteMessage) => void) | null = null;
  return {
    requestPermission: jest.fn(),
    getToken: jest.fn(),
    onTokenRefresh: jest.fn(() => () => {}),
    onMessage: jest.fn(() => () => {}),
    onNotificationOpenedApp: jest.fn((listener: (message: RemoteMessage) => void) => {
      openedListener = listener;
      return jest.fn();
    }),
    getInitialNotification: jest.fn(async () => null),
    __emitOpened(message: RemoteMessage) {
      openedListener?.(message);
    },
    ...overrides,
  } as unknown as PushMessaging & { __emitOpened(message: RemoteMessage): void };
}

function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
  return {
    current: {},
    isReady: overrides.isReady ?? (() => true),
    navigate: jest.fn(),
  } as unknown as Parameters<typeof usePushNotificationNavigation>[0];
}

function readyMessage(type: string): RemoteMessage {
  return { data: { type } } as unknown as RemoteMessage;
}

describe('usePushNotificationNavigation', () => {
  it('navigates to Settings for a PASSWORD_CHANGED tap while already ready', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  it('navigates to Statements for IMPORT_STATEMENT_READY', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('IMPORT_STATEMENT_READY'));

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
  });

  it('navigates to Statements for IMPORT_STATEMENT_HELD', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('IMPORT_STATEMENT_HELD'));

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
  });

  it('does nothing for a type this app does not know how to route', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('SOME_FUTURE_TYPE'));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('does nothing for a message carrying no data.type at all', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened({} as unknown as RemoteMessage);

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  // Signed IN throughout (a persisted session restored on cold start, still bootstrapping) --
  // distinct from the fully-signed-out case below, which this hook now drops rather than stashes.
  it('stashes the tap and does not navigate yet when not ready but still signed in (e.g. still bootstrapping)', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    renderHook(() => usePushNotificationNavigation(navigationRef, false, true, { messaging }));
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('replays the stashed tap once ready becomes true, for a session that was signed in throughout', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    const { rerender } = renderHook(
      ({ ready }: { ready: boolean }) => usePushNotificationNavigation(navigationRef, ready, true, { messaging }),
      { initialProps: { ready: false } },
    );
    await Promise.resolve();
    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true });

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  // Bug fix: this used to stash unconditionally regardless of signedIn, relying only on the
  // true -> false transition effect to clear a stale tap -- which never fires for a tap that
  // arrived while ALREADY signed out (there was no transition to catch it). A system notification
  // delivered before sign-out can still sit in the OS tray and be tapped after sign-out (revoking
  // the device token doesn't retract it), and unlike useEmailChangeDeepLink's token -- which the
  // backend rejects for the wrong user -- Settings/Statements have no such scoping to catch a
  // replay for whoever signs in next.
  it('drops a tap that arrives while fully signed out, never replaying it for a later sign-in', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) =>
        usePushNotificationNavigation(navigationRef, ready, signedIn, { messaging }),
      { initialProps: { ready: false, signedIn: false } },
    );
    await Promise.resolve();

    // A stale notification, delivered before this signed-out state, tapped from the OS tray now.
    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    // A (possibly different) user signs in on this device.
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  // Mirrors useEmailChangeDeepLink's own D6 regression lock: a mid-session ready dip that is NOT
  // a sign-out (signedIn stays true throughout) must not drop a still-pending tap.
  it('does NOT clear a pending tap when ready drops for a still-signed-in user', async () => {
    let navReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => navReady });
    const messaging = fakeMessaging();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) =>
        usePushNotificationNavigation(navigationRef, ready, signedIn, { messaging }),
      { initialProps: { ready: true, signedIn: true } },
    );
    await Promise.resolve();

    messaging.__emitOpened(readyMessage('IMPORT_STATEMENT_READY'));
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: false, signedIn: true });

    navReady = true;
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
  });

  // The counterpart: a REAL sign-out (signedIn true -> false) must drop a still-pending tap so a
  // different account signing in next never has someone else's push replayed at them.
  it('clears a still-pending tap on a real sign-out', async () => {
    let navReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => navReady });
    const messaging = fakeMessaging();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) =>
        usePushNotificationNavigation(navigationRef, ready, signedIn, { messaging }),
      { initialProps: { ready: false, signedIn: true } },
    );
    await Promise.resolve();
    // Genuinely stashed here: signedIn is already true, only `ready`/the navigator itself lag.
    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));

    rerender({ ready: true, signedIn: true });
    // navReady still false -- the navigator itself hasn't finished mounting -- so this proves the
    // tap is still sitting in pendingRef, not that it was never stashed at all.
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    // A real sign-out.
    rerender({ ready: false, signedIn: false });

    // A different user signs in, and this time the navigator is ready too.
    navReady = true;
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('picks up a cold-launch tap from getInitialNotification, not just onNotificationOpenedApp', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = fakeMessaging({
      getInitialNotification: jest.fn(async () => readyMessage('PASSWORD_CHANGED')),
    });
    renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  it('never throws when messaging is unavailable (e.g. no native Firebase app registered)', async () => {
    const navigationRef = fakeNavigationRef();
    const messaging = {
      onNotificationOpenedApp: () => { throw new Error('no native Firebase app registered'); },
    } as unknown as PushMessaging;

    expect(() => {
      renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    }).not.toThrow();
  });

  it('calls onNavigationReady (wired to NavigationContainer.onReady) to retry a pending tap once the ref actually reports ready', async () => {
    let navReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => navReady });
    const messaging = fakeMessaging();
    const { result } = renderHook(() => usePushNotificationNavigation(navigationRef, true, true, { messaging }));
    await Promise.resolve();
    messaging.__emitOpened(readyMessage('PASSWORD_CHANGED'));
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    navReady = true;
    result.current.onNavigationReady();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });
});
