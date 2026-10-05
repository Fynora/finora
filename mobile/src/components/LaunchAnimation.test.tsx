import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';
import * as Worklets from 'react-native-worklets';
import {
  LAUNCH_TIMELINE as T,
  LaunchAnimation,
  setLaunchAnimationPlayedForTests,
  shouldPlayLaunchAnimation,
} from './LaunchAnimation';

jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));

// useReducedMotion reads the OS setting once at module load and is not spy-able (a non-configurable
// export), so the mock reads this flag instead.
let mockReducedMotion = false;
let mockThrowOnRender = false;
// Reanimated's Jest runtime has no frame callbacks (registerFrameCallback is undefined there), so
// the component's frame gate is captured here and driven with synthetic frames where a test needs
// it. Left alone, the gate never fires and the timeline begins from the startWaitCap timer.
type FrameInfo = { timeSincePreviousFrame: number | null };
let mockFrameCallback: ((info: FrameInfo) => void) | null = null;
jest.mock('react-native-reanimated', () => {
  const actual = jest.requireActual('react-native-reanimated');
  return {
    __esModule: true,
    ...actual,
    default: actual.default,
    useReducedMotion: () => {
      if (mockThrowOnRender) throw new Error('render failure');
      return mockReducedMotion;
    },
    useFrameCallback: (callback: (info: FrameInfo) => void) => {
      mockFrameCallback = callback;
      return { setActive: () => undefined, isActive: true, callbackId: 0 };
    },
  };
});

const GLYPH_HEIGHT = 48 * 1.6;

function advance(ms: number) {
  act(() => {
    jest.advanceTimersByTime(ms);
  });
}

/** Feeds the captured frame gate one UI frame, `gap` ms after the previous one. */
function frame(gap: number | null) {
  act(() => {
    mockFrameCallback?.({ timeSincePreviousFrame: gap });
  });
}

describe('LaunchAnimation', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    setLaunchAnimationPlayedForTests(false);
    mockFrameCallback = null;
  });

  afterEach(() => {
    jest.useRealTimers();
    mockReducedMotion = false;
    mockThrowOnRender = false;
    jest.restoreAllMocks();
  });

  it('holds still on an empty field until the splash is released', () => {
    const onDone = jest.fn();
    render(<LaunchAnimation ready={false} onDone={onDone} />);

    advance(T.failsafe + 1000);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
    // Not even mounted yet: a Text laid out before the fonts load keeps the system fallback.
    expect(screen.queryByText('FYNORA')).not.toBeOnTheScreen();
    expect(onDone).not.toHaveBeenCalled();
    expect(shouldPlayLaunchAnimation()).toBe(true);
  });

  it('assembles the mark, then lifts away and reports done exactly once', () => {
    const onDone = jest.fn();
    const { rerender } = render(<LaunchAnimation ready={false} onDone={onDone} />);
    rerender(<LaunchAnimation ready onDone={onDone} />);
    expect(screen.getByText('FYNORA')).toBeOnTheScreen();

    // No steady frames here, so the timeline waits for its cap before it begins.
    advance(T.startWaitCap - 10);
    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
    advance(10);

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

    advance(T.startWaitCap + T.reducedHold - 50);
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

  it('keeps blocking touches through the lift, so a tap cannot reach app content still hidden', () => {
    render(<LaunchAnimation ready onDone={jest.fn()} />);

    advance(T.startWaitCap + T.exitStart + T.liftDelay + T.liftDuration / 2);

    expect(screen.getByTestId('launch-animation').props.pointerEvents).toBe('auto');
  });

  it('stops blocking touches as soon as the reduced-motion fade starts', () => {
    mockReducedMotion = true;
    render(<LaunchAnimation ready onDone={jest.fn()} />);
    expect(screen.getByTestId('launch-animation').props.pointerEvents).toBe('auto');

    advance(T.startWaitCap + T.reducedHold + 10);

    expect(screen.getByTestId('launch-animation').props.pointerEvents).toBe('none');
  });

  it('lifts by its own measured height, not an assumed screen size', () => {
    render(<LaunchAnimation ready onDone={jest.fn()} />);
    fireEvent(screen.getByTestId('launch-animation'), 'layout', {
      nativeEvent: { layout: { x: 0, y: 0, width: 400, height: 1234 } },
    });

    advance(T.startWaitCap + T.exitStart + T.liftDelay + T.liftDuration + 100);

    expect(screen.getByTestId('launch-animation')).toHaveAnimatedStyle({ transform: [{ translateY: -1234 }] });
  });

  it('drops the overlay and reports done if it fails to render, rather than taking the app down', () => {
    mockThrowOnRender = true;
    jest.spyOn(console, 'error').mockImplementation(() => undefined);
    const onDone = jest.fn();

    render(<LaunchAnimation ready onDone={onDone} />);

    expect(screen.queryByTestId('launch-animation')).not.toBeOnTheScreen();
    expect(onDone).toHaveBeenCalledTimes(1);
    expect(shouldPlayLaunchAnimation()).toBe(false);
  });

  // The first draw of the whole app stalls the UI thread (183-333ms measured on Android); a timeline
  // started before it would run on through the stall and skip the stem's draw.
  it('begins as soon as the UI thread draws two steady frames, without waiting for the cap', () => {
    render(<LaunchAnimation ready onDone={jest.fn()} />);

    frame(null);
    frame(300); // the startup stall
    frame(16);
    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
    frame(16);

    advance(T.stemStart + T.stemDuration + 50);
    expect(T.stemStart + T.stemDuration + 50).toBeLessThan(T.startWaitCap);
    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: GLYPH_HEIGHT });
  });

  it('does not count a stall as steady: the run of steady frames starts over after one', () => {
    render(<LaunchAnimation ready onDone={jest.fn()} />);

    frame(16);
    frame(T.smoothFrameMs); // at the threshold, not under it
    frame(16);
    advance(T.stemStart + T.stemDuration + 50);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
  });

  it('ignores frames before the splash is released', () => {
    const { rerender } = render(<LaunchAnimation ready={false} onDone={jest.fn()} />);

    frame(16);
    frame(16);
    frame(16);
    rerender(<LaunchAnimation ready={false} onDone={jest.fn()} />);
    advance(T.stemStart + T.stemDuration + 50);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
  });

  it('never begins the timeline after a tap has already started the exit', () => {
    const onDone = jest.fn();
    render(<LaunchAnimation ready onDone={onDone} />);

    advance(100);
    fireEvent.press(screen.getByLabelText('Fynora'));
    advance(T.startWaitCap + T.stemStart + T.stemDuration);

    expect(screen.getByTestId('launch-animation-stem')).toHaveAnimatedStyle({ height: 0 });
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('does not play at all with a screen reader on, so its user is never kept waiting', async () => {
    jest.spyOn(AccessibilityInfo, 'isScreenReaderEnabled').mockResolvedValue(true);
    const onDone = jest.fn();

    render(<LaunchAnimation ready={false} onDone={onDone} />);

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(shouldPlayLaunchAnimation()).toBe(false);
    // Nothing left running to report done a second time.
    advance(T.failsafe + 1000);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});
