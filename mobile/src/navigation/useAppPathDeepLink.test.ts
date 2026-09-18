import { renderHook } from '@testing-library/react-native';
import { Linking } from 'react-native';
import { parseAppPathDeepLink, useAppPathDeepLink } from './useAppPathDeepLink';

const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');

describe('parseAppPathDeepLink', () => {
  it.each([
    ['https://app.fynora.net/app/settings', 'Settings'],
    ['https://app.fynora.net/app/imports/6f2a-job', 'Statements'],
    ['https://dev-app.fynora.net/app/imports', 'Statements'],
    ['finora://app/settings', 'Settings'],
  ])('%s -> %s', (url, route) => {
    expect(parseAppPathDeepLink(url)).toBe(route);
  });

  it.each([
    // The app opens this page in a browser itself (MySubscriptionScreen), so it must not divert it.
    'https://app.fynora.net/app/billing',
    // Deeper than the exact settings link the backend emails: no mapping, so not dropped on Settings.
    'https://app.fynora.net/app/settings/bank-sync/abc/confirm',
    'https://app.fynora.net/app/dashboard',
    'https://app.fynora.net/app/settingsX',
    'https://app.fynora.net/reset-password?token=abc',
    'https://evil.example/app/settings',
    'not a url',
  ])('ignores %s', (url) => {
    expect(parseAppPathDeepLink(url)).toBeNull();
  });
});

describe('useAppPathDeepLink', () => {
  let urlListener: ((event: { url: string }) => void) | null;

  function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
    return {
      current: {},
      isReady: overrides.isReady ?? (() => true),
      navigate: jest.fn(),
    } as unknown as Parameters<typeof useAppPathDeepLink>[0];
  }

  beforeEach(() => {
    urlListener = null;
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_event, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('navigates into the More stack straight away when the app is ready', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useAppPathDeepLink(navigationRef, true, true));
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/app/imports/job-1' });

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
  });

  it('holds a link that arrives while signed out and replays it once the app is ready', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useAppPathDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: false } },
    );
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/app/imports/job-1' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).toHaveBeenCalledTimes(1);
    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
  });

  it('keeps a held link through a transient ready dip while still signed in', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useAppPathDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: true, signedIn: true } },
    );
    await Promise.resolve();

    // Mid-session phone re-verification: ready drops, the token doesn't.
    rerender({ ready: false, signedIn: true });
    urlListener?.({ url: 'https://app.fynora.net/app/settings' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true, signedIn: true });
    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  it('drops a held link on a real sign-out so the next person to sign in does not inherit it', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useAppPathDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: true } },
    );
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/app/settings' });
    rerender({ ready: false, signedIn: false });
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('waits for the navigation container when it is not ready yet, then goes on onNavigationReady', async () => {
    let containerReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => containerReady });
    const { result } = renderHook(() => useAppPathDeepLink(navigationRef, true, true));
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/app/settings' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    containerReady = true;
    result.current.onNavigationReady();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  it('picks up a cold-launch link from getInitialURL', async () => {
    getInitialURLSpy.mockResolvedValue('https://app.fynora.net/app/settings');
    const navigationRef = fakeNavigationRef();
    renderHook(() => useAppPathDeepLink(navigationRef, true, true));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Settings' });
  });

  it('ignores links that are not one of its paths', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useAppPathDeepLink(navigationRef, true, true));
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/verify-email?token=abc' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  // The launch URL is module state, so each of these tests uses a URL no other test does.
  describe('launch URL replay after a remount (RootErrorBoundary "Try again")', () => {
    const LAUNCH = 'https://app.fynora.net/app/imports/remount-job';

    it('does not re-navigate when the hook remounts with the same launch URL', async () => {
      getInitialURLSpy.mockResolvedValue(LAUNCH);
      const navigationRef = fakeNavigationRef();
      const first = renderHook(() => useAppPathDeepLink(navigationRef, true, true));
      await Promise.resolve();
      await Promise.resolve();
      expect(navigationRef.navigate).toHaveBeenCalledTimes(1);

      first.unmount();
      (navigationRef.navigate as jest.Mock).mockClear();
      renderHook(() => useAppPathDeepLink(navigationRef, true, true));
      await Promise.resolve();
      await Promise.resolve();

      expect(navigationRef.navigate).not.toHaveBeenCalled();
    });

    it('still handles the same link when it is tapped again after the remount (a live event)', async () => {
      getInitialURLSpy.mockResolvedValue(LAUNCH);
      const navigationRef = fakeNavigationRef();
      const first = renderHook(() => useAppPathDeepLink(navigationRef, true, true));
      await Promise.resolve();
      await Promise.resolve();
      first.unmount();
      (navigationRef.navigate as jest.Mock).mockClear();
      renderHook(() => useAppPathDeepLink(navigationRef, true, true));
      await Promise.resolve();
      await Promise.resolve();

      (navigationRef.navigate as jest.Mock).mockClear();
      urlListener?.({ url: LAUNCH });

      expect(navigationRef.navigate).toHaveBeenCalledTimes(1);
      expect(navigationRef.navigate).toHaveBeenCalledWith('More', { screen: 'Statements' });
    });
  });
});
