import type { MonthComparison } from '../../api/endpoints';
import { comparisonGateNote, comparisonMonthName, deltaPhrase, netChangePhrase } from './comparison';

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`;
const reliable = (over: Partial<MonthComparison> = {}): MonthComparison => ({
  previousMonth: '2026-08', reliable: true, gateReason: null, previousIncome: 100, previousExpense: 50,
  incomeDeltaPct: 10, expenseDeltaPct: -8, netDelta: 34469, ...over,
});
const unreliable = (gateReason: string | null): MonthComparison => ({
  previousMonth: '2026-08', reliable: false, gateReason, previousIncome: null, previousExpense: null,
  incomeDeltaPct: null, expenseDeltaPct: null, netDelta: null,
});

describe('comparisonMonthName', () => {
  it('names a prior month in the same year by month alone, and adds the year across years', () => {
    expect(comparisonMonthName('2026-08', '2026-09')).toBe('August');
    expect(comparisonMonthName('2025-12', '2026-01')).toBe('December 2025');
  });
});

describe('deltaPhrase', () => {
  it('uses an arrow for direction, so colour is never the only signal', () => {
    expect(deltaPhrase(42.4, 'August')).toBe('↑ 42% vs August');
    expect(deltaPhrase(-92.1, 'August')).toBe('↓ 92% vs August');
  });
  it('says no change when it rounds to zero, in either direction', () => {
    expect(deltaPhrase(0, 'August')).toBe('No change vs August');
    expect(deltaPhrase(0.4, 'August')).toBe('No change vs August');
    expect(deltaPhrase(-0.4, 'August')).toBe('No change vs August');
  });
  it('is null with no percent, or a bad number', () => {
    expect(deltaPhrase(null, 'August')).toBeNull();
    expect(deltaPhrase(undefined, 'August')).toBeNull();
    expect(deltaPhrase(Number.NaN, 'August')).toBeNull();
    expect(deltaPhrase(Number.POSITIVE_INFINITY, 'August')).toBeNull();
  });
  it('rounds a half to the nearest whole percent in either direction', () => {
    expect(deltaPhrase(0.5, 'August')).toBe('↑ 1% vs August');
    expect(deltaPhrase(-0.5, 'August')).toBe('↓ 1% vs August');
  });
});

describe('netChangePhrase', () => {
  it('states the amount and direction', () => {
    expect(netChangePhrase(reliable({ netDelta: -34469 }), 'August', rupees)).toBe('₹34,469 less than August');
    expect(netChangePhrase(reliable({ netDelta: 1200 }), 'August', rupees)).toBe('₹1,200 more than August');
  });
  it('says the same when the change rounds to zero', () => {
    expect(netChangePhrase(reliable({ netDelta: 0.3 }), 'August', rupees)).toBe('Same as August');
  });
  it('is null when unreliable, absent, or without a net change', () => {
    expect(netChangePhrase(unreliable('COVERAGE_GAP'), 'August', rupees)).toBeNull();
    expect(netChangePhrase(null, 'August', rupees)).toBeNull();
    expect(netChangePhrase(undefined, 'August', rupees)).toBeNull();
    expect(netChangePhrase(reliable({ netDelta: null }), 'August', rupees)).toBeNull();
  });
});

describe('comparisonGateNote', () => {
  const note = (r: string | null) => comparisonGateNote(unreliable(r), 'September', 'August');

  it('gives each reason its own plain sentence naming the months', () => {
    expect(note('PRIOR_BEFORE_HISTORY')).toBe('Your history starts after August, so there is nothing to compare September with.');
    expect(note('SELECTED_MONTH_PARTIAL')).toContain('September is only partly in your history');
    expect(note('PARTIAL_PRIOR_MONTH')).toContain('August is only partly in your history');
    expect(note('COVERAGE_GAP')).toContain('A statement is missing');
    expect(note('ACCOUNT_STATEMENT_BEHIND')).toContain("An account's latest data stops short of September");
    expect(note('TOO_FEW_PRIOR_TRANSACTIONS')).toBe('August has too few transactions to compare September with.');
  });
  it('says nothing when there is simply no data', () => {
    expect(note('NO_TRANSACTION_HISTORY')).toBeNull();
    expect(note('NO_TRANSACTIONS_IN_MONTH')).toBeNull();
  });
  it('never shows a raw code: an unknown or missing reason falls back to a generic sentence', () => {
    expect(note('SOMETHING_NEW')).toBe('September is not compared with August.');
    expect(note(null)).toBe('September is not compared with August.');
  });
  it('is null for a reliable or absent comparison', () => {
    expect(comparisonGateNote(reliable(), 'September', 'August')).toBeNull();
    expect(comparisonGateNote(null, 'September', 'August')).toBeNull();
    expect(comparisonGateNote(undefined, 'September', 'August')).toBeNull();
  });
});
