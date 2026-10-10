import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { DashboardCard } from './DashboardCard';
import { fmtCurrency } from '../../lib/format';
import { fonts, spacing, typography, useTheme } from '../../theme';
import type { Account } from '../../types';

const MAX_AVATARS = 4;

/**
 * The balance card. Card redesign (2026-10-10): full width on its own row, with the balance as
 * the headline, the bank avatars beside it, and the counts and "View Accounts" in a footer.
 */
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
      <View style={styles.top}>
        {/* Grouped into one accessible node: swiping "Total Balance", "₹12,48,320", "As of today"
            as three separate items loses the connection between them. Same label format
            ("Total Balance: <value>, <caption>") the KPI card this figure moved out of used. */}
        <View style={styles.balance} accessible accessibilityLabel={`Total Balance: ${fmtCurrency(totalBalance)}, ${caption}`}>
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Total Balance</Text>
          <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {fmtCurrency(totalBalance)}
          </Text>
          <Text style={[typography.caption, { color: c.mutedInk }]}>{caption}</Text>
        </View>
        <View style={styles.avatarRow}>
          {shown.map((a) => (
            <View key={a.id} testID={`account-avatar-${a.id}`} style={[styles.avatar, { backgroundColor: a.bank.colorHex, borderColor: c.card }]}>
              <Text style={styles.avatarText}>{a.bank.initials}</Text>
            </View>
          ))}
          {overflow > 0 ? (
            <View testID="account-avatar-overflow" style={[styles.avatar, { backgroundColor: c.border, borderColor: c.card }]}>
              <Text style={[styles.avatarText, { color: c.ink }]}>+{overflow}</Text>
            </View>
          ) : null}
        </View>
      </View>
      <View style={[styles.footer, { borderTopColor: c.border }]}>
        <Text style={[typography.bodyS, styles.counts, { color: c.mutedInk }]}>
          {accounts.length} Account{accounts.length === 1 ? '' : 's'} · {bankCount} Bank{bankCount === 1 ? '' : 's'}
        </Text>
        <Pressable onPress={onViewAll} style={styles.cta} accessibilityRole="button" accessibilityLabel="View Accounts">
          <Text style={[typography.labelS, { color: c.primary }]}>View Accounts</Text>
          <Ionicons name="chevron-forward" size={14} color={c.primary} />
        </Pressable>
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.ms },
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms },
  balance: { flex: 1, gap: spacing.xs },
  // paddingRight cancels the last avatar's negative margin, so the stack ends at the card's edge.
  avatarRow: { flexDirection: 'row', paddingRight: 8 },
  // borderColor set per call site (c.card), not here -- this ring separates overlapping avatars
  // by matching the card surface behind them, so it has to flip with the theme instead of always
  // being white (which read as a bright halo around each avatar on a dark card).
  avatar: {
    width: 32, height: 32, borderRadius: 16, alignItems: 'center', justifyContent: 'center',
    marginRight: -8, borderWidth: 2,
  },
  avatarText: { fontFamily: fonts.bodyBold, fontSize: 10, color: '#FFFFFF' },
  footer: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth, gap: spacing.sm,
  },
  counts: { flexShrink: 1 },
  cta: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44 },
});
