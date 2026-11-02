import { render, screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { light } from '../../theme/palette';
import type { StagedRow } from '../../types';
import { ReviewSummary } from './ReviewSummary';

// The real hook resolves once per test process; mocked so the glass card never remounts mid-test.
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

function row(type: StagedRow['type'], amount: number): StagedRow {
  return {
    date: '2026-09-05', description: 'Sample', amount, type, suggestedCategory: 'Other', categorySource: 'rule',
    ruleId: null, likelyDuplicate: false, referenceNumber: null, balanceAfter: null, duplicateMatch: null,
    rowPosition: null, categoryConfidence: null, international: false, foreignCurrency: null, foreignAmount: null,
  };
}

const rows = [row('INCOME', 62400), row('EXPENSE', 1240), row('EXPENSE', 649), row('INCOME', 100)];

describe('ReviewSummary', () => {
  it('names the account, the period and the row count', () => {
    render(
      <ReviewSummary
        name="Sample Bank Savings" masked="••0001" periodStart="2026-09-01" periodEnd="2026-09-30"
        fileFormat="PDF" rows={rows} included={[true, true, true, true]}
      />,
    );
    expect(screen.getByText('Sample Bank Savings')).toBeOnTheScreen();
    expect(screen.getByText('••0001')).toBeOnTheScreen();
    expect(screen.getByText('2026-09-01 to 2026-09-30 · 4 transactions')).toBeOnTheScreen();
    expect(screen.getByText('PDF')).toBeOnTheScreen();
  });

  it('totals only the included rows, income in green and expenses in red', () => {
    render(
      <ReviewSummary
        name="Sample Bank Savings" masked={null} periodStart={null} periodEnd={null}
        fileFormat={null} rows={rows} included={[true, true, false, false]}
      />,
    );
    const inText = screen.getByText('In ₹62,400');
    const outText = screen.getByText('Out ₹1,240');
    expect(StyleSheet.flatten(inText.props.style).color).toBe(light.successInk);
    expect(StyleSheet.flatten(outText.props.style).color).toBe(light.dangerInk);
    expect(screen.getByText('4 transactions')).toBeOnTheScreen();
  });
});
