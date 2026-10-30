import { decodeBase64, encodeBase64 } from './base64';

function bytes(...values: number[]): ArrayBuffer {
  return new Uint8Array(values).buffer;
}

function ascii(text: string): ArrayBuffer {
  return new Uint8Array([...text].map((c) => c.charCodeAt(0))).buffer;
}

describe('encodeBase64', () => {
  it('encodes lengths that are not a multiple of three, with the right padding', () => {
    // The classic RFC 4648 vectors -- these are what catch an off-by-one in the tail handling,
    // which is the only part of this that is easy to get wrong.
    expect(encodeBase64(ascii(''))).toBe('');
    expect(encodeBase64(ascii('f'))).toBe('Zg==');
    expect(encodeBase64(ascii('fo'))).toBe('Zm8=');
    expect(encodeBase64(ascii('foo'))).toBe('Zm9v');
    expect(encodeBase64(ascii('foob'))).toBe('Zm9vYg==');
    expect(encodeBase64(ascii('fooba'))).toBe('Zm9vYmE=');
    expect(encodeBase64(ascii('foobar'))).toBe('Zm9vYmFy');
  });

  it('encodes bytes that are not printable text', () => {
    // A statement is a PDF or a CSV read as raw bytes, not a string -- 0x00 and 0xff have to
    // survive, and a signed-byte mistake would corrupt exactly these.
    expect(encodeBase64(bytes(0x00, 0x00, 0x00))).toBe('AAAA');
    expect(encodeBase64(bytes(0xff, 0xff, 0xff))).toBe('////');
    expect(encodeBase64(bytes(0xfb, 0xff, 0xbf))).toBe('+/+/');
    // '%PDF-1.6' -- the header of every file this actually gets pointed at.
    expect(encodeBase64(bytes(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x36))).toBe('JVBERi0xLjY=');
  });

  it('round-trips through the platform decoder', () => {
    // Independent check: something else has to agree, or these assertions are just restating the
    // implementation. Every byte value appears at least once.
    const all = new Uint8Array(256);
    for (let i = 0; i < 256; i++) all[i] = i;

    const decoded = Buffer.from(encodeBase64(all.buffer), 'base64');

    expect(Uint8Array.from(decoded)).toEqual(all);
  });
});

describe('decodeBase64', () => {
  it('decodes the RFC 4648 vectors, padded and unpadded', () => {
    const text = (b: Uint8Array) => String.fromCharCode(...b);
    expect(text(decodeBase64(''))).toBe('');
    expect(text(decodeBase64('Zg=='))).toBe('f');
    expect(text(decodeBase64('Zm8='))).toBe('fo');
    expect(text(decodeBase64('Zm9v'))).toBe('foo');
    expect(text(decodeBase64('Zm9vYg=='))).toBe('foob');
    expect(text(decodeBase64('Zm9vYmE='))).toBe('fooba');
    expect(text(decodeBase64('Zm9vYmFy'))).toBe('foobar');
    expect(text(decodeBase64('Zm9vYg'))).toBe('foob');
  });

  it('agrees with the platform encoder for every byte value', () => {
    const all = new Uint8Array(256);
    for (let i = 0; i < 256; i++) all[i] = i;

    expect(decodeBase64(Buffer.from(all).toString('base64'))).toEqual(all);
  });

  it('round-trips encodeBase64 at lengths around the 3-byte boundary', () => {
    for (let n = 0; n < 10; n++) {
      const input = new Uint8Array(n).map((_, i) => (i * 37 + 11) & 0xff);
      expect(decodeBase64(encodeBase64(input.buffer))).toEqual(input);
    }
  });

  it('throws on a character outside the alphabet instead of decoding garbage', () => {
    expect(() => decodeBase64('Zm9v!A==')).toThrow();
    expect(() => decodeBase64('Zm9 v')).toThrow();
  });
});
