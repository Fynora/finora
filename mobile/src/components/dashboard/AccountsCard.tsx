import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { DashboardCard } from './DashboardCard';
import { fmtCurrency } from '../../lib/format';
import { fonts, spacing, typography, useTheme } from '../../theme';
import type { Account } from '../../types';

const MAX_AVATARS = 4;

/**
 * The balance card. Card redesign (2026-10-10): full width on its own row, with the balance as
 * the headline and the bank avatars beside it.
 *
 * 2026-10-11, to the approved design: one line under the balance (the date, then the counts)
 * and an arrow that opens Accounts. It had a second row with the counts and a "View Accounts"
 * text link, which at an accessibility text size broke "Accounts" in the middle of the word.
 * The arrow is still a button named "View Accounts".
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
  const counts = [
    `${accounts.length} account${accounts.length === 1 ? '' : 's'}`,
    `${bankCount} bank${bankCount === 1 ? '' : 's'}`,
  ];
  const shown = accounts.slice(0, MAX_AVATARS);
  const overflow = accounts.length - shown.length;

  return (
    <DashboardCard>
      <View style={styles.top}>
        {/* Grouped into one accessible node: swiping "Total Balance", "₹12,48,320", "As of today"
            as three separate items loses the connection between them. Same label format
            ("Total Balance: <value>, <caption>") the KPI card this figure moved out of used, with
            the counts after it now that they sit inside this node. */}
        <View
          style={styles.balance}
          accessible
          accessibilityLabel={[`Total Balance: ${fmtCurrency(totalBalance)}`, caption, ...counts].filter(Boolean).join(', ')}
        >
          <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Total Balance</Text>
          <Text style={[typography.numberL, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.7}>
            {fmtCurrency(totalBalance)}
          </Text>
          {/* One paragraph, so it wraps as one. The date keeps a Text of its own: it is the
              staleness caption, and what says so elsewhere looks it up by its exact words. */}
          <Text style={[typography.caption, { color: c.mutedInk }]}>
            {caption ? <Text>{caption}</Text> : null}
            {/* A no-break space keeps a count with its noun: the line otherwise broke as
                "... 3" / "banks" on a simulator. */}
            <Text>{`${caption ? ' · ' : ''}${counts.map((count) => count.replace(' ', ' ')).join(' · ')}`}</Text>
          </Text>
        </View>
        <View style={styles.avatarRow}>
          {shown.map((a) => (
            <View key={a.id} testID={`account-avatar-${a.id}`} style={[styles.avatar, { backgroundColor: a.bank.colorHex, borderColor: c.card }]}>
              <Text style={styles.avatarText} maxFontSizeMultiplier={1}>{a.bank.initials}</Text>
            </View>
          ))}
          {overflow > 0 ? (
            <View testID="account-avatar-overflow" style={[styles.avatar, { backgroundColor: c.border, borderColor: c.card }]}>
              <Text style={[styles.avatarText, { color: c.ink }]} maxFontSizeMultiplier={1}>+{overflow}</Text>
            </View>
          ) : null}
        </View>
        <Pressable onPress={onViewAll} style={styles.cta} accessibilityRole="button" accessibilityLabel="View Accounts">
          <Ionicons name="chevron-forward" size={18} color={c.mutedInk} />
        </Pressable>
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
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
  // The avatar is a fixed-size graphic, so its text does not grow with Dynamic Type
  // (maxFontSizeMultiplier at the call sites): scaled, the initials ran out of the circle and
  // into the next one.
  avatarText: { fontFamily: fonts.bodyBold, fontSize: 10, color: '#FFFFFF' },
  // The arrow alone is 18 points; the button around it is the 44 point target. The arrow sits at
  // the button's right edge, which is the card's content edge.
  cta: { minWidth: 44, minHeight: 44, alignItems: 'flex-end', justifyContent: 'center' },
});
