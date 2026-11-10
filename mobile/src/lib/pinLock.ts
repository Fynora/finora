import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
import { withAuthenticating } from './appLock';
import { safeStorage } from './safeStorage';

/**
 * The app PIN: 4 digits, required to open the app on a signed-in phone (design 2026-11-10).
 *
 * The PIN never leaves the phone and is never stored as itself: only SHA-256 of a random salt plus
 * the PIN, in SecureStore (Keychain / Keystore). Stated limit: 4 digits are 10,000 choices, so
 * anyone able to read SecureStore could try them all against the hash. Reading SecureStore takes a
 * compromised phone, and on such a phone the refresh token in the same store is already exposed, so
 * a slower hash would not change the outcome.
 *
 * The wrong-try count lives in SecureStore too, not in memory, so killing the app between tries
 * does not reset it. Nothing here is kept in module state.
 */

const HASH_KEY = 'finora_pin_hash';
const SALT_KEY = 'finora_pin_salt';
const FAILURES_KEY = 'finora_pin_failures';

/** Wrong PINs in a row before the phone is signed out. */
export const MAX_PIN_TRIES = 5;

export type PinCheck = { ok: true } | { ok: false; triesLeft: number };

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

function hashPin(salt: string, pin: string): Promise<string> {
  return Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, salt + pin, {
    encoding: Crypto.CryptoEncoding.HEX,
  });
}

async function readFailures(): Promise<number> {
  const raw = await safeStorage.getItem(FAILURES_KEY);
  const n = raw === null ? 0 : Number.parseInt(raw, 10);
  // An unreadable count is treated as the budget spent, not as a fresh one: failing open here
  // would let a tampered value buy unlimited tries.
  return Number.isFinite(n) && n >= 0 ? n : MAX_PIN_TRIES;
}

export async function hasPin(): Promise<boolean> {
  return (await safeStorage.getItem(HASH_KEY)) !== null;
}

/** Sets a new PIN (exactly 4 digits) and starts a fresh try budget. */
export async function setPin(pin: string): Promise<void> {
  if (!/^\d{4}$/.test(pin)) throw new Error('A PIN is exactly 4 digits');
  const salt = toHex(await Crypto.getRandomBytesAsync(16));
  const hash = await hashPin(salt, pin);
  // Written with SecureStore directly, not safeStorage: a PIN that silently failed to save would
  // leave the user unable to open the app. Let the caller see the error.
  await SecureStore.setItemAsync(SALT_KEY, salt);
  await SecureStore.setItemAsync(HASH_KEY, hash);
  await SecureStore.setItemAsync(FAILURES_KEY, '0');
}

/**
 * Checks a PIN against the stored one. A wrong PIN is counted (and saved) before this returns.
 * Once the budget is spent every check fails, even the right PIN: the caller signs out at
 * `triesLeft === 0`.
 */
export function checkPin(pin: string): Promise<PinCheck> {
  return withAuthenticating(async () => {
    const failures = await readFailures();
    if (failures >= MAX_PIN_TRIES) return { ok: false, triesLeft: 0 };
    const [salt, stored] = await Promise.all([safeStorage.getItem(SALT_KEY), safeStorage.getItem(HASH_KEY)]);
    if (salt !== null && stored !== null && (await hashPin(salt, pin)) === stored) {
      await safeStorage.setItem(FAILURES_KEY, '0');
      return { ok: true };
    }
    const next = failures + 1;
    await SecureStore.setItemAsync(FAILURES_KEY, String(next));
    return { ok: false, triesLeft: Math.max(0, MAX_PIN_TRIES - next) };
  });
}

/** A biometric unlock proves the owner as well as the PIN does: full budget again. */
export async function resetFailures(): Promise<void> {
  await safeStorage.setItem(FAILURES_KEY, '0');
}

/** Sign-out: the next account on this phone starts with no PIN. */
export async function clearPin(): Promise<void> {
  await Promise.all([
    safeStorage.removeItem(HASH_KEY),
    safeStorage.removeItem(SALT_KEY),
    safeStorage.removeItem(FAILURES_KEY),
  ]);
}
