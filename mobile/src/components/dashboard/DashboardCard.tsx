import type { ReactNode } from 'react';
import { StyleSheet, type ViewStyle } from 'react-native';
import { radius, shadows, spacing } from '../../theme';
import { GlassSurface, type GlassVariant } from '../GlassSurface';

/**
 * Dashboard-only surface for the passbook redesign -- a hairline border (not `Card`'s full 1px)
 * plus more internal whitespace and a soft shadow, not zero separation. Corrected from an
 * initial fully-borderless design: the app has no elevation/shadow token anywhere (see
 * docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md's scope-boundary
 * section), so removing every visual separator risked cards merging together -- "quiet, not
 * flat." Deliberately NOT a change to `Card.tsx` itself. Same `{children, style, testID}` shape
 * as `Card` so a call site swaps between them with no other change.
 *
 * Since the glass redesign both are GlassSurface panels; GlassSurface owns the fill, the edge
 * colour and the solid fallback under Reduce Transparency. What stays here is the shape: the
 * hairline border, the larger padding and the soft shadow.
 *
 * Card redesign (2026-10-10): radius 20 and two paddings. Every dashboard card and tile is a
 * glass `panel` (GlassSurface's default): Liquid Glass on iOS 26, blur on older iOS, the tinted
 * fill on Android. `variant` is passed through only so a call site can drop to the tinted `row`
 * surface if a device measurement shows the extra native effect views cost frames; nothing on
 * the dashboard uses it by default.
 */
export function DashboardCard({
  children, style, testID, variant, padding = 'regular',
}: {
  children: ReactNode; style?: ViewStyle; testID?: string; variant?: GlassVariant; padding?: 'regular' | 'compact';
}) {
  return (
    <GlassSurface
      testID={testID}
      variant={variant}
      style={[
        styles.card,
        styles.shadow,
        padding === 'compact' ? styles.compact : styles.regular,
        style,
      ]}
    >
      {children}
    </GlassSurface>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.xxl },
  // Soft separation without a hard edge. On iOS this is a box shadow, and GlassSurface builds a
  // surface that carries one as an unclipped view with the glass behind it. Until 2026-10-10 the
  // card set shadowColor/Opacity/Radius on the glass view itself, which clips to its radius and
  // so clipped the shadow away: measured on an iOS 26 screenshot, the page right under a card
  // was the bare page colour. Android casts the same box shadow: its elevation shadow showed
  // through the see-through fill as a grey rim round a white box (Pixel 10 emulator, 2026-10-16).
  shadow: { boxShadow: shadows.card },
  regular: { padding: spacing.ml },
  compact: { padding: spacing.md },
});
