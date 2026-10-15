import { StyleSheet, View } from 'react-native';
import { radius, spacing, useTheme } from '../../theme';
import { skeletonTokens } from '../../theme/skeleton';
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

/** FinancialMemoryScreen's seven tiles, in order, and which of them carry a caption line under the
 *  label (History, Completeness, Merchants identified, Manual corrections). Without the captions the
 *  loaded screen came out about 43 pt taller than its skeleton on the simulator (2026-10-16). */
const MEMORY_TILE_HAS_CAPTION = [true, true, false, false, true, false, true];

export function FinancialMemorySkeleton() {
  return (
    <SkeletonGroup testID="financial-memory-skeleton" style={styles.columnMd}>
      <SkeletonLines count={2} size="label" lastWidth="70%" />
      <View style={styles.twoUpGrid}>
        {MEMORY_TILE_HAS_CAPTION.map((hasCaption, i) => (
          <Card key={i} style={styles.memoryTile}>
            <SkeletonText size="value" width="50%" />
            <SkeletonText size="meta" width="75%" />
            {hasCaption ? <SkeletonText size="caption" width="85%" /> : null}
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
          {/* ProfileScreen's save-status row: empty until the name is edited, but it holds its room. */}
          <View
            testID="skeleton-save-status"
            style={[styles.stackSm, { height: skeletonTokens.control.saveStatus }]}
          />
          <SkeletonBox size="button" />
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
      {/* The real title and subtitle are separate children of the content's gap column, with the
          subtitle pulled up 4 -- so the subtitle starts one gap minus 4 below the title. */}
      <View>
        <SkeletonText size="hero" width="50%" />
        <View style={styles.referralSubtitle}>
          <SkeletonLines count={2} size="body" lastWidth="75%" />
        </View>
      </View>
      <SkeletonHero aspectRatio={heroAspectRatio} />
      <Card>
        <View style={styles.threeUp}>
          {[0, 1, 2].map((i) => (
            <View key={i} style={styles.centredColumn}>
              <SkeletonIcon size="sm" round />
              <SkeletonText size="caption" width="70%" />
              <SkeletonText size="tiny" width="80%" />
            </View>
          ))}
        </View>
      </Card>
      <Card style={styles.columnSm}>
        <SkeletonText size="label" width="40%" />
        <SkeletonBox size="codeRow" />
        <SkeletonBox size="button" />
        <View style={styles.spacedRow}>
          {[0, 1, 2, 3, 4].map((i) => (
            <View key={i} style={styles.channel}>
              <SkeletonIcon size="lg" round />
              <SkeletonText size="tiny" width="80%" />
            </View>
          ))}
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

export function TicketDetailSkeleton() {
  return (
    <SkeletonGroup testID="ticket-detail-skeleton">
      <Card>
        <View style={styles.rowStart}>
          <View style={styles.fill}>
            <SkeletonText size="caption" width="45%" />
            <SkeletonText size="subject" width="75%" style={styles.tight} />
            <SkeletonText size="caption" width="30%" style={styles.tight} />
          </View>
          <SkeletonBox size="badge" width="22%" />
        </View>
        <View style={styles.gapMd}>
          <SkeletonLines count={3} size="body" />
        </View>
      </Card>
    </SkeletonGroup>
  );
}

// Description and hint line counts are the fixed copy as it wraps on a 402pt-wide phone.
export function SettingsGeneralSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="settings-general-skeleton">
      <SkeletonFormField />
      {/* Timezone is a plain label over the picker -- not a TextField, so no error slot under it. */}
      <SkeletonText size="meta" width="20%" style={styles.fieldLabel} />
      <SkeletonBox size="field" />
      <SkeletonText size="meta" width="15%" style={styles.themeLabel} />
      <SkeletonBox size="segments" />
      <View style={styles.gapSm}>
        <SkeletonLines count={2} size="caption" lastWidth="20%" line={skeletonTokens.textLine.hint} />
      </View>
      <View
        testID="skeleton-save-status"
        style={[styles.saveStatusRow, { height: skeletonTokens.control.saveStatus }]}
      />
      <SkeletonBox size="button" />
      {/* NotificationPreferencesSection */}
      <View style={[styles.notificationSection, { borderTopColor: c.border }]}>
        <SkeletonText size="body" width="30%" style={styles.notificationHeading} />
        <NotificationPreferencesRowsSkeleton />
      </View>
      <View style={[styles.retakeTourRow, { borderTopColor: c.border }]}>
        <View style={styles.flexFill}>
          <SkeletonText size="meta" width="45%" style={styles.fieldLabel} />
          <SkeletonText size="caption" width="80%" style={styles.gapSm} line={skeletonTokens.textLine.hint} />
        </View>
        <SkeletonText size="label" width="25%" />
      </View>
    </SkeletonGroup>
  );
}

/** NotificationPreferencesSection's rows under its heading: email, push, and the footnote. Used by
 *  the section's own loading state and inside SettingsGeneralSkeleton, so the two cannot drift. */
export function NotificationPreferencesRowsSkeleton() {
  return (
    <>
      <SkeletonSettingsRow trailing="switch" metaLines={3} />
      <SkeletonSettingsRow trailing="switch" metaLines={2} />
      <View style={styles.gapSm}>
        <SkeletonLines count={2} size="meta" lastWidth="45%" />
      </View>
    </>
  );
}

/** StatementHistoryScreen's account cards: the account name over its bank and statement count,
 *  straight inside the card's padding. Two stand in for "some". */
export function StatementGroupCardsSkeleton() {
  return (
    <>
      {[0, 1].map((i) => (
        <Card key={i} style={styles.statementCard}>
          <SkeletonText size="heading" width="45%" />
          <SkeletonText size="label" width="55%" line={skeletonTokens.textLine.statementBody} />
        </Card>
      ))}
    </>
  );
}

export function SettingsSecuritySkeleton() {
  return (
    <SkeletonGroup testID="settings-security-skeleton">
      <SkeletonSettingsRow />
      <SkeletonBox size="button" style={styles.gapSmStackMd} />
      <SkeletonSettingsRow />
      <SkeletonBox size="button" style={styles.gapSmStackMd} />
      <SkeletonSettingsRow trailing="badge" />
      {/* AppLockSection: its description wraps to two lines beside the switch. */}
      <SkeletonSettingsRow trailing="switch" metaLines={2} />
      {/* DeviceSessionsSection: title, a two-line blurb, the session cards. */}
      <View style={styles.gapMd}>
        <SkeletonText size="body" width="40%" />
        <View style={styles.sessionsBlurb}>
          <SkeletonLines count={2} size="caption" lastWidth="65%" line={skeletonTokens.textLine.hint} />
        </View>
        <DeviceSessionCardsSkeleton />
      </View>
    </SkeletonGroup>
  );
}

/** DeviceSessionsSection's session cards: a device line over two detail lines, in a bordered card.
 *  Used by that section's own loading state and inside SettingsSecuritySkeleton. */
export function DeviceSessionCardsSkeleton() {
  const c = useTheme();
  return (
    <View>
      {[0, 1].map((i) => (
        <View key={i} testID="skeleton-session-card" style={[styles.sessionCard, { borderColor: c.border }]}>
          <SkeletonText size="label" width="45%" />
          <SkeletonText size="caption" width="70%" style={styles.sessionDetail} />
          <SkeletonText size="caption" width="50%" style={styles.sessionDetail} />
        </View>
      ))}
    </View>
  );
}

export function SettingsCategorizationSkeleton() {
  return (
    <SkeletonGroup testID="settings-categorization-skeleton">
      <View style={styles.rowCenterMd}>
        <SkeletonBox size="field" width="15%" />
        <SkeletonText size="value" width="20%" />
        <SkeletonBox size="field" width="15%" />
      </View>
      <View style={styles.gapSm}>
        <SkeletonLines count={2} size="caption" line={skeletonTokens.textLine.hint} />
      </View>
      <SkeletonText size="label" width="70%" style={styles.gapSm} />
      <View
        testID="skeleton-save-status"
        style={[styles.saveStatusRow, { height: skeletonTokens.control.saveStatus }]}
      />
      <SkeletonBox size="button" />
      {/* InflowKindsSettings: its two cards, each with two rows standing in for "some". */}
      <Card style={styles.inflowCard}>
        <SkeletonText size="heading" width="40%" style={styles.inflowHeading} />
        <SkeletonLines count={2} size="meta" lastWidth="55%" />
        <InflowKindRowsSkeleton />
      </Card>
      <Card style={styles.inflowCard}>
        <SkeletonText size="heading" width="55%" style={styles.inflowHeading} />
        <SenderRuleRowsSkeleton />
      </Card>
    </SkeletonGroup>
  );
}

/** InflowKindsSettings' money-kind rows: a name, and the income label or switch on the right. Used
 *  by that card's own loading state and inside SettingsCategorizationSkeleton. */
export function InflowKindRowsSkeleton() {
  const c = useTheme();
  return (
    <View style={styles.inflowRows}>
      {[0, 1].map((i) => (
        <View key={i} style={[styles.inflowRow, { borderBottomColor: c.border }]}>
          <SkeletonText size="body" width="40%" />
          <SkeletonText size="meta" width="30%" />
        </View>
      ))}
    </View>
  );
}

/** InflowKindsSettings' remembered-sender rows: a name over its kind and payment count, Forget on
 *  the right. Used by that card's own loading state and inside SettingsCategorizationSkeleton. */
export function SenderRuleRowsSkeleton() {
  const c = useTheme();
  return (
    <View style={styles.inflowRows}>
      {[0, 1].map((i) => (
        <View key={i} style={[styles.inflowRow, { borderBottomColor: c.border }]}>
          <View style={styles.flexFill}>
            <SkeletonText size="body" width="60%" />
            <SkeletonText size="meta" width="45%" />
          </View>
          <SkeletonText size="meta" width="12%" />
        </View>
      ))}
    </View>
  );
}

export function SettingsAccountSkeleton() {
  const c = useTheme();
  return (
    <SkeletonGroup testID="settings-account-skeleton">
      {/* Deactivate's hint wraps to three lines, Delete's to two; each ends in a 13pt link. */}
      {[3, 2].map((hintLines, i) => (
        <View key={i} style={[styles.dangerRow, i === 0 && { borderBottomColor: c.border, borderBottomWidth: StyleSheet.hairlineWidth }]}>
          <SkeletonText size="meta" width="40%" style={styles.fieldLabel} />
          <View style={styles.gapSm}>
            <SkeletonLines count={hintLines} size="caption" line={skeletonTokens.textLine.hint} />
          </View>
          <SkeletonText size="label" width="35%" />
        </View>
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
  // FinancialMemoryScreen's tile: label and caption each sit 2 below the line above.
  memoryTile: { flexBasis: '47%', flexGrow: 1, padding: spacing.sm, gap: 2 },
  threeUp: { flexDirection: 'row', gap: spacing.sm },
  threeUpTile: { flex: 1, paddingHorizontal: spacing.sm, gap: spacing.xs },
  columnSmRow: { gap: spacing.sm },
  rowCenterMd: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  dangerRow: { paddingVertical: spacing.md },
  gapSmStackMd: { marginTop: spacing.sm, marginBottom: spacing.md },
  // SettingsGeneralScreen's own spacing: field labels 6 above their control, the theme label 16
  // below the picker.
  fieldLabel: { marginBottom: 6 },
  themeLabel: { marginTop: spacing.md, marginBottom: 6 },
  // Settings General's and Categorization's save-status row: 8 above and below.
  saveStatusRow: { marginVertical: spacing.sm },
  notificationSection: { borderTopWidth: StyleSheet.hairlineWidth, marginTop: spacing.lg, paddingTop: spacing.md },
  notificationHeading: { marginBottom: spacing.xs },
  retakeTourRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    marginTop: spacing.md, paddingTop: spacing.md, borderTopWidth: StyleSheet.hairlineWidth,
  },
  flexFill: { flex: 1, marginRight: spacing.sm },
  // InflowKindsSettings' card: its children 8 apart, 16 below what is above; SectionHeading's 8
  // under the title; rows padded 8 with a hairline under each.
  inflowCard: { gap: spacing.sm, marginTop: spacing.md },
  statementCard: { marginBottom: spacing.md },
  // DeviceSessionsSection's own spacing: the blurb 2 under the title and 8 above the cards; each card
  // padded 10 by 12 inside a 1pt border, 4 apart, its detail lines 2 apart.
  sessionsBlurb: { marginTop: 2, marginBottom: spacing.sm },
  sessionCard: {
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, marginBottom: spacing.xs,
  },
  sessionDetail: { marginTop: 2 },
  inflowHeading: { marginBottom: spacing.sm },
  inflowRows: { gap: spacing.sm },
  inflowRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm,
    paddingVertical: spacing.sm, borderBottomWidth: StyleSheet.hairlineWidth,
  },
  centredColumn: { flex: 1, alignItems: 'center', gap: spacing.xs },
  spacedRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs },
  referralSubtitle: { marginTop: spacing.md - 4 },
  // ReferralsScreen's share channel: the icon, then its label 6 below.
  channel: { alignItems: 'center', gap: 6, width: '18%' },
  wrappedCard: { borderRadius: radius.xl, padding: spacing.lg },
});
