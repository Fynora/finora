import { importFailureMessage } from './importFailureMessages';
import { TRUST_REVIEW_REJECTED } from './errorCodes';

describe('importFailureMessage', () => {
  it('has a curated message for a trust-review rejection, not the generic fallback', () => {
    const message = importFailureMessage(TRUST_REVIEW_REJECTED);
    expect(message).toBeDefined();
    expect(message).toMatch(/could not read it accurately enough/);
  });

  it('returns undefined for an unmapped code, leaving the fallback decision to the caller', () => {
    expect(importFailureMessage('SOME_UNMAPPED_CODE')).toBeUndefined();
  });

  it('returns undefined for null/absent codes', () => {
    expect(importFailureMessage(null)).toBeUndefined();
    expect(importFailureMessage(undefined)).toBeUndefined();
  });
});
