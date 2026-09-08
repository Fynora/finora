import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';
import { SkeletonChart } from '../components/skeletons/Skeletons';
import { HorizontalBarList } from '../components/charts/HorizontalBarList';
import { LearningGrowthChart } from '../components/charts/LearningGrowthChart';
import { SpendTrendChart } from '../components/charts/SpendTrendChart';
import { analyticsApi, reportsApi } from '../api/endpoints';
import { fmtCurrency, monthLabel, monthLabelLong } from '../lib/format';
import { spacing, useTheme } from '../theme';

const ALL_TIME_LABEL = 'All time';

/**
 * Mobile counterpart to frontend/src/pages/AdvancedReports.tsx -- same five panels (Top Merchants,
 * Top Categories, Spend Trend, Category Confidence, Learning Growth), same ADVANCED_REPORTS gate.
 * Reached from the More menu, always visible even to a Free user -- the gate below shows the
 * upgrade prompt on open rather than hiding the entry point, same reasoning as web's Sidebar.tsx
 * ("Hiding it entirely would mean a Free user can't discover the feature exists at all").
 */
export function AdvancedReportsScreen() {
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const navigation = useNavigation();
  const chartWidth = width - spacing.md * 2 - spacing.md * 2;

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
    >
      <View style={styles.titleRow}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={10} accessibilityRole="button" accessibilityLabel="Back">
          <Ionicons name="chevron-back" size={22} color={c.ink} />
        </Pressable>
        <View style={styles.titleTextCol}>
          <View style={styles.titleWithIcon}>
            <Ionicons name="ribbon" size={18} color={c.primary} />
            <Text style={[styles.title, { color: c.ink }]}>Advanced Reports</Text>
          </View>
          <Text style={[styles.subtitle, { color: c.muted }]}>
            Deeper analysis of your spending, built from the same engine behind your Dashboard.
          </Text>
        </View>
      </View>

      <PremiumFeatureGate featureKey="ADVANCED_REPORTS" fallback={<UpgradePrompt />}>
        <AdvancedReportsContent chartWidth={chartWidth} />
      </PremiumFeatureGate>
    </ScrollView>
  );
}

/** More context than PremiumFeatureGate's own generic default, since this gates an entire screen
 *  rather than one widget -- same reasoning as web's identical UpgradePrompt. */
function UpgradePrompt() {
  const c = useTheme();
  return (
    <Card style={styles.section}>
      <EmptyState message="Advanced Reports is a Plus & Premium feature -- top merchants, spend trends, category confidence, and how the categorization engine is learning your habits, all built from your own transaction history." />
      <Text style={[styles.upgradeHint, { color: c.primary }]}>Open Settings › Subscription to view plans.</Text>
    </Card>
  );
}

function AdvancedReportsContent({ chartWidth }: { chartWidth: number }) {
  const c = useTheme();
  const [month, setMonth] = useState(''); // '' = all-time, matching web
  const [pickerOpen, setPickerOpen] = useState(false);

  const monthsQ = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });
  const topMerchantsQ = useQuery({
    queryKey: ['advanced-reports-top-merchants', month],
    queryFn: () => analyticsApi.topMerchants(month || undefined),
  });
  const topCategoriesQ = useQuery({
    queryKey: ['advanced-reports-top-categories', month],
    queryFn: () => analyticsApi.topCategories(month || undefined),
  });
  const trendQ = useQuery({ queryKey: ['advanced-reports-trend'], queryFn: () => analyticsApi.trend() });
  const confidenceQ = useQuery({ queryKey: ['advanced-reports-confidence'], queryFn: () => analyticsApi.categoryConfidence() });
  const learningQ = useQuery({ queryKey: ['advanced-reports-learning-growth'], queryFn: () => analyticsApi.learningGrowth() });

  // Newest first, same reasoning as ReportsScreen's own identical picker: the month someone opens
  // this for is nearly always a recent one.
  const monthsNewestFirst = useMemo(() => [...(monthsQ.data ?? [])].reverse(), [monthsQ.data]);
  // OptionPickerModal's options are plain display strings with no separate value -- unlike
  // ReportsScreen's own month picker (which passes raw "YYYY-MM" straight through and shows it
  // unformatted), this maps a real label back to the raw month string it stands for.
  const monthOptions = useMemo(() => [ALL_TIME_LABEL, ...monthsNewestFirst.map(monthLabelLong)], [monthsNewestFirst]);
  const labelToMonth = useMemo(() => {
    const map: Record<string, string> = { [ALL_TIME_LABEL]: '' };
    monthsNewestFirst.forEach((m) => { map[monthLabelLong(m)] = m; });
    return map;
  }, [monthsNewestFirst]);
  const selectedLabel = month === '' ? ALL_TIME_LABEL : monthLabelLong(month);

  return (
    <View>
      <Card style={styles.section}>
        <View style={styles.periodRow}>
          <View style={styles.periodTextCol}>
            <Text style={[styles.periodLabel, { color: c.muted }]}>Period</Text>
            <Pressable
              onPress={() => setPickerOpen(true)}
              accessibilityRole="button"
              accessibilityLabel={`Period: ${selectedLabel}`}
            >
              <Text style={[styles.periodValue, { color: c.ink }]}>{selectedLabel}</Text>
            </Pressable>
          </View>
          <Text style={[styles.periodHint, { color: c.muted }]}>
            Applies to Top Merchants and Top Categories. The rest always cover your full history.
          </Text>
        </View>
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Top Merchants" />
        {topMerchantsQ.isLoading ? (
          <ActivityIndicator color={c.primary} style={styles.loader} />
        ) : (
          <HorizontalBarList
            rows={(topMerchantsQ.data ?? []).map((m) => ({
              key: m.merchantId, label: m.merchantName, sub: `${m.transactionCount} txns`, value: m.totalSpend,
            }))}
            valueLabel={fmtCurrency}
            emptyMessage="Import a statement or add transactions to see your top merchants."
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Top Categories" />
        {topCategoriesQ.isLoading ? (
          <ActivityIndicator color={c.primary} style={styles.loader} />
        ) : (
          <HorizontalBarList
            rows={(topCategoriesQ.data ?? []).map((cat) => ({
              key: cat.categoryId, label: cat.categoryName, sub: `${cat.transactionCount} txns`, value: cat.totalSpend,
            }))}
            valueLabel={fmtCurrency}
            emptyMessage="Your top spending categories will appear here."
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Spend Trend" />
        <Text style={[styles.panelHint, { color: c.muted }]}>Merchant-attributed spend over your trailing 6 months.</Text>
        {trendQ.isLoading ? (
          <SkeletonChart width={chartWidth} />
        ) : (
          <SpendTrendChart
            points={(trendQ.data ?? []).map((p) => ({ label: monthLabel(p.month), value: p.totalSpend }))}
            width={chartWidth}
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Category Confidence" />
        <Text style={[styles.panelHint, { color: c.muted }]}>
          How sure the categorization engine is about each category, on average, across your merchants.
        </Text>
        {confidenceQ.isLoading ? (
          <ActivityIndicator color={c.primary} style={styles.loader} />
        ) : (
          <HorizontalBarList
            rows={(confidenceQ.data ?? []).map((p) => ({
              key: p.category, label: p.category, sub: `${p.merchantCount} merchants`, value: p.avgConfidence,
            }))}
            maxValue={100}
            valueLabel={(v) => `${Math.round(v)}%`}
            emptyMessage="Confirm a few categorizations and this fills in."
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Learning Growth" />
        <Text style={[styles.panelHint, { color: c.muted }]}>
          Categorizations the engine learned on its own vs. ones you corrected, per month.
        </Text>
        {learningQ.isLoading ? (
          <SkeletonChart width={chartWidth} />
        ) : (
          <LearningGrowthChart
            points={(learningQ.data ?? []).map((p) => ({
              label: monthLabel(p.month), learnedCount: p.learnedCount, correctedCount: p.correctedCount,
            }))}
            width={chartWidth}
          />
        )}
      </Card>

      <OptionPickerModal
        visible={pickerOpen}
        title="Period"
        options={monthOptions}
        selected={selectedLabel}
        onSelect={(label) => {
          setMonth(labelToMonth[label] ?? '');
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm, marginBottom: spacing.md },
  titleTextCol: { flex: 1 },
  titleWithIcon: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  title: { fontSize: 20, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4 },
  section: { marginBottom: spacing.md },
  upgradeHint: { fontSize: 12, fontWeight: '600', marginTop: spacing.sm, textAlign: 'center' },
  periodRow: { gap: spacing.xs },
  periodTextCol: { gap: 2 },
  periodLabel: { fontSize: 11, textTransform: 'uppercase' },
  periodValue: { fontSize: 15, fontWeight: '600' },
  periodHint: { fontSize: 11 },
  panelHint: { fontSize: 12, marginTop: -4, marginBottom: spacing.sm },
  loader: { paddingVertical: spacing.md },
});
