import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import * as appLock from './appLock';

const mockedHasHardware = LocalAuthentication.hasHardwareAsync as jest.MockedFunction<
  typeof LocalAuthentication.hasHardwareAsync
>;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.MockedFunction<
  typeof LocalAuthentication.isEnrolledAsync
>;
const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;

beforeEach(() => {
  appLock.__resetAuthenticatingStateForTests();
  appLock.__resetEnabledCacheForTests();
});

describe('isSupported', () => {
  it('requires both hardware and an enrolled biometric', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    expect(await appLock.isSupported()).toBe(true);
  });

  it('is false with hardware but nothing enrolled', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(false);
    expect(await appLock.isSupported()).toBe(false);
  });

  it('is false with no hardware at all', async () => {
    mockedHasHardware.mockResolvedValueOnce(false);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    expect(await appLock.isSupported()).toBe(false);
  });
});

describe('isEnabled / setEnabled', () => {
  it('defaults to disabled with nothing stored', async () => {
    expect(await appLock.isEnabled()).toBe(false);
  });

  it('persists true as the exact string isEnabled checks for', async () => {
    await appLock.setEnabled(true);
    expect(await appLock.isEnabled()).toBe(true);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBe('true');
  });

  it('removes the key entirely rather than writing a false value', async () => {
    await appLock.setEnabled(true);
    await appLock.setEnabled(false);
    expect(await appLock.isEnabled()).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
  });

  // D1 (Track D). "Absent" and "threw" must not collapse to the same false -- a genuinely absent
  // key means the user never turned the lock on (fine to open), but a thrown read means whether
  // the lock is on cannot actually be determined, which has to fail closed instead.
  it('fails closed to true (not the generic false-on-error safeStorage would give) when the read throws', async () => {
    const mockedGetItemAsync = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    mockedGetItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));

    expect(await appLock.isEnabled()).toBe(true);
  });
});

// AppLockGate needs to know SYNCHRONOUSLY, on a foreground return, whether it can safely leave the
// app uncovered -- the async SecureStore read is exactly the gap it is trying not to show anything
// in. That is only sound because setEnabled() is the one place the setting is ever written, so
// this cache cannot go stale. "Unknown" must stay distinct from "off": anything short of a
// confirmed read or write has to keep covering.
describe('isKnownDisabled', () => {
  it('is unknown (false) until the setting has actually been read or written', () => {
    expect(appLock.isKnownDisabled()).toBe(false);
  });

  it('becomes true after a read that finds the lock off', async () => {
    await appLock.isEnabled();
    expect(appLock.isKnownDisabled()).toBe(true);
  });

  it('is false after a read that finds the lock on', async () => {
    await appLock.setEnabled(true);
    await appLock.isEnabled();
    expect(appLock.isKnownDisabled()).toBe(false);
  });

  it('follows setEnabled() immediately, in both directions, without another read', async () => {
    await appLock.setEnabled(true);
    expect(appLock.isKnownDisabled()).toBe(false);
    await appLock.setEnabled(false);
    expect(appLock.isKnownDisabled()).toBe(true);
    await appLock.setEnabled(true);
    expect(appLock.isKnownDisabled()).toBe(false);
  });

  it('goes back to unknown when a read throws, rather than trusting the last answer', async () => {
    await appLock.isEnabled();
    expect(appLock.isKnownDisabled()).toBe(true);
    const mockedGetItemAsync = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    mockedGetItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));

    await appLock.isEnabled();

    expect(appLock.isKnownDisabled()).toBe(false);
  });

  // The dangerous interleaving: a read that started BEFORE Settings turned the lock on resolves
  // AFTER the write, still carrying the old "off". It must not put the cache back to "off", or the
  // next return to the app would skip the cover for a lock that is actually on.
  it('is not overwritten by a read that started before a write and resolves after it', async () => {
    const mockedGet = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    let resolveRead: (value: string | null) => void = () => {};
    mockedGet.mockImplementationOnce(() => new Promise<string | null>((resolve) => { resolveRead = resolve; }));

    const staleRead = appLock.isEnabled();
    await appLock.setEnabled(true);
    resolveRead(null);
    await staleRead;

    expect(appLock.isKnownDisabled()).toBe(false);
  });

  it('is not reset to unknown by a failed read that started before a write and finished after it', async () => {
    const mockedGet = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    let rejectRead: (error: Error) => void = () => {};
    mockedGet.mockImplementationOnce(() => new Promise<string | null>((_resolve, reject) => { rejectRead = reject; }));

    const staleRead = appLock.isEnabled();
    await appLock.setEnabled(false);
    rejectRead(new Error('keychain unavailable'));
    await staleRead;

    expect(appLock.isKnownDisabled()).toBe(true);
  });

  it('is unknown (not "off") while a write is in flight', async () => {
    await appLock.isEnabled();
    expect(appLock.isKnownDisabled()).toBe(true);
    const mockedSet = SecureStore.setItemAsync as jest.MockedFunction<typeof SecureStore.setItemAsync>;
    let release: () => void = () => {};
    mockedSet.mockImplementationOnce(() => new Promise<void>((resolve) => { release = resolve; }));

    const pending = appLock.setEnabled(true);
    expect(appLock.isKnownDisabled()).toBe(false);
    release();
    await pending;
    // This mocked write stored nothing, so the read-back confirms "off" -- the cache follows what
    // is actually stored, not what was asked for.
    expect(appLock.isKnownDisabled()).toBe(true);
  });

  // safeStorage swallows write failures, so setEnabled() resolves normally even when nothing was
  // stored. Believing it would leave the cache saying "off" while the lock is still on.
  it('reflects what is actually stored when turning the lock off silently fails', async () => {
    await appLock.setEnabled(true);
    const mockedDelete = SecureStore.deleteItemAsync as jest.MockedFunction<typeof SecureStore.deleteItemAsync>;
    mockedDelete.mockRejectedValueOnce(new Error('keychain unavailable'));

    await appLock.setEnabled(false);

    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBe('true');
    expect(appLock.isKnownDisabled()).toBe(false);
  });

  it('stays unknown when the write cannot be confirmed by reading it back', async () => {
    await appLock.isEnabled();
    const mockedGet = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    mockedGet.mockRejectedValueOnce(new Error('keychain unavailable'));

    await appLock.setEnabled(false);

    expect(appLock.isKnownDisabled()).toBe(false);
  });
});

describe('authenticate', () => {
  it('resolves true only on a genuine successful result', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    expect(await appLock.authenticate('Unlock Fynora')).toBe(true);
  });

  it('collapses an unsuccessful result to false', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    expect(await appLock.authenticate('Unlock Fynora')).toBe(false);
  });

  it('fails closed to false if authenticateAsync itself throws', async () => {
    mockedAuthenticateAsync.mockRejectedValueOnce(new Error('hardware busy'));
    expect(await appLock.authenticate('Unlock Fynora')).toBe(false);
  });

  it('leaves the device passcode fallback enabled (does not set disableDeviceFallback)', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await appLock.authenticate('Unlock Fynora');

    const call = mockedAuthenticateAsync.mock.calls[0][0];
    expect(call?.disableDeviceFallback).not.toBe(true);
  });
});

// isAuthenticating/justFinishedAuthenticating exist for AppLockGate's foreground listener to tell
// a self-induced AppState blip (from ANY caller's Face ID sheet, not just AppLockGate's own) apart
// from the user genuinely returning to the app -- see appLock.ts's own comment on why this has to
// be shared, module-level state rather than something scoped to one component.
describe('isAuthenticating / justFinishedAuthenticating', () => {
  afterEach(() => jest.restoreAllMocks());

  it('is false with nothing in flight and nothing ever resolved', () => {
    expect(appLock.isAuthenticating()).toBe(false);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(false);
  });

  it('is true for the entire duration of an authenticate() call, false again once it resolves', async () => {
    let resolveAuth: ((result: LocalAuthentication.LocalAuthenticationResult) => void) | undefined;
    mockedAuthenticateAsync.mockImplementationOnce(
      () => new Promise((resolve) => { resolveAuth = resolve; })
    );

    const pending = appLock.authenticate('Unlock Fynora');
    expect(appLock.isAuthenticating()).toBe(true);

    resolveAuth?.({ success: true });
    await pending;
    expect(appLock.isAuthenticating()).toBe(false);
  });

  it('reports justFinishedAuthenticating for windowMs after resolving, from either outcome', async () => {
    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(1_000_000);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    await appLock.authenticate('Unlock Fynora');

    nowSpy.mockReturnValue(1_000_000 + 200);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(true);

    nowSpy.mockReturnValue(1_000_000 + 1500);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(false);
  });
});

// isLocked/setLockedFlag exist so AuthContext's foreground-push handler (outside AppLockGate's
// own subtree) can tell the lock screen is showing right now, before it does anything with a
// native modal that would float above whatever AppLockGate itself is rendering -- see
// setLockedFlag's own doc comment.
describe('isLocked / setLockedFlag', () => {
  afterEach(() => appLock.__resetLockedFlagForTests());

  it('is false until something sets it', () => {
    expect(appLock.isLocked()).toBe(false);
  });

  it('reflects whatever was last set', () => {
    appLock.setLockedFlag(true);
    expect(appLock.isLocked()).toBe(true);

    appLock.setLockedFlag(false);
    expect(appLock.isLocked()).toBe(false);
  });
});
