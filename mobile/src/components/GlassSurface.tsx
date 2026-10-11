import { Platform, StyleSheet, View, type ViewProps, type ViewStyle } from 'react-native';
import { BlurView } from 'expo-blur';
import { GlassView, isGlassEffectAPIAvailable, isLiquidGlassAvailable } from 'expo-glass-effect';
import { useTheme } from '../theme';
import { dark } from '../theme/palette';
import { glassFill } from '../theme/glass';
import { useReduceTransparency } from '../lib/useReduceTransparency';

export type GlassVariant = 'panel' | 'row';

/**
 * The one glass surface. `panel` (cards, sheets, modals, the tab bar) gets real glass on iOS:
 * Liquid Glass (GlassView) on iOS 26+ when the API is actually present -- some iOS 26 betas ship
 * without it and crash, which is what `isGlassEffectAPIAvailable()` exists for -- and BlurView on
 * iOS 16.4-25. Android and `row` surfaces (list rows, inputs, chips; a FlatList of native blur
 * views is costly) stay on the Phase 1 tinted View. Callers never branch on platform.
 *
 * The native paths keep the Phase 1 tint as an inner layer under the children: that is the fill
 * glassContrast.test.ts measured, so text contrast never drops below the measured floor however
 * the blur renders. A deliberate caller tint (Dashboard's warning banners) moves onto that layer,
 * because a backgroundColor on the native view itself would sit over the effect. Under Reduce
 * Transparency, or before the setting is known, every path is the pre-glass solid card.
 *
 * Availability and Platform.OS are read at render, not at module load: the same guard, and the
 * tests can drive each path without a module-registry reset (which splits React in two).
 */
export function GlassSurface({ style, children, variant = 'panel', ...rest }: ViewProps & { variant?: GlassVariant }) {
  const c = useTheme();
  const surface = useGlassSurfaceStyle();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  const native = !solid && variant === 'panel' && Platform.OS === 'ios';

  if (!native) {
    return (
      <View {...rest} style={[styles.edge, surface, style]}>
        {children}
      </View>
    );
  }

  const { backgroundColor, ...shape } = StyleSheet.flatten(style) ?? ({} as ViewStyle);
  const tint = (
    <View
      testID="glass-tint"
      pointerEvents="none"
      style={[StyleSheet.absoluteFill, { backgroundColor: backgroundColor ?? glassFill(c) }]}
    />
  );
  // Scheme from palette identity, same reason as GlassScreen: useThemeSetting() throws without a
  // provider, useTheme() falls back, and both hand back the exact `dark`/`light` module objects.
  const scheme = c === dark ? 'dark' : 'light';
  const liquid = isLiquidGlassAvailable() && isGlassEffectAPIAvailable();
  const blurTint = scheme === 'dark' ? 'systemThinMaterialDark' : 'systemThinMaterialLight';

  // A surface that casts a shadow cannot be the effect view itself: the effect view has to clip
  // to its corner radius (below), and on iOS a view that clips also clips its own shadow -- the
  // dashboard cards declared one that was never drawn. So the caller's whole style, shadow
  // included, goes on a plain unclipped view, and the glass is a clipped layer behind the
  // children. Such a surface no longer clips its children; a caller that needs both (the health
  // hero's glow) wraps a shadowless GlassSurface in its own shadow view instead.
  if (shape.boxShadow) {
    const corners = Object.fromEntries(Object.entries(shape).filter(([key]) => /^border.*Radius$/.test(key)));
    const layer = [StyleSheet.absoluteFill, styles.clip, corners];
    return (
      <View {...rest} style={[styles.edge, { borderColor: c.glassEdge }, shape]}>
        {liquid ? (
          <GlassView testID="glass-layer" pointerEvents="none" glassEffectStyle="regular" colorScheme={scheme} style={layer}>
            {tint}
          </GlassView>
        ) : (
          <BlurView testID="glass-layer" pointerEvents="none" intensity={60} tint={blurTint} style={layer}>
            {tint}
          </BlurView>
        )}
        {children}
      </View>
    );
  }

  const outer = [styles.edge, styles.clip, { borderColor: c.glassEdge }, shape];

  if (liquid) {
    return (
      <GlassView {...rest} glassEffectStyle="regular" colorScheme={scheme} style={outer}>
        {tint}
        {children}
      </GlassView>
    );
  }
  return (
    <BlurView {...rest} intensity={60} tint={blurTint} style={outer}>
      {tint}
      {children}
    </BlurView>
  );
}

/**
 * The fill/edge pair GlassSurface paints, for the few surfaces that cannot be a GlassSurface
 * View -- a Pressable list row that needs onPress/android_ripple, for instance. Same solid
 * fallback under Reduce Transparency, so there is still one source of truth for "what glass
 * looks like". Always a `row`-grade surface: native blur applies to panels only.
 */
export function useGlassSurfaceStyle(): { backgroundColor: string; borderColor: string } {
  const c = useTheme();
  const solid = useReduceTransparency() !== false; // null (unknown) renders solid too
  return solid
    ? { backgroundColor: c.card, borderColor: c.border }
    : { backgroundColor: glassFill(c), borderColor: c.glassEdge };
}

const styles = StyleSheet.create({
  edge: { borderWidth: StyleSheet.hairlineWidth },
  // The native effect views do not clip to the border radius on their own.
  clip: { overflow: 'hidden' },
});
