import { describe, it, expect } from 'vitest';
import { importFailureMessage } from './importFailureMessages';
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
});
