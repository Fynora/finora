import { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { useNavigation, type NavigationProp, type ParamListBase } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { AppRefreshControl } from '../components/AppRefreshControl';
import { Card, EmptyState } from '../components/Card';
import { GlassScreen } from '../components/GlassScreen';
import { ComingUpCard } from '../components/investments/ComingUpCard';
import { AddHoldingSheet, HoldingActionsSheet, UpdateValueSheet } from '../components/investments/HoldingSheets';
import { HoldingsCard } from '../components/investments/HoldingsCard';
import { INVESTMENT_ACTIVITY_QUERY_KEY, MoneyGoingInCard } from '../components/investments/MoneyGoingInCard';
import { NetWorthCard } from '../components/investments/NetWorthCard';
import { PortfolioCard } from '../components/investments/PortfolioCard';
import { InvestmentsSkeleton } from '../components/skeletons/ScreenSkeletons';
import { accountsApi, networthApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { AppAlert } from '../lib/appAlert';
import { withBypass } from '../lib/changeSync';
import { fmtCurrency } from '../lib/format';
import { hapticError, hapticSuccess } from '../lib/haptics';
import { assetShare, comingUp, shareLabel, summarizeHoldings } from '../lib/investmentSummary';
import { reportTransportFailure, requestStartedAt } from '../lib/monitoring';
import { isPausedCold } from '../lib/refreshingIndicator';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { trackNavigation } from '../lib/trackNavigation';
import { useSingleFlight } from '../lib/useSingleFlight';
import type { AppTabParamList } from '../navigation/types';
import { padForTabBar, useTabBarSpace } from '../navigation/useTabBarSpace';
import { radius, spacing, typography, useTheme } from '../theme';
import type { Account } from '../types';

/** The payments behind the Money going in card, in the Transactions tab. */
function openPayments(navigation: NavigationProp<ParamListBase>, categoryId: string, dateFrom: string, dateTo: string) {
  trackNavigation('transactions', 'contextual');
  navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
    filters: { categoryId, dateFrom, dateTo, label: 'Investments · last 12 months', nonce: Date.now() },
  });
}

/**
 * Investments: the total and how it is spread, what matures next, money going in, then every holding
 * by type. All numbers and status lines come from lib/investmentSummary.ts, a byte-for-byte copy of
 * the web app's. Design: docs/superpowers/specs/2026-11-18-investments-redesign-design.md
 *
 * Only one sheet is visible at a time: moving from the menu to Update value swaps two sibling sheets
 * in one step, the same way the Budgets screen does.
 */
export function InvestmentsScreen() {
  // Holdings and net worth are the most sensitive figures in the app.
  usePreventScreenCapture();
  const c = useTheme();
  const tabBar = useTabBarSpace();
  const queryClient = useQueryClient();
  const navigation = useNavigation<NavigationProp<ParamListBase>>();
  const singleFlight = useSingleFlight();
  const [addOpen, setAddOpen] = useState(false);
  // The menu's holding outlives `menuOpen` so the sheet keeps its title while it slides away.
  const [menuFor, setMenuFor] = useState<Account | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [updating, setUpdating] = useState<Account | null>(null);
  const [error, setError] = useState<string | null>(null);

  // useQueries so one failing endpoint still leaves the other usable.
  const [accountsQ, netWorthQ] = useQueries({
    queries: [
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      { queryKey: ['networth'], queryFn: () => networthApi.current() },
    ],
  });
  const holdings = useMemo(() => (accountsQ.data ?? []).filter((a) => a.accountType === 'INVESTMENT'), [accountsQ.data]);
  const loading = accountsQ.isLoading || netWorthQ.isLoading;
  // A failed refresh keeps what is on screen: only "no answer at all" is stated as not known. A cold
  // query paused for lack of connectivity is the same case, and must not read as ₹0.
  const holdingsUnknown = accountsQ.data === undefined && (accountsQ.isError || isPausedCold(accountsQ));
  const netWorthUnknown = netWorthQ.data === undefined && (netWorthQ.isError || isPausedCold(netWorthQ));
  const refreshing = (accountsQ.isFetching || netWorthQ.isFetching) && !loading;

  function refresh() {
    // withBypass: a pull is a read, not an edit -- it must not wait for the change stamp (lib/changeSync.ts).
    withBypass(() => {
      void queryClient.invalidateQueries({ queryKey: ['accounts'] });
      void queryClient.invalidateQueries({ queryKey: ['networth'] });
      void queryClient.invalidateQueries({ queryKey: [INVESTMENT_ACTIVITY_QUERY_KEY] });
    });
  }

  /** A holding changes total assets, so the Dashboard's net worth and health score move too. */
  function refreshAfterChange() {
    refresh();
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  }

  /** Rethrows so the sheet that called it can show the error and stay open. */
  async function saveValue(holding: Account, balance: number) {
    const startedAt = requestStartedAt();
    try {
      await accountsApi.update(holding.id, { name: holding.name, accountType: 'INVESTMENT', balance });
    } catch (e) {
      hapticError();
      reportTransportFailure(e, 'investments:update-value', startedAt);
      throw e;
    }
    hapticSuccess();
    queryClient.setQueryData<Account[]>(['accounts'], (current) => current?.map((a) => (a.id === holding.id ? { ...a, balance } : a)));
    setUpdating(null);
    refreshAfterChange();
  }

  async function addHolding(body: { name: string; balance: number; investmentKind: string }) {
    const startedAt = requestStartedAt();
    let created: Account;
    try {
      created = await accountsApi.create({ ...body, accountType: 'INVESTMENT' });
    } catch (e) {
      hapticError();
      reportTransportFailure(e, 'investments:add-holding', startedAt);
      throw e;
    }
    hapticSuccess();
    queryClient.setQueryData<Account[]>(['accounts'], (current) => (current ? [...current, created] : current));
    setAddOpen(false);
    refreshAfterChange();
  }

  function confirmDelete(holding: Account) {
    AppAlert.alert('Delete this holding?', `"${holding.name}" will be removed from your net worth. This can't be undone.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void removeHolding(holding) },
    ]);
  }

  async function removeHolding(holding: Account) {
    await singleFlight(async () => {
      setError(null);
      const startedAt = requestStartedAt();
      try {
        await accountsApi.remove(holding.id);
        hapticSuccess();
        queryClient.setQueryData<Account[]>(['accounts'], (current) => current?.filter((a) => a.id !== holding.id));
        refreshAfterChange();
      } catch (e) {
        hapticError();
        reportTransportFailure(e, 'investments:remove-holding', startedAt);
        setError(toUserMessage(e, 'Could not delete this holding.'));
      }
    });
  }

  if (loading) {
    return (
      <GlassScreen style={styles.glassRoot}>
        <ScrollView contentContainerStyle={padForTabBar(styles.content, tabBar)} scrollIndicatorInsets={{ bottom: tabBar }}>
          {/* The accounts list is cached across launches, so it often already knows how many
              holdings there are while net worth is still loading. */}
          <InvestmentsSkeleton holdingsCount={accountsQ.data ? holdings.length : undefined} />
        </ScrollView>
      </GlassScreen>
    );
  }

  const today = new Date();
  const summary = summarizeHoldings(holdings);
  const invested = netWorthUnknown ? null : assetShare(summary.total, netWorthQ.data?.totalAssets);
  const countLabel = `${summary.count} ${summary.count === 1 ? 'holding' : 'holdings'}`;

  return (
    <GlassScreen style={styles.glassRoot}>
      <ScrollView
        contentContainerStyle={padForTabBar(styles.content, tabBar)}
        scrollIndicatorInsets={{ bottom: tabBar }}
        keyboardShouldPersistTaps="handled"
        refreshControl={<AppRefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
      >
        <View style={styles.actions}>
          <Pressable
            onPress={() => setAddOpen(true)}
            style={[styles.addButton, { backgroundColor: c.primary }]}
            accessibilityRole="button"
            accessibilityLabel="Add holding"
          >
            <Ionicons name="add" size={18} color={c.onPrimary} />
            <Text style={[typography.labelM, { color: c.onPrimary }]}>Add holding</Text>
          </Pressable>
        </View>

        {error ? (
          <Pressable onPress={() => setError(null)} accessibilityRole="button" accessibilityLabel="Dismiss error">
            <Text style={[styles.error, { color: c.dangerInk, backgroundColor: c.dangerBg }]}>{error}</Text>
          </Pressable>
        ) : null}

        {holdingsUnknown ? (
          <Card>
            <Text style={[typography.bodyM, { color: c.dangerInk }]}>Could not load your investments.</Text>
          </Card>
        ) : summary.count === 0 ? (
          <Card>
            <EmptyState message="No investments yet. Add a holding, or import a deposit statement." />
          </Card>
        ) : (
          <PortfolioCard
            summary={summary}
            subtitle={invested === null ? countLabel : `${countLabel} · ${shareLabel(invested)} of your assets`}
            money={fmtCurrency}
          />
        )}

        <ComingUpCard rows={comingUp(holdings, today)} money={fmtCurrency} />
        <MoneyGoingInCard
          today={today}
          onSeePayments={(categoryId, dateFrom, dateTo) => openPayments(navigation, categoryId, dateFrom, dateTo)}
        />
        {!holdingsUnknown && summary.count > 0 ? (
          <HoldingsCard
            summary={summary}
            today={today}
            money={fmtCurrency}
            onMore={(holding) => { setMenuFor(holding); setMenuOpen(true); }}
          />
        ) : null}
        <NetWorthCard netWorth={netWorthQ.data ?? null} failed={netWorthUnknown} money={fmtCurrency} />

        <HoldingActionsSheet
          holding={menuFor}
          visible={menuOpen}
          onClose={() => setMenuOpen(false)}
          onUpdate={() => { setUpdating(menuFor); setMenuOpen(false); }}
          onDelete={() => { setMenuOpen(false); if (menuFor) confirmDelete(menuFor); }}
        />
        {updating ? (
          <UpdateValueSheet
            key={updating.id}
            holding={updating}
            money={fmtCurrency}
            onClose={() => setUpdating(null)}
            onSave={(value) => saveValue(updating, value)}
          />
        ) : null}
        {addOpen ? <AddHoldingSheet onClose={() => setAddOpen(false)} onSave={addHolding} /> : null}
      </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.ms },
  actions: { flexDirection: 'row', justifyContent: 'flex-end' },
  addButton: { flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 44, paddingHorizontal: spacing.md, borderRadius: 999 },
  error: { fontSize: 13, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10, overflow: 'hidden' },
});
