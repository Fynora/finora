import type { ReactNode } from 'react';
import { Platform, StyleSheet, View, type ViewStyle } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Dashboard-only surface for the passbook redesign -- a hairline border (not `Card`'s full 1px)
 * plus more internal whitespace and a soft shadow, not zero separation. Corrected from an
 * initial fully-borderless design: the app has no elevation/shadow token anywhere (see
 * docs/superpowers/specs/2026-09-10-dashboard-passbook-redesign-design.md's scope-boundary
 * section), so removing every visual separator risked cards merging together -- "quiet, not
 * flat." Deliberately NOT a change to `Card.tsx` itself. Same `{children, style, testID}` shape
 * as `Card` so a call site swaps between them with no other change.
 */
export function DashboardCard({
  children, style, testID,
}: { children: ReactNode; style?: ViewStyle; testID?: string }) {
  const c = useTheme();
  return (
    <View testID={testID} style={[styles.card, { backgroundColor: c.card, borderColor: c.border }, style]}>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: radius.lg,
    padding: spacing.lg, // more breathing room than Card's spacing.md
    // Soft, deliberately subtle -- separation without a hard edge. iOS/Android render shadow
    // props differently (Android ignores shadowColor/Offset/Opacity/Radius and uses elevation
    // instead), so both are set to the same visual intent rather than picking one platform.
    ...Platform.select({
      ios: {
        shadowColor: '#000000',
        shadowOffset: { width: 0, height: 1 },
        shadowOpacity: 0.06,
        shadowRadius: 6,
      },
      android: { elevation: 2 },
    }),
  },
});
