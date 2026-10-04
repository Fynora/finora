import { act, fireEvent, render, screen } from '@testing-library/react-native';
import * as Worklets from 'react-native-worklets';
import {
  LAUNCH_TIMELINE as T,
  LaunchAnimation,
  resetLaunchAnimationForTests,
  shouldPlayLaunchAnimation,
} from './LaunchAnimation';

jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));

// useReducedMotion reads the OS setting once at module load and is not spy-able (a non-configurable
// export), so the mock reads this flag instead.
let mockReducedMotion = false;
jest.mock('react-native-reanimated', () => {
  const actual = jest.requireActual('react-native-reanimated');
  return { __esModule: true, ...actual, default: actual.default, useReducedMotion: () => mockReducedMotion };
});

const GLYPH_HEIGHT = 48 * 1.6;

function advance(ms: number) {
  act(() => {
    jest.advanceTimersByTime(ms);
  });
}

describe('LaunchAnimation', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    resetLaunchAnimationForTests();
  });

  afterEach(() => {
    jest.useRealTimers();
    mockReducedMotion = false;
    jest.restoreAllMocks();
  });

  it('holds still on an empty field until the splash is released', () => {
    const onDone = jest.fn();
    render(<LaunchAnimation ready={false} onDone={onDone} />);

    advance(T.failsafe + 1000);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
    // Not even mounted yet: a Text laid out before the fonts load keeps the system fallback.
    expect(screen.queryByText('Fynora')).not.toBeOnTheScreen();
    expect(onDone).not.toHaveBeenCalled();
    expect(shouldPlayLaunchAnimation()).toBe(true);
  });

  it('assembles the mark, then lifts away and reports done exactly once', () => {
    const onDone = jest.fn();
    const { rerender } = render(<LaunchAnimation ready={false} onDone={onDone} />);
    rerender(<LaunchAnimation ready onDone={onDone} />);
    expect(screen.getByText('Fynora')).toBeOnTheScreen();

    advance(T.stemStart + T.stemDuration + 50);
    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: GLYPH_HEIGHT });
    expect(onDone).not.toHaveBeenCalled();

    // Still covering the app until the lift has finished...
    advance(T.exitStart + T.liftDelay + T.liftDuration - 50 - (T.stemStart + T.stemDuration + 50));
    expect(onDone).not.toHaveBeenCalled();
    // ...and done right after it, well before the failsafe.
    advance(150);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(shouldPlayLaunchAnimation()).toBe(false);

    // The failsafe timer must not report done a second time.
    advance(T.failsafe);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skips straight to the exit when tapped', () => {
    const onDone = jest.fn();
    render(<LaunchAnimation ready onDone={onDone} />);

    advance(500);
    fireEvent.press(screen.getByLabelText('Fynora'));
    advance(T.liftDelay + T.liftDuration + 100);

    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('ignores taps before the splash is released', () => {
    const onDone = jest.fn();
    render(<LaunchAnimation ready={false} onDone={onDone} />);

    fireEvent.press(screen.getByLabelText('Fynora'));
    advance(T.liftDelay + T.liftDuration + 100);

    expect(onDone).not.toHaveBeenCalled();
  });

  it('under reduced motion shows the finished mark, holds briefly, then fades out', () => {
    mockReducedMotion = true;
    const onDone = jest.fn();
    render(<LaunchAnimation ready onDone={onDone} />);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: GLYPH_HEIGHT });

    advance(T.reducedHold - 50);
    expect(onDone).not.toHaveBeenCalled();

    advance(50 + T.reducedFade + 100);
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('still reports done from the failsafe if the completion callback never arrives', () => {
    // Loses the animation's completion callback, to prove the JS failsafe timer alone still
    // removes the overlay.
    jest.spyOn(Worklets, 'scheduleOnRN').mockImplementation(() => undefined);
    const onDone = jest.fn();
    render(<LaunchAnimation ready onDone={onDone} />);

    advance(T.failsafe - 50);
    expect(onDone).not.toHaveBeenCalled();

    advance(100);
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(shouldPlayLaunchAnimation()).toBe(false);
  });
});
