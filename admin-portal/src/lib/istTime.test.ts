import { describe, it, expect } from 'vitest';
import {
  estimateMinutes, formatIst, formatIstDate, isInputInsideWindow, isInsideWindow, isoToIstInput,
  istInputToIso, toTimeInput, todayIst,
} from './istTime';

describe('istTime', () => {
  it('reads a datetime-local value as IST, whatever the machine zone is', () => {
    // 19:30 IST is 14:00 UTC.
    expect(istInputToIso('2026-10-06T19:30')).toBe('2026-10-06T14:00:00.000Z');
  });

  it('round-trips an instant through the IST input format', () => {
    expect(isoToIstInput('2026-10-06T14:00:00.000Z')).toBe('2026-10-06T19:30');
    expect(isoToIstInput(istInputToIso('2026-12-31T21:59'))).toBe('2026-12-31T21:59');
  });

  it('carries the date across midnight when the offset pushes it over', () => {
    // 20:00 UTC on the 6th is 01:30 IST on the 7th.
    expect(isoToIstInput('2026-10-06T20:00:00.000Z')).toBe('2026-10-07T01:30');
    expect(formatIst('2026-10-06T20:00:00.000Z')).toBe('07 Oct 2026, 01:30 IST');
  });

  it('formats an absent value as a dash', () => {
    expect(formatIst(null)).toBe('—');
    expect(formatIstDate(undefined)).toBe('—');
  });

  it('formats an IST calendar date without any zone conversion', () => {
    expect(formatIstDate('2026-10-06')).toBe('06 Oct 2026');
  });

  it('turns the API time of day into what a time input wants', () => {
    expect(toTimeInput('07:30:00')).toBe('07:30');
    expect(toTimeInput('19:05')).toBe('19:05');
    expect(toTimeInput(null)).toBe('');
  });

  it('matches the server window 07:00-21:59 at both edges', () => {
    expect(isInsideWindow('06:59')).toBe(false);
    expect(isInsideWindow('07:00')).toBe(true);
    expect(isInsideWindow('21:59')).toBe(true);
    expect(isInsideWindow('22:00')).toBe(false);
    expect(isInputInsideWindow('2026-10-06T21:59')).toBe(true);
    expect(isInputInsideWindow('2026-10-06T22:00')).toBe(false);
  });

  it('gives today in IST, not in UTC', () => {
    // 20:00 UTC on the 6th is already the 7th in India.
    expect(todayIst(new Date('2026-10-06T20:00:00Z'))).toBe('2026-10-07');
    expect(todayIst(new Date('2026-10-06T10:00:00Z'))).toBe('2026-10-06');
  });

  it('estimates minutes at 100 a minute, never less than one', () => {
    expect(estimateMinutes(0)).toBe(1);
    expect(estimateMinutes(100)).toBe(1);
    expect(estimateMinutes(101)).toBe(2);
    expect(estimateMinutes(6000)).toBe(60);
  });
});
