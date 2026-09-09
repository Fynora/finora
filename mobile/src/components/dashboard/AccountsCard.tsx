import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Card, SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { radius, spacing, useTheme } from '../../theme';
import type { Account } from '../../types';

const MAX_AVATARS = 4;

export function AccountsCard({
  accounts, totalBalance, caption, onViewAll,
}: {
  accounts: Account[];
  totalBalance: number;
  caption: string;
  onViewAll: () => void;
}) {
  const c = useTheme();
  const bankCount = new Set(accounts.map((a) => a.bank.id)).size;
  const shown = accounts.slice(0, MAX_AVATARS);
  const overflow = accounts.length - shown.length;

  return (
    <Card style={styles.card}>
      <SectionHeading title="Accounts" />
      <Text style={[styles.counts, { color: c.muted }]}>
        {accounts.length} Account{accounts.length === 1 ? '' : 's'} · {bankCount} Bank{bankCount === 1 ? '' : 's'}
      </Text>
      <View style={styles.avatarRow}>
        {shown.map((a) => (
          <View key={a.id} style={[styles.avatar, { backgroundColor: a.bank.colorHex }]}>
            <Text style={styles.avatarText}>{a.bank.initials}</Text>
          </View>
        ))}
        {overflow > 0 ? (
          <View style={[styles.avatar, { backgroundColor: c.border }]}>
            <Text style={[styles.avatarText, { color: c.ink }]}>+{overflow}</Text>
          </View>
        ) : null}
      </View>
      {/* Grouped into one accessible node, same as every Monthly Snapshot card -- see
          MonthlySnapshotGrid's identical comment on why swiping "Total Balance", "₹12,48,320",
          "As of today" as three separate items loses the connection between them. Same label
          format ("Total Balance: <value>, <caption>") the KPI card this figure moved out of used. */}
      <View accessible accessibilityLabel={`Total Balance: ${fmtCurrency(totalBalance)}, ${caption}`}>
        <Text style={[styles.balanceLabel, { color: c.muted }]}>Total Balance</Text>
        <Text style={[styles.balanceValue, { color: c.ink }]}>{fmtCurrency(totalBalance)}</Text>
        <Text style={[styles.caption, { color: c.mutedInk }]}>{caption}</Text>
      </View>
      <Pressable
        onPress={onViewAll}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primaryLight }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.primary }]}>View Accounts</Text>
      </Pressable>
    </Card>
  );
}

const styles = StyleSheet.create({
  card: {},
  counts: { fontSize: 12, marginBottom: spacing.sm },
  avatarRow: { flexDirection: 'row', marginBottom: spacing.sm },
  avatar: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
    marginRight: -8, borderWidth: 2, borderColor: '#FFFFFF',
  },
  avatarText: { fontSize: 10, fontWeight: '700', color: '#FFFFFF' },
  balanceLabel: { fontSize: 11, marginTop: spacing.xs },
  balanceValue: { fontSize: 20, fontWeight: '700', marginTop: 2 },
  caption: { fontSize: 11, marginTop: 2 },
  cta: { marginTop: spacing.sm, minHeight: 36, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  ctaText: { fontSize: 12, fontWeight: '600' },
});
