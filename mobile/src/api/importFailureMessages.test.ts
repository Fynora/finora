import { importFailureMessage, importFailureTitle } from './importFailureMessages';
import { PDF_PASSWORD_REQUIRED, PDF_TOO_LARGE, TRUST_REVIEW_REJECTED } from './errorCodes';

describe('importFailureMessage', () => {
  it('has a curated message for a trust-review rejection, not the generic fallback', () => {
    const message = importFailureMessage(TRUST_REVIEW_REJECTED);
    expect(message).toBeDefined();
    expect(message).toMatch(/could not read it accurately enough/);
  });

  it('says what to do when a PDF has too many pages, rather than nothing', () => {
    expect(importFailureMessage(PDF_TOO_LARGE)).toMatch(/too many pages.*smaller files/i);
  });

  it('says what to do when a queued job failed for want of a password, not nothing', () => {
    // Prod, 2026-09-19: no entry meant a bare "Couldn't finish" with no reason on the failed card.
    expect(importFailureMessage(PDF_PASSWORD_REQUIRED)).toMatch(/password protected.*enter the password/i);
  });

  it('returns undefined for an unmapped code, leaving the fallback decision to the caller', () => {
    expect(importFailureMessage('SOME_UNMAPPED_CODE')).toBeUndefined();
  });

  it('returns undefined for null/absent codes', () => {
    expect(importFailureMessage(null)).toBeUndefined();
    expect(importFailureMessage(undefined)).toBeUndefined();
  });

  // Every code that has a message also has a plain headline: a message with no headline leaves the
  // card leading with a generic error, which is what a user reads first.
  it('has a headline for every code that has a message', () => {
    const codes = ['IMPORT_001', 'IMPORT_007', 'IMPORT_008', 'IMPORT_010', 'IMPORT_011', 'IMPORT_013', 'IMPORT_014', 'IMPORT_015'];
    for (const code of codes) {
      expect([code, importFailureMessage(code) !== undefined]).toEqual([code, true]);
      expect([code, importFailureTitle(code) !== undefined]).toEqual([code, true]);
    }
  });

  it('has no headline for a code it has no message for', () => {
    expect(importFailureTitle('SOME_UNMAPPED_CODE')).toBeUndefined();
    expect(importFailureTitle(null)).toBeUndefined();
  });
});
