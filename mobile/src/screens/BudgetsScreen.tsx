import { useState } from 'react';
import {
  Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation, type NavigationProp, type ParamListBase } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { Card, EmptyState } from '../components/Card';
import { SkeletonBudgetCard, SkeletonGroup } from '../components/skeletons/Skeletons';
import { BudgetActionsSheet } from '../components/budgets/BudgetActionsSheet';
import { BudgetCard } from '../components/budgets/BudgetCard';
import { BudgetHeroCard } from '../components/budgets/BudgetHeroCard';
import { EditLimitSheet } from '../components/budgets/EditLimitSheet';
import { NewBudgetSheet } from '../components/budgets/NewBudgetSheet';
import { groupInk } from '../components/budgets/PaceBar';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import { AppAlert } from '../lib/appAlert';
import type { Budget } from '../types';
import { toUserMessage } from '../lib/apiError';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { currentYearMonth, fmtCurrency, monthDateRange, monthLabel } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { colorHexFor, iconNameFor } from '../lib/categoryIcons';
import { GROUP_LABEL, groupBudgets, monthClock, overallPace } from '../lib/budgetPace';
import { useSingleFlight } from '../lib/useSingleFlight';
import { spacing, typography, useTheme } from '../theme';
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
 * Adding and editing happen in bottom sheets; the three-dot button on a card opens a menu (Edit
 * limit, See transactions, Remove budget). Only one sheet is visible at a time: moving from the menu
 * to Edit limit swaps two sibling sheets in one step, which does not hit the iOS nested-modal freeze
 * (that needs a sheet INSIDE another closing together with it).
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
  // getParent() reaches the tab navigator (Track C/C4).
  const navigation = useNavigation<NavigationProp<ParamListBase>>();
  const [newOpen, setNewOpen] = useState(false);
  const [menuFor, setMenuFor] = useState<Budget | null>(null);
  const [editFor, setEditFor] = useState<Budget | null>(null);
  const [error, setError] = useState<string | null>(null);
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

  /** Upsert by category name. Rethrows so the sheet that called it can show the error and stay open. */
  async function saveBudget(categoryName: string, amount: number) {
    const startedAt = requestStartedAt();
    try {
      await budgetsApi.upsert(categoryName, amount);
    } catch (e) {
      reportTransportFailure(e, 'budgets:upsert', startedAt);
      // hapticError, not hapticWarning: the server rejected a well-formed submit.
      hapticError();
      throw e;
    }
    hapticSuccess();
    setError(null);
    // Dashboard's budget widget and the health score/notifications both read this.
    void queryClient.invalidateQueries({ queryKey: ['budgets'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    // A first budget is a Journey milestone; the Dashboard's Journey card isn't on this screen.
    void queryClient.invalidateQueries({ queryKey: ['timeline'] });
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
      <View style={styles.actions}>
        <Pressable
          onPress={() => setNewOpen(true)}
          style={[styles.newButton, { backgroundColor: c.primary }]}
          accessibilityRole="button"
          accessibilityLabel="New budget"
        >
          <Ionicons name="add" size={18} color={c.onPrimary} />
          <Text style={[typography.labelM, { color: c.onPrimary }]}>New budget</Text>
        </Pressable>
      </View>

      {!isLoading && !isError && budgets.length > 0 ? (
        <BudgetHeroCard totalSpent={totalSpent} totalLimit={totalLimit} overall={overallPace(budgets, clock)} clock={clock} money={fmtCurrency} />
      ) : null}

      {error ? <Text style={[typography.bodyM, { color: c.dangerInk }]}>{error}</Text> : null}

      <View style={styles.list}>
        {isLoading ? (
          <SkeletonGroup style={styles.skeletonList}>
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
          </SkeletonGroup>
        ) : isError ? (
          <Card>
            <Text style={[typography.bodyM, { color: c.dangerInk }]}>Could not load budgets.</Text>
          </Card>
        ) : budgets.length === 0 ? (
          <Card>
            <EmptyState
              message="No budgets set yet. Tap New budget to set one."
              actionLabel="Set your first budget"
              onAction={() => setNewOpen(true)}
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
                      onMore={() => setMenuFor(b)}
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

      <BudgetActionsSheet
        budget={menuFor}
        visible={menuFor !== null}
        onClose={() => setMenuFor(null)}
        onEdit={() => { setEditFor(menuFor); setMenuFor(null); }}
        onSeeTransactions={() => { const b = menuFor; setMenuFor(null); if (b) openTransactions(navigation, b); }}
        onRemove={() => { const b = menuFor; setMenuFor(null); if (b) confirmRemove(b); }}
      />
      {editFor ? (
        <EditLimitSheet
          key={editFor.id}
          budget={editFor}
          clock={clock}
          onClose={() => setEditFor(null)}
          onSave={async (amount) => {
            await saveBudget(editFor.categoryName, amount);
            setEditFor(null);
          }}
        />
      ) : null}
      {newOpen ? (
        <NewBudgetSheet
          onClose={() => setNewOpen(false)}
          onSave={async (categoryName, amount) => {
            await saveBudget(categoryName, amount);
            setNewOpen(false);
          }}
        />
      ) : null}
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.ms },
  actions: { flexDirection: 'row', justifyContent: 'flex-end' },
  newButton: {
    flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 44,
    paddingHorizontal: spacing.md, borderRadius: 999,
  },
  list: { gap: spacing.sm },
  // The cards' spacing inside `list`, kept when they are wrapped in one placeholder group.
  skeletonList: { gap: spacing.sm },
  group: { gap: spacing.sm },
  groupHeading: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingTop: spacing.sm, paddingLeft: spacing.xs },
  groupDot: { width: 8, height: 8, borderRadius: 4 },
  hint: { paddingHorizontal: spacing.xs, paddingTop: spacing.xs },
});
