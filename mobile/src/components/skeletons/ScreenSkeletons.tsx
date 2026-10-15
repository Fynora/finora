import { StyleSheet, View } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { Card } from '../Card';
import {
  SkeletonBox, SkeletonChart, SkeletonFormField, SkeletonGroup, SkeletonHero, SkeletonIcon, SkeletonLines,
  SkeletonListRow, SkeletonProfileHeader, SkeletonSettingsRow, SkeletonText,
} from './Skeletons';

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

export function FinancialMemorySkeleton() {
  return (
    <SkeletonGroup testID="financial-memory-skeleton" style={styles.columnMd}>
      <SkeletonLines count={2} size="label" lastWidth="70%" />
      <View style={styles.twoUpGrid}>
        {Array.from({ length: 7 }).map((_, i) => (
          <Card key={i} style={styles.twoUpTile}>
            <SkeletonText size="value" width="50%" />
            <SkeletonText size="meta" width="75%" />
          </Card>
        ))}
      </View>
      <Card>
        <SkeletonText size="heading" width="60%" style={styles.stackSm} />
        <SkeletonListRow />
        <SkeletonListRow />
        <SkeletonListRow divider={false} />
      </Card>
    </SkeletonGroup>
  );
}

export function InvestmentsSkeleton() {
  return (
    <SkeletonGroup testID="investments-skeleton">
      <View style={styles.headerEnd}>
        <SkeletonText size="body" width="15%" />
      </View>
      <View style={styles.threeUp}>
        {[0, 1, 2].map((i) => (
          <Card key={i} style={styles.threeUpTile}>
            <SkeletonText size="tiny" width="70%" />
            <SkeletonText size="heading" width="85%" />
          </Card>
        ))}
      </View>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="35%" style={styles.stackSm} />
        <SkeletonChart variant="donut" />
      </Card>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="45%" style={styles.stackSm} />
        <SkeletonChart />
      </Card>
      <Card style={styles.gapMd}>
        <SkeletonText size="heading" width="30%" style={styles.stackSm} />
        <SkeletonListRow />
        <SkeletonListRow divider={false} />
      </Card>
    </SkeletonGroup>
  );
}

export function GoalsSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="goals-skeleton">
      <View style={styles.headerRow}>
        <SkeletonText size="title" width="25%" />
        <SkeletonText size="body" width="25%" />
      </View>
      <View style={styles.columnSm}>
        {[0, 1].map((i) => (
          <Card key={i}>
            <View style={[styles.headerRow, styles.stackSm]}>
              <SkeletonText size="heading" width="45%" />
              <SkeletonText size="meta" width="30%" />
            </View>
            <SkeletonBox size="bar" round />
            <SkeletonText size="caption" width="35%" style={styles.gapSm} />
            <View style={[styles.dividedRow, { borderTopColor: c.border }, styles.columnSmRow]}>
              <SkeletonBox size="touch" width="48%" />
              <SkeletonBox size="touch" width="48%" />
            </View>
          </Card>
        ))}
      </View>
    </SkeletonGroup>
  );
}

export function JourneySkeleton() {
  return (
    <SkeletonGroup testID="journey-skeleton" style={styles.columnMd}>
      {[0, 1].map((i) => (
        <Card key={i}>
          <SkeletonText size="subheading" width="20%" style={styles.stackSm} />
          {[0, 1, 2].map((j) => (
            <View key={j} style={styles.stackSm}>
              <SkeletonText size="body" width="70%" />
              <SkeletonText size="meta" width="45%" style={styles.tight} />
            </View>
          ))}
        </Card>
      ))}
      <SkeletonText size="label" width="45%" />
    </SkeletonGroup>
  );
}

export function WrappedSkeleton() {
  return (
    <SkeletonGroup testID="wrapped-skeleton">
      <Card style={styles.wrappedCard}>
        <SkeletonText size="label" width="45%" />
        <SkeletonText size="display" width="30%" style={styles.gapSm} />
        <SkeletonText size="amount" width="12%" style={styles.gapMd} />
        <SkeletonText size="label" width="50%" style={styles.tight} />
        <SkeletonText size="body" width="70%" style={styles.gapMd} />
        <SkeletonText size="body" width="55%" style={styles.gapSm} />
      </Card>
    </SkeletonGroup>
  );
}

export function ProfileSkeleton() {
  return (
    <SkeletonGroup testID="profile-skeleton">
      <SkeletonProfileHeader />
      <Card style={styles.stackMd}>
        <SkeletonText size="subheading" width="50%" />
        <SkeletonText size="meta" width="65%" style={styles.tight} />
        <View style={styles.gapMd}>
          <SkeletonFormField />
          {[0, 1, 2].map((i) => (
            <View key={i} style={styles.stackSm}>
              <SkeletonText size="meta" width="25%" />
              <SkeletonText size="body" width="55%" style={styles.tight} />
            </View>
          ))}
          <SkeletonBox size="button" style={styles.gapSm} />
        </View>
      </Card>
      <Card style={styles.stackMd}>
        <SkeletonText size="subheading" width="45%" />
        <SkeletonText size="meta" width="70%" style={styles.tight} />
        <View style={styles.gapMd}>
          <SkeletonSettingsRow />
          <SkeletonSettingsRow divider={false} trailing="badge" />
          <SkeletonBox size="touch" width="40%" style={styles.gapSm} />
        </View>
      </Card>
    </SkeletonGroup>
  );
}

export function ReferralsSkeleton({ heroAspectRatio }: { heroAspectRatio: number }) {
  return (
    <SkeletonGroup testID="referrals-skeleton" style={styles.columnMd}>
      <View>
        <SkeletonText size="hero" width="50%" />
        <SkeletonLines count={2} size="body" lastWidth="75%" />
      </View>
      <SkeletonHero aspectRatio={heroAspectRatio} />
      <Card>
        <View style={styles.threeUp}>
          {[0, 1, 2].map((i) => (
            <View key={i} style={styles.centredColumn}>
              <SkeletonIcon size="sm" round />
              <SkeletonText size="caption" width="70%" />
            </View>
          ))}
        </View>
      </Card>
      <Card style={styles.columnSm}>
        <SkeletonText size="label" width="40%" />
        <SkeletonBox size="codeRow" />
        <SkeletonBox size="button" />
        <View style={styles.spacedRow}>
          {[0, 1, 2, 3, 4].map((i) => <SkeletonIcon key={i} size="lg" round />)}
        </View>
      </Card>
      <View style={styles.threeUp}>
        {[0, 1, 2].map((i) => (
          <Card key={i} style={styles.threeUpTile}>
            <SkeletonText size="caption" width="70%" />
            <SkeletonText size="value" width="50%" />
          </Card>
        ))}
      </View>
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
  columnSmRow: { gap: spacing.sm },
  centredColumn: { flex: 1, alignItems: 'center', gap: spacing.xs },
  spacedRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs },
  wrappedCard: { borderRadius: radius.xl, padding: spacing.lg },
});
