import { Pressable, StyleSheet, Text, View } from 'react-native';
import { DashboardCard } from './DashboardCard';
import { SectionHeading } from '../Card';
import { fmtCurrency } from '../../lib/format';
import { fonts, radius, spacing, useTheme } from '../../theme';
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
    <DashboardCard style={styles.card}>
      <SectionHeading title="Accounts" />
      <Text style={[styles.counts, { color: c.muted, fontFamily: fonts.body }]}>
        {accounts.length} Account{accounts.length === 1 ? '' : 's'} · {bankCount} Bank{bankCount === 1 ? '' : 's'}
      </Text>
      <View style={styles.avatarRow}>
        {shown.map((a) => (
          <View key={a.id} style={[styles.avatar, { backgroundColor: a.bank.colorHex }]}>
            <Text style={[styles.avatarText, { fontFamily: fonts.bodyBold }]}>{a.bank.initials}</Text>
          </View>
        ))}
        {overflow > 0 ? (
          <View style={[styles.avatar, { backgroundColor: c.border }]}>
            <Text style={[styles.avatarText, { color: c.ink, fontFamily: fonts.bodyBold }]}>+{overflow}</Text>
          </View>
        ) : null}
      </View>
      {/* Grouped into one accessible node, same as every Monthly Snapshot card -- see
          MonthlySnapshotGrid's identical comment on why swiping "Total Balance", "₹12,48,320",
          "As of today" as three separate items loses the connection between them. Same label
          format ("Total Balance: <value>, <caption>") the KPI card this figure moved out of used. */}
      <View accessible accessibilityLabel={`Total Balance: ${fmtCurrency(totalBalance)}, ${caption}`}>
        <Text style={[styles.balanceLabel, { color: c.muted, fontFamily: fonts.body }]}>Total Balance</Text>
        <Text style={[styles.balanceValue, { color: c.ink, fontFamily: fonts.display }]}>{fmtCurrency(totalBalance)}</Text>
        <Text style={[styles.caption, { color: c.mutedInk, fontFamily: fonts.body }]}>{caption}</Text>
      </View>
      <Pressable
        onPress={onViewAll}
        hitSlop={8}
        style={[styles.cta, { backgroundColor: c.primaryLight }]}
        accessibilityRole="button"
      >
        <Text style={[styles.ctaText, { color: c.primary, fontFamily: fonts.bodyBold }]}>View Accounts</Text>
      </Pressable>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  // flex: 1 so this card matches CashFlowMiniCard's height when they sit side by side in
  // DashboardScreen's cardRow (same fix already applied on the still-open card-alignment PR this
  // worktree branched before -- origin/main didn't have it yet, so it's carried forward here
  // since this task touches the same file anyway). A no-op when this card renders alone
  // (full-width, no Cash Flow data), since its parent there isn't a flex row.
  card: { flex: 1 },
  counts: { fontSize: 12, marginBottom: spacing.sm },
  avatarRow: { flexDirection: 'row', marginBottom: spacing.sm },
  avatar: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
    marginRight: -8, borderWidth: 2, borderColor: '#FFFFFF',
  },
  avatarText: { fontSize: 10, color: '#FFFFFF' },
  balanceLabel: { fontSize: 11, marginTop: spacing.xs },
  balanceValue: { fontSize: 20, marginTop: 2 },
  caption: { fontSize: 11, marginTop: 2 },
  cta: { marginTop: spacing.sm, minHeight: 36, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  ctaText: { fontSize: 12 },
});
