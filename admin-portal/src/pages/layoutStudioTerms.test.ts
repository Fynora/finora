import { describe, expect, it } from 'vitest';
import { describeReason } from './layoutStudioTerms';

// The CSV path's reasons (TransactionNormalizer.failureReasonKey, PreviewGenerator) reach the same
// histogram as the PDF locator's, so each needs words of its own rather than the generic fallback.
describe('describeReason', () => {
  it.each([
    'UNANCHORED_NO_HEADER_ROW',
    'UNANCHORED_NO_AMOUNT_COLUMN',
    'UNANCHORED_AMOUNT_COLUMN_EMPTY',
    'UNANCHORED_UNRECOGNIZED_COLUMN_VALUE',
    'UNANCHORED_REJECTED_AFTER_PARSE',
    'UNANCHORED_DATE_COLUMN_EMPTY',
    'UNANCHORED_NO_DATE_COLUMN',
  ])('describes %s in plain words', (reason) => {
    expect(describeReason(reason).meaning).not.toMatch(/No plain-English description/);
  });

  it('shows the shape of an amount it could not read', () => {
    expect(describeReason('UNANCHORED_AMOUNT_UNPARSEABLE:9999/-').label).toBe('Amount not understood: 9999/-');
  });

  it('still shows the shape of a date it could not read', () => {
    expect(describeReason('UNANCHORED_DATE_UNPARSEABLE:99-XXXX-9999').label)
      .toBe('Date not understood: 99-XXXX-9999');
  });
});
