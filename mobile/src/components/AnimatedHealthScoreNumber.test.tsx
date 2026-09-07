import { act, render, screen } from '@testing-library/react-native';
import { AnimatedHealthScoreNumber } from './AnimatedHealthScoreNumber';

describe('AnimatedHealthScoreNumber', () => {
  // See AnimatedNumber.test.tsx's own "settles on the new formatted value when the prop changes"
  // test for why this needs fake timers advanced and Reanimated's toHaveAnimatedProps matcher,
  // not a plain .props.defaultValue read: useAnimatedProps updates the native prop directly,
  // bypassing React's render cycle, so even a first-mount count-up (this component starts at 0
  // regardless of `value`, unlike AnimatedNumber) is invisible to a synchronous prop read.
  it('counts up from zero to the target value on mount', () => {
    jest.useFakeTimers();
    render(<AnimatedHealthScoreNumber value={82} duration={50} testID="score" />);

    // Before the animation runs, the display starts at 0, not the target -- the whole point of
    // this component versus AnimatedNumber (which shows the correct value immediately).
    expect(screen.getByTestId('score')).toHaveAnimatedProps({ text: '0', defaultValue: '0' });

    act(() => { jest.advanceTimersByTime(50); });

    expect(screen.getByTestId('score')).toHaveAnimatedProps({ text: '82', defaultValue: '82' });
    jest.useRealTimers();
  });

  it('animates smoothly from the last shown value when the score changes after mount', () => {
    jest.useFakeTimers();
    const { rerender } = render(<AnimatedHealthScoreNumber value={40} duration={50} testID="score" />);
    act(() => { jest.advanceTimersByTime(50); });
    expect(screen.getByTestId('score')).toHaveAnimatedProps({ text: '40', defaultValue: '40' });

    rerender(<AnimatedHealthScoreNumber value={75} duration={50} testID="score" />);
    act(() => { jest.advanceTimersByTime(50); });

    expect(screen.getByTestId('score')).toHaveAnimatedProps({ text: '75', defaultValue: '75' });
    jest.useRealTimers();
  });
});
