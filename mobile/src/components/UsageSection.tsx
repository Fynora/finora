import { StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { accountsApi, analyticsApi, budgetsApi, goalsApi, usageApi } from '../api/endpoints';
import { spacing, useTheme } from '../theme';
import { Card, SectionHeading } from './Card';

const DASH = '—';

function count(n: number | undefined) {
  return n === undefined ? DASH : n.toLocaleString('en-IN');
}

function plural(n: number | undefined, one: string, many: string) {
  return n === 1 ? one : many;
}

/** Mobile counterpart of the "How you're using Fynora" tiles in frontend/src/pages/Billing.tsx.
 *  A tile whose query hasn't answered (or failed) reads "—", not 0: the web page defaults to 0
 *  there, which is indistinguishable from a real empty account. The web page's separate "Premium
 *  Value Received" figure is deliberately not ported -- it is a hardcoded illustration, not
 *  computed from anything. */
export function UsageSection({ isFree }: { isFree: boolean }) {
  const c = useTheme();
  // Same query keys the screens that own this data use, so an already-cached list is reused.
  const { data: accounts } = useQuery({ queryKey: ['accounts'], queryFn: () => accountsApi.list() });
  const { data: goals } = useQuery({ queryKey: ['goals'], queryFn: () => goalsApi.list() });
  const { data: budgets } = useQuery({ queryKey: ['budgets'], queryFn: () => budgetsApi.list() });
  const { data: importStats } = useQuery({
    queryKey: ['import-statistics'], queryFn: () => analyticsApi.importStatistics(), retry: false,
  });
  const { data: insights } = useQuery({
    queryKey: ['usage', 'insights'], queryFn: () => usageApi.viewCount('insights'), retry: false,
  });

  const tiles = [
    { label: 'Smart Insights', value: count(insights?.viewCount), desc: 'insights viewed' },
    {
      label: 'Goals Created', value: count(goals?.length),
      desc: plural(goals?.length, 'goal', 'goals'),
    },
    {
      label: 'Budgets Managed', value: count(budgets?.length),
      desc: plural(budgets?.length, 'budget', 'budgets'),
    },
    { label: 'Statement Imports', value: count(importStats?.totalStatements), desc: 'statements imported' },
    {
      label: 'Connected Accounts', value: count(accounts?.length),
      desc: plural(accounts?.length, 'account', 'accounts'),
    },
    { label: 'Transactions Imported', value: count(importStats?.totalTransactionsImported), desc: 'transactions' },
  ];

  return (
    <Card>
      <SectionHeading title={`How you're using ${isFree ? 'Fynora' : 'Premium'}`} />
      <View style={styles.grid}>
        {tiles.map((t) => (
          <View
            key={t.label}
            accessible
            accessibilityLabel={`${t.label}: ${t.value} ${t.desc}`}
            style={[styles.tile, { borderColor: c.border }]}
          >
            <Text style={[styles.value, { color: c.ink }]}>{t.value}</Text>
            <Text style={[styles.label, { color: c.muted }]}>{t.label} · {t.desc}</Text>
          </View>
        ))}
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  tile: { flexBasis: '47%', flexGrow: 1, borderWidth: 1, borderRadius: 12, padding: spacing.sm },
  value: { fontSize: 20, fontWeight: '700' },
  label: { fontSize: 11, marginTop: 2 },
});
