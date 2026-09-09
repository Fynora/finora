import { render, screen, fireEvent } from '@testing-library/react-native';
import { VerificationPanel } from './VerificationPanel';
import type { VerificationFinding, VerificationReport } from '../types';

function report(overrides: Partial<VerificationReport> = {}): VerificationReport {
  return {
    findings: [],
    headerReconstructionUncertain: false,
    textSource: 'NATIVE_PDF',
    reliabilityStatus: null,
    ...overrides,
  };
}

function finding(overrides: Partial<VerificationFinding> = {}): VerificationFinding {
  return { rule: 'BALANCE_CHAIN', outcome: 'VERIFIED', details: {}, ...overrides };
}

describe('VerificationPanel', () => {
  it('renders nothing when there is no verification report', () => {
    render(<VerificationPanel verification={null} />);
    expect(screen.queryByText('Statement verification')).toBeNull();
  });

  it('renders nothing when the report carries zero findings', () => {
    render(<VerificationPanel verification={report({ findings: [] })} />);
    expect(screen.queryByText('Statement verification')).toBeNull();
  });

  it('shows the server-computed CLEAN verdict and starts collapsed', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'CLEAN', findings: [finding()] })} />);

    expect(screen.getByText('Imported successfully')).toBeTruthy();
    expect(screen.queryByText(/transaction\(s\) checked/)).toBeNull();
  });

  it('shows a NEEDS_ATTENTION verdict distinctly from REVIEW_RECOMMENDED', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'NEEDS_ATTENTION', findings: [finding({ outcome: 'FAILED' })] })} />);
    expect(screen.getByText('Import needs attention')).toBeTruthy();
  });

  it('softens REVIEW_RECOMMENDED to "Imported with notes" rather than an alarming label', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'REVIEW_RECOMMENDED', findings: [finding({ outcome: 'WARNING' })] })} />);
    expect(screen.getByText('Imported with notes')).toBeTruthy();
  });

  it('falls back to counting findings when the server sends no reliabilityStatus', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: null,
      findings: [finding({ outcome: 'VERIFIED' }), finding({ rule: 'COLUMN_AMBIGUITY', outcome: 'WARNING' })],
    })} />);
    expect(screen.getByText('1 finding')).toBeTruthy();
  });

  it('expands to show each finding\'s rule label, outcome, and summary sentence', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: 'CLEAN',
      findings: [finding({
        details: { rowsChecked: 8, rowsWithBalance: 8, anchoredOnOpeningBalance: false, discrepancies: [] },
      })],
    })} />);

    fireEvent.press(screen.getByText('Statement verification'));

    expect(screen.getByText(/Running balance.*verified/)).toBeTruthy();
    expect(screen.getByText(/8 transaction\(s\) checked/)).toBeTruthy();
    expect(screen.getByText(/no opening balance/)).toBeTruthy();
  });

  it('names a discrepancy count on the balance-chain summary when rows disagree', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: 'NEEDS_ATTENTION',
      findings: [finding({
        outcome: 'FAILED',
        details: {
          rowsChecked: 10, rowsWithBalance: 10, anchoredOnOpeningBalance: true,
          discrepancies: [{ rowIndex: 3, expectedBalance: 100, actualBalance: 90, difference: 10 }],
        },
      })],
    })} />);

    fireEvent.press(screen.getByText('Statement verification'));

    expect(screen.getByText(/1 row didn't match/)).toBeTruthy();
  });

  it('shows the OCR provenance note only when the text source involved OCR', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'REVIEW_RECOMMENDED', textSource: 'OCR', findings: [finding({ outcome: 'WARNING' })] })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.getByText(/read using OCR/)).toBeTruthy();
  });

  it('shows no OCR note for a purely native-text read', () => {
    render(<VerificationPanel verification={report({ reliabilityStatus: 'CLEAN', textSource: 'NATIVE_PDF', findings: [finding()] })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.queryByText(/read using OCR/)).toBeNull();
  });

  it('names an unrecognized rule rather than silently dropping it', () => {
    render(<VerificationPanel verification={report({
      reliabilityStatus: null,
      findings: [finding({ rule: 'SOME_FUTURE_RULE', outcome: 'WARNING' })],
    })} />);
    fireEvent.press(screen.getByText('Statement verification'));
    expect(screen.getByText('SOME_FUTURE_RULE', { exact: false })).toBeTruthy();
    expect(screen.getByText(/doesn't know how to display yet/)).toBeTruthy();
  });
});
