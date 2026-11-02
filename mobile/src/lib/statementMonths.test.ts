import { monthName, monthsLineText, qualifiesForMonths, statementMonths } from './statementMonths';

const TODAY = '2026-11-02';

function seg(periodStart: string, periodEnd: string) {
  return { periodStart, periodEnd };
}
function gap(gapStart: string, gapEnd: string) {
  return { gapStart, gapEnd };
}
function monthly(months: string[]) {
  return months.map((m) => {
    const [y, mm] = m.split('-').map(Number);
    const last = new Date(y, mm, 0).getDate();
    return seg(`${m}-01`, `${m}-${String(last).padStart(2, '0')}`);
  });
}
function chips(result: ReturnType<typeof statementMonths>) {
  return result.chips.map((c) => [c.month, c.state]);
}

describe('statementMonths', () => {
  it('monthly savings Jun–Sep → covered Jun..Sep, next Oct', () => {
    const r = statementMonths({ segments: monthly(['2026-06', '2026-07', '2026-08', '2026-09']), gaps: [] }, TODAY);
    expect(chips(r)).toEqual([
      ['2026-06', 'covered'], ['2026-07', 'covered'], ['2026-08', 'covered'], ['2026-09', 'covered'],
      ['2026-10', 'next'],
    ]);
    expect(monthsLineText(r.line)).toBe("Next: October's statement");
  });

  it('August gap → missing', () => {
    const r = statementMonths(
      { segments: monthly(['2026-06', '2026-07', '2026-09']), gaps: [gap('2026-08-01', '2026-08-31')] },
      TODAY,
    );
    expect(chips(r)).toEqual([
      ['2026-06', 'covered'], ['2026-07', 'covered'], ['2026-08', 'missing'], ['2026-09', 'covered'],
      ['2026-10', 'next'],
    ]);
    expect(monthsLineText(r.line)).toBe("August's statement is missing");
  });

  it('card cycles, one absent cycle → one missing chip', () => {
    const r = statementMonths(
      {
        segments: [seg('2026-06-15', '2026-07-14'), seg('2026-07-15', '2026-08-14'), seg('2026-09-15', '2026-10-14')],
        gaps: [gap('2026-08-15', '2026-09-14')],
      },
      TODAY,
    );
    expect(chips(r)).toEqual([
      ['2026-07', 'covered'], ['2026-08', 'covered'], ['2026-09', 'missing'], ['2026-10', 'covered'],
    ]);
    expect(monthsLineText(r.line)).toBe("September's statement is missing");
  });

  it('one long statement → every month covered', () => {
    const r = statementMonths({ segments: [seg('2026-06-01', '2026-09-30')], gaps: [] }, TODAY);
    expect(chips(r)).toEqual([
      ['2026-06', 'covered'], ['2026-07', 'covered'], ['2026-08', 'covered'], ['2026-09', 'covered'],
      ['2026-10', 'next'],
    ]);
  });

  it('latest end + 1 month after today → up to date', () => {
    const r = statementMonths(
      { segments: [seg('2026-08-15', '2026-09-14'), seg('2026-09-15', '2026-10-14')], gaps: [] },
      TODAY,
    );
    expect(r.chips.some((c) => c.state === 'next')).toBe(false);
    expect(r.line).toEqual({ kind: 'up-to-date' });
    expect(monthsLineText(r.line)).toBe('Up to date');
  });

  it('two missing → count line', () => {
    const r = statementMonths(
      { segments: monthly(['2026-06', '2026-09']), gaps: [gap('2026-07-01', '2026-08-31')] },
      TODAY,
    );
    expect(r.line).toEqual({ kind: 'missing', months: ['2026-08', '2026-07'] });
    expect(monthsLineText(r.line)).toBe('2 statements are missing');
  });

  it('at most five past chips', () => {
    const months = ['01', '02', '03', '04', '05', '06', '07', '08', '09'].map((m) => `2026-${m}`);
    const r = statementMonths({ segments: monthly(months), gaps: [] }, TODAY);
    expect(chips(r)).toEqual([
      ['2026-05', 'covered'], ['2026-06', 'covered'], ['2026-07', 'covered'], ['2026-08', 'covered'],
      ['2026-09', 'covered'], ['2026-10', 'next'],
    ]);
  });

  it('31 Jan end clamps to the end of February', () => {
    const coverage = { segments: [seg('2027-01-01', '2027-01-31')], gaps: [] };
    expect(statementMonths(coverage, '2027-02-28').line).toEqual({ kind: 'next', month: '2027-02' });
    expect(statementMonths(coverage, '2027-02-27').line).toEqual({ kind: 'up-to-date' });
  });

  it('December rolls the year', () => {
    const r = statementMonths({ segments: [seg('2026-12-01', '2026-12-31')], gaps: [] }, '2027-01-31');
    expect(r.line).toEqual({ kind: 'next', month: '2027-01' });
    expect(r.chips[r.chips.length - 1]).toEqual({ month: '2027-01', state: 'next' });
  });

  it('no segments → empty', () => {
    expect(statementMonths({ segments: [], gaps: [] }, TODAY)).toEqual({ chips: [], line: { kind: 'up-to-date' } });
  });
});

describe('qualifiesForMonths', () => {
  it('only statement-imported accounts with statements', () => {
    expect(qualifiesForMonths({ primarySource: 'MANUAL', statementsCount: 3 })).toBe(true);
    expect(qualifiesForMonths({ primarySource: 'MANUAL', statementsCount: 0 })).toBe(false);
    expect(qualifiesForMonths({ primarySource: 'ACCOUNT_AGGREGATOR', statementsCount: 3 })).toBe(false);
  });
});

describe('monthName', () => {
  it('names a month without its year', () => {
    expect(monthName('2026-10', 'short')).toBe('Oct');
    expect(monthName('2026-10', 'long')).toBe('October');
  });
});
