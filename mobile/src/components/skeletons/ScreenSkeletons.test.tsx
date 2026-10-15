import { render, screen } from '@testing-library/react-native';
import { PixelRatio } from 'react-native';
import { ThemeProvider } from '../../theme';
import { skeletonTokens } from '../../theme/skeleton';
import {
  FinancialMemorySkeleton, InvestmentsSkeleton, ProfileSkeleton, ReferralsSkeleton, SettingsCategorizationSkeleton,
  SettingsAccountSkeleton, SettingsGeneralSkeleton, SettingsSecuritySkeleton, StatementGroupCardsSkeleton,
} from './ScreenSkeletons';

// Text placeholders scale with the system text size (jest's window reports 2); controls do not.
const scaled = (h: number) => Math.round(h * PixelRatio.getFontScale());

function heightOf(el: { props: { style?: unknown } }): unknown {
  return Object.assign({}, ...[el.props.style].flat(Infinity).filter(Boolean)).height;
}

// Screen skeletons are checked against the loaded screen on the simulator; these pin the fixed
// shapes that comparison found, so they cannot drift back.
describe('FinancialMemorySkeleton', () => {
  it('gives the four tiles that carry a caption (History, Completeness, Merchants, Corrections) a caption line', () => {
    render(<ThemeProvider><FinancialMemorySkeleton /></ThemeProvider>);
    // intro 2 + tiles (7 x value and label + 4 captions) + recurring heading 1 + 3 rows x 3
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2 + 7 * 2 + 4 + 1 + 9);
  });
});

describe('ProfileSkeleton', () => {
  it('keeps the save-status row above the Save button, so the button does not drop when the form loads', () => {
    render(<ThemeProvider><ProfileSkeleton /></ThemeProvider>);
    const slot = screen.getByTestId('skeleton-save-status', { hidden: true });
    const style = [slot.props.style].flat(Infinity).filter(Boolean);
    expect(style).toEqual(expect.arrayContaining([expect.objectContaining({ height: skeletonTokens.control.saveStatus })]));
  });
});

describe('SettingsGeneralSkeleton', () => {
  function renderIt() {
    render(<ThemeProvider><SettingsGeneralSkeleton /></ThemeProvider>);
  }

  it('gives only the low-balance TextField an error slot -- the timezone picker has none', () => {
    renderIt();
    expect(screen.getAllByTestId('skeleton-field-message', { hidden: true })).toHaveLength(1);
  });

  it('keeps the save-status row above Save preferences', () => {
    renderIt();
    expect(heightOf(screen.getByTestId('skeleton-save-status', { hidden: true }))).toBe(skeletonTokens.control.saveStatus);
  });

  it('draws the theme switch at the segmented control height and the hint on its own 16pt lines', () => {
    renderIt();
    const heights = screen.getAllByTestId('shimmer-block', { hidden: true }).map(heightOf);
    expect(heights).toContain(skeletonTokens.control.segments);
    const hintLines = screen.getAllByTestId('skeleton-line', { hidden: true })
      .filter((l) => heightOf(l) === scaled(skeletonTokens.textLine.hint));
    // the theme hint's 2 lines and the retake-tour hint's 1
    expect(hintLines).toHaveLength(3);
  });

  it('shows the notification rows with their wrapped descriptions, the footnote and the retake-tour row', () => {
    renderIt();
    // low balance (label, field) + timezone (label, picker) + theme (label, segments) + hint 2
    // + save button + notifications (heading, email 1+3+switch, push 1+2+switch, footnote 2)
    // + retake tour (label, hint, link)
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2 + 2 + 2 + 2 + 1 + (1 + 5 + 4 + 2) + 3);
  });
});

describe('SettingsCategorizationSkeleton', () => {
  function renderIt() {
    render(<ThemeProvider><SettingsCategorizationSkeleton /></ThemeProvider>);
  }

  it('keeps the save-status row above Save setting and draws the hint on its own 16pt lines', () => {
    renderIt();
    expect(heightOf(screen.getByTestId('skeleton-save-status', { hidden: true }))).toBe(skeletonTokens.control.saveStatus);
    const hintLines = screen.getAllByTestId('skeleton-line', { hidden: true })
      .filter((l) => heightOf(l) === scaled(skeletonTokens.textLine.hint));
    expect(hintLines).toHaveLength(2);
  });

  it('draws the money-kinds and remembered-senders cards under the button', () => {
    renderIt();
    // stepper 3 + hint 2 + review link 1 + save button 1
    // + kinds card (heading, description 2, 2 rows x 2) + senders card (heading, 2 rows x 3)
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3 + 2 + 1 + 1 + 7 + 7);
  });
});

describe('SettingsSecuritySkeleton', () => {
  it('has the App Lock row at two lines and the sessions section as heading, blurb and session cards', () => {
    render(<ThemeProvider><SettingsSecuritySkeleton /></ThemeProvider>);
    // email row 2 + button + password row 2 + button + phone row (2 + badge) + app lock (1 + 2 + switch)
    // + sessions heading + blurb 2 + 2 cards x 3
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2 + 1 + 2 + 1 + 3 + 4 + 1 + 2 + 6);
    expect(screen.getAllByTestId('skeleton-session-card', { hidden: true })).toHaveLength(2);
    const blurbLines = screen.getAllByTestId('skeleton-line', { hidden: true })
      .filter((l) => heightOf(l) === scaled(skeletonTokens.textLine.hint));
    expect(blurbLines).toHaveLength(2);
  });
});

describe('SettingsAccountSkeleton', () => {
  it('draws the deactivate hint at three 16pt lines and the delete hint at two', () => {
    render(<ThemeProvider><SettingsAccountSkeleton /></ThemeProvider>);
    // each row: label + link; hints 3 + 2
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2 * 2 + 3 + 2);
    const hintLines = screen.getAllByTestId('skeleton-line', { hidden: true })
      .filter((l) => heightOf(l) === scaled(skeletonTokens.textLine.hint));
    expect(hintLines).toHaveLength(5);
  });
});

describe('StatementGroupCardsSkeleton', () => {
  it('is two account cards, each a name line over an 18pt meta line, with no row padding around them', () => {
    render(<ThemeProvider><StatementGroupCardsSkeleton /></ThemeProvider>);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(2 * 2);
    const nameLine = scaled(skeletonTokens.text.heading * skeletonTokens.lineHeight);
    const metaLine = scaled(skeletonTokens.textLine.statementBody);
    expect(screen.getAllByTestId('skeleton-line', { hidden: true }).map(heightOf))
      .toEqual([nameLine, metaLine, nameLine, metaLine]);
  });
});

describe('InvestmentsSkeleton', () => {
  function renderIt(holdingsCount?: number) {
    render(<ThemeProvider><InvestmentsSkeleton holdingsCount={holdingsCount} /></ThemeProvider>);
  }

  it('with no holdings known, draws the empty allocation and holdings messages', () => {
    renderIt(0);
    expect(screen.getAllByTestId('skeleton-empty-state', { hidden: true })).toHaveLength(2);
    expect(screen.queryByTestId('skeleton-chart-donut', { hidden: true })).toBeNull();
    expect(screen.queryAllByTestId('skeleton-holding-row', { hidden: true })).toHaveLength(0);
  });

  it('with holdings known, draws the donut and one row per holding', () => {
    renderIt(3);
    expect(screen.getByTestId('skeleton-chart-donut', { hidden: true })).toBeTruthy();
    expect(screen.getAllByTestId('skeleton-holding-row', { hidden: true })).toHaveLength(3);
  });

  it('draws the net worth trend as the snapshot button row over the trend chart with its date axis', () => {
    renderIt(0);
    expect(screen.getByTestId('skeleton-chart-trend', { hidden: true })).toBeTruthy();
    const heights = screen.getAllByTestId('shimmer-block', { hidden: true }).map(heightOf);
    expect(heights).toContain(skeletonTokens.control.touch);
    expect(heights).toContain(skeletonTokens.chart.trend);
  });

  it('with holdings not yet known, draws the donut and two rows standing in for some', () => {
    renderIt(undefined);
    expect(screen.getByTestId('skeleton-chart-donut', { hidden: true })).toBeTruthy();
    expect(screen.getAllByTestId('skeleton-holding-row', { hidden: true })).toHaveLength(2);
  });
});

describe('ReferralsSkeleton', () => {
  it('gives each step a label and a caption, and each share channel its label', () => {
    render(<ThemeProvider><ReferralsSkeleton heroAspectRatio={2} /></ThemeProvider>);
    // title 1 + subtitle 2 + hero 1 + steps 3 x (circle, label, caption) + code card (label, code row,
    // share button, 5 x (circle, label)) + stats 3 x 2
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(1 + 2 + 1 + 9 + 3 + 10 + 6);
  });
});
