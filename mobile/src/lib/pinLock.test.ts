import * as SecureStore from 'expo-secure-store';

import * as pinLockModule from './pinLock';
import * as appLock from './appLock';

// Real SHA-256 from Node -- see test/expoCryptoNodeMock.ts.
jest.mock('expo-crypto', () => require('../test/expoCryptoNodeMock'));

const store = (SecureStore as unknown as { __store: Map<string, string> }).__store;

// pinLock keeps no state in memory -- everything lives in SecureStore -- so the module itself is
// the same "after a restart" as before it; what a restart would lose is anything not in the store.
function load(): typeof import('./pinLock') {
  return pinLockModule;
}

describe('pinLock', () => {
  it.each(['', '123', '12345', 'abcd', '12a4', ' 1234'])('rejects %p as a PIN', async (pin) => {
    const pinLock = load();
    await expect(pinLock.setPin(pin)).rejects.toThrow();
    expect(await pinLock.hasPin()).toBe(false);
  });

  it('has no PIN until one is set', async () => {
    expect(await load().hasPin()).toBe(false);
  });

  it('accepts the right PIN and resets failures', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    await pinLock.checkPin('0000');

    expect(await pinLock.checkPin('4821')).toEqual({ ok: true });
    // A fresh budget after a success: the next wrong try leaves 4, not 3.
    expect(await pinLock.checkPin('0000')).toEqual({ ok: false, triesLeft: 4 });
  });

  it('counts down tries left', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');

    const results = [];
    for (let i = 0; i < 5; i++) results.push(await pinLock.checkPin('1111'));

    expect(results.map((r) => (r.ok ? 'ok' : r.triesLeft))).toEqual([4, 3, 2, 1, 0]);
    expect(pinLock.MAX_PIN_TRIES).toBe(5);
  });

  it('persists the wrong-try count across reloads (closing the app does not reset it)', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    for (let i = 0; i < 4; i++) await pinLock.checkPin('1111');
    // What survives the app being killed is exactly what is in SecureStore.
    expect(store.get('finora_pin_failures')).toBe('4');

    expect(await pinLock.checkPin('1111')).toEqual({ ok: false, triesLeft: 0 });
  });

  it('reads the count from storage, not from memory', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    store.set('finora_pin_failures', '4');

    expect(await pinLock.checkPin('1111')).toEqual({ ok: false, triesLeft: 0 });
  });

  it('stays at zero tries once the budget is spent, even for the right PIN', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    for (let i = 0; i < 5; i++) await pinLock.checkPin('1111');

    expect(await pinLock.checkPin('4821')).toEqual({ ok: false, triesLeft: 0 });
  });

  it('resetFailures gives a full budget back (after a biometric unlock)', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    await pinLock.checkPin('1111');
    await pinLock.checkPin('1111');

    await pinLock.resetFailures();

    expect(await pinLock.checkPin('1111')).toEqual({ ok: false, triesLeft: 4 });
  });

  it('setPin resets failures', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    await pinLock.checkPin('1111');
    await pinLock.setPin('7350');

    expect(await pinLock.checkPin('1111')).toEqual({ ok: false, triesLeft: 4 });
    expect(await pinLock.checkPin('7350')).toEqual({ ok: true });
  });

  it('clearPin removes everything', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    await pinLock.checkPin('1111');

    await pinLock.clearPin();

    expect(await pinLock.hasPin()).toBe(false);
    expect([...store.keys()].filter((k) => k.startsWith('finora_pin_'))).toEqual([]);
  });

  it('stores no plain PIN, and a different salt each time it is set', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    const firstHash = store.get('finora_pin_hash');
    expect([...store.values()]).not.toContain('4821');
    expect([...store.values()].some((v) => v.includes('4821'))).toBe(false);

    await pinLock.setPin('4821');
    expect(store.get('finora_pin_hash')).not.toBe(firstHash);
  });

  it('a wrong PIN with no PIN set is not accepted', async () => {
    const pinLock = load();
    expect((await pinLock.checkPin('0000')).ok).toBe(false);
  });

  it('counts as authenticating while a check runs, so nothing is drawn over the PIN screen', async () => {
    const pinLock = load();
    appLock.__resetAuthenticatingStateForTests();
    await pinLock.setPin('4821');

    const pending = pinLock.checkPin('4821');
    expect(appLock.isAuthenticating()).toBe(true);
    await pending;
    expect(appLock.isAuthenticating()).toBe(false);
  });

  // A storage read that fails must never open the app or give a free guess.
  it('treats an unreadable PIN as set, so a storage glitch cannot skip to setting a new one', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    (SecureStore.getItemAsync as jest.Mock).mockRejectedValueOnce(new Error('keystore busy'));

    expect(await pinLock.hasPin()).toBe(true);
  });

  it('does not check a PIN when the try count cannot be read, and counts nothing', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    store.set('finora_pin_failures', '3');
    (SecureStore.getItemAsync as jest.Mock).mockRejectedValueOnce(new Error('keystore busy'));

    await expect(pinLock.checkPin('4821')).rejects.toThrow();
    expect(store.get('finora_pin_failures')).toBe('3');
  });

  it('does not check a PIN when the stored PIN cannot be read', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');
    const real = (SecureStore.getItemAsync as jest.Mock).getMockImplementation()!;
    (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (k: string) => {
      if (k === 'finora_pin_hash') throw new Error('keystore busy');
      return real(k);
    });

    try {
      await expect(pinLock.checkPin('0000')).rejects.toThrow();
    } finally {
      (SecureStore.getItemAsync as jest.Mock).mockImplementation(real);
    }
    expect(store.get('finora_pin_failures')).toBe('0');
  });

  it('counts every guess when several are checked at once', async () => {
    const pinLock = load();
    await pinLock.setPin('4821');

    await Promise.all([pinLock.checkPin('1111'), pinLock.checkPin('2222'), pinLock.checkPin('3333')]);

    expect(store.get('finora_pin_failures')).toBe('3');
  });
});
