import { useEffect } from 'react';
import { StyleSheet, TextInput, type StyleProp, type TextStyle } from 'react-native';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { CHART_REVEAL_DURATION } from './charts/ChartReveal';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

interface Props {
  value: number;
  style?: StyleProp<TextStyle>;
  duration?: number;
  testID?: string;
}

/**
 * Mobile counterpart to frontend/src/pages/Dashboard.tsx's AnimatedHealthScoreNumber/useCountUp --
 * counts up from 0 to `value` on mount, then settles. Deliberately NOT AnimatedNumber (the KPI
 * cards' component): that one intentionally skips animating the first render and only transitions
 * on a later CHANGE ("never a count-up intro" -- see its own doc comment), which is the opposite
 * of what the Financial Health Score card wants here.
 *
 * Reuses AnimatedNumber's technique regardless -- useAnimatedProps driving a non-editable
 * TextInput's `text` prop directly on the UI thread, so the ~60/sec updates while counting never
 * trigger a JS re-render of the rest of the Dashboard. Reanimated's withTiming already respects
 * the OS's reduced-motion setting by default (ReduceMotion.System) -- unlike web's manual
 * prefers-reduced-motion check, nothing extra is needed here for that.
 *
 * A later score change (e.g. a background refetch landing a new value) animates smoothly from
 * whatever was last shown, same as AnimatedNumber -- only the very first mount counts up from
 * zero, matching web's identical behavior.
 */
export function AnimatedHealthScoreNumber({ value, style, duration = CHART_REVEAL_DURATION, testID }: Props) {
  const animated = useSharedValue(0);

  useEffect(() => {
    animated.value = withTiming(value, { duration, easing: Easing.out(Easing.cubic) });
  }, [value, duration, animated]);

  const animatedProps = useAnimatedProps(() => {
    // `text`/`defaultValue` aren't part of RN's public TextInputProps type, but both are real
    // native props TextInput accepts -- same cast as AnimatedNumber's identical call.
    const formatted = String(Math.round(animated.value));
    return {
      text: formatted,
      defaultValue: formatted,
    } as Partial<React.ComponentProps<typeof TextInput>>;
  });

  return (
    <AnimatedTextInput
      testID={testID}
      editable={false}
      // See AnimatedNumber's identical props for why each of these is needed, not just
      // editable={false} alone (Android TalkBack double-announcement, etc.).
      focusable={false}
      pointerEvents="none"
      underlineColorAndroid="transparent"
      style={[styles.text, style]}
      animatedProps={animatedProps}
      accessibilityLabel={String(Math.round(value))}
    />
  );
}

const styles = StyleSheet.create({
  text: { padding: 0, margin: 0 },
});
