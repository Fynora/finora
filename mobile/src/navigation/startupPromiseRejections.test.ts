import { renderHook, waitFor } from '@testing-library/react-native';
import { Linking } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as monitoring from '../lib/monitoring';
import { safeStorage } from '../lib/safeStorage';
import { useAppPathDeepLink } from './useAppPathDeepLink';
import { useEmailChangeDeepLink } from './useEmailChangeDeepLink';
import { useEmailVerificationDeepLink } from './useEmailVerificationDeepLink';
import { usePushNotificationNavigation } from './usePushNotificationNavigation';
import { useReferralDeepLink } from './useReferralDeepLink';
import { useResetPasswordDeepLink } from './useResetPasswordDeepLink';
import { useShareIntentDeepLink } from './useShareIntentDeepLink';

/**
 * Every one of these hooks fires a promise on mount that the device can reject (the launch URL, the
 * notification that opened the app, the share stash in AsyncStorage). Each was written as a bare
 * `void somePromise.then(...)`, so a rejection became an unhandled promise rejection -- the same
 * shape as FYNORA-MOBILE-V's mailto link -- with nothing to act on. Each now reports the failure and
 * carries on; reportHandledError being called with the very error is what proves the `.catch` ran.
 */

const mockUseShareIntentContext = jest.fn();
jest.mock('expo-share-intent', () => ({
  useShareIntentContext: () => mockUseShareIntentContext(),
}));

const failure = new Error('native module refused');
const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');
const reportSpy = jest.spyOn(monitoring, 'reportHandledError');

function fakeNavigationRef() {
  return { current: {}, isReady: () => true, navigate: jest.fn() } as never;
}

describe('startup promises that can reject', () => {
  beforeEach(() => {
    getInitialURLSpy.mockReset().mockRejectedValue(failure);
    addEventListenerSpy.mockReset().mockReturnValue({ remove: jest.fn() } as never);
    reportSpy.mockReset().mockImplementation(() => {});
    mockUseShareIntentContext.mockReset().mockReturnValue({
      hasShareIntent: false,
      shareIntent: { files: null, text: null, webUrl: null, type: null },
      resetShareIntent: jest.fn(),
    });
  });

  afterAll(() => {
    getInitialURLSpy.mockRestore();
    addEventListenerSpy.mockRestore();
    reportSpy.mockRestore();
  });

  it.each([
    ['referral', 'referral-deep-link:initial-url', () => useReferralDeepLink(fakeNavigationRef(), true)],
    ['email change', 'email-change-deep-link:initial-url', () => useEmailChangeDeepLink(fakeNavigationRef(), true, true)],
    ['app path', 'app-path-deep-link:initial-url', () => useAppPathDeepLink(fakeNavigationRef(), true, true)],
    [
      'reset password',
      'reset-password-deep-link:initial-url',
      () => useResetPasswordDeepLink(fakeNavigationRef(), { bootstrapping: false, signedIn: false, signOut: jest.fn() }),
    ],
    ['email verification', 'email-verification-deep-link:initial-url', () => useEmailVerificationDeepLink()],
  ])('reports a failing launch URL for the %s link instead of leaving it unhandled', async (_name, context, hook) => {
    renderHook(hook);

    await waitFor(() => expect(reportSpy).toHaveBeenCalledWith(failure, context));
  });

  it('reports a failing launch notification instead of leaving it unhandled', async () => {
    const messaging = {
      onNotificationOpenedApp: jest.fn(() => () => {}),
      getInitialNotification: jest.fn().mockRejectedValue(failure),
    };

    renderHook(() => usePushNotificationNavigation(fakeNavigationRef(), true, true, { messaging } as never));

    await waitFor(() => expect(reportSpy).toHaveBeenCalledWith(failure, 'push-navigation:initial-notification'));
  });

  it('reports a failing read of the stashed share instead of leaving it unhandled', async () => {
    jest.spyOn(AsyncStorage, 'getItem').mockRejectedValueOnce(failure);

    renderHook(() => useShareIntentDeepLink(fakeNavigationRef(), true, true));

    await waitFor(() => expect(reportSpy).toHaveBeenCalledWith(failure, 'share-intent:read-pending'));
  });

  it('reports a failing read of the signed-in user id instead of leaving it unhandled', async () => {
    jest.spyOn(safeStorage, 'getItem').mockRejectedValueOnce(failure);

    renderHook(() => useShareIntentDeepLink(fakeNavigationRef(), true, true));

    await waitFor(() => expect(reportSpy).toHaveBeenCalledWith(failure, 'share-intent:read-user-id'));
  });
});
