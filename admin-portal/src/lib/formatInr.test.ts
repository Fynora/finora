import { describe, expect, it } from 'vitest';
import { formatInr } from './formatInr';

describe('formatInr', () => {
  it('puts the minus sign before the rupee sign', () => {
    expect(formatInr(-100396.53)).toBe('−₹1,00,396.53');
  });

  it('formats positive amounts and zero in Indian grouping', () => {
    expect(formatInr(488000)).toBe('₹4,88,000');
    expect(formatInr(0)).toBe('₹0');
  });

  it('shows paise as two digits when there are any', () => {
    expect(formatInr(1234.5)).toBe('₹1,234.50');
    expect(formatInr(-0.5)).toBe('−₹0.50');
  });
});
