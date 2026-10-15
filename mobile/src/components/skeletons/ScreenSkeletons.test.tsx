import { render, screen } from '@testing-library/react-native';
import { ThemeProvider } from '../../theme';
import { skeletonTokens } from '../../theme/skeleton';
import { FinancialMemorySkeleton, ProfileSkeleton, ReferralsSkeleton } from './ScreenSkeletons';

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

describe('ReferralsSkeleton', () => {
  it('gives each step a label and a caption, and each share channel its label', () => {
    render(<ThemeProvider><ReferralsSkeleton heroAspectRatio={2} /></ThemeProvider>);
    // title 1 + subtitle 2 + hero 1 + steps 3 x (circle, label, caption) + code card (label, code row,
    // share button, 5 x (circle, label)) + stats 3 x 2
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(1 + 2 + 1 + 9 + 3 + 10 + 6);
  });
});
