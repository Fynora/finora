import { renderHook } from '@testing-library/react-native';
import { Alert, Linking, type AlertButton } from 'react-native';
import { parseResetPasswordDeepLink, useResetPasswordDeepLink } from './useResetPasswordDeepLink';

const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');
const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});

const LINK = 'https://app.fynora.net/reset-password?token=tok-123';

describe('parseResetPasswordDeepLink', () => {
  it('reads the token from the https app link', () => {
    expect(parseResetPasswordDeepLink(LINK)).toEqual({ token: 'tok-123' });
  });

  it('reads it from the custom-scheme form and from the dev host', () => {
    expect(parseResetPasswordDeepLink('finora://reset-password?token=tok-123')).toEqual({ token: 'tok-123' });
    expect(parseResetPasswordDeepLink('https://dev-app.fynora.net/reset-password?token=tok-123')).toEqual({ token: 'tok-123' });
  });

  it('recognises a reset link that carries no token, so the user can be told rather than left with nothing', () => {
    expect(parseResetPasswordDeepLink('https://app.fynora.net/reset-password')).toEqual({ token: null });
    expect(parseResetPasswordDeepLink('https://app.fynora.net/reset-password?token=')).toEqual({ token: null });
  });

  it.each([
    'https://app.fynora.net/verify-email?token=abc',
    'https://app.fynora.net/reset-password/extra?token=abc',
    'https://evil.example/reset-password?token=abc',
    'https://app.fynora.net.evil.example/reset-password?token=abc',
    'not a url',
  ])('ignores %s', (url) => {
    expect(parseResetPasswordDeepLink(url)).toBeNull();
  });
});

describe('useResetPasswordDeepLink', () => {
  let urlListener: ((event: { url: string }) => void) | null;
  const signOut = jest.fn();

  type Opts = { bootstrapping: boolean; signedIn: boolean; signOut: () => void };
  const signedOut: Opts = { bootstrapping: false, signedIn: false, signOut };

  function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
    return {
      current: {},
      isReady: overrides.isReady ?? (() => true),
      navigate: jest.fn(),
    } as unknown as Parameters<typeof useResetPasswordDeepLink>[0];
  }

  function lastAlertButtons(): AlertButton[] {
    const call = alertSpy.mock.calls[alertSpy.mock.calls.length - 1];
    return (call?.[2] ?? []) as AlertButton[];
  }

  beforeEach(() => {
    urlListener = null;
    signOut.mockReset();
    alertSpy.mockClear();
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_event, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('opens the reset screen with the token when the link arrives while signed out', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();

    urlListener?.({ url: LINK });

    expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-123' });
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('picks up a link that cold-launched the app', async () => {
    getInitialURLSpy.mockResolvedValue(LINK);
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-123' });
  });

  it('does not replay the launch URL when the hook remounts -- RootErrorBoundary\'s "Try again" remounts RootNavigator, and getInitialURL keeps returning the launch link for the whole process', async () => {
    getInitialURLSpy.mockResolvedValue('https://app.fynora.net/reset-password?token=tok-launch-once');
    const navigationRef = fakeNavigationRef();
    const first = renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();
    await Promise.resolve();
    expect(navigationRef.navigate).toHaveBeenCalledTimes(1);

    first.unmount();
    (navigationRef.navigate as jest.Mock).mockClear();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('still handles the same link when it is tapped again after launch (a live event, not the launch URL)', async () => {
    getInitialURLSpy.mockResolvedValue('https://app.fynora.net/reset-password?token=tok-launch-twice');
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();
    await Promise.resolve();
    (navigationRef.navigate as jest.Mock).mockClear();

    urlListener?.({ url: 'https://app.fynora.net/reset-password?token=tok-launch-twice' });

    expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-launch-twice' });
  });

  it('waits out auth bootstrapping, because it cannot yet tell signed-in from signed-out', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(
      (opts: Opts) => useResetPasswordDeepLink(navigationRef, opts),
      { initialProps: { ...signedOut, bootstrapping: true } },
    );
    await Promise.resolve();

    urlListener?.({ url: LINK });
    expect(navigationRef.navigate).not.toHaveBeenCalled();
    expect(alertSpy).not.toHaveBeenCalled();

    rerender({ ...signedOut, bootstrapping: false });

    expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-123' });
  });

  it('holds the link until the navigation container is ready, then goes on onNavigationReady', async () => {
    let containerReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => containerReady });
    const { result } = renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();

    urlListener?.({ url: LINK });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    containerReady = true;
    result.current.onNavigationReady();

    expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-123' });
  });

  describe('when already signed in', () => {
    const signedIn: Opts = { bootstrapping: false, signedIn: true, signOut };

    it('asks before signing out, and does not navigate yet', async () => {
      const navigationRef = fakeNavigationRef();
      renderHook(() => useResetPasswordDeepLink(navigationRef, signedIn));
      await Promise.resolve();

      urlListener?.({ url: LINK });

      expect(alertSpy).toHaveBeenCalledTimes(1);
      expect(alertSpy.mock.calls[0][1]).toMatch(/signs you out on all your devices/i);
      expect(signOut).not.toHaveBeenCalled();
      expect(navigationRef.navigate).not.toHaveBeenCalled();
    });

    it('signs out on confirm, then opens the reset screen once the signed-out stack is up', async () => {
      const navigationRef = fakeNavigationRef();
      const { rerender } = renderHook(
        (opts: Opts) => useResetPasswordDeepLink(navigationRef, opts),
        { initialProps: signedIn },
      );
      await Promise.resolve();
      urlListener?.({ url: LINK });

      lastAlertButtons().find((b) => /sign out/i.test(b.text ?? ''))?.onPress?.();
      expect(signOut).toHaveBeenCalledTimes(1);
      expect(navigationRef.navigate).not.toHaveBeenCalled();

      rerender(signedOut);

      expect(navigationRef.navigate).toHaveBeenCalledTimes(1);
      expect(navigationRef.navigate).toHaveBeenCalledWith('ResetPassword', { token: 'tok-123' });
    });

    it('drops the link on cancel, so a later, unrelated sign-out does not open the reset screen', async () => {
      const navigationRef = fakeNavigationRef();
      const { rerender } = renderHook(
        (opts: Opts) => useResetPasswordDeepLink(navigationRef, opts),
        { initialProps: signedIn },
      );
      await Promise.resolve();
      urlListener?.({ url: LINK });

      lastAlertButtons().find((b) => b.style === 'cancel')?.onPress?.();
      rerender(signedOut);

      expect(signOut).not.toHaveBeenCalled();
      expect(navigationRef.navigate).not.toHaveBeenCalled();
    });

    it('asks again if the user cancels and then taps the link a second time', async () => {
      const navigationRef = fakeNavigationRef();
      renderHook(() => useResetPasswordDeepLink(navigationRef, signedIn));
      await Promise.resolve();

      urlListener?.({ url: LINK });
      lastAlertButtons().find((b) => b.style === 'cancel')?.onPress?.();
      urlListener?.({ url: LINK });

      expect(alertSpy).toHaveBeenCalledTimes(2);
    });

    it('does not stack a second prompt when the same link is delivered again while one is open', async () => {
      const navigationRef = fakeNavigationRef();
      renderHook(() => useResetPasswordDeepLink(navigationRef, signedIn));
      await Promise.resolve();

      urlListener?.({ url: LINK });
      urlListener?.({ url: LINK });

      expect(alertSpy).toHaveBeenCalledTimes(1);
    });

    it('prompts after bootstrapping finishes if the link arrived first', async () => {
      const navigationRef = fakeNavigationRef();
      const { rerender } = renderHook(
        (opts: Opts) => useResetPasswordDeepLink(navigationRef, opts),
        { initialProps: { ...signedIn, bootstrapping: true } },
      );
      await Promise.resolve();
      urlListener?.({ url: LINK });
      expect(alertSpy).not.toHaveBeenCalled();

      rerender(signedIn);

      expect(alertSpy).toHaveBeenCalledTimes(1);
    });
  });

  it('tells the user when the link carries no token, and does not navigate', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/reset-password' });

    expect(alertSpy).toHaveBeenCalledWith('Reset link incomplete', expect.stringMatching(/request a new/i));
    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('opens the screen again if the user taps the same link again after leaving it', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();

    urlListener?.({ url: LINK });
    urlListener?.({ url: LINK });

    expect(navigationRef.navigate).toHaveBeenCalledTimes(2);
  });

  it('ignores every other link', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useResetPasswordDeepLink(navigationRef, signedOut));
    await Promise.resolve();

    urlListener?.({ url: 'https://app.fynora.net/verify-email?token=abc' });
    urlListener?.({ url: 'finora://register?ref=X' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
