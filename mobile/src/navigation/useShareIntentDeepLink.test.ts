import { renderHook, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useShareIntentDeepLink } from './useShareIntentDeepLink';

// Same key the hook itself uses (PENDING_SHARE_KEY is a private module const, not exported) --
// mirrors useNavigationStatePersistence.test.ts's own NAV_STATE_KEY, hardcoded the same way.
// AsyncStorage itself is mocked globally in src/test/setup.ts (an in-memory map with real async
// semantics, cleared before every test), so it's used here directly, not re-mocked per test.
const PENDING_SHARE_KEY = 'finora_pending_shared_statement';

const mockUseShareIntentContext = jest.fn();
jest.mock('expo-share-intent', () => ({
  useShareIntentContext: () => mockUseShareIntentContext(),
}));

function fakeShareIntentContext(overrides: Record<string, unknown> = {}) {
  return {
    hasShareIntent: false,
    shareIntent: { files: null, text: null, webUrl: null, type: null },
    resetShareIntent: jest.fn(),
    ...overrides,
  };
}

function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
  return {
    current: {},
    isReady: overrides.isReady ?? (() => true),
    navigate: jest.fn(),
  } as unknown as Parameters<typeof useShareIntentDeepLink>[0];
}

function pdfFile(name = 'statement.pdf') {
  return {
    fileName: name, mimeType: 'application/pdf', path: `file:///cache/${name}`,
    size: 1000, width: null, height: null, duration: null,
  };
}

describe('useShareIntentDeepLink', () => {
  beforeEach(() => {
    mockUseShareIntentContext.mockReset();
  });

  it('navigates to Import with the shared file when ready and signed in', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({
        file: { uri: 'file:///cache/statement.pdf', name: 'statement.pdf', type: 'application/pdf' },
        format: 'PDF',
      }),
    });
  });

  it('falls back to mimeType when the provider gave no fileName', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: null, mimeType: 'application/pdf', path: 'file:///cache/FILE_123.pdf', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({
        file: { uri: 'file:///cache/FILE_123.pdf', name: 'statement.pdf', type: 'application/pdf' },
        format: 'PDF',
      }),
    });
  });

  it('falls back to the resolved path when both fileName and mimeType are unhelpful', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: 'document', mimeType: 'application/octet-stream', path: 'file:///cache/document.csv', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFile: expect.objectContaining({ format: 'CSV' }),
    });
  });

  it('produces a sharedFileError, not a throw, when no name/mimeType/path gives a usable format', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: null, mimeType: 'application/octet-stream', path: 'content://com.example/1', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    expect(() => renderHook(() => useShareIntentDeepLink(navigationRef, true, true))).not.toThrow();
    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFileError: expect.objectContaining({ message: 'Choose a .csv or .pdf bank or credit card statement.' }),
    });
  });

  it('navigates with a sharedFileError for an unsupported shared file', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({
        hasShareIntent: true,
        shareIntent: {
          files: [{ fileName: 'photo.jpg', mimeType: 'image/jpeg', path: 'file:///cache/photo.jpg', size: 1, width: null, height: null, duration: null }],
          text: null, webUrl: null, type: 'file',
        },
      }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', {
      sharedFileError: expect.objectContaining({ message: "Choose a .csv or .pdf bank or credit card statement." }),
    });
  });

  it('stashes the share and does not navigate yet when not ready', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, false, true));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('replays the stashed share once ready becomes true', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready }: { ready: boolean }) => useShareIntentDeepLink(navigationRef, ready, true),
      { initialProps: { ready: false } },
    );
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true });

    expect(navigationRef.navigate).toHaveBeenCalledWith('Import', expect.objectContaining({ sharedFile: expect.anything() }));
  });

  it('drops a stashed share on a real sign-out, never replaying it for a later sign-in', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useShareIntentDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: true } },
    );
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: false, signedIn: false });
    rerender({ ready: true, signedIn: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('does nothing when hasShareIntent is false', () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('persists a stashed file to AsyncStorage, then clears it once consumed', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    expect(navigationRef.navigate).toHaveBeenCalled();
    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
  });

  it('recovers a persisted share on a fresh mount when nothing arrives live this session', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());
    const persisted = {
      file: { uri: 'file:///cache/statement.pdf', name: 'statement.pdf', type: 'application/pdf' },
      format: 'PDF',
      nonce: Date.now(),
    };
    await AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(persisted));

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    await waitFor(() => expect(navigationRef.navigate).toHaveBeenCalledWith('Import', { sharedFile: persisted }));
  });

  it('drops a persisted share older than an hour without consuming it', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(fakeShareIntentContext());
    const stale = {
      file: { uri: 'file:///cache/old.pdf', name: 'old.pdf', type: 'application/pdf' },
      format: 'PDF',
      nonce: Date.now() - 2 * 60 * 60 * 1000, // 2 hours old, past the 1-hour cutoff
    };
    await AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(stale));

    renderHook(() => useShareIntentDeepLink(navigationRef, true, true));

    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('clears the persisted share on a real sign-out, not just the in-memory one', async () => {
    const navigationRef = fakeNavigationRef();
    mockUseShareIntentContext.mockReturnValue(
      fakeShareIntentContext({ hasShareIntent: true, shareIntent: { files: [pdfFile()], text: null, webUrl: null, type: 'file' } }),
    );
    const { rerender } = renderHook(
      ({ ready, signedIn }: { ready: boolean; signedIn: boolean }) => useShareIntentDeepLink(navigationRef, ready, signedIn),
      { initialProps: { ready: false, signedIn: true } },
    );
    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).not.toBeNull());

    rerender({ ready: false, signedIn: false });

    await waitFor(async () => expect(await AsyncStorage.getItem(PENDING_SHARE_KEY)).toBeNull());
  });
});
