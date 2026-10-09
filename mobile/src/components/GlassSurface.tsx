import { StyleSheet, View, type ViewProps } from 'react-native';
import { useTheme } from '../theme';
import { glassFill } from '../theme/glass';
import { useReduceTransparency } from '../lib/useReduceTransparency';

export type GlassVariant = 'panel' | 'row';

/**
 * The one glass surface. Phase 1: translucent fill + hairline edge on every platform. Phase 2
 * swaps the `panel` body for GlassView (iOS 26+) / BlurView (iOS 16.4-25) HERE ONLY -- callers
 * never branch on platform. `row` (list rows, inputs, chips) never blurs: a FlatList of native
 * blur views is costly, and rows sit on a panel or the backdrop that already reads as glass.
 *
 * Fill and edge come BEFORE the caller's style: a deliberate tint or border colour from the call
 * site wins (Dashboard's warning banners tint a Card with warningBg; Import's and Statement
 * History's error cards give it a danger border -- that colour IS the signal). Re-adding an opaque
 * `c.card`/`c.bg` fill by hand is what glassMigration.test.ts guards against, so "after" was never
 * what kept surfaces glass. Under Reduce Transparency, or before the setting is known, the pair is
 * exactly the pre-glass solid card, so that path needs no separate component.
 */
export function GlassSurface({ style, children, variant = 'panel', ...rest }: ViewProps & { variant?: GlassVariant }) {
  const surface = useGlassSurfaceStyle();
  void variant; // consumed in Phase 2 (native blur paths)
  return (
    <View {...rest} style={[styles.edge, surface, style]}>
      {children}
    </View>
  );
}

/**
 * The fill/edge pair GlassSurface paints, for the few surfaces that cannot be a GlassSurface
 * View -- a Pressable list row that needs onPress/android_ripple, for instance. Same solid
 * fallback under Reduce Transparency, so there is still one source of truth for "what glass
 * looks like". Always a `row`-grade surface: Phase 2's native blur applies to panels only.
 */
export function useGlassSurfaceStyle(): { backgroundColor: string; borderColor: string } {
  const c = useTheme();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  return solid
    ? { backgroundColor: c.card, borderColor: c.border }
    : { backgroundColor: glassFill(c), borderColor: c.glassEdge };
}

const styles = StyleSheet.create({ edge: { borderWidth: StyleSheet.hairlineWidth } });
