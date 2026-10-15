import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { analyticsApi } from '../../api/endpoints';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, typography, useTheme } from '../../theme';
import { DashboardCard } from '../dashboard/DashboardCard';
import { SectionHeading } from '../Card';
import { PremiumFeatureGate } from '../PremiumFeatureGate';

const TOP = 5;

/** Real data, for a user who has Advanced Reports. Its own component so that the paid endpoint is
 *  only ever called once the entitlement is confirmed; a free user never reaches this. The query key
 *  is the one Advanced Reports uses, so a month either screen has loaded opens instantly on the other. */
function MerchantsList({ month, onOpenAdvanced }: { month: string; onOpenAdvanced: () => void }) {
  const c = useTheme();
  const { data, isLoading } = useQuery({
    queryKey: ['advanced-reports-top-merchants', month],
    queryFn: () => analyticsApi.topMerchants(month),
    retry: false,
  });
  // A failure or an empty month leaves the card out: this is an extra, not the report.
  if (isLoading || !data || data.length === 0) return null;
  const rows = data.slice(0, TOP);
  const max = Math.max(...rows.map((m) => m.totalSpend), 1);
  return (
    <DashboardCard testID="top-merchants">
      <SectionHeading title="Top merchants" />
      {rows.map((m) => (
        <View key={m.merchantId} style={styles.merchant}>
          <View style={styles.line}>
            <Text style={[typography.bodyM, styles.name, { color: c.ink }]} numberOfLines={1}>{m.merchantName}</Text>
            <Text style={[typography.bodyM, { color: c.ink }]}>{fmtCurrency(m.totalSpend)}</Text>
          </View>
          <View style={[styles.track, { backgroundColor: c.border }]}>
            <View style={{ width: `${(m.totalSpend / max) * 100}%`, height: '100%', borderRadius: 3, backgroundColor: c.primary }} />
          </View>
          <Text style={[typography.caption, { color: c.mutedInk }]}>
            {m.transactionCount} {m.transactionCount === 1 ? 'transaction' : 'transactions'}
          </Text>
        </View>
      ))}
      <Text style={[typography.caption, { color: c.mutedInk }]}>Counted by merchant, so it can differ from the categories.</Text>
      <Pressable onPress={onOpenAdvanced} accessibilityRole="button" style={styles.link} hitSlop={6}>
        <Text style={[typography.labelM, { color: c.brassInk }]}>See more in Advanced Reports</Text>
      </Pressable>
    </DashboardCard>
  );
}

/** What a user without Advanced Reports sees: the shape of the card with stand-in bars, never any of
 *  their data (the stand-ins are identical for everyone), and one way to see the plans. */
function LockedMerchants({ onViewPlans }: { onViewPlans: () => void }) {
  const c = useTheme();
  return (
    <DashboardCard testID="locked-merchants">
      <View style={styles.lockedHead}>
        <SectionHeading title="Top merchants" />
        <View style={[styles.badge, { borderColor: c.border }]}>
          <Ionicons name="lock-closed-outline" size={11} color={c.mutedInk} />
          <Text style={[typography.caption, { color: c.mutedInk }]}>Plus &amp; Premium</Text>
        </View>
      </View>
      <View style={styles.bars} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" testID="locked-merchants-bars">
        {[92, 61, 40].map((w) => (
          <View key={w} style={[styles.track, { backgroundColor: c.border }]}>
            <View style={{ width: `${w}%`, height: '100%', borderRadius: 3, backgroundColor: c.muted }} />
          </View>
        ))}
      </View>
      <Text style={[typography.bodyM, { color: c.ink }]}>See which merchants took the most of your money.</Text>
      <Pressable
        onPress={onViewPlans} accessibilityRole="button" accessibilityLabel="View plans"
        style={[styles.cta, { backgroundColor: c.primary }]}
      >
        <Text style={[typography.labelM, { color: c.onPrimary }]}>View plans</Text>
      </Pressable>
    </DashboardCard>
  );
}

/** Top merchants for the month: real data with Advanced Reports, a locked preview without it. */
export function MerchantsCard({ month, onOpenAdvanced, onViewPlans }: {
  month: string; onOpenAdvanced: () => void; onViewPlans: () => void;
}) {
  return (
    <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<LockedMerchants onViewPlans={onViewPlans} />}>
      <MerchantsList month={month} onOpenAdvanced={onOpenAdvanced} />
    </PremiumFeatureGate>
  );
}

const styles = StyleSheet.create({
  merchant: { gap: 3, marginBottom: spacing.sm },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: spacing.sm },
  name: { flex: 1 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden' },
  link: { alignSelf: 'flex-start', minHeight: 44, justifyContent: 'center' },
  lockedHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: spacing.sm },
  badge: { flexDirection: 'row', alignItems: 'center', gap: 4, borderWidth: 1, borderRadius: 6, paddingHorizontal: 6, paddingVertical: 2 },
  bars: { gap: spacing.sm, opacity: 0.5, marginBottom: spacing.sm },
  cta: { alignSelf: 'flex-start', borderRadius: radius.md, minHeight: 44, paddingHorizontal: spacing.md, justifyContent: 'center', marginTop: spacing.sm },
});
