import { reconciliationBadge } from './reconciliationBadge';

// Extracted out of LedgerScreen.tsx (Phase 4) so TransactionExplanationModal can reuse the same
// label/tone mapping without a circular import between the two. Behaviour is unchanged from the
// version LedgerScreen.test.tsx already exercises through the row's own badge rendering -- this
// pins the pure function directly, now that it has a second, independent caller.
describe('reconciliationBadge', () => {
  it('returns null for the common OK status -- no badge for an ordinary transaction', () => {
    expect(reconciliationBadge('OK')).toBeNull();
  });

  it.each([
    ['DUPLICATE', 'Duplicate', 'danger'],
    ['TRANSFER', 'Transfer', 'primary'],
    ['REFUND', 'Refund', 'success'],
    ['REVERSAL', 'Reversed', 'warning'],
    ['INVESTMENT_TRANSFER', 'Investment', 'primary'],
    ['SUPERSEDED', 'Superseded', 'muted'],
  ] as const)('maps %s to label %s and tone %s', (status, label, tone) => {
    const badge = reconciliationBadge(status);
    expect(badge?.label).toBe(label);
    expect(badge?.tone).toBe(tone);
    expect(badge?.hint).toBeTruthy();
  });
});
