import { describe, expect, it } from 'vitest';
import { formatForeignAmount } from './foreignAmount';

describe('formatForeignAmount', () => {
  it('renders the currency code and a two-decimal amount', () => {
    expect(formatForeignAmount('USD', 12.5)).toBe('USD 12.50');
    expect(formatForeignAmount('USD', 40)).toBe('USD 40.00');
    expect(formatForeignAmount('EUR', 1234.5)).toBe('EUR 1,234.50');
  });

  it('renders zero, which is a real printed amount rather than an absent one', () => {
    expect(formatForeignAmount('USD', 0)).toBe('USD 0.00');
  });

  it('renders nothing unless both halves are present', () => {
    expect(formatForeignAmount(null, null)).toBeNull();
    expect(formatForeignAmount('USD', null)).toBeNull();
    expect(formatForeignAmount(null, 12.5)).toBeNull();
    expect(formatForeignAmount('', 12.5)).toBeNull();
    expect(formatForeignAmount(undefined, undefined)).toBeNull();
  });
});
