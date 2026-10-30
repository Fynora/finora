/**
 * Decodes UTF-8 bytes into a string.
 *
 * Written out rather than reached for `TextDecoder`: Hermes does not ship a spec-compliant
 * TextDecoder without an extra polyfill dependency this project doesn't have (see
 * facebook/hermes#1403) -- same reasoning as `encodeBase64` in base64.ts avoiding `btoa`.
 *
 * Used by statementImportsApi.downloadFile's error handling: axios on React Native hands back an
 * ArrayBuffer even for a failed request's JSON error body (responseType: 'arraybuffer' applies to
 * error responses too), so the envelope has to be decoded back into text before it can be parsed.
 */
/**
 * Encodes a string into UTF-8 bytes -- decodeUtf8's inverse, and written out for the identical
 * reason: no spec-compliant TextEncoder under Hermes without a polyfill this project doesn't have.
 *
 * Used by queryCacheCipher.ts (D4, Track D security cleanup): expo-crypto's aesEncryptAsync takes
 * raw bytes, not a JS string, for the JSON payload being encrypted before it reaches AsyncStorage.
 */
export function encodeUtf8(str: string): Uint8Array {
  // Written straight into a byte array sized for the worst case (3 bytes per UTF-16 unit; a
  // surrogate pair is 2 units and 4 bytes), then trimmed. The earlier version pushed every byte
  // onto a JS array and copied it at the end; on the persisted query cache (~350K characters) that
  // took 97-256 ms of the JS thread per save on a 2-core Android emulator, which is what froze
  // typing in Ledger search.
  const bytes = new Uint8Array(str.length * 3);
  let n = 0;

  for (let i = 0; i < str.length; i++) {
    let codePoint = str.charCodeAt(i);

    // A UTF-16 surrogate pair represents one codepoint above the BMP -- decodeUtf8's 4-byte
    // branch run in reverse, combined back into the single codepoint it actually encodes.
    if (codePoint >= 0xd800 && codePoint <= 0xdbff && i + 1 < str.length) {
      const low = str.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        codePoint = 0x10000 + ((codePoint - 0xd800) << 10) + (low - 0xdc00);
        i += 1;
      }
    }

    if (codePoint < 0x80) {
      bytes[n++] = codePoint;
    } else if (codePoint < 0x800) {
      bytes[n++] = 0xc0 | (codePoint >> 6);
      bytes[n++] = 0x80 | (codePoint & 0x3f);
    } else if (codePoint < 0x10000) {
      bytes[n++] = 0xe0 | (codePoint >> 12);
      bytes[n++] = 0x80 | ((codePoint >> 6) & 0x3f);
      bytes[n++] = 0x80 | (codePoint & 0x3f);
    } else {
      bytes[n++] = 0xf0 | (codePoint >> 18);
      bytes[n++] = 0x80 | ((codePoint >> 12) & 0x3f);
      bytes[n++] = 0x80 | ((codePoint >> 6) & 0x3f);
      bytes[n++] = 0x80 | (codePoint & 0x3f);
    }
  }

  // A copy, not a subarray: callers hand this to native code, which should not see the unused
  // tail of the worst-case buffer through .buffer.
  return bytes.slice(0, n);
}

// Code units are collected into chunks and turned into a string a chunk at a time. Building the
// string one `+=` per character was the restore-side cost on large inputs. 8192 stays well under
// any engine's argument-count limit for String.fromCharCode.apply.
const DECODE_CHUNK = 8192;

/**
 * Bug found in review (Track D/D4): queryCacheCipher.ts calls this with `someUint8Array.buffer`,
 * not the view itself. `.buffer` is the view's UNDERLYING ArrayBuffer, which can be larger than
 * the view when the view has a non-zero byteOffset or a byteLength short of the buffer's own
 * (a subarray, or a view expo-crypto handed back into some larger pooled buffer) -- `new
 * Uint8Array(buffer)` ignores both and would decode whatever surrounds the intended bytes instead
 * of just them. Accepting the view directly and reading its own byteOffset/byteLength keeps this
 * correct regardless of what the caller passes; `ArrayBufferLike` is kept for
 * statementImportsApi.downloadFile's use, which already has a real top-level ArrayBuffer with no
 * view to speak of.
 */
export function decodeUtf8(input: ArrayBufferLike | ArrayBufferView): string {
  const bytes = ArrayBuffer.isView(input)
    ? new Uint8Array(input.buffer, input.byteOffset, input.byteLength)
    : new Uint8Array(input);
  const parts: string[] = [];
  let units: number[] = [];
  let i = 0;

  while (i < bytes.length) {
    const b0 = bytes[i];

    if (b0 < 0x80) {
      units.push(b0);
      i += 1;
    } else if (b0 >> 5 === 0b110) {
      units.push(((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
    } else if (b0 >> 4 === 0b1110) {
      units.push(((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
      i += 3;
    } else {
      // 4-byte sequence -- decodes to a codepoint above the BMP, which JS strings represent as a
      // UTF-16 surrogate pair rather than one code unit.
      const cp = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
      const adjusted = cp - 0x10000;
      units.push(0xd800 + (adjusted >> 10), 0xdc00 + (adjusted & 0x3ff));
      i += 4;
    }

    if (units.length >= DECODE_CHUNK) {
      parts.push(String.fromCharCode.apply(null, units));
      units = [];
    }
  }

  if (units.length > 0) parts.push(String.fromCharCode.apply(null, units));
  return parts.join('');
}
