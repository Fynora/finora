import { StyleSheet, View } from 'react-native';
import { spacing, useTheme } from '../../theme';
import { Card } from '../Card';
import { SkeletonGroup, SkeletonIcon, SkeletonText } from './Skeletons';

/**
 * One skeleton per whole screen whose first load used to be a centred spinner. Each is built from
 * that screen's real layout (read 2026-10-15): the same wrappers, the same number of blocks where
 * the count is fixed, sizes from skeletonTokens. The screen keeps its own GlassScreen and
 * ScrollView around this, with the same contentContainerStyle, so padding, safe-area insets and the
 * tab-bar space are identical when the content lands. Dynamic counts (how many accounts) are not
 * copied; two stand in for "some".
 */

export function AccountsSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="accounts-skeleton">
      <View style={styles.headerRow}>
        <SkeletonText size="title" width="35%" />
        <SkeletonText size="body" width="25%" />
      </View>
      {[0, 1].map((i) => (
        <Card key={i} style={styles.stackSm}>
          <View style={styles.rowCenter}>
            <SkeletonIcon size="md" />
            <View style={styles.fill}>
              <SkeletonText size="heading" width="55%" />
              <SkeletonText size="caption" width="35%" style={styles.tight} />
            </View>
          </View>
          <SkeletonText size="title" width="45%" style={styles.gapSm} />
          <SkeletonText size="meta" width="30%" style={styles.tight} />
          <View style={[styles.dividedRow, { borderTopColor: c.border }]}>
            <SkeletonText size="caption" width="30%" />
            <SkeletonText size="caption" width="30%" />
          </View>
        </Card>
      ))}
    </SkeletonGroup>
  );
}

const styles = StyleSheet.create({
  headerRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: spacing.sm },
  headerEnd: { alignItems: 'flex-end', marginBottom: spacing.sm },
  rowCenter: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  rowStart: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', gap: spacing.sm },
  fill: { flex: 1 },
  tight: { marginTop: spacing.xs },
  gapSm: { marginTop: spacing.sm },
  gapMd: { marginTop: spacing.md },
  stackSm: { marginBottom: spacing.sm },
  stackMd: { marginBottom: spacing.md },
  columnSm: { gap: spacing.sm },
  columnMd: { gap: spacing.md },
  dividedRow: {
    flexDirection: 'row', justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.sm, paddingTop: spacing.sm,
  },
  twoUpGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  twoUpTile: { flexBasis: '47%', flexGrow: 1, padding: spacing.sm, gap: spacing.xs },
  threeUp: { flexDirection: 'row', gap: spacing.sm },
  threeUpTile: { flex: 1, paddingHorizontal: spacing.sm, gap: spacing.xs },
});
