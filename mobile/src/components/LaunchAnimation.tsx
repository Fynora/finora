import { Component, useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Dimensions, Pressable, StyleSheet, Text, type LayoutChangeEvent } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import Animated, {
  Easing,
  ReduceMotion,
  cancelAnimation,
  useAnimatedStyle,
  useFrameCallback,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSequence,
  withTiming,
} from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';
import { fonts } from '../theme/fonts';

/**
 * Cold-start launch animation: the F is assembled bar by bar on a full-screen graphite field, the
 * "FYNORA" wordmark settles in beneath it, then the field lifts off to reveal the app.
 *
 * The field is the icon plate. iOS and Android both zoom the tapped icon up to fill the screen, so
 * the first frame here is that plate with nothing on it: GRAPHITE is BrandMark's fixed square
 * colour, the Android adaptive icon's backgroundColor, and the native splash's backgroundColor in
 * app.config.ts. All three must stay equal, or there is a visible colour step at the handoff.
 *
 * Bar geometry is BrandMark's 100-unit viewBox (stem x32 y26 13x48, top arm 34x13, middle arm at
 * y50 25x12, corner radius 4), shifted so the glyph's own top-left is the origin. The arms grow out
 * of the stem rather than fading in: each starts 13 units wide, exactly hidden under the finished
 * stem, so the visible part emerges from the stem's right edge.
 *
 * Every timing uses ReduceMotion.Never on purpose. Under the OS reduced-motion setting this
 * component takes its own static path instead (mark already assembled, a short hold, then a
 * fade). Leaving the default ReduceMotion.System would make each timing instant instead, so the
 * sequence would collapse into a single frame and the overlay would vanish before it was seen.
 */

const GRAPHITE = '#262A33';
const CREAM = '#F4F1EC';

/** Points per BrandMark viewBox unit. The glyph renders 54x77pt. */
const U = 1.6;
const GLYPH_WIDTH = 34 * U;
const GLYPH_HEIGHT = 48 * U;
const STEM_WIDTH = 13 * U;
const RADIUS = 4 * U;

const SETTLE = Easing.bezier(0.22, 1, 0.36, 1);
const LIFT = Easing.bezier(0.65, 0, 0.35, 1);

/** Milliseconds from start. Exported so tests can drive the sequence without restating it. */
export const LAUNCH_TIMELINE = {
  /**
   * The timeline starts only once the UI thread is drawing steadily (two frames in a row under
   * smoothFrameMs apart), not the moment the splash is released: the first draw of the whole app
   * under the overlay stalls the UI thread, measured at 183-333ms on an Android release build, and a
   * time-based animation runs on through a stall, so the stem's draw was skipped. startWaitCap
   * bounds the wait if frames never settle.
   */
  smoothFrameMs: 100,
  startWaitCap: 1000,
  stemStart: 120,
  stemDuration: 300,
  topArmStart: 440,
  middleArmStart: 520,
  armDuration: 240,
  settleStart: 820,
  settleDuration: 320,
  wordmarkStart: 900,
  wordmarkDuration: 280,
  exitStart: 1700,
  markFadeDuration: 160,
  liftDelay: 40,
  liftDuration: 360,
  reducedHold: 300,
  reducedFade: 200,
  /**
   * Upper bound on how long the overlay may stay up after `ready`, longer than startWaitCap plus
   * the whole timeline. A full-screen overlay that never left would make the app unusable, so this
   * JS timer removes it even if the animation's own completion callback never arrives.
   */
  failsafe: 4500,
} as const;

const T = LAUNCH_TIMELINE;

function timing(toValue: number, duration: number, easing = SETTLE) {
  return withTiming(toValue, { duration, easing, reduceMotion: ReduceMotion.Never });
}

let played = false;

/** False once the animation has finished in this JS runtime, so it plays on cold start only. */
export function shouldPlayLaunchAnimation() {
  return !played;
}

/**
 * Test-only. `false` starts a test from a fresh cold start; `true` starts it as if the animation
 * already finished, for tests about the app's steady state that mount the real App.
 */
export function setLaunchAnimationPlayedForTests(value: boolean) {
  played = value;
}

type LaunchAnimationProps = {
  /** False while the native splash is still up. Nothing moves until it is true. */
  ready: boolean;
  onDone: () => void;
};

/**
 * Sits outside App's RootErrorBoundary (it has to cover everything), so an error thrown here would
 * otherwise unmount the whole app. Drops the overlay instead: the app underneath is already mounted.
 */
class LaunchAnimationBoundary extends Component<{ onError: () => void; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch() {
    played = true;
    this.props.onError();
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

export function LaunchAnimation(props: LaunchAnimationProps) {
  return (
    <LaunchAnimationBoundary onError={props.onDone}>
      <LaunchAnimationContent {...props} />
    </LaunchAnimationBoundary>
  );
}

function LaunchAnimationContent({ ready, onDone }: LaunchAnimationProps) {
  const reducedMotion = useReducedMotion();
  // How far the lift travels: the overlay's own measured height, read when the exit starts, so it
  // clears the screen exactly whatever the window reports (Android edge-to-edge) or a rotation does
  // in between. Seeded with the screen's longer side for the frames before layout arrives.
  const fieldHeight = useSharedValue(Math.max(Dimensions.get('screen').width, Dimensions.get('screen').height));
  const onFieldLayout = useCallback((event: LayoutChangeEvent) => {
    fieldHeight.set(event.nativeEvent.layout.height);
  }, [fieldHeight]);

  const stemHeight = useSharedValue(reducedMotion ? GLYPH_HEIGHT : 0);
  const topArmWidth = useSharedValue(reducedMotion ? 34 * U : 0);
  const middleArmWidth = useSharedValue(reducedMotion ? 25 * U : 0);
  const glyphScale = useSharedValue(reducedMotion ? 0.97 : 1);
  const wordmarkOpacity = useSharedValue(reducedMotion ? 1 : 0);
  const wordmarkShift = useSharedValue(reducedMotion ? 0 : 8);
  const markOpacity = useSharedValue(1);
  const markScale = useSharedValue(1);
  const fieldShift = useSharedValue(0);
  const fieldOpacity = useSharedValue(1);

  const [exiting, setExiting] = useState(false);
  const finishedRef = useRef(false);
  const begunRef = useRef(false);
  const released = useSharedValue(false);
  const smoothRun = useSharedValue(0);
  const exitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const finish = useCallback(() => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    played = true;
    onDone();
  }, [onDone]);

  const startExit = useCallback(() => {
    // A tap can arrive before the timeline has begun; it must not begin after this.
    begunRef.current = true;
    if (exitTimerRef.current !== null) {
      clearTimeout(exitTimerRef.current);
      exitTimerRef.current = null;
    }
    setExiting(true);
    // Stop the assembly wherever it is; the mark fades out from that state.
    for (const value of [stemHeight, topArmWidth, middleArmWidth, glyphScale, wordmarkOpacity, wordmarkShift]) {
      cancelAnimation(value);
    }
    const onExitFinished = (completed?: boolean) => {
      'worklet';
      if (completed) scheduleOnRN(finish);
    };
    if (reducedMotion) {
      fieldOpacity.set(withTiming(0, { duration: T.reducedFade, reduceMotion: ReduceMotion.Never }, onExitFinished));
      return;
    }
    markOpacity.set(timing(0, T.markFadeDuration));
    markScale.set(timing(0.94, T.markFadeDuration));
    fieldShift.set(withDelay(
      T.liftDelay,
      withTiming(-fieldHeight.get(), { duration: T.liftDuration, easing: LIFT, reduceMotion: ReduceMotion.Never }, onExitFinished),
      ReduceMotion.Never,
    ));
  }, [
    finish, fieldHeight, reducedMotion, stemHeight, topArmWidth, middleArmWidth, glyphScale, wordmarkOpacity,
    wordmarkShift, markOpacity, markScale, fieldShift, fieldOpacity,
  ]);

  // With a screen reader on, the animation is skipped outright: it is purely visual, so it would
  // only make the user wait, and while it covered the app their reader would have nothing useful
  // to read. Done at once, before or after the splash is released.
  useEffect(() => {
    let cancelled = false;
    AccessibilityInfo.isScreenReaderEnabled()
      .then((enabled) => {
        if (!cancelled && enabled) finish();
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [finish]);

  // Starts the timeline. Called once: by the frame gate below, or by the startWaitCap timer.
  const begin = useCallback(() => {
    if (begunRef.current) return;
    begunRef.current = true;
    if (!reducedMotion) {
      stemHeight.set(withDelay(T.stemStart, timing(GLYPH_HEIGHT, T.stemDuration), ReduceMotion.Never));
      topArmWidth.set(withDelay(
        T.topArmStart,
        withSequence(timing(STEM_WIDTH, 0), timing(34 * U, T.armDuration)),
        ReduceMotion.Never,
      ));
      middleArmWidth.set(withDelay(
        T.middleArmStart,
        withSequence(timing(STEM_WIDTH, 0), timing(25 * U, T.armDuration)),
        ReduceMotion.Never,
      ));
      glyphScale.set(withDelay(T.settleStart, timing(0.97, T.settleDuration), ReduceMotion.Never));
      wordmarkOpacity.set(withDelay(T.wordmarkStart, timing(1, T.wordmarkDuration), ReduceMotion.Never));
      wordmarkShift.set(withDelay(T.wordmarkStart, timing(0, T.wordmarkDuration), ReduceMotion.Never));
    }
    exitTimerRef.current = setTimeout(startExit, reducedMotion ? T.reducedHold : T.exitStart);
  }, [
    reducedMotion, startExit, stemHeight, topArmWidth, middleArmWidth, glyphScale, wordmarkOpacity, wordmarkShift,
  ]);

  // The frame gate: counts consecutive steady frames on the UI thread once the splash is released,
  // and begins the timeline on the second. Switched off once it has fired.
  const gate = useFrameCallback((info) => {
    'worklet';
    if (!released.get()) return;
    const gap = info.timeSincePreviousFrame;
    const steady = gap !== null && gap > 0 && gap < T.smoothFrameMs;
    smoothRun.set(steady ? smoothRun.get() + 1 : 0);
    if (smoothRun.get() >= 2) {
      released.set(false);
      scheduleOnRN(begin);
    }
  });

  useEffect(() => {
    if (!ready) return;
    released.set(true);
    const startCap = setTimeout(begin, T.startWaitCap);
    const failsafe = setTimeout(finish, T.failsafe);
    return () => {
      clearTimeout(startCap);
      if (exitTimerRef.current !== null) clearTimeout(exitTimerRef.current);
      clearTimeout(failsafe);
      gate.setActive(false);
    };
    // Runs once, when the splash hands over. What could change after that does not affect it:
    // onDone from App is a stable callback, and the lift distance is read when the exit runs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  const fieldStyle = useAnimatedStyle(() => ({
    opacity: fieldOpacity.value,
    transform: [{ translateY: fieldShift.value }],
  }));
  const markStyle = useAnimatedStyle(() => ({
    opacity: markOpacity.value,
    transform: [{ scale: markScale.value }],
  }));
  const glyphStyle = useAnimatedStyle(() => ({ transform: [{ scale: glyphScale.value }] }));
  const stemStyle = useAnimatedStyle(() => ({ height: stemHeight.value }));
  const topArmStyle = useAnimatedStyle(() => ({ width: topArmWidth.value }));
  const middleArmStyle = useAnimatedStyle(() => ({ width: middleArmWidth.value }));
  const wordmarkStyle = useAnimatedStyle(() => ({
    opacity: wordmarkOpacity.value,
    transform: [{ translateY: wordmarkShift.value }],
  }));

  return (
    <Animated.View
      testID="launch-animation"
      style={[StyleSheet.absoluteFill, styles.field, fieldStyle]}
      onLayout={onFieldLayout}
      // Keeps blocking touches through the lift (a few hundred ms), so a tap can't land on app content
      // the field still hides. The reduced-motion fade is the exception: an opacity fade leaves the
      // field covering the whole screen, and if its completion callback were ever lost it would keep
      // swallowing every touch until the failsafe. The lifted field is off-screen by then instead.
      pointerEvents={exiting && reducedMotion ? 'none' : 'auto'}
    >
      <StatusBar style="light" />
      <Pressable
        style={styles.center}
        onPress={startExit}
        disabled={!ready || exiting}
        accessibilityRole="button"
        accessibilityLabel="Fynora"
        accessibilityHint="Skips the opening animation"
      >
        <Animated.View style={markStyle}>
          <Animated.View style={[styles.glyph, glyphStyle]}>
            <Animated.View style={[styles.bar, styles.topArm, topArmStyle]} />
            <Animated.View style={[styles.bar, styles.middleArm, middleArmStyle]} />
            <Animated.View testID="launch-animation-stem" style={[styles.bar, styles.stem, stemStyle]} />
          </Animated.View>
          {/* Mounted only once `ready`: this overlay's first commit lands before the fonts finish
              loading, and a Text laid out then keeps the system fallback after Manrope arrives
              (seen on the iOS simulator: the wordmark rendered in SF Pro). */}
          {ready ? (
            <Animated.View style={[styles.wordmarkSlot, wordmarkStyle]}>
              <Text style={styles.wordmark} allowFontScaling={false}>FYNORA</Text>
            </Animated.View>
          ) : null}
        </Animated.View>
      </Pressable>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  field: {
    backgroundColor: GRAPHITE,
    zIndex: 1000,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  glyph: {
    width: GLYPH_WIDTH,
    height: GLYPH_HEIGHT,
  },
  bar: {
    position: 'absolute',
    left: 0,
    backgroundColor: CREAM,
    borderRadius: RADIUS,
  },
  // Anchored at the bottom so a growing height reads as the stem drawing upward.
  stem: {
    bottom: 0,
    width: STEM_WIDTH,
  },
  topArm: {
    top: 0,
    height: 13 * U,
  },
  middleArm: {
    top: 24 * U,
    height: 12 * U,
  },
  // Absolute, so the wordmark arriving never shifts the glyph off centre.
  wordmarkSlot: {
    position: 'absolute',
    top: GLYPH_HEIGHT + 28,
    left: -100,
    right: -100,
    alignItems: 'center',
  },
  // The brand wordmark as the app and web draw it everywhere else (AuthScreenLayout, the
  // dashboard header, the web sidebar and auth pages): uppercase Manrope ExtraBold with wide
  // tracking, about 0.07em, so the launch hands over to the same lettering the first screen uses.
  wordmark: {
    color: CREAM,
    fontFamily: fonts.display,
    fontSize: 20,
    letterSpacing: 1.4,
  },
});
