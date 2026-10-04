import { useCallback, useEffect, useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, useWindowDimensions } from 'react-native';
import { StatusBar } from 'expo-status-bar';
import Animated, {
  Easing,
  ReduceMotion,
  cancelAnimation,
  useAnimatedStyle,
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
 * "Fynora" wordmark settles in beneath it, then the field lifts off to reveal the app.
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
   * Upper bound on how long the overlay may stay up after `ready`. A full-screen overlay that
   * never left would make the app unusable, so this JS timer removes it even if the animation's
   * own completion callback never arrives.
   */
  failsafe: 4000,
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

/** Test-only: lets each test start from a fresh cold start. */
export function resetLaunchAnimationForTests() {
  played = false;
}

export function LaunchAnimation({ ready, onDone }: {
  /** False while the native splash is still up. Nothing moves until it is true. */
  ready: boolean;
  onDone: () => void;
}) {
  const reducedMotion = useReducedMotion();
  // The lift travels the screen's longer side, so it clears the screen in either orientation even
  // if the device rotates between the exit being scheduled and running.
  const { width, height } = useWindowDimensions();
  const liftDistance = Math.max(width, height);

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
  const exitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const finish = useCallback(() => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    played = true;
    onDone();
  }, [onDone]);

  const startExit = useCallback(() => {
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
      withTiming(-liftDistance, { duration: T.liftDuration, easing: LIFT, reduceMotion: ReduceMotion.Never }, onExitFinished),
      ReduceMotion.Never,
    ));
  }, [
    finish, liftDistance, reducedMotion, stemHeight, topArmWidth, middleArmWidth, glyphScale, wordmarkOpacity,
    wordmarkShift, markOpacity, markScale, fieldShift, fieldOpacity,
  ]);

  useEffect(() => {
    if (!ready) return;
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
    const failsafe = setTimeout(finish, T.failsafe);
    return () => {
      if (exitTimerRef.current !== null) clearTimeout(exitTimerRef.current);
      clearTimeout(failsafe);
    };
    // Runs once, when the splash hands over. The scheduled exit keeps this render's startExit; what
    // could change after that (onDone from App is a stable callback, liftDistance is
    // orientation-invariant) does not affect it.
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
      pointerEvents={exiting ? 'none' : 'auto'}
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
              <Text style={styles.wordmark} allowFontScaling={false}>Fynora</Text>
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
    elevation: 1000,
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
  wordmark: {
    color: CREAM,
    fontFamily: fonts.displayBold,
    fontSize: 22,
    letterSpacing: -0.2,
  },
});
