import { describe, it, expect } from 'vitest';
import { looksLikeValidIdentifier } from './identifierPatterns';

describe('looksLikeValidIdentifier', () => {
  it.each(['jane@example.com', '9876543210', '+919876543210'])( // synthetic-ok
    'accepts %s',
    (value) => {
      expect(looksLikeValidIdentifier(value)).toBe(true);
    },
  );

  it.each([
    '123@',            // the reported bug: no "@domain.tld", not a real phone number either
    '123',
    'abc',
    '0876543210',      // synthetic-ok: 10 digits but doesn't start 6-9 -- not a real Indian mobile number
    '+15551234567',    // synthetic-ok: non-Indian number
    '',
    '   ',
  ])('rejects %s', (value) => {
    expect(looksLikeValidIdentifier(value)).toBe(false);
  });

  it('trims surrounding whitespace before checking', () => {
    expect(looksLikeValidIdentifier('  jane@example.com  ')).toBe(true);
  });
});
