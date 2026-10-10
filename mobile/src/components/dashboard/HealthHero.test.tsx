import { fireEvent, render, screen, act } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { HealthHero } from './HealthHero';
import { heroTones } from '../../lib/heroTones';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';

// null = "not known yet", which every glass surface renders as its solid look. Pinned because the
// real hook flips from null to false a tick after the first render in a file, which would make
// the hero's surface depend on test order. One test below sets it to false for the glass path.
const mockReduce = { value: null as boolean | null };
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: () => mockReduce.value }));
afterEach(() => { mockReduce.value = null; });

function renderHero(props: Partial<React.ComponentProps<typeof HealthHero>> = {}) {
  return render(
    <ThemeProvider>
      <HealthHero
        available
        healthScore={72}
        healthLabel="Good"
        healthScoreDeltaVsLastMonth={4}
        healthSparkline={[]}
        healthScoreTransactionCount={0}
        healthScoreMinTransactions={10}
        onImportPress={jest.fn()}
        {...props}
      />
    </ThemeProvider>
  );
}

describe('HealthHero', () => {
  it('shows the score, label and title', async () => {
    jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
    renderHero();
    await act(async () => { jest.advanceTimersByTime(500); });
    expect(screen.getByText('Financial Health Score')).toBeTruthy();
    expect(screen.getByText('Good')).toBeTruthy();
    expect(screen.getByTestId('health-score-value')).toHaveAnimatedProps({ text: '72', defaultValue: '72' });
    jest.useRealTimers();
  });

  // The delta is against the most recent PRIOR snapshot, which can be further back than last
  // month (DashboardService's gap-skipping lookup), so the pill makes no month claim -- the web
  // dashboard's "vs your last recorded score", shortened for the pill.
  it('shows a positive delta pill', () => {
    renderHero({ healthScoreDeltaVsLastMonth: 4 });
    expect(screen.getByText('+4 vs last score')).toBeTruthy();
    expect(screen.queryByText(/this month/)).toBeNull();
  });

  it('shows a negative delta pill', () => {
    renderHero({ healthScoreDeltaVsLastMonth: -3 });
    expect(screen.getByText('-3 vs last score')).toBeTruthy();
  });

  it('hides the delta pill entirely when null', () => {
    renderHero({ healthScoreDeltaVsLastMonth: null });
    expect(screen.queryByText(/vs last score/)).toBeNull();
  });

  it('renders the onboarding branch, with a Continue Setup CTA, when not available', () => {
    const onImportPress = jest.fn();
    renderHero({ available: false, healthScoreTransactionCount: 4, healthScoreMinTransactions: 10, onImportPress });

    expect(screen.getByText('Getting Started')).toBeTruthy();
    expect(screen.getByText('4 / 10 transactions')).toBeTruthy();
    expect(screen.getByText('40%')).toBeTruthy();

    fireEvent.press(screen.getByText('Continue Setup'));
    expect(onImportPress).toHaveBeenCalled();
  });

  // Zero is a real denominator when the server sends no floor; the old code divided by it.
  it('shows 0% rather than NaN% when the transaction floor is zero', () => {
    renderHero({ available: false, healthScoreTransactionCount: 3, healthScoreMinTransactions: 0 });
    expect(screen.getByText('0%')).toBeTruthy();
    expect(screen.queryByText(/NaN|Infinity/)).toBeNull();
  });

  it.each([true, null])('is an opaque hero when Reduce Transparency is %s', (reduce) => {
    mockReduce.value = reduce;
    renderHero();
    const style = StyleSheet.flatten(screen.getByTestId('health-hero').props.style);
    expect(style.backgroundColor).toBe(light.primaryDark);
    // A solid hero has never had an outline; the edge takes the fill's own colour.
    expect(style.borderColor).toBe(light.primaryDark);
  });

  it('is glass tinted with its own colour when transparency is allowed', () => {
    mockReduce.value = false;
    renderHero();
    // GlassSurface moves a caller's fill onto its tint layer, under the content.
    expect(screen.getByTestId('glass-tint')).toHaveStyle({ backgroundColor: heroTones(light).surface });
  });

  it('keeps the onboarding hero on the same surface as the scored one', () => {
    renderHero({ available: false });
    expect(StyleSheet.flatten(screen.getByTestId('health-hero').props.style).backgroundColor).toBe(light.primaryDark);
  });
});
