import { AppState } from 'react-native';
import * as StoreReview from 'expo-store-review';
import { AppAlert } from './appAlert';
import * as appLock from './appLock';
import { safeStorage } from './safeStorage';
import {
  STORE_REVIEW_LAST_ASKED_KEY,
  STORE_REVIEW_MIN_GAP_MS,
  maybeAskForStoreReview,
} from './storeReview';

jest.mock('expo-store-review', () => ({ hasAction: jest.fn(), requestReview: jest.fn() }));
// Only the three "is something in front of the person" reads are replaced; each test that needs one
// true sets it with mockReturnValueOnce, so it never leaks into the next test.
jest.mock('./appLock', () => ({
  ...jest.requireActual('./appLock'),
  isLocked: jest.fn(() => false),
  isAuthenticating: jest.fn(() => false),
  isSharing: jest.fn(() => false),
}));

const storeReview = StoreReview as jest.Mocked<typeof StoreReview>;
// Same approach as festivalIcon.test.ts: AppState.currentState is a getter in this environment.
let appState = 'active';
Object.defineProperty(AppState, 'currentState', { configurable: true, get: () => appState });
const NOW = Date.UTC(2026, 10, 10, 12, 0, 0);

describe('maybeAskForStoreReview', () => {
  beforeEach(() => {
    jest.spyOn(Date, 'now').mockReturnValue(NOW);
    appState = 'active';
    storeReview.hasAction.mockResolvedValue(true);
    storeReview.requestReview.mockResolvedValue(undefined);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('asks when this device has never been asked, and records when', async () => {
    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBe(String(NOW));
  });

  it('does not ask again one millisecond before the gap is up', async () => {
    await safeStorage.setItem(STORE_REVIEW_LAST_ASKED_KEY, String(NOW - STORE_REVIEW_MIN_GAP_MS + 1));

    await maybeAskForStoreReview();

    expect(storeReview.hasAction).not.toHaveBeenCalled();
    expect(storeReview.requestReview).not.toHaveBeenCalled();
  });

  it('asks again once the gap is exactly up', async () => {
    await safeStorage.setItem(STORE_REVIEW_LAST_ASKED_KEY, String(NOW - STORE_REVIEW_MIN_GAP_MS));

    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBe(String(NOW));
  });

  it('asks a second call only after the gap, not straight away', async () => {
    await maybeAskForStoreReview();
    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
  });

  it('neither asks nor records a date when the store flow is not available (TestFlight)', async () => {
    storeReview.hasAction.mockResolvedValue(false);

    await maybeAskForStoreReview();

    expect(storeReview.requestReview).not.toHaveBeenCalled();
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBeNull();
  });

  it('treats an unreadable stored value as never asked', async () => {
    await safeStorage.setItem(STORE_REVIEW_LAST_ASKED_KEY, 'not-a-date');

    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
  });

  it('treats a date in the future (phone clock moved back) as never asked, so it cannot block for months', async () => {
    await safeStorage.setItem(STORE_REVIEW_LAST_ASKED_KEY, String(NOW + 1));

    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBe(String(NOW));
  });

  it('never rejects when the native availability check fails', async () => {
    storeReview.hasAction.mockRejectedValue(new Error('native module unavailable'));

    await expect(maybeAskForStoreReview()).resolves.toBeUndefined();
    expect(storeReview.requestReview).not.toHaveBeenCalled();
  });

  // Both native modules reject only when nothing was presented (iOS: no foreground window scene;
  // Android: the Play review flow failed), so a failure must not use up the next 90 days.
  it('never rejects when the native request fails, and does not count it as asked', async () => {
    storeReview.requestReview.mockRejectedValueOnce(new Error('review flow failed'));

    await expect(maybeAskForStoreReview()).resolves.toBeUndefined();
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBeNull();

    await maybeAskForStoreReview();
    expect(storeReview.requestReview).toHaveBeenCalledTimes(2);
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBe(String(NOW));
  });

  describe('when something else is in front of the person', () => {
    async function expectNotAskedAndNotRecorded() {
      await maybeAskForStoreReview();
      expect(storeReview.requestReview).not.toHaveBeenCalled();
      expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBeNull();
    }

    it.each(['background', 'inactive'])('does not ask while the app is %s', async (state) => {
      appState = state;
      await expectNotAskedAndNotRecorded();
    });

    it('does not ask over the app-lock screen', async () => {
      jest.mocked(appLock.isLocked).mockReturnValueOnce(true);
      await expectNotAskedAndNotRecorded();
    });

    it('does not ask during a Face ID / fingerprint prompt', async () => {
      jest.mocked(appLock.isAuthenticating).mockReturnValueOnce(true);
      await expectNotAskedAndNotRecorded();
    });

    it('does not ask while the share sheet is open', async () => {
      jest.mocked(appLock.isSharing).mockReturnValueOnce(true);
      await expectNotAskedAndNotRecorded();
    });

    it('does not ask on top of an in-app alert waiting for an answer', async () => {
      AppAlert.alert('Low balance', 'Your balance is low.');
      await expectNotAskedAndNotRecorded();
    });

    it('asks at the next trigger once the screen is free again', async () => {
      jest.mocked(appLock.isLocked).mockReturnValueOnce(true);
      await maybeAskForStoreReview();

      await maybeAskForStoreReview();
      expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
    });
  });

  it('asks only once when two callers fire at the same moment', async () => {
    await Promise.all([maybeAskForStoreReview(), maybeAskForStoreReview()]);

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
  });

  it('can ask again after an earlier call finished without asking', async () => {
    storeReview.hasAction.mockResolvedValueOnce(false);
    await maybeAskForStoreReview();

    await maybeAskForStoreReview();

    expect(storeReview.requestReview).toHaveBeenCalledTimes(1);
  });
});
