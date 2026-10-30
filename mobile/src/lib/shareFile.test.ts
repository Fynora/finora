import { AppState, Platform } from 'react-native';
import * as Sharing from 'expo-sharing';
import { File } from 'expo-file-system';
import { __resetSharingStateForTests, isSharing } from './appLock';
import { SHARE_RETURN_GRACE_MS, shareFileAndCleanUp } from './shareFile';

jest.mock('expo-sharing', () => ({ isAvailableAsync: jest.fn(), shareAsync: jest.fn() }));
jest.mock('expo-file-system', () => ({
  File: class {
    uri: string;
    delete = jest.fn();
    constructor(uri: string) { this.uri = uri; }
  },
}));

const isAvailableAsync = Sharing.isAvailableAsync as jest.Mock;
const shareAsync = Sharing.shareAsync as jest.Mock;
const OPTIONS = { mimeType: 'application/pdf', dialogTitle: 'report.pdf' };

// Jest's AppState cannot emit on its own; capture the listener and fire it by hand.
let emit: ((state: string) => void) | undefined;
const removeListener = jest.fn();

/** A share call that settles only when the test says so (or never). */
function pendingShare() {
  let settle!: { resolve: () => void; reject: (e: unknown) => void };
  shareAsync.mockReturnValue(new Promise<void>((resolve, reject) => { settle = { resolve, reject }; }));
  return () => settle;
}

/** Runs the share and records whether it has settled, without awaiting it. */
function startShare(file: File) {
  const state = { done: false, error: undefined as unknown };
  const promise = shareFileAndCleanUp(file, OPTIONS).then(
    () => { state.done = true; },
    (e: unknown) => { state.done = true; state.error = e; },
  );
  return { state, promise };
}

const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };

beforeEach(() => {
  jest.useFakeTimers();
  __resetSharingStateForTests();
  emit = undefined;
  removeListener.mockClear();
  isAvailableAsync.mockReset().mockResolvedValue(true);
  shareAsync.mockReset();
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_: string, listener: (s: string) => void) => {
    emit = listener;
    return { remove: removeListener };
  }) as never);
  jest.replaceProperty(Platform, 'OS', 'android');
});

afterEach(() => {
  jest.restoreAllMocks();
  jest.useRealTimers();
});

describe('shareFileAndCleanUp', () => {
  it('passes the file to the share sheet and deletes it once the share settles', async () => {
    shareAsync.mockResolvedValue(undefined);
    const file = new File('file:///cache/report.pdf');

    await shareFileAndCleanUp(file, OPTIONS);

    expect(shareAsync).toHaveBeenCalledWith('file:///cache/report.pdf', OPTIONS);
    expect(file.delete).toHaveBeenCalledTimes(1);
    expect(removeListener).toHaveBeenCalledTimes(1);
    expect(isSharing()).toBe(false);
  });

  it('rejects when the share fails, and still deletes the file', async () => {
    shareAsync.mockRejectedValue(new Error('Failed to share the file'));
    const file = new File('file:///cache/report.pdf');

    await expect(shareFileAndCleanUp(file, OPTIONS)).rejects.toThrow('Failed to share the file');
    expect(file.delete).toHaveBeenCalledTimes(1);
    expect(removeListener).toHaveBeenCalledTimes(1);
  });

  it('throws without sharing when sharing is unavailable', async () => {
    isAvailableAsync.mockResolvedValue(false);
    const file = new File('file:///cache/report.pdf');

    await expect(shareFileAndCleanUp(file, OPTIONS)).rejects.toThrow('Sharing is not available on this device.');
    expect(shareAsync).not.toHaveBeenCalled();
    expect(file.delete).toHaveBeenCalledTimes(1);
  });

  describe('on Android, a share call that never settles', () => {
    it('stops being waited for once the app is back in front and the grace period passes', async () => {
      pendingShare();
      const file = new File('file:///cache/report.pdf');
      const { state, promise } = startShare(file);
      await flush();

      // The sheet opens (the app leaves the front), then Back closes it.
      emit?.('background');
      emit?.('active');
      jest.advanceTimersByTime(SHARE_RETURN_GRACE_MS - 1);
      await flush();
      expect(state.done).toBe(false);

      jest.advanceTimersByTime(1);
      await promise;
      expect(state.error).toBeUndefined();
      expect(file.delete).toHaveBeenCalledTimes(1);
      expect(removeListener).toHaveBeenCalledTimes(1);
      // The app lock's share suppression ends too, rather than staying on for the rest of the session.
      expect(isSharing()).toBe(false);
    });

    it('is not given up on while the share sheet is still open', async () => {
      pendingShare();
      const { state } = startShare(new File('file:///cache/report.pdf'));
      await flush();

      emit?.('background');
      jest.advanceTimersByTime(60_000);
      await flush();
      expect(state.done).toBe(false);
    });

    it('is not given up on for an "active" that was never preceded by leaving the app', async () => {
      pendingShare();
      const { state } = startShare(new File('file:///cache/report.pdf'));
      await flush();

      emit?.('active');
      jest.advanceTimersByTime(60_000);
      await flush();
      expect(state.done).toBe(false);
    });

    it('restarts the grace period when the app leaves the front again before it ends', async () => {
      pendingShare();
      const { state, promise } = startShare(new File('file:///cache/report.pdf'));
      await flush();

      emit?.('background');
      emit?.('active');
      jest.advanceTimersByTime(SHARE_RETURN_GRACE_MS - 100);
      emit?.('background');
      jest.advanceTimersByTime(60_000);
      await flush();
      expect(state.done).toBe(false);

      emit?.('active');
      jest.advanceTimersByTime(SHARE_RETURN_GRACE_MS);
      await promise;
      expect(state.done).toBe(true);
    });

    it('takes the share call\'s own result when it arrives within the grace period', async () => {
      const settle = pendingShare();
      const { state, promise } = startShare(new File('file:///cache/report.pdf'));
      await flush();

      emit?.('background');
      emit?.('active');
      settle().reject(new Error('Failed to share the file'));
      await promise;
      expect(state.error).toEqual(new Error('Failed to share the file'));

      // The grace timer was cancelled: nothing fires later.
      expect(jest.getTimerCount()).toBe(0);
    });
  });

  it('on iOS, waits for the share call itself and does not watch the app state', async () => {
    jest.replaceProperty(Platform, 'OS', 'ios');
    const settle = pendingShare();
    const { state, promise } = startShare(new File('file:///cache/report.pdf'));
    await flush();

    expect(AppState.addEventListener).not.toHaveBeenCalled();
    jest.advanceTimersByTime(60_000);
    await flush();
    expect(state.done).toBe(false);

    settle().resolve();
    await promise;
    expect(state.done).toBe(true);
  });
});
