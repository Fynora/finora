import { importFailureMessage } from './importFailureMessages';
import { PDF_PASSWORD_REQUIRED, TRUST_REVIEW_REJECTED } from './errorCodes';

describe('importFailureMessage', () => {
  it('has a curated message for a trust-review rejection, not the generic fallback', () => {
    const message = importFailureMessage(TRUST_REVIEW_REJECTED);
    expect(message).toBeDefined();
    expect(message).toMatch(/could not read it accurately enough/);
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
});
