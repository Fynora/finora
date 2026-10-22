import { describe, it, expect } from 'vitest';
import { importFailureMessage, importFailureTitle, isStatementRefusal } from './importFailureMessages';
import {
  IMPORT_SESSION_HELD_FOR_REVIEW, IMPORT_SYSTEM_BUSY, NO_TRANSACTIONS_FOUND, PAYMENT_APP_HISTORY, PDF_PASSWORD_REQUIRED,
  PDF_TOO_LARGE, TRUST_REVIEW_REJECTED,
} from './errorCodes';

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

  // Every code that has a message also has a plain headline: a message with no headline leaves the
  // card leading with a generic error, which is what a user reads first.
  it('has a headline for every code that has a message', () => {
    const codes = ['IMPORT_001', 'IMPORT_007', 'IMPORT_008', 'IMPORT_010', 'IMPORT_011', 'IMPORT_013', 'IMPORT_014', 'IMPORT_015', 'IMPORT_017', 'IMPORT_018', 'IMPORT_020'];
    for (const code of codes) {
      expect(importFailureMessage(code), code).toBeDefined();
      expect(importFailureTitle(code), code).toBeDefined();
    }
  });

  it('says the file mixes several accounts and what to upload instead', () => {
    expect(importFailureMessage('IMPORT_020')).toMatch(/more than one account/i);
    expect(importFailureMessage('IMPORT_020')).toMatch(/upload each bank or card statement on its own/i);
    expect(importFailureTitle('IMPORT_020')).toBe('This file mixes several accounts');
  });

  it('says the file is a payment app history and what to import instead', () => {
    expect(importFailureMessage('IMPORT_018')).toMatch(/payment app history.*not a bank statement/i);
    expect(importFailureMessage('IMPORT_018')).toMatch(/import those bank statements instead/i);
    expect(importFailureTitle('IMPORT_018')).toBe('This is a payment app history');
    // Names no app: the backend recognises a UPI app's history by its row grammar, not its brand,
    // so the copy must stay true whichever app produced the file.
    expect(importFailureMessage('IMPORT_018')).not.toMatch(/paytm|phonepe|google pay/i);
  });

  it('says what to do when a CSV is malformed, in plain words', () => {
    expect(importFailureMessage('IMPORT_017')).toMatch(/damaged or cut short.*downloading it again/i);
    expect(importFailureTitle('IMPORT_017')).toBe('This file looks damaged');
  });

  it('has no headline for a code it has no message for', () => {
    expect(importFailureTitle('SOME_UNMAPPED_CODE')).toBeUndefined();
    expect(importFailureTitle(null)).toBeUndefined();
  });
});

// Gate 2a: "What will you do now?" is asked about a refused statement only.
describe('isStatementRefusal', () => {
  it('is true for an import code that judged the file', () => {
    expect(isStatementRefusal(NO_TRANSACTIONS_FOUND)).toBe(true);
    expect(isStatementRefusal(PAYMENT_APP_HISTORY)).toBe(true);
  });

  it('is false for errors that are not about the statement', () => {
    expect(isStatementRefusal('INTERNAL_ERROR')).toBe(false);
    expect(isStatementRefusal('TOO_MANY_REQUESTS')).toBe(false);
    expect(isStatementRefusal('UNAUTHORIZED')).toBe(false);
    expect(isStatementRefusal(IMPORT_SYSTEM_BUSY)).toBe(false);
    expect(isStatementRefusal(IMPORT_SESSION_HELD_FOR_REVIEW)).toBe(false);
    expect(isStatementRefusal(null)).toBe(false);
    expect(isStatementRefusal(undefined)).toBe(false);
  });
});
