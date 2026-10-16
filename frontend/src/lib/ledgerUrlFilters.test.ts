import { describe, expect, it } from 'vitest';
import { categoryIdByName, ledgerLinkForCategory, parseLedgerUrlFilters } from './ledgerUrlFilters';

const parse = (qs: string) => parseLedgerUrlFilters(new URLSearchParams(qs));

describe('parseLedgerUrlFilters', () => {
  it('reads a date range and a category', () => {
    expect(parse('category=Food%20%26%20Dining&from=2026-09-01&to=2026-09-30'))
      .toEqual({ categoryName: 'Food & Dining', dateFrom: '2026-09-01', dateTo: '2026-09-30' });
  });

  it('returns nothing for a link with no filters (the plain search link keeps working)', () => {
    expect(parse('q=swiggy')).toEqual({});
    expect(parse('')).toEqual({});
  });

  it('ignores a date that has the right shape but is not a real day', () => {
    expect(parse('from=2026-02-31&to=2026-09-30')).toEqual({ dateTo: '2026-09-30' });
    expect(parse('from=2026-13-01')).toEqual({});
  });

  it('accepts a real leap day and rejects it in a non-leap year', () => {
    expect(parse('from=2028-02-29')).toEqual({ dateFrom: '2028-02-29' });
    expect(parse('from=2027-02-29')).toEqual({});
  });

  it('ignores anything that is not an ISO date, so a stale or hand-edited link sends nothing odd', () => {
    expect(parse('from=yesterday&to=30-09-2026')).toEqual({});
    expect(parse("from=2026-09-01'%20OR%201=1")).toEqual({});
  });

  it('drops a backwards range whole rather than matching nothing', () => {
    expect(parse('from=2026-09-30&to=2026-09-01')).toEqual({});
  });

  it('keeps a one-day range (from equals to)', () => {
    expect(parse('from=2026-09-14&to=2026-09-14')).toEqual({ dateFrom: '2026-09-14', dateTo: '2026-09-14' });
  });

  it('ignores a blank category', () => {
    expect(parse('category=%20%20')).toEqual({});
  });
});

describe('categoryIdByName', () => {
  const categories = [{ id: 'a', name: 'Shopping' }, { id: 'b', name: 'Food & Dining' }];

  it('matches exactly, ignoring case and surrounding spaces', () => {
    expect(categoryIdByName(categories, ' food & dining ')).toBe('b');
  });

  it('is undefined for a name with no category (the catch-all Uncategorized bucket) -- meaning no filter', () => {
    expect(categoryIdByName(categories, 'Uncategorized')).toBeUndefined();
  });

  it('is undefined while the categories have not loaded', () => {
    expect(categoryIdByName(undefined, 'Shopping')).toBeUndefined();
  });

  it('does not match on a partial name', () => {
    expect(categoryIdByName(categories, 'Food')).toBeUndefined();
  });
});

describe('ledgerLinkForCategory', () => {
  it('bounds the link by the month and encodes the name', () => {
    const link = ledgerLinkForCategory('Food & Dining', '2026-09', '2026-09-30');
    expect(link.startsWith('/app/transactions?')).toBe(true);
    expect(parse(link.split('?')[1])).toEqual({ categoryName: 'Food & Dining', dateFrom: '2026-09-01', dateTo: '2026-09-30' });
  });

  it('round-trips a name that contains URL-special characters', () => {
    const link = ledgerLinkForCategory('Rent + Maintenance?', '2026-02', '2026-02-28');
    expect(parse(link.split('?')[1]).categoryName).toBe('Rent + Maintenance?');
  });
});
