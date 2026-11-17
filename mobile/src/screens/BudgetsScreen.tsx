import { useState } from 'react';
import {
  Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation, type NavigationProp, type ParamListBase } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { Button } from '../components/Button';
import { Card, EmptyState } from '../components/Card';
import { CategoryPickerModal } from '../components/CategoryPickerModal';
import { SkeletonBudgetCard, SkeletonGroup } from '../components/skeletons/Skeletons';
import { TextField } from '../components/TextField';
import { BudgetCard } from '../components/budgets/BudgetCard';
import { BudgetHeroCard } from '../components/budgets/BudgetHeroCard';
import { groupInk } from '../components/budgets/PaceBar';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import { AppAlert } from '../lib/appAlert';
import type { Budget } from '../types';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { currentYearMonth, fmtCurrency, monthDateRange, monthLabel } from '../lib/format';
import { hapticError, hapticSuccess, hapticWarning } from '../lib/haptics';
import { colorHexFor, iconNameFor } from '../lib/categoryIcons';
import { GROUP_LABEL, groupBudgets, monthClock, overallPace } from '../lib/budgetPace';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, typography, useTheme } from '../theme';
import type { AppTabParamList } from '../navigation/types';
import { trackNavigation } from '../lib/trackNavigation';
import { GlassScreen } from '../components/GlassScreen';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';

/** This month's transactions in one category -- what a budget card is already showing a total of. */
function openTransactions(navigation: NavigationProp<ParamListBase>, b: Budget) {
  const month = currentYearMonth();
  const { dateFrom, dateTo } = monthDateRange(month);
  trackNavigation('transactions', 'contextual');
  navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
    filters: {
      categoryId: b.categoryId, dateFrom, dateTo,
      label: `${b.categoryName} · ${monthLabel(month)}`,
      nonce: Date.now(),
    },
  });
}

/**
 * Budgets: the month at a glance (ring hero), then every budget grouped by risk -- over, heading
 * over at this pace, on track -- with a pace marker on each bar. Every number and status line comes
 * from lib/budgetPace.ts, a byte-for-byte copy of the web app's, so both apps say the same thing.
 * Design: docs/superpowers/specs/2026-11-17-budgets-redesign-design.md
 *
 * The category comes from the same picker the import review uses, never free text, so a typo can't
 * create a budget against a category no transaction will ever be filed under.
 */
export function BudgetsScreen() {
  // D3 (Track D security cleanup). Budget amounts and category spend are financial figures like
  // any other -- same screenshot/screen-recording exposure Dashboard/Accounts/Statement History
  // already guard against.
  usePreventScreenCapture();
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const queryClient = useQueryClient();
  // BudgetsScreen lives inside the More stack (see AppTabs.tsx), not on the tab bar itself --
  // getParent() reaches the tab navigator the same way StatementHistoryScreen's own re-import
  // link does, for the identical reason (Track C/C4).
  const navigation = useNavigation<NavigationProp<ParamListBase>>();
  const [category, setCategory] = useState<string | null>(null);
  const [limit, setLimit] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, confirmSaved] = useTransientFlag();
  const singleFlight = useSingleFlight();

  const { data: budgets = [], isLoading, isError, isFetching, refetch } = useQuery({
    queryKey: ['budgets'],
    queryFn: () => budgetsApi.list(),
  });
  // Icons and colours only. A failure falls back to a neutral icon and never blocks the budgets.
  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    retry: false,
  });
  const categoryById = new Map(categories.map((cat) => [cat.id, cat]));

  async function save() {
    const amount = parsePositiveAmount(limit);
    if (!category) {
      setError('Pick a category first.');
      hapticWarning();
      return;
    }
    if (amount === null) {
      setError('Monthly limit must be a number greater than zero.');
      hapticWarning();
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      const startedAt = requestStartedAt();
      try {
        await budgetsApi.upsert(category, amount);
        setCategory(null);
        setLimit('');
        confirmSaved();
        hapticSuccess();
        // Dashboard's budget widget and the health score/notifications both read this.
        void queryClient.invalidateQueries({ queryKey: ['budgets'] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
        // A first budget is a Journey milestone; the Dashboard's Journey card isn't on this screen.
        void queryClient.invalidateQueries({ queryKey: ['timeline'] });
      } catch (e) {
        reportTransportFailure(e, 'budgets:upsert', startedAt);
        setError(toUserMessage(e, 'Could not save this budget. Try again.'));
        // hapticError, not hapticWarning -- this is the server rejecting a well-formed submit, not
        // the client-side "form isn't complete yet" case the two validation checks above cover.
        hapticError();
      } finally {
        setSaving(false);
      }
    });
  }

  function confirmRemove(b: Budget) {
    AppAlert.alert(`Remove the ${b.categoryName} budget?`,
      'Your transactions stay as they are. You can set a budget for this category again at any time.', [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Remove', style: 'destructive', onPress: () => void remove(b) },
      ]);
  }

  async function remove(b: Budget) {
    await singleFlight(async () => {
      setError(null);
      const startedAt = requestStartedAt();
      try {
        await budgetsApi.remove(b.id);
        hapticSuccess();
        // Same caches a save invalidates: this list, and the Dashboard's widget and notices.
        void queryClient.invalidateQueries({ queryKey: ['budgets'] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      } catch (e) {
        reportTransportFailure(e, 'budgets:delete', startedAt);
        setError(toUserMessage(e, 'Could not remove this budget. Try again.'));
        hapticError();
      }
    });
  }

  const clock = monthClock(new Date());
  const totalSpent = budgets.reduce((sum, b) => sum + b.spentThisMonth, 0);
  const totalLimit = budgets.reduce((sum, b) => sum + b.monthlyLimit, 0);

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView
      contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}
      keyboardShouldPersistTaps="handled"
      refreshControl={
        <AppRefreshControl refreshing={isFetching && !isLoading} onRefresh={() => void refetch()} tintColor={c.primary} />
      }
    >
      {!isLoading && !isError && budgets.length > 0 ? (
        <BudgetHeroCard totalSpent={totalSpent} totalLimit={totalLimit} overall={overallPace(budgets, clock)} clock={clock} money={fmtCurrency} />
      ) : null}

      <Card style={styles.formCard}>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Category</Text>
        <Pressable
          onPress={() => setPickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityLabel={category ? `Category: ${category}. Change` : 'Choose a category'}
        >
          <Text style={[styles.pickerText, { color: category ? c.ink : c.muted }]}>
            {category ?? 'Choose a category'}
          </Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">
            ›
          </Text>
        </Pressable>

        <View style={styles.limitField}>
          <TextField
            label="Monthly limit"
            value={limit}
            onChangeText={setLimit}
            keyboardType="decimal-pad"
            placeholder="0"
          />
        </View>

        <Button label={saving ? 'Saving…' : 'Set Budget'} onPress={() => void save()} loading={saving} />

        {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
        {saved ? <Text style={[styles.saved, { color: c.successInk }]}>Saved.</Text> : null}
      </Card>

      <View style={styles.list}>
        {isLoading ? (
          <SkeletonGroup style={styles.skeletonList}>
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
          </SkeletonGroup>
        ) : isError ? (
          <Card>
            <Text style={[styles.error, { color: c.dangerInk }]}>Could not load budgets.</Text>
          </Card>
        ) : budgets.length === 0 ? (
          <Card>
            <EmptyState
              message="No budgets set yet. Tap New budget to set one."
              actionLabel="Set your first budget"
              onAction={() => setPickerOpen(true)}
            />
          </Card>
        ) : (
          <>
            {groupBudgets(budgets, clock).map((g) => (
              <View key={g.group} style={styles.group}>
                <View style={styles.groupHeading}>
                  <View style={[styles.groupDot, { backgroundColor: groupInk(c, g.group) }]} />
                  <Text accessibilityRole="header" style={[typography.labelS, { color: groupInk(c, g.group) }]}>
                    {`${GROUP_LABEL[g.group]} · ${g.items.length}`}
                  </Text>
                </View>
                {g.items.map(({ budget: b, pace }) => {
                  const cat = categoryById.get(b.categoryId);
                  return (
                    <BudgetCard
                      key={b.id}
                      budget={b}
                      pace={pace}
                      clock={clock}
                      iconName={iconNameFor(cat?.icon ?? 'tag')}
                      color={colorHexFor(cat?.color ?? 'gray')}
                      money={fmtCurrency}
                      onOpen={() => openTransactions(navigation, b)}
                      onMore={() => confirmRemove(b)}
                    />
                  );
                })}
              </View>
            ))}
            <Text style={[typography.bodyS, styles.hint, { color: c.mutedInk }]}>
              Tap a card to see its transactions. The three-dot button edits or removes.
            </Text>
          </>
        )}
      </View>

      {/* CategoryPickerModal, not OptionPickerModal: setting up a budget for a category that
          doesn't exist yet is a real scenario, and the old fixed list offered no way to create
          one -- same gap as CategoryReviewScreen's identical fix. */}
      <CategoryPickerModal
        visible={pickerOpen}
        title="Category"
        selectedName={category}
        onSelect={(cat) => {
          setCategory(cat.name);
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.ms },
  formCard: {},
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  picker: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  pickerText: { fontSize: 15 },
  chevron: { fontSize: 20, lineHeight: 20 },
  limitField: { marginTop: spacing.sm },
  error: { fontSize: 13, marginTop: spacing.sm },
  saved: { fontSize: 13, marginTop: spacing.sm },
  list: { gap: spacing.sm },
  // The cards' spacing inside `list`, kept when they are wrapped in one placeholder group.
  skeletonList: { gap: spacing.sm },
  group: { gap: spacing.sm },
  groupHeading: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingTop: spacing.sm, paddingLeft: spacing.xs },
  groupDot: { width: 8, height: 8, borderRadius: 4 },
  hint: { paddingHorizontal: spacing.xs, paddingTop: spacing.xs },
});
