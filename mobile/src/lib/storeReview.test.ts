import * as StoreReview from 'expo-store-review';
import { safeStorage } from './safeStorage';
import {
  STORE_REVIEW_LAST_ASKED_KEY,
  STORE_REVIEW_MIN_GAP_MS,
  maybeAskForStoreReview,
} from './storeReview';

jest.mock('expo-store-review', () => ({ hasAction: jest.fn(), requestReview: jest.fn() }));

const storeReview = StoreReview as jest.Mocked<typeof StoreReview>;
const NOW = Date.UTC(2026, 10, 10, 12, 0, 0);

describe('maybeAskForStoreReview', () => {
  beforeEach(() => {
    jest.spyOn(Date, 'now').mockReturnValue(NOW);
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

  it('never rejects when the native request fails, and still counts it as asked', async () => {
    storeReview.requestReview.mockRejectedValue(new Error('review flow failed'));

    await expect(maybeAskForStoreReview()).resolves.toBeUndefined();
    expect(await safeStorage.getItem(STORE_REVIEW_LAST_ASKED_KEY)).toBe(String(NOW));
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
