import { useEffect } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import Animated, { useAnimatedStyle, useReducedMotion, useSharedValue, withTiming } from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { GlassView, isGlassEffectAPIAvailable, isLiquidGlassAvailable } from 'expo-glass-effect';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useGlassSurfaceStyle } from '../GlassSurface';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { spacing, typography, useTheme } from '../../theme';
import { dark } from '../../theme/palette';
import { glassFill } from '../../theme/glass';

export const PINNED_BAR_HEIGHT = 54;
/**
 * How far the list moves before the bar appears: the space above the large title. The list is cut
 * off at the status bar, so from here on the title would meet that edge; the bar appears at the
 * same moment and the title goes under it instead.
 */
export const PINNED_BAR_SHOWN_AFTER = spacing.sm;
const FADE_MS = 150;

function RoundButton({ icon, label, onPress }: { icon: 'add' | 'search'; label: string; onPress: () => void }) {
  const c = useTheme();
  const glass = useGlassSurfaceStyle();
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={label} style={[styles.round, glass]}>
      <Ionicons name={icon} size={22} color={c.ink} />
    </Pressable>
  );
}

/** The title, the count under it and the add button. Part of the list's header: it scrolls away. */
export function LedgerTitleBlock({ countLabel, onAdd }: { countLabel: string; onAdd: () => void }) {
  const c = useTheme();
  return (
    <View testID="ledger-title-block" style={styles.block}>
      <View style={styles.blockText}>
        {/* One word beside a button: at the largest text sizes it broke in the middle
            ("Transactio" / "ns"). It grows to 1.4 times and shrinks to fit a narrow screen. */}
        <Text
          accessibilityRole="header"
          numberOfLines={1}
          adjustsFontSizeToFit
          maxFontSizeMultiplier={1.4}
          style={[typography.screenTitle, { color: c.ink }]}
        >
          Transactions
        </Text>
        {/* Always rendered, so the blocks under it do not jump when the count arrives. */}
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>{countLabel}</Text>
      </View>
      <RoundButton icon="add" label="Add transaction" onPress={onAdd} />
    </View>
  );
}

/**
 * The bar's surface. Clear while the list is at rest, solid once it has moved, so nothing is cut
 * by a hard edge under the clock. A glass or blur view does not draw when it, or a view
 * above it, has an opacity below one (Expo's documented limit, from UIVisualEffectView), so glass
 * is never faded that way here: Liquid Glass animates its own style, and the tint over it is a
 * child, which may fade freely.
 */
function PinnedSurface({ solid }: { solid: boolean }) {
  const c = useTheme();
  const reducedMotion = useReducedMotion();
  // Android has no blur behind a tinted fill, so a see-through bar would show the list through it.
  const opaque = useReduceTransparency() !== false || Platform.OS !== 'ios';
  const shown = useSharedValue(solid ? 1 : 0);
  // Reanimated's timing follows the system's Reduce Motion setting and becomes immediate under it.
  useEffect(() => { shown.set(withTiming(solid ? 1 : 0, { duration: FADE_MS })); }, [solid, shown]);
  const fade = useAnimatedStyle(() => ({ opacity: shown.get() }));

  if (opaque) {
    return (
      <Animated.View
        testID="ledger-pinned-fill"
        pointerEvents="none"
        style={[
          StyleSheet.absoluteFill,
          styles.edge,
          { backgroundColor: c.card, borderBottomColor: c.border }, /* glass-exempt: the solid bar for Reduce Transparency and Android, where content must not show through */
          fade,
        ]}
      />
    );
  }

  const tint = { backgroundColor: glassFill(c), borderBottomColor: c.glassEdge };
  if (isLiquidGlassAvailable() && isGlassEffectAPIAvailable()) {
    return (
      <GlassView
        pointerEvents="none"
        colorScheme={c === dark ? 'dark' : 'light'}
        glassEffectStyle={{ style: solid ? 'regular' : 'none', animate: !reducedMotion, animationDuration: FADE_MS / 1000 }}
        style={StyleSheet.absoluteFill}
      >
        <Animated.View testID="ledger-pinned-fill" pointerEvents="none" style={[StyleSheet.absoluteFill, styles.edge, tint, fade]} />
      </GlassView>
    );
  }
  // iOS 16.4 to 25: a blur view cannot be faded by opacity either, and there is no simulator below
  // iOS 26 here to check an animated blur on, so it appears and disappears at once.
  return solid ? (
    <BlurView
      testID="ledger-pinned-blur"
      pointerEvents="none"
      intensity={60}
      tint={c === dark ? 'systemThinMaterialDark' : 'systemThinMaterialLight'}
      style={StyleSheet.absoluteFill}
    >
      <View testID="ledger-pinned-fill" pointerEvents="none" style={[StyleSheet.absoluteFill, styles.edge, tint]} />
    </BlurView>
  ) : null;
}

/**
 * Sits over the top of the screen, from its very top to 54 points under the status bar. Hidden
 * while the list is at rest. Once the list has moved, the surface and the small title with its two
 * buttons appear together: the surface hides the large title and its add button as they scroll
 * under it, so their replacements have to be there from the same moment (seen on the simulator
 * when the two appeared at different times: a frosted, empty bar).
 */
export function LedgerPinnedBar({
  shown, topInset, onSearch, onAdd,
}: { shown: boolean; topInset: number; onSearch: () => void; onAdd: () => void }) {
  const c = useTheme();
  const opacity = useSharedValue(shown ? 1 : 0);
  useEffect(() => { opacity.set(withTiming(shown ? 1 : 0, { duration: FADE_MS })); }, [shown, opacity]);
  const fade = useAnimatedStyle(() => ({ opacity: opacity.get() }));

  return (
    <View
      testID="ledger-pinned-bar"
      // Never catches a touch itself; its buttons do, and only while they are shown.
      pointerEvents={shown ? 'box-none' : 'none'}
      style={[styles.bar, { height: topInset + PINNED_BAR_HEIGHT, paddingTop: topInset }]}
    >
      <PinnedSurface solid={shown} />
      <Animated.View
        testID="ledger-pinned-contents"
        pointerEvents={shown ? 'box-none' : 'none'}
        accessibilityElementsHidden={!shown}
        importantForAccessibility={shown ? 'auto' : 'no-hide-descendants'}
        style={[styles.contents, fade]}
      >
        <Text style={[typography.cardTitle, styles.barTitle, { color: c.ink }]} numberOfLines={1} maxFontSizeMultiplier={1.3}>
          Transactions
        </Text>
        <RoundButton icon="search" label="Search transactions" onPress={onSearch} />
        <RoundButton icon="add" label="Add transaction" onPress={onAdd} />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  block: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms,
    paddingTop: spacing.sm,
  },
  blockText: { flex: 1 },
  round: {
    width: 44, height: 44, borderRadius: 22, borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center', justifyContent: 'center',
  },
  bar: { position: 'absolute', top: 0, left: 0, right: 0 },
  edge: { borderBottomWidth: StyleSheet.hairlineWidth },
  contents: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingHorizontal: spacing.ml },
  barTitle: { flex: 1 },
});
