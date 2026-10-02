import { describe, it, expect } from 'vitest';
import { ruleAmountRange } from './ruleAmountRange';

describe('ruleAmountRange', () => {
  it('shows both bounds', () => {
    expect(ruleAmountRange(8000, 12000)).toBe('₹8,000 – ₹12,000');
  });
  it('shows a single bound as at least / at most', () => {
    expect(ruleAmountRange(8000, null)).toBe('at least ₹8,000');
    expect(ruleAmountRange(null, 12000)).toBe('at most ₹12,000');
  });
  it('keeps paise when there are any', () => {
    expect(ruleAmountRange(7999.5, 12001)).toBe('₹7,999.50 – ₹12,001');
  });
  it('is null when the rule has no bounds', () => {
    expect(ruleAmountRange(null, null)).toBeNull();
    expect(ruleAmountRange(undefined, undefined)).toBeNull();
  });
});
