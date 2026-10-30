const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/**
 * Base64-encodes raw bytes.
 *
 * Written out rather than reached for from a global or a dependency. React Native does expose
 * `btoa`, but it takes a *binary string*, so using it means first building a string as long as the
 * file out of char codes -- and its presence is a property of the runtime rather than of anything
 * this project declares. A statement can be up to 10 MB (UPLOAD_MAX_FILE_SIZE), so neither the
 * intermediate string nor an undeclared global is worth it when the encoder is fifteen lines and
 * can be tested directly.
 *
 * Used by statementImportsApi.downloadFile: expo-file-system's writeSync() takes a string, and axios
 * on React Native hands back an ArrayBuffer with no Blob to convert through.
 */
export function encodeBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let out = '';

  // Three bytes -> four characters. The tail (1 or 2 leftover bytes) is padded with '=' below.
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = bytes[i + 1];
    const b2 = bytes[i + 2];

    out += ALPHABET[b0 >> 2];
    out += ALPHABET[((b0 & 0x03) << 4) | ((b1 ?? 0) >> 4)];
    out += b1 === undefined ? '=' : ALPHABET[((b1 & 0x0f) << 2) | ((b2 ?? 0) >> 6)];
    out += b2 === undefined ? '=' : ALPHABET[b2 & 0x3f];
  }

  return out;
}

const DECODE_TABLE = (() => {
  const table = new Int16Array(128).fill(-1);
  for (let i = 0; i < ALPHABET.length; i++) table[ALPHABET.charCodeAt(i)] = i;
  return table;
})();

/**
 * Decodes standard base64 (padding optional) into raw bytes: encodeBase64's inverse, written out
 * for the same reasons. Throws on any character outside the alphabet rather than decoding garbage.
 *
 * Used by queryCacheCipher.ts: the cache is stored as base64 text, but on Android expo-crypto's
 * AESSealedData.fromCombined only accepts bytes. Passing it the text is what made every restore
 * fail there.
 */
export function decodeBase64(text: string): Uint8Array {
  let end = text.length;
  while (end > 0 && text.charCodeAt(end - 1) === 0x3d /* '=' */) end -= 1;
  if (end % 4 === 1) throw new Error('decodeBase64: invalid length');

  const out = new Uint8Array(Math.floor((end * 3) / 4));
  let o = 0;
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < end; i++) {
    const code = text.charCodeAt(i);
    const value = code < 128 ? DECODE_TABLE[code] : -1;
    if (value < 0) throw new Error(`decodeBase64: invalid character at ${i}`);
    buffer = ((buffer << 6) | value) & 0xffffff;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[o++] = (buffer >> bits) & 0xff;
    }
  }
  return out;
}
