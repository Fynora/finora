/**
 * expo-crypto backed by Node's own crypto, for tests that store a PIN (lib/pinLock.ts). There is no
 * native module under the test runner; a real SHA-256 keeps the PIN check honest instead of a fake
 * that would accept whatever pinLock did. Use with:
 *   jest.mock('expo-crypto', () => require('../test/expoCryptoNodeMock'));
 */
const nodeCrypto = jest.requireActual<typeof import('crypto')>('crypto');

export const CryptoDigestAlgorithm = { SHA256: 'SHA-256' };
export const CryptoEncoding = { HEX: 'hex' };
export const getRandomBytesAsync = jest.fn(async (n: number) => new Uint8Array(nodeCrypto.randomBytes(n)));
export const digestStringAsync = jest.fn(async (_algorithm: string, data: string) =>
  nodeCrypto.createHash('sha256').update(data, 'utf8').digest('hex'));
