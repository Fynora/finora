import { describe, expect, it } from 'vitest';
import { estimateOpeningBalanceFromTotalDue } from './newAccountPayload';
import type { StagedRow } from '../types';

const row = (over: Partial<StagedRow> = {}): StagedRow => ({
  date: '2026-08-01',
  description: 'ACME STORES',
  amount: 1200,
  type: 'EXPENSE',
  suggestedCategory: 'Shopping',
  categorySource: 'rule',
  ruleId: null,
  likelyDuplicate: false,
  duplicateMatch: null,
  referenceNumber: null,
  balanceAfter: null,
  confidence: null,
  merchant: null,
  merchantConfidence: null,
  categoryConfidence: null,
  rowPosition: null,
  ...over,
});

describe('estimateOpeningBalanceFromTotalDue', () => {
  it('works the statement total backwards through net(rows) to an opening balance', () => {
    // A real customer's Axis Bank import: total amount due 27665.16, and the imported rows net
    // to 17583.17 (EXPENSE minus INCOME, the credit-card convention). Nothing detected a previous
    // balance, so the account started at 0 and landed on 17546.37 -- roughly 40% of the real
    // total. openingBalance = totalAmountDue - net(rows) closes that gap.
    const rows = [
      row({ type: 'EXPENSE', amount: 12000 }),
      row({ type: 'EXPENSE', amount: 5583.17 }),
      row({ type: 'INCOME', amount: 0 }),
    ];

    const estimated = estimateOpeningBalanceFromTotalDue(rows, 'CREDIT_CARD', 27665.16);

    expect(estimated).toBeCloseTo(27665.16 - 17583.17, 5);
  });

  it('nets EXPENSE and INCOME rows using the credit-card convention', () => {
    const rows = [row({ type: 'EXPENSE', amount: 1000 }), row({ type: 'INCOME', amount: 400 })];

    // net(rows) = 1000 - 400 = 600; opening = 2000 - 600 = 1400
    expect(estimateOpeningBalanceFromTotalDue(rows, 'CREDIT_CARD', 2000)).toBeCloseTo(1400, 5);
  });

  it('is null when nothing states a total amount due -- there is nothing to work backwards from', () => {
    expect(estimateOpeningBalanceFromTotalDue([row()], 'CREDIT_CARD', null)).toBeNull();
  });

  it('is null for any account type other than CREDIT_CARD -- totalAmountDue is a credit-card-only field', () => {
    expect(estimateOpeningBalanceFromTotalDue([row()], 'SAVINGS', 2000)).toBeNull();
    expect(estimateOpeningBalanceFromTotalDue([row()], 'WALLET', 2000)).toBeNull();
    expect(estimateOpeningBalanceFromTotalDue([row()], 'INVESTMENT', 2000)).toBeNull();
  });

  it('handles an empty row list -- opening balance equals the total outright', () => {
    expect(estimateOpeningBalanceFromTotalDue([], 'CREDIT_CARD', 2000)).toBe(2000);
  });
});
