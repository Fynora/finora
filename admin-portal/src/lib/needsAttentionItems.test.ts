import { describe, it, expect } from 'vitest';
import { needsAttentionItems } from './needsAttentionItems';
import type { NeedsAttentionDto } from '../types';

function data(overrides: Partial<NeedsAttentionDto> = {}): NeedsAttentionDto {
  return {
    importsWithSkippedRowsToday: 0,
    lockedAccounts: 0,
    transactionsNeedingCategoryReview: 0,
    transactionsFlaggedAsDuplicates: 0,
    statementsHeldForTrustReview: 0,
    importsHeldForReview: 0,
    ...overrides,
  };
}

describe('needsAttentionItems', () => {
  it('returns nothing when every field is zero', () => {
    expect(needsAttentionItems(data())).toEqual([]);
  });

  it('includes only the fields that are non-zero', () => {
    const items = needsAttentionItems(data({ lockedAccounts: 2, transactionsFlaggedAsDuplicates: 5 }));

    expect(items.map((i) => i.label)).toEqual([
      'accounts are currently locked out',
      'transactions are flagged as potential duplicates',
    ]);
    expect(items.map((i) => i.count)).toEqual([2, 5]);
  });

  it('carries the real count and a navigable link for the two fields that have one', () => {
    const items = needsAttentionItems(data({ importsWithSkippedRowsToday: 3 }));

    expect(items).toEqual([
      expect.objectContaining({ count: 3, to: '/diagnostics', linkLabel: 'View in Diagnostics' }),
    ]);
  });

  it('leaves to/linkLabel null for the two fields with nowhere to link', () => {
    const items = needsAttentionItems(data({ transactionsNeedingCategoryReview: 14 }));

    expect(items).toEqual([expect.objectContaining({ count: 14, to: null, linkLabel: null })]);
  });

  it('includes every non-zero field at once, in a stable order, holds first', () => {
    const items = needsAttentionItems(data({
      importsWithSkippedRowsToday: 1,
      lockedAccounts: 2,
      transactionsNeedingCategoryReview: 3,
      transactionsFlaggedAsDuplicates: 4,
      statementsHeldForTrustReview: 5,
      importsHeldForReview: 6,
    }));

    expect(items.map((i) => i.count)).toEqual([5, 6, 1, 2, 3, 4]);
  });

  // Gate 1 spec §4: a hold past the 48-hour promise outranks everything else here -- the user was
  // told a time and it has passed. Each kind links to its own queue and says how late the oldest is.
  describe('holds past the 48-hour promise', () => {
    const NOW = Date.parse('2026-10-09T12:00:00Z');

    it('leads the list, one row per kind, each linked to its own queue with the oldest age', () => {
      const items = needsAttentionItems(data({
        statementsHeldForTrustReview: 3,
        importsHeldForReview: 1,
        lockedAccounts: 2,
        trustHoldsOverdue: { count: 2, oldestHeldSince: '2026-10-06T09:00:00Z' },
        importHoldsOverdue: { count: 1, oldestHeldSince: '2026-10-07T10:00:00Z' },
      }), NOW);

      expect(items.slice(0, 2)).toEqual([
        expect.objectContaining({
          count: 2,
          label: 'held statements are past the 48-hour promise — oldest waiting 3 days',
          to: '/held-statements', linkLabel: 'Open Held Statements',
        }),
        expect.objectContaining({
          count: 1,
          label: 'held import is past the 48-hour promise — oldest waiting 2 days',
          to: '/held-imports', linkLabel: 'Open Held Imports',
        }),
      ]);
      expect(items.map((i) => i.count)).toEqual([2, 1, 3, 1, 2]);
    });

    it('shows no overdue row when nothing is overdue', () => {
      const items = needsAttentionItems(data({
        statementsHeldForTrustReview: 1,
        trustHoldsOverdue: { count: 0, oldestHeldSince: null },
        importHoldsOverdue: { count: 0, oldestHeldSince: null },
      }), NOW);

      expect(items.map((i) => i.label)).toEqual(['statement is waiting for trust review']);
    });

    it('treats a server that predates the overdue counts as having none', () => {
      expect(needsAttentionItems(data({ importsHeldForReview: 2 }), NOW).map((i) => i.count)).toEqual([2]);
    });

    it('still shows the row, without an age, if the oldest time is missing', () => {
      const items = needsAttentionItems(data({
        trustHoldsOverdue: { count: 1, oldestHeldSince: null },
      }), NOW);

      expect(items[0].label).toBe('held statement is past the 48-hour promise');
    });
  });

  it('points each hold count at its own queue', () => {
    const items = needsAttentionItems(data({ statementsHeldForTrustReview: 3, importsHeldForReview: 2 }));

    expect(items).toEqual([
      expect.objectContaining({
        count: 3, label: 'statements are waiting for trust review',
        to: '/held-statements', linkLabel: 'Open Held Statements',
      }),
      expect.objectContaining({
        count: 2, label: 'imports are held for review', to: '/held-imports', linkLabel: 'Open Held Imports',
      }),
    ]);
  });

  it('reads correctly for a single hold of each kind', () => {
    const items = needsAttentionItems(data({ statementsHeldForTrustReview: 1, importsHeldForReview: 1 }));

    expect(items.map((i) => i.label)).toEqual([
      'statement is waiting for trust review',
      'import is held for review',
    ]);
  });
});
