import { describe, it, expect } from 'vitest';
import type { ExpectedShare } from '../api/endpoints';
import { UPI_ID, dayMonth, daysBetween, expectedSharesIn, serverMessage, upiRequestUrl, withPeople } from './people';

// Every name, id and amount here is invented.

describe('withPeople', () => {
  it('splits balances into owed to you and you owe, largest first, never a family person or a zero', () => {
    const { owedToYou, youOwe } = withPeople([
      { personKey: 'a', label: 'A', owedToUser: 500, since: '2026-09-03', family: false },
      { personKey: 'b', label: 'B', owedToUser: 1200, since: '2026-09-10', family: false },
      { personKey: 'c', label: 'C', owedToUser: -800, since: '2026-09-12', family: false },
      { personKey: 'd', label: 'D', owedToUser: 3000, since: '2026-09-01', family: true },
      { personKey: 'e', label: 'E', owedToUser: 0, since: null, family: false },
    ]);
    expect(owedToYou.map((p) => p.personKey)).toEqual(['b', 'a']);
    expect(youOwe.map((p) => p.personKey)).toEqual(['c']);
  });
});

describe('expectedSharesIn', () => {
  const share = (overrides: Partial<ExpectedShare> = {}): ExpectedShare => ({
    personKey: 'vpa:flatmate.a', label: 'Flatmate A', usualAmount: 9000, usualDay: 4, late: false, ruleId: 'r1',
    ...overrides,
  });

  it('says whose share, around which day, and marks one already late', () => {
    expect(expectedSharesIn([share(), share({ ruleId: 'r2', label: 'Flatmate B', usualDay: 21, late: true })])).toEqual([
      { key: 'r1', title: "Flatmate A's share", when: 'around the 4th', amount: 9000, late: false },
      { key: 'r2', title: "Flatmate B's share", when: 'late, usually around the 21st', amount: 9000, late: true },
    ]);
  });

  it('names the day with its English ending', () => {
    const when = (day: number) => expectedSharesIn([share({ usualDay: day })])[0].when;
    expect([1, 2, 3, 11, 12, 13, 22, 23, 30].map(when)).toEqual([
      'around the 1st', 'around the 2nd', 'around the 3rd', 'around the 11th', 'around the 12th',
      'around the 13th', 'around the 22nd', 'around the 23rd', 'around the 30th',
    ]);
  });
});

describe('dates', () => {
  it('writes a day and a short month, September as Sep', () => {
    expect(dayMonth('2026-10-04')).toBe('4 Oct');
    expect(dayMonth('2026-09-20')).toBe('20 Sep');
  });

  it('counts whole days between two dates', () => {
    expect(daysBetween('2026-10-06', '2026-12-05')).toBe(60);
    expect(daysBetween('2026-10-06', '2026-10-06')).toBe(0);
  });
});

describe('the UPI request', () => {
  it('asks for the amount owed, to the user\'s own UPI id, in rupees', () => {
    expect(upiRequestUrl('user.sample@okbank', 500)).toBe('upi://pay?pa=user.sample@okbank&am=500.00&cu=INR');
    expect(upiRequestUrl('user.sample@okbank', 1234.5)).toBe('upi://pay?pa=user.sample@okbank&am=1234.50&cu=INR');
  });

  it('keeps only name@bank, the same rule the server applies', () => {
    expect(UPI_ID.test('user.sample@okbank')).toBe(true);
    expect(UPI_ID.test('user@bank&am=1')).toBe(false);
    expect(UPI_ID.test('not an id')).toBe(false);
  });
});

describe('serverMessage', () => {
  it('says what the server said when it refused, else the fallback', () => {
    const refused = { response: { status: 422, data: { message: 'Nothing is owed here.' } } };
    expect(serverMessage(refused, 'Try again.')).toBe('Nothing is owed here.');
    expect(serverMessage(new Error('offline'), 'Try again.')).toBe('Try again.');
    expect(serverMessage({ response: { status: 500, data: {} } }, 'Try again.')).toBe('Try again.');
  });
});
