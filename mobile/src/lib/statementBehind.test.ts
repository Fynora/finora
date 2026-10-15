import { longMonthName, statementBehindNote } from './statementBehind';

describe('longMonthName', () => {
  it('spells the month out from a year-month or a full date', () => {
    expect(longMonthName('2026-09')).toBe('September');
    expect(longMonthName('2026-03-31')).toBe('March');
    expect(longMonthName('2026-12-01')).toBe('December');
  });
});

describe('statementBehindNote', () => {
  it('is null when nothing is behind', () => {
    expect(statementBehindNote(null, 'September')).toBeNull();
    expect(statementBehindNote(undefined, 'September')).toBeNull();
  });

  it('names the one account and the day its statement ends', () => {
    expect(statementBehindNote({ accountName: 'HDFC Card', coveredThrough: '2026-09-11', accountCount: 1 }, 'September'))
      .toBe("September is partial so far: HDFC Card's latest data ends Sep 11, so its last days aren't in yet.");
  });

  it('counts the accounts when several are behind, and names the one reaching least far', () => {
    expect(statementBehindNote({ accountName: 'Axis Card', coveredThrough: '2026-08-20', accountCount: 3 }, 'September'))
      .toBe("September is partial so far: 3 accounts' data ends before month-end, the earliest being Axis Card on Aug 20.");
  });

  it('falls back to a plain word when the account has no name', () => {
    expect(statementBehindNote({ accountName: '  ', coveredThrough: '2026-09-11', accountCount: 1 }, 'September'))
      .toContain("An account's latest data ends Sep 11");
  });

  it('does not shift the day by a timezone: the 1st stays the 1st and the last stays the last', () => {
    expect(statementBehindNote({ accountName: 'X', coveredThrough: '2026-09-01', accountCount: 1 }, 'September')).toContain('Sep 1,');
    expect(statementBehindNote({ accountName: 'X', coveredThrough: '2026-08-31', accountCount: 1 }, 'September')).toContain('Aug 31,');
  });
});
