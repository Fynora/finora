import { useEffect } from 'react';
import {
  AccessibilityInfo, Animated, AppState, StyleSheet, type DimensionValue, type ViewStyle,
} from 'react-native';
import { useTheme } from '../../theme';
import { skeletonTokens as T } from '../../theme/skeleton';

export interface ShimmerProps {
  width?: DimensionValue;
  height: DimensionValue;
  borderRadius?: number;
  style?: ViewStyle;
  testID?: string;
}

/**
 * One Animated.Value and one Animated.loop, shared by every Shimmer instance in the app, rather
 * than each instance running its own independent loop. A single loading shell can mount a couple
 * dozen Shimmer instances at once (e.g. Dashboard's first paint, or 8 skeleton rows x 3 Shimmers
 * each on Ledger) -- one shared driver means one JS->native-driver bridge call to start/stop
 * instead of one per instance, and every shimmer on screen pulses in sync as a side effect.
 * Ref-counted rather than always-on: the loop starts when the first Shimmer mounts and stops when
 * the last one unmounts, so it never runs in the background once nothing is loading anywhere.
 */
const sharedPulse = new Animated.Value(T.pulse.low);
let activeShimmerCount = 0;
let sharedLoop: Animated.CompositeAnimation | null = null;

/**
 * The pulse stops while the app is not in the foreground and restarts when it comes back. Measured
 * on the simulator (2026-10-15): with a skeleton held on screen and the app in the background, the
 * app's main thread took 209 samples in 8 s and its JS thread 83, against 29 and 1 for the same app
 * backgrounded with nothing loading -- the loop kept running where nobody could see it.
 * Subscribed only while something is loading, so an idle app holds no listener.
 */
let appInBackground = false;
let appStateSub: { remove: () => void } | null = null;

/** 'unknown' (iOS before its first report) and anything else that is not plainly out of the
 *  foreground count as foreground, so a pulse is never withheld on a guess. */
const isBackgroundState = (state: unknown) => state === 'background' || state === 'inactive';

function watchAppState() {
  if (appStateSub) return;
  // Read fresh on every new loading episode: the flag left behind when the last skeleton unmounted
  // in the background (and its listener went with it) says nothing about the app now.
  appInBackground = isBackgroundState(AppState.currentState);
  appStateSub = AppState.addEventListener('change', (state) => {
    if (isBackgroundState(state)) {
      appInBackground = true;
      sharedLoop?.stop();
      sharedLoop = null;
    } else {
      appInBackground = false;
      if (activeShimmerCount > 0 && !sharedLoop) startPulseUnlessReducedMotion();
    }
  });
}

function unwatchAppState() {
  appStateSub?.remove();
  appStateSub = null;
}

/**
 * Starts the pulse only if the OS "Reduce Motion" setting is off.
 *
 * This is the one animation in the app that had to ask. AnimatedNumber and ChartReveal are built on
 * Reanimated, whose withTiming defaults to `ReduceMotion.System` and so already goes instant when
 * the setting is on -- but this loop is React Native's own Animated API, which has no equivalent
 * behaviour and will happily run forever. And of the three it is the one that matters most: an
 * indefinitely repeating pulse is precisely the motion class that setting exists to suppress, and
 * a skeleton is on screen during every loading state in the app rather than for one 450ms
 * transition.
 *
 * Async because isReduceMotionEnabled() is a native round trip, so the count can have fallen back
 * to zero (a fast response, nothing left loading) before it resolves -- starting the loop then
 * would leave it running with no Shimmer mounted to ever release it.
 */
function startPulse() {
  // Guarded rather than assumed: isReduceMotionEnabled() is a native round trip, so by the time it
  // answers the count can have fallen back to zero (a fast response, nothing left loading) or
  // another episode can already have started a loop. Starting one here in either case would leave
  // it running with no Shimmer mounted to ever release it.
  if (activeShimmerCount === 0 || sharedLoop || appInBackground) return;
  sharedLoop = Animated.loop(
    Animated.sequence([
      Animated.timing(sharedPulse, { toValue: T.pulse.high, duration: T.pulse.halfCycleMs, useNativeDriver: true }),
      Animated.timing(sharedPulse, { toValue: T.pulse.low, duration: T.pulse.halfCycleMs, useNativeDriver: true }),
    ])
  );
  sharedLoop.start();
}

function startPulseUnlessReducedMotion() {
  void AccessibilityInfo.isReduceMotionEnabled()
    .then((reduceMotion) => {
      if (!reduceMotion) {
        startPulse();
        return;
      }
      if (activeShimmerCount === 0) return;
      // Static opacity instead of the pulse under Reduce Motion. Deliberately toward the bright end
      // of the range the animation sweeps: the block still has to read as a visible placeholder, it
      // just stops moving.
      sharedPulse.setValue(T.pulse.reducedMotion);
    })
    // A device that can't answer gets the animation rather than a frozen placeholder: fail toward
    // the pre-existing behaviour, not toward a skeleton that looks broken.
    .catch(startPulse);
}

function acquireSharedLoop() {
  activeShimmerCount += 1;
  if (activeShimmerCount === 1) {
    // releaseSharedLoop's stop() below freezes sharedPulse wherever it was mid-fade, not
    // necessarily back at 0.35 -- without this reset, the first pulse of a new loading episode
    // starts from that leftover value instead of the intended minimum, reading as a stutter.
    sharedPulse.setValue(T.pulse.low);
    watchAppState();
    startPulseUnlessReducedMotion();
    // No announcement here: each placeholder group (SkeletonGroup) is one element labelled
    // "Loading", which a screen reader reads when it reaches it -- the spec rules out a live-region
    // interruption every time something starts loading.
  }
}

function releaseSharedLoop() {
  activeShimmerCount -= 1;
  if (activeShimmerCount === 0) {
    sharedLoop?.stop();
    sharedLoop = null;
    unwatchAppState();
  }
}

/** Test-only: how many Shimmers hold the shared pulse right now. */
export function __activeShimmerCountForTests(): number {
  return activeShimmerCount;
}

export function Shimmer({
  width = '100%', height, borderRadius = T.radius.line, style, testID = 'shimmer-block',
}: ShimmerProps) {
  const c = useTheme();

  useEffect(() => {
    acquireSharedLoop();
    return () => releaseSharedLoop();
  }, []);

  return (
    <Animated.View
      testID={testID}
      // Placeholder only -- it stands in for content a screen reader will hear announced once the
      // real value replaces it. Exposing it too would double-announce every field on first load.
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.block, { width, height, borderRadius, backgroundColor: c[T.fill], opacity: sharedPulse }, style]}
    />
  );
}

const styles = StyleSheet.create({
  block: { overflow: 'hidden' },
});
