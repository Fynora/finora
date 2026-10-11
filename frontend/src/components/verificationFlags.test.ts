import { describe, expect, it } from 'vitest';
import { flaggedRows, NO_FLAGGED_ROWS } from './verificationFlags';
import type { VerificationFinding, VerificationReport } from '../types';

/**
 * A marked row sends a person to read one transaction, so the line these tests hold is "mark the
 * right row or mark none". Every case is a report the screen can really be handed: one from the
 * current backend, one stored by an earlier version, or one that is malformed.
 */
describe('flaggedRows', () => {
  const report = (...findings: VerificationFinding[]): VerificationReport => ({
    findings, headerReconstructionUncertain: false, textSource: null, reliabilityStatus: null,
  });
  const chain = (discrepancies: unknown, outcome: VerificationFinding['outcome'] = 'WARNING'): VerificationFinding => ({
    rule: 'BALANCE_CHAIN', outcome, details: { rowsChecked: 40, discrepancies },
  });
  const descriptions = (outliers: unknown, outcome: VerificationFinding['outcome'] = 'WARNING'): VerificationFinding => ({
    rule: 'DESCRIPTION_CORRUPTION', outcome, details: { rowsChecked: 40, outliers },
  });

  it('marks the rows each check points at, by where they are shown', () => {
    const flagged = flaggedRows(report(
      chain([{ rowIndex: 2, displayIndex: 4 }]),
      descriptions([{ rowIndex: 5, displayIndex: 1 }]),
    ), 7);

    expect(flagged).toEqual({ balance: [4], description: [1] });
  });

  it('lists a check\'s rows top to bottom, once each', () => {
    const flagged = flaggedRows(report(
      descriptions([{ displayIndex: 19 }, { displayIndex: 2 }, { displayIndex: 19 }, { displayIndex: 13 }]),
    ), 40);

    expect(flagged.description).toEqual([2, 13, 19]);
    expect(flagged.balance).toBeNull();
  });

  it('marks the first and the last row of the list', () => {
    expect(flaggedRows(report(chain([{ displayIndex: 0 }, { displayIndex: 6 }])), 7).balance).toEqual([0, 6]);
  });

  it('marks nothing from rowIndex alone', () => {
    // What an earlier backend stored: a position, but in a list that is not the one on screen.
    expect(flaggedRows(report(
      chain([{ rowIndex: 2 }]),
      descriptions([{ rowIndex: 5, length: 162 }]),
    ), 7)).toBe(NO_FLAGGED_ROWS);
  });

  it('marks none of a check\'s rows when any one of them has no position', () => {
    const flagged = flaggedRows(report(
      chain([{ rowIndex: 2, displayIndex: 4 }, { rowIndex: 3 }]),
      descriptions([{ rowIndex: 5, displayIndex: 1 }]),
    ), 7);

    // The balance check cannot place all of its rows; the description check still can.
    expect(flagged).toEqual({ balance: null, description: [1] });
  });

  it.each([
    ['one past the end of the list', 7],
    ['negative', -1],
    ['a fraction', 2.5],
    ['text', '4'],
    ['null', null],
    ['not a number', Number.NaN],
  ])('marks nothing for a position that is %s', (_name, displayIndex) => {
    expect(flaggedRows(report(chain([{ displayIndex }])), 7)).toBe(NO_FLAGGED_ROWS);
  });

  it('marks nothing when the list is empty', () => {
    expect(flaggedRows(report(chain([{ displayIndex: 0 }])), 0)).toBe(NO_FLAGGED_ROWS);
  });

  it.each(['VERIFIED', 'NOT_APPLICABLE'] as const)('marks nothing for a check that is %s', (outcome) => {
    // Neither outcome sends rows, but a row that is not being questioned must never be marked.
    expect(flaggedRows(report(chain([{ displayIndex: 4 }], outcome), descriptions([{ displayIndex: 1 }], outcome)), 7))
      .toBe(NO_FLAGGED_ROWS);
  });

  it('marks the rows of a check that failed outright', () => {
    expect(flaggedRows(report(chain([{ displayIndex: 4 }], 'FAILED')), 7).balance).toEqual([4]);
  });

  it.each([
    ['no report', null],
    ['an undefined report', undefined],
    ['a report with no findings', report()],
    ['a finding with no details', report({ rule: 'BALANCE_CHAIN', outcome: 'WARNING', details: null as never })],
    ['discrepancies that are not a list', report(chain({ displayIndex: 4 }))],
    ['an empty list', report(chain([]), descriptions([]))],
    ['entries that are not objects', report(chain([null, 4, 'row 4']))],
  ])('marks nothing for %s', (_name, verification) => {
    expect(flaggedRows(verification as VerificationReport | null | undefined, 7)).toBe(NO_FLAGGED_ROWS);
  });

  it('reads no other check, whatever it carries', () => {
    // COLUMN_AMBIGUITY has its own rowIndex, into the rows before they were normalised.
    expect(flaggedRows(report({
      rule: 'COLUMN_AMBIGUITY', outcome: 'WARNING',
      details: { ambiguities: [{ rowIndex: 3, displayIndex: 3 }], discrepancies: [{ displayIndex: 3 }] },
    }), 7)).toBe(NO_FLAGGED_ROWS);
  });
});
