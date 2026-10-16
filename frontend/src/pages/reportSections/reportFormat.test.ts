import { describe, expect, it } from 'vitest';
import { fmt, lastDayOf, monthLabelLong, shortDate } from './reportFormat';

describe('reportFormat', () => {
  it('names a date by its own day, never the one before', () => {
    expect(shortDate('2026-09-14')).toBe('Sep 14');
    expect(shortDate('2026-01-01')).toBe('Jan 1');
    expect(shortDate('2026-12-31')).toBe('Dec 31');
  });

  it('finds the last day of a month, leap years included', () => {
    expect(lastDayOf('2026-09')).toBe('2026-09-30');
    expect(lastDayOf('2028-02')).toBe('2028-02-29');
    expect(lastDayOf('2026-02')).toBe('2026-02-28');
  });

  it('writes a negative amount with the sign before the rupee, and groups the Indian way', () => {
    expect(fmt(-500)).toBe('-₹500');
    expect(fmt(126328)).toBe('₹1,26,328');
    expect(fmt(0)).toBe('₹0');
  });

  it('names a month in full', () => {
    expect(monthLabelLong('2026-09')).toBe('September 2026');
  });
});
