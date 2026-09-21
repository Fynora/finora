import { describe, it, expect } from 'vitest';
import { GMAIL_SYNC_UI_ENABLED } from './features';

// Some tests that touch this flag mock it so both states can be exercised; this one reads the REAL
// shipped value and fails the moment someone flips the constant, which is the point. Gmail sync is
// paused until Google's annual CASA assessment is funded. To switch it back on, follow
// docs/engineering/gmail-sync-paused.md, which includes updating this test.
describe('features', () => {
  it('has Gmail sync paused in the shipped build', () => {
    expect(GMAIL_SYNC_UI_ENABLED).toBe(false);
  });
});
