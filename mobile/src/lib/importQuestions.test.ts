import { canConfirmImport, type ImportGateState } from './importGate';
import {
  categoryGuessCount, fileIntoCollapsed, importBarMessage, questionRows, undismissedFlagCount,
} from './importQuestions';
import type { DuplicateDecision } from './importReview';
import type { StagedRow } from '../types';

function row(over: Partial<StagedRow> = {}): StagedRow {
  return {
    date: '2026-09-05', description: 'Sample Mart', amount: 100, type: 'EXPENSE', suggestedCategory: 'Groceries',
    categorySource: 'rule', ruleId: null, likelyDuplicate: false, referenceNumber: null, balanceAfter: null,
    duplicateMatch: null, rowPosition: null, categoryConfidence: null, international: false, foreignCurrency: null,
    foreignAmount: null, ...over,
  };
}

const DUP = {
  likelyDuplicate: true,
  duplicateMatch: {
    existingTransactionId: 't1', existingAccountId: null, existingDate: '2026-09-05', existingDescription: 'Sample Tv',
    existingAmount: 649, existingType: 'EXPENSE' as const, existingImportedAt: '2026-10-01T00:00:00Z', matchCount: 1,
    confidence: 'EXACT' as const, reason: 'Same date and amount',
  },
};

const NO_FLAGS = { balance: null, description: null };

describe('questionRows', () => {
  const rows = [row(), row(), row(), row(DUP), row()];
  const decisions: DuplicateDecision[] = ['import', 'import', 'import', 'unresolved', 'import'];

  it('lists unresolved duplicates and flagged rows in statement order', () => {
    expect(questionRows(rows, decisions, { balance: null, description: [1] }, new Set())).toEqual([1, 3]);
  });

  it('leaves out a duplicate that has been answered', () => {
    const answered: DuplicateDecision[] = ['import', 'import', 'import', 'skip', 'import'];
    expect(questionRows(rows, answered, NO_FLAGS, new Set())).toEqual([]);
  });

  it('leaves out a flag marked "Looks right"', () => {
    expect(questionRows(rows, decisions, { balance: [0], description: [1] }, new Set([1]))).toEqual([0, 3]);
  });

  it('lists a row that is both flagged and unresolved once', () => {
    expect(questionRows(rows, decisions, { balance: [3], description: [3] }, new Set())).toEqual([3]);
  });
});

describe('undismissedFlagCount', () => {
  it('counts each flagged row once, less the dismissed ones', () => {
    expect(undismissedFlagCount({ balance: [0, 2], description: [2, 4] }, new Set([4]))).toBe(2);
    expect(undismissedFlagCount(NO_FLAGS, new Set())).toBe(0);
  });
});

describe('categoryGuessCount', () => {
  it('counts only included rows whose category is a guess', () => {
    const rows = [
      row({ categorySource: 'default' }), row({ categorySource: 'structural_p2p' }),
      row({ categorySource: 'default' }), row({ categorySource: 'learned' }),
    ];
    expect(categoryGuessCount(rows, [true, true, false, true])).toBe(2);
  });
});

describe('fileIntoCollapsed', () => {
  const ok = {
    isReimport: false, accountChoice: 'existing' as const, selectedAccountId: 'a1',
    amountOwedInvalid: false, newAccountBankMissing: false,
  };
  it('folds for a chosen existing account with nothing to fix', () => expect(fileIntoCollapsed(ok)).toBe(true));
  it('folds for a re-import', () =>
    expect(fileIntoCollapsed({ ...ok, isReimport: true, selectedAccountId: '' })).toBe(true));
  it('stays open for a new account', () => expect(fileIntoCollapsed({ ...ok, accountChoice: 'new' })).toBe(false));
  it('stays open with no account picked', () => expect(fileIntoCollapsed({ ...ok, selectedAccountId: '' })).toBe(false));
  it('stays open while the amount owed is invalid', () =>
    expect(fileIntoCollapsed({ ...ok, amountOwedInvalid: true })).toBe(false));
  it('stays open while the bank is missing', () =>
    expect(fileIntoCollapsed({ ...ok, newAccountBankMissing: true })).toBe(false));
});

describe('importBarMessage', () => {
  const clear: ImportGateState = {
    includedCount: 3, outstanding: 0, isReimport: false, accountChoice: 'existing', selectedAccountId: 'a1',
    freePlanLimitBlocks: false, amountOwedInvalid: false, newAccountBankMissing: false,
  };
  const blocked = (text: string) => ({ kind: 'blocked', text });

  it('names outstanding questions first', () => {
    expect(importBarMessage({ ...clear, outstanding: 2, includedCount: 0 }, 0)).toEqual(blocked('Answer 2 questions to import'));
    expect(importBarMessage({ ...clear, outstanding: 1 }, 0)).toEqual(blocked('Answer 1 question to import'));
  });
  it('then an account not chosen', () =>
    expect(importBarMessage({ ...clear, selectedAccountId: '' }, 0)).toEqual(blocked('Choose an account to import into')));
  it('then a missing bank', () =>
    expect(importBarMessage({ ...clear, accountChoice: 'new', newAccountBankMissing: true }, 0))
      .toEqual(blocked("Choose the new account's bank to import")));
  it('then an invalid amount owed', () =>
    expect(importBarMessage({ ...clear, amountOwedInvalid: true }, 0)).toEqual(blocked('Fix the amount owed to import')));
  it('then the Free plan limit', () =>
    expect(importBarMessage({ ...clear, freePlanLimitBlocks: true }, 0))
      .toEqual(blocked("This statement is over the Free plan's one-month limit")));
  it('then nothing included', () =>
    expect(importBarMessage({ ...clear, includedCount: 0 }, 0)).toEqual(blocked('Choose at least one transaction to import')));

  it('asks to check flagged rows when nothing blocks', () => {
    expect(importBarMessage(clear, 2)).toEqual({ kind: 'check', text: '2 rows to check' });
    expect(importBarMessage(clear, 1)).toEqual({ kind: 'check', text: '1 row to check' });
  });
  it('says nothing when there is nothing to say', () => expect(importBarMessage(clear, 0)).toBeNull());
  it('never asks a re-import to choose an account', () =>
    expect(importBarMessage({ ...clear, isReimport: true, selectedAccountId: '' }, 0)).toBeNull());

  it('blocks exactly when canConfirmImport refuses, for every gate state', () => {
    const states: ImportGateState[] = [];
    for (const includedCount of [0, 3])
      for (const outstanding of [0, 1])
        for (const isReimport of [false, true])
          for (const accountChoice of ['existing', 'new'] as const)
            for (const selectedAccountId of ['', 'a1'])
              for (const freePlanLimitBlocks of [false, true])
                for (const amountOwedInvalid of [false, true])
                  for (const newAccountBankMissing of [false, true])
                    states.push({
                      includedCount, outstanding, isReimport, accountChoice, selectedAccountId,
                      freePlanLimitBlocks, amountOwedInvalid, newAccountBankMissing,
                    });
    expect(states).toHaveLength(256);
    for (const s of states) {
      expect([s, importBarMessage(s, 0)?.kind === 'blocked']).toEqual([s, !canConfirmImport(s)]);
    }
  });
});
