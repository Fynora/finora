import { describe, it, expect } from 'vitest';
import { suspendedTokenFrom } from './suspension';

describe('suspendedTokenFrom', () => {
  it('returns the token from a suspended refusal', () => {
    expect(suspendedTokenFrom({
      response: { data: { errorCode: 'AUTH_018', details: { suspendedAccessToken: 'tok' } } },
    })).toBe('tok');
  });

  it('is null for a suspended refusal without a token (a suspended admin account)', () => {
    expect(suspendedTokenFrom({ response: { data: { errorCode: 'AUTH_018', details: {} } } })).toBeNull();
    expect(suspendedTokenFrom({ response: { data: { errorCode: 'AUTH_018', details: { suspendedAccessToken: '' } } } })).toBeNull();
  });

  it('is null for any other error', () => {
    expect(suspendedTokenFrom({ response: { data: { errorCode: 'AUTH_007', details: { suspendedAccessToken: 'tok' } } } })).toBeNull();
    expect(suspendedTokenFrom(new Error('network'))).toBeNull();
    expect(suspendedTokenFrom(undefined)).toBeNull();
  });
});
