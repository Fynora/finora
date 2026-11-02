import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Privacy workstream 6: the sign-in and sign-up screens said "Bank-grade encryption" and "Your
 * financial data is encrypted", while Fynora encrypts only statement files and saved statement
 * passwords (Privacy Policy, Data Encryption & Security). Read from the source files, because these
 * screens need the whole auth context to render and the claim is in fixed text.
 */
const FILES = [
  '../AuthEntry.tsx',
  './MarketingPanel.tsx',
  './PasswordStep.tsx',
  './RegisterStep.tsx',
];

const OVERCLAIMS = [
  /bank[- ](grade|level)/i,
  /financial data is encrypted/i,
  /your data is encrypted/i,
  /end-to-end/i,
];

describe('sign-in and sign-up screens claim only the encryption Fynora does', () => {
  for (const file of FILES) {
    it(`${file} makes no broader encryption claim`, () => {
      const source = readFileSync(resolve(__dirname, file), 'utf8');
      expect(OVERCLAIMS.filter((p) => p.test(source)).map(String)).toEqual([]);
    });
  }
});
