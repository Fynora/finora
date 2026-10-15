import type { ReactNode } from 'react';
import { StyleSheet, View, type DimensionValue, type StyleProp, type ViewStyle } from 'react-native';
import { spacing, useTheme } from '../../theme';
import {
  skeletonTokens as T, type SkeletonControlSize, type SkeletonIconSize, type SkeletonTextSize,
} from '../../theme/skeleton';
import { Card } from '../Card';
import { DashboardCard } from '../dashboard/DashboardCard';
import { Shimmer } from './Shimmer';

/**
 * First-load placeholders only. Every call site gates these behind `isLoading` (React Query v5: true
 * only when there is no cached data for that key yet), never `isFetching` -- so a background refetch
 * never swaps rendered data back out for one of these.
 *
 * Every size comes from skeletonTokens (theme/skeleton.ts); skeletonTokens.guard.test.ts fails if a
 * file in this folder writes its own. Card-shaped blocks compose the real Card so a change to Card's
 * look cannot drift them out of sync.
 */

/**
 * One loading region, read once by a screen reader as "Loading". The Shimmers inside are already
 * hidden from assistive technology; grouping them gives VoiceOver and TalkBack a single element to
 * land on instead of nothing at all. Wrap every cluster of placeholders that stands in for one
 * region of a screen in exactly one of these.
 */
export function SkeletonGroup({ children, style, testID = 'skeleton-group' }: {
  children: ReactNode;
  style?: StyleProp<ViewStyle>;
  testID?: string;
}) {
  return (
    <View testID={testID} style={style} accessible accessibilityLabel="Loading">
      {children}
    </View>
  );
}

/**
 * A line of text. It takes the room a real line of that font size takes (skeletonTokens.lineHeight,
 * measured), so nothing below it moves when the text lands; the bar drawn inside is the font size
 * tall, or `bar` times it inside a paragraph. Text that sets its own lineHeight passes it as `line`.
 */
export function SkeletonText({ size, width = '100%', style, bar = 1, line }: {
  size: SkeletonTextSize;
  width?: DimensionValue;
  style?: ViewStyle;
  bar?: number;
  line?: number;
}) {
  const fontSize = T.text[size];
  return (
    <View testID="skeleton-line" style={[styles.lineBox, { width, height: line ?? Math.round(fontSize * T.lineHeight) }, style]}>
      <Shimmer width="100%" height={Math.round(fontSize * bar)} />
    </View>
  );
}

/** A fixed-height control: a field, a button, a chip, a progress bar. */
export function SkeletonBox({ size, width = '100%', round = false, style }: {
  size: SkeletonControlSize;
  width?: DimensionValue;
  round?: boolean;
  style?: ViewStyle;
}) {
  const height = T.control[size];
  return <Shimmer width={width} height={height} borderRadius={round ? height / 2 : T.radius.block} style={style} />;
}

/** An icon, badge or avatar: square with the block radius, or round. */
export function SkeletonIcon({ size, round = false, style }: {
  size: SkeletonIconSize;
  round?: boolean;
  style?: ViewStyle;
}) {
  const side = T.icon[size];
  return <Shimmer width={side} height={side} borderRadius={round ? side / 2 : T.radius.block} style={style} />;
}

/** A few lines of text, the last one shorter, the way a paragraph ends. */
export function SkeletonLines({ count = 3, size = 'meta', lastWidth = '60%', line }: {
  count?: number;
  size?: SkeletonTextSize;
  lastWidth?: DimensionValue;
  line?: number;
}) {
  return (
    <View testID="skeleton-lines">
      {Array.from({ length: count }).map((_, i) => (
        <SkeletonText key={i} size={size} width={i === count - 1 ? lastWidth : '100%'} bar={T.paragraphBar} line={line} />
      ))}
    </View>
  );
}

/** An optional icon, a title line over a caption line, an optional amount on the right. For
 *  accounts, holdings, recurring payments, tickets, statements and candidates. */
export function SkeletonListRow({ icon = null, roundIcon = false, amount = true, divider = true }: {
  icon?: SkeletonIconSize | null;
  roundIcon?: boolean;
  amount?: boolean;
  divider?: boolean;
}) {
  const c = useTheme();
  return (
    <View style={[styles.listRow, divider && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
      {icon ? <SkeletonIcon size={icon} round={roundIcon} /> : null}
      <View style={styles.listRowMain}>
        <SkeletonText size="heading" width="65%" />
        <SkeletonText size="caption" width="40%" />
      </View>
      {amount ? <Shimmer width={T.fixedWidth.amount} height={T.text.body} /> : null}
    </View>
  );
}

/** TextField's shape: a label line over the 48pt input row, and the slot it keeps under the input
 *  for an error message, so the fields below do not move when the real form lands. */
export function SkeletonFormField() {
  return (
    <View style={styles.formField}>
      <SkeletonText size="meta" width="30%" style={styles.fieldLabel} />
      <SkeletonBox size="field" />
      <View testID="skeleton-field-message" style={[styles.fieldMessage, { height: T.control.fieldMessage }]} />
    </View>
  );
}

/** Profile's identity card: the avatar circle, then name, email and a meta line. */
export function SkeletonProfileHeader() {
  return (
    <Card style={styles.profileHeader}>
      <SkeletonIcon size="avatar" round />
      <View style={styles.profileHeaderText}>
        <SkeletonText size="subject" width="55%" />
        <SkeletonText size="label" width="70%" />
        <SkeletonText size="caption" width="45%" />
      </View>
    </Card>
  );
}

/** A Settings row: title and meta lines, and the control at its end. */
export function SkeletonSettingsRow({ trailing = 'none', divider = true, metaLines = 1 }: {
  trailing?: 'switch' | 'chevron' | 'badge' | 'none';
  divider?: boolean;
  /** How many lines the real description wraps to, where the copy is fixed. */
  metaLines?: number;
}) {
  const c = useTheme();
  return (
    <View style={[styles.settingsRow, divider && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
      <View testID="skeleton-settings-row-main" style={styles.settingsRowMain}>
        <SkeletonText size="body" width="45%" />
        {metaLines > 1
          ? <SkeletonLines count={metaLines} size="meta" />
          : <SkeletonText size="meta" width="70%" />}
      </View>
      {trailing === 'switch' ? (
        <Shimmer width={T.fixedWidth.switch} height={T.control.switchHeight} borderRadius={T.control.switchHeight / 2} />
      ) : trailing === 'chevron' ? (
        <SkeletonText size="value" width="5%" />
      ) : trailing === 'badge' ? (
        <SkeletonBox size="badge" width="20%" round />
      ) : null}
    </View>
  );
}

/** HorizontalBarList's row: label and value on one line, the bar track under them. */
export function SkeletonBarRow() {
  return (
    <View style={styles.barRow}>
      <View style={styles.barRowTop}>
        <SkeletonText size="body" width="50%" />
        <SkeletonText size="meta" width="15%" />
      </View>
      <SkeletonBox size="barTrack" round />
    </View>
  );
}

/** A full-width illustration with a known aspect ratio (Refer & Earn's hero). */
export function SkeletonHero({ aspectRatio }: { aspectRatio: number }) {
  return (
    <View style={[styles.hero, { aspectRatio }]}>
      <Shimmer width="100%" height="100%" borderRadius={T.radius.card} />
    </View>
  );
}

export function SkeletonCard({ lines = 3, style }: { lines?: number; style?: ViewStyle }) {
  return (
    <Card style={style}>
      {/* heading: SectionHeading's real title is 15 (Card.tsx), the size it stands in for. */}
      <SkeletonText size="heading" width="40%" style={styles.heading} />
      {Array.from({ length: lines }).map((_, i) => (
        <SkeletonText key={i} size="meta" width={i === lines - 1 ? '60%' : '100%'} style={styles.line} />
      ))}
    </Card>
  );
}

/** Mirrors DashboardScreen's and LedgerScreen's own txnRow layout: description + meta on the
 *  left, an amount on the right. */
export function SkeletonTransactionRow() {
  const c = useTheme();
  return (
    <View style={[styles.txnRow, { borderBottomColor: c.border }]} testID="skeleton-transaction-row">
      <View style={styles.txnMain}>
        <SkeletonText size="body" width="70%" style={styles.line} />
        <SkeletonText size="caption" width="40%" />
      </View>
      <Shimmer width={T.fixedWidth.amount} height={T.text.body} />
    </View>
  );
}

/** Stands in for LedgerSnapshotCard's four tiles (same two-up basis, same compact glass tile), so
 *  the grid does not jump when the figures land: label, number and chip placeholders per tile. */
export function SkeletonKpiGrid() {
  return (
    <View style={styles.kpiGrid} testID="skeleton-kpi-grid">
      {Array.from({ length: 4 }).map((_, i) => (
        <DashboardCard key={i} padding="compact" style={styles.kpiTile}>
          <SkeletonText size="caption" width="50%" style={styles.line} />
          <SkeletonText size="title" width="80%" style={styles.line} />
          <SkeletonBox size="chip" width="40%" round />
        </DashboardCard>
      ))}
    </View>
  );
}

/** Mirrors BudgetsScreen's budget card: a header placeholder standing in for category name +
 *  amounts, a progress-bar-shaped placeholder, and a one-line footer. */
export function SkeletonBudgetCard() {
  return (
    <Card testID="skeleton-budget-card">
      <SkeletonText size="body" width="60%" style={styles.heading} />
      <SkeletonBox size="bar" round style={styles.line} />
      <SkeletonText size="caption" width="35%" style={styles.line} />
    </Card>
  );
}

/** Matches CashFlowChart's fixed height and DonutChart's fixed diameter -- lib/chartGeometry.ts,
 *  through skeletonTokens.chart -- so the skeleton never drifts from the chart it stands in for. */
export function SkeletonChart({ variant = 'bar', width = '100%' }: { variant?: 'bar' | 'donut'; width?: DimensionValue }) {
  if (variant === 'donut') {
    return (
      <View style={styles.donutWrap} testID="skeleton-chart-donut">
        <Shimmer width={T.chart.donut} height={T.chart.donut} borderRadius={T.chart.donut / 2} />
      </View>
    );
  }
  return <Shimmer testID="skeleton-chart-bar" width={width} height={T.chart.cashflow} borderRadius={T.radius.block} />;
}

const styles = StyleSheet.create({
  heading: { marginBottom: spacing.sm },
  line: { marginBottom: spacing.sm },
  lineBox: { justifyContent: 'center' },
  listRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: 10 },
  listRowMain: { flex: 1, gap: spacing.xs },
  // TextField's own spacing: label 6 above the input, error slot 2 below it, 4 under the field.
  formField: { marginBottom: spacing.xs },
  fieldLabel: { marginBottom: 6 },
  fieldMessage: { marginTop: 2 },
  profileHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginBottom: spacing.md },
  profileHeaderText: { flex: 1, gap: spacing.xs },
  settingsRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 10 },
  // Every real settings row (Security, App Lock, notifications, Profile) puts its meta 2 under the title.
  settingsRowMain: { flex: 1, marginRight: spacing.sm, gap: 2 },
  barRow: { gap: spacing.xs },
  barRowTop: { flexDirection: 'row', justifyContent: 'space-between' },
  hero: { width: '100%' },
  txnRow: {
    flexDirection: 'row', alignItems: 'center', paddingVertical: 10, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  txnMain: { flex: 1, marginRight: spacing.sm, gap: 6 },
  donutWrap: { alignItems: 'center', justifyContent: 'center', paddingVertical: spacing.sm },
  kpiGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.ms },
  kpiTile: { flexBasis: '47%', flexGrow: 1 },
});
