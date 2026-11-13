import { isHiddenToday, localDateKey, notNowValue } from './dashboardQuickSort';

describe('dashboard Quick sort "Not now"', () => {
  const morning = new Date(2026, 10, 13, 9, 0);

  it('uses the local calendar date', () => {
    expect(localDateKey(new Date(2026, 0, 5, 23, 59))).toBe('2026-01-05');
  });

  it('stays hidden for the rest of the same day', () => {
    const stored = notNowValue('A@example.com', morning);
    expect(isHiddenToday(stored, 'a@example.com', new Date(2026, 10, 13, 23, 59))).toBe(true);
  });

  it('is back at local midnight, not 24 hours later', () => {
    const stored = notNowValue('a@example.com', morning);
    expect(isHiddenToday(stored, 'a@example.com', new Date(2026, 10, 14, 0, 0))).toBe(false);
  });

  it('does not hide the card for another account on the same device', () => {
    const stored = notNowValue('a@example.com', morning);
    expect(isHiddenToday(stored, 'b@example.com', morning)).toBe(false);
  });

  it('shows the card when nothing is stored', () => {
    expect(isHiddenToday(null, 'a@example.com', morning)).toBe(false);
  });
});
