import * as Crypto from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';
import { withAuthenticating } from './appLock';
import { safeStorage } from './safeStorage';

/**
 * The app PIN: 4 digits, required to open the app on a signed-in phone (design 2026-11-10).
 *
 * The PIN never leaves the phone and is never stored as itself: only SHA-256 of a random salt plus
 * the PIN, in SecureStore (Keychain / Keystore). Salt and hash are one stored value, so a save that
 * fails partway can never pair a new salt with the old hash and leave no PIN that works. Stated limit: 4 digits are 10,000 choices, so
 * anyone able to read SecureStore could try them all against the hash. Reading SecureStore takes a
 * compromised phone, and on such a phone the refresh token in the same store is already exposed, so
 * a slower hash would not change the outcome.
 *
 * The wrong-try count lives in SecureStore too, not in memory, so killing the app between tries
 * does not reset it. Nothing here is kept in module state.
 */

// "<salt hex>:<sha-256 hex>"
const HASH_KEY = 'finora_pin_hash';
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

/** The wrong-try count. Read straight from SecureStore, not through safeStorage: safeStorage turns
 *  a failed read into "nothing stored", which here would hand back a fresh budget. A failed read
 *  throws instead, so the caller checks nothing rather than allowing an uncounted guess. */
async function readFailures(): Promise<number> {
  const raw = await SecureStore.getItemAsync(FAILURES_KEY);
  const n = raw === null ? 0 : Number.parseInt(raw, 10);
  // A stored value that is not a count is treated as the budget spent, not as a fresh one: failing
  // open here would let a tampered value buy unlimited tries.
  return Number.isFinite(n) && n >= 0 ? n : MAX_PIN_TRIES;
}

/**
 * Whether this phone has a PIN. A failed read answers yes: answering no would send the user to PIN
 * setup, which sets a new PIN without the old one -- a storage glitch would then skip the lock.
 * The lock screen that shows instead reports a failed check and lets them try again.
 */
export async function hasPin(): Promise<boolean> {
  try {
    return (await SecureStore.getItemAsync(HASH_KEY)) !== null;
  } catch {
    return true;
  }
}

/** Sets a new PIN (exactly 4 digits) and starts a fresh try budget. */
export async function setPin(pin: string): Promise<void> {
  if (!/^\d{4}$/.test(pin)) throw new Error('A PIN is exactly 4 digits');
  const salt = toHex(await Crypto.getRandomBytesAsync(16));
  const hash = await hashPin(salt, pin);
  // Written with SecureStore directly, not safeStorage: a PIN that silently failed to save would
  // leave the user unable to open the app. Let the caller see the error.
  // The fresh budget first: if the PIN write after it fails, the old PIN still works and its count
  // is unchanged in practice (setPin follows a correct PIN or a sign-in, both of which reset it).
  await SecureStore.setItemAsync(FAILURES_KEY, '0');
  await SecureStore.setItemAsync(HASH_KEY, `${salt}:${hash}`);
}

// One check at a time. Two checks running together would both read the same count and both write
// count + 1, letting two guesses cost one try.
let checkQueue: Promise<unknown> = Promise.resolve();

/**
 * Checks a PIN against the stored one. A wrong PIN is counted (and saved) before this returns.
 * Once the budget is spent every check fails, even the right PIN: the caller signs out at
 * `triesLeft === 0`. Throws when the PIN or the count cannot be read or the count cannot be saved:
 * the caller says the PIN could not be checked, and nothing is opened or left uncounted.
 */
export function checkPin(pin: string): Promise<PinCheck> {
  const previous = checkQueue;
  // Counted as authenticating from the moment it is asked for, including any wait in the queue.
  const run = withAuthenticating(() => previous.then(async (): Promise<PinCheck> => {
    const failures = await readFailures();
    if (failures >= MAX_PIN_TRIES) return { ok: false, triesLeft: 0 };
    const stored = await SecureStore.getItemAsync(HASH_KEY);
    const [salt, hash] = stored?.split(':') ?? [];
    if (salt && hash && (await hashPin(salt, pin)) === hash) {
      await SecureStore.setItemAsync(FAILURES_KEY, '0');
      return { ok: true };
    }
    const next = failures + 1;
    await SecureStore.setItemAsync(FAILURES_KEY, String(next));
    return { ok: false, triesLeft: Math.max(0, MAX_PIN_TRIES - next) };
  }));
  checkQueue = run.catch(() => undefined);
  return run;
}

/** A biometric unlock proves the owner as well as the PIN does: full budget again. */
export async function resetFailures(): Promise<void> {
  await safeStorage.setItem(FAILURES_KEY, '0');
}

/** Sign-out: the next account on this phone starts with no PIN. */
export async function clearPin(): Promise<void> {
  await Promise.all([
    safeStorage.removeItem(HASH_KEY),
    safeStorage.removeItem(FAILURES_KEY),
  ]);
}
