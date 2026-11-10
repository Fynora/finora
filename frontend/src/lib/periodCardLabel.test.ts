import { describe, expect, it } from 'vitest';
import { monthCardLabel, rangeCardLabel } from './periodCardLabel';

describe('monthCardLabel', () => {
  it('names the days covered when the month is partly covered', () => {
    expect(monthCardLabel('2026-11', '2026-11-08', 'This Month')).toBe('1–8 Nov');
  });
  it('names a single day when data stops on the 1st', () => {
    expect(monthCardLabel('2026-11', '2026-11-01', 'This Month')).toBe('1 Nov');
  });
  it('names the month when data reaches its last day', () => {
    expect(monthCardLabel('2026-11', '2026-11-30', 'This Month')).toBe('November');
  });
  it('takes the month length from the calendar', () => {
    expect(monthCardLabel('2026-02', '2026-02-28', 'x')).toBe('February');
  });
  it('falls back when there is no date or no month', () => {
    expect(monthCardLabel('2026-11', null, 'This Month')).toBe('This Month');
    expect(monthCardLabel('2026-11', undefined, 'This Month')).toBe('This Month');
    expect(monthCardLabel(null, null, 'This Month')).toBe('This Month');
  });
  it('keeps the year for a month that is not the current one', () => {
    expect(monthCardLabel('2025-08', '2025-08-20', 'Aug 25', true)).toBe('1–20 Aug 2025');
    expect(monthCardLabel('2025-08', '2025-08-31', 'Aug 25', true)).toBe('August 2025');
    expect(monthCardLabel('2025-08', '2025-08-01', 'Aug 25', true)).toBe('1 Aug 2025');
  });
  it('starts on the first covered day when statements start mid-month', () => {
    expect(monthCardLabel('2026-11', '2026-11-30', 'This Month', false, '2026-11-20')).toBe('20–30 Nov');
    expect(monthCardLabel('2026-11', '2026-11-25', 'This Month', false, '2026-11-20')).toBe('20–25 Nov');
    expect(monthCardLabel('2026-11', '2026-11-20', 'This Month', false, '2026-11-20')).toBe('20 Nov');
    expect(monthCardLabel('2025-08', '2025-08-31', 'Aug 25', true, '2025-08-12')).toBe('12–31 Aug 2025');
  });
  it('treats a missing or out-of-month start as the 1st', () => {
    expect(monthCardLabel('2026-11', '2026-11-30', 'This Month', false, null)).toBe('November');
    expect(monthCardLabel('2026-11', '2026-11-30', 'This Month', false, '2026-11-01')).toBe('November');
    expect(monthCardLabel('2026-11', '2026-11-08', 'This Month', false, '2026-10-20')).toBe('1–8 Nov');
  });
  it('falls back when the date is outside the month', () => {
    expect(monthCardLabel('2026-11', '2026-10-31', 'This Month')).toBe('This Month');
  });
});

describe('rangeCardLabel', () => {
  it('names the months of a range in one year', () => {
    expect(rangeCardLabel('2026-06-01', '2026-11-30')).toBe('Jun–Nov 2026');
  });
  it('names both years when a range crosses a year', () => {
    expect(rangeCardLabel('2025-12-01', '2026-02-28')).toBe('Dec 2025–Feb 2026');
  });
  it('names one month when start and end are in the same month', () => {
    expect(rangeCardLabel('2026-11-01', '2026-11-30')).toBe('Nov 2026');
  });
});
