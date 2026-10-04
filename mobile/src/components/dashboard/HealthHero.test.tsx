import { fireEvent, render, screen, act } from '@testing-library/react-native';
import { HealthHero } from './HealthHero';
import { ThemeProvider } from '../../theme';

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
});
