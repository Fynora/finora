import { describe, expect, it } from 'vitest';
import type { RefreshRunDetail } from '../../api/endpoints';
import { outcomeLabel } from './refreshFormat';

const run = (status: RefreshRunDetail['status'], reason: string | null): RefreshRunDetail => ({
  runId: null, statementImportId: '00000000-0000-0000-0000-000000000001', fileName: null, accountName: null,
  periodStart: null, periodEnd: null, status, createdAt: null, rowsChanged: 0, rowsAdded: 0, rowsRemoved: 0,
  factsChanged: 0, balanceChange: null, reason, changed: [], added: [], removed: [], skippedAsDuplicate: [], facts: [],
});

describe('outcomeLabel', () => {
  it('says why a refresh was held: a reading that fails the statement checks, or one removing too much', () => {
    expect(outcomeLabel(run('NEEDS_REVIEW', 'TRUST_HOLD'))).toContain("didn't add up against the statement's own figures");
    expect(outcomeLabel(run('NEEDS_REVIEW', 'TRUST_HOLD'))).not.toContain('too many rows');
    expect(outcomeLabel(run('NEEDS_REVIEW', null))).toContain('would remove too many rows');
  });
});
