import { renderHook } from '@testing-library/react-native';
import { Linking } from 'react-native';
import { parseReferralDeepLink, useReferralDeepLink } from './useReferralDeepLink';

// Same spy-on-the-real-module approach as useEmailChangeDeepLink.test.ts -- see that file's own
// comment for why this doesn't replace the whole 'react-native' module.
const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');

describe('parseReferralDeepLink', () => {
  it('extracts the referral code from a well-formed link', () => {
    expect(parseReferralDeepLink('finora://register?ref=FRIEND123')).toEqual({ referralCode: 'FRIEND123' });
  });

  it('decodes a URL-encoded code', () => {
    expect(parseReferralDeepLink('finora://register?ref=A%26B')).toEqual({ referralCode: 'A&B' });
  });

  it('returns null for a different path this app does not handle', () => {
    expect(parseReferralDeepLink('finora://some-other-path?ref=FRIEND123')).toBeNull();
  });

  it('returns null when ref is missing from the query string', () => {
    expect(parseReferralDeepLink('finora://register?utm_source=x')).toBeNull();
  });

  it('returns null for a bare "finora://register" with no query string at all', () => {
    expect(parseReferralDeepLink('finora://register')).toBeNull();
  });

  it('returns null for a completely unrelated URL, without throwing', () => {
    expect(parseReferralDeepLink('https://example.com/whatever')).toBeNull();
  });
});

describe('useReferralDeepLink', () => {
  let urlListener: ((event: { url: string }) => void) | null;

  function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
    return {
      current: {},
      isReady: overrides.isReady ?? (() => true),
      navigate: jest.fn(),
    } as unknown as Parameters<typeof useReferralDeepLink>[0];
  }

  beforeEach(() => {
    urlListener = null;
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_event, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('navigates to Register with the code when the link arrives while signed out', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useReferralDeepLink(navigationRef, true));
    await Promise.resolve();

    urlListener?.({ url: 'finora://register?ref=FRIEND123' });

    expect(navigationRef.navigate).toHaveBeenCalledWith('Register', { referralCode: 'FRIEND123' });
  });

  it('does nothing when the link arrives while already signed in (AuthStack not mounted)', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useReferralDeepLink(navigationRef, false));
    await Promise.resolve();

    urlListener?.({ url: 'finora://register?ref=FRIEND123' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('replays a link stashed before the navigator finished mounting, once it becomes ready', async () => {
    let navReady = false;
    const navigationRef = fakeNavigationRef({ isReady: () => navReady });
    const { result } = renderHook(() => useReferralDeepLink(navigationRef, true));
    await Promise.resolve();

    urlListener?.({ url: 'finora://register?ref=FRIEND123' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    navReady = true;
    // Retried the same way NavigationContainer's onReady prop retries it in the app.
    result.current.onNavigationReady();

    expect(navigationRef.navigate).toHaveBeenCalledWith('Register', { referralCode: 'FRIEND123' });
  });

  // The core reasoning this hook departs from useEmailChangeDeepLink for: a code that arrives
  // while signed in is never stashed at all, so a later, unrelated sign-out has nothing stale to
  // replay -- unlike the email-change link, there is no legitimate "came back later" case for a
  // referral code once an account already exists.
  it('never replays a link that arrived while signed in, even after a later sign-out', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(
      ({ authStackActive }: { authStackActive: boolean }) => useReferralDeepLink(navigationRef, authStackActive),
      { initialProps: { authStackActive: false } },
    );
    await Promise.resolve();

    urlListener?.({ url: 'finora://register?ref=FRIEND123' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    // Unrelated sign-out, well after the link arrived.
    rerender({ authStackActive: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('picks up a cold-launch link from getInitialURL, not just the live "url" event', async () => {
    getInitialURLSpy.mockResolvedValue('finora://register?ref=COLDSTART');
    const navigationRef = fakeNavigationRef();
    renderHook(() => useReferralDeepLink(navigationRef, true));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).toHaveBeenCalledWith('Register', { referralCode: 'COLDSTART' });
  });

  it('ignores a URL that does not match the register path', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useReferralDeepLink(navigationRef, true));
    await Promise.resolve();

    urlListener?.({ url: 'finora://some-other-screen' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });
});
