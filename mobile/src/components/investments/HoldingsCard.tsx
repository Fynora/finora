import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../Card';
import { hasDepositTerms, holdingTerms, shareLabel, type PortfolioSummary } from '../../lib/investmentSummary';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, typography, useTheme } from '../../theme';
import type { Account } from '../../types';
import { GROUP_COLOR } from './groupColors';

/** Every holding, grouped by type, largest first inside each group. */
export function HoldingsCard({ summary, today, money, onMore }: {
  summary: PortfolioSummary<Account>; today: Date; money: (n: number) => string; onMore: (holding: Account) => void;
}) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  return (
    <Card>
      <Text accessibilityRole="header" style={[typography.cardTitle, { color: c.ink }]}>Holdings</Text>
      {summary.groups.map((group) => (
        <View key={group.key}>
          <View style={styles.groupHeading}>
            <View style={styles.groupName}>
              <View style={[styles.dot, { backgroundColor: GROUP_COLOR[group.key] }]} />
              <Text accessibilityRole="header" style={[typography.labelS, styles.shrink, { color: c.ink }]}>{group.label}</Text>
            </View>
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`${money(group.total)} · ${shareLabel(group.share)}`}</Text>
          </View>
          {group.items.map(({ holding, share }) => {
            const percent = shareLabel(share);
            const deposit = hasDepositTerms(holding);
            const terms = deposit ? holdingTerms(holding, today, money) : null;
            const value = money(holding.balance);
            return (
              <View key={holding.id} style={[styles.row, { borderTopColor: c.border }]}>
                <View
                  accessible
                  accessibilityLabel={`${holding.name}: ${value}, ${percent} of your portfolio.${terms ? ` ${terms}.` : ''}`}
                  style={[styles.rowBody, largeText && styles.rowBodyStacked]}
                >
                  <View style={styles.shrink}>
                    <Text style={[typography.labelM, { color: c.ink }]}>{holding.name}</Text>
                    <Text style={[typography.bodyS, { color: c.mutedInk }]}>
                      {terms ? `${percent} · ${terms.charAt(0).toLowerCase()}${terms.slice(1)}` : `${percent} of portfolio`}
                    </Text>
                  </View>
                  <Text style={[typography.labelM, { color: c.ink }]}>{value}</Text>
                </View>
                <Pressable
                  onPress={() => onMore(holding)}
                  hitSlop={4}
                  style={styles.more}
                  accessibilityRole="button"
                  accessibilityLabel={`More actions for ${holding.name}`}
                >
                  <Ionicons name="ellipsis-vertical" size={18} color={c.mutedInk} />
                </Pressable>
              </View>
            );
          })}
        </View>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  groupHeading: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm, paddingTop: spacing.md, paddingBottom: spacing.xs },
  groupName: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, flexShrink: 1 },
  dot: { width: 8, height: 8, borderRadius: 4, flexShrink: 0 },
  shrink: { flexShrink: 1 },
  row: { flexDirection: 'row', alignItems: 'center', borderTopWidth: StyleSheet.hairlineWidth, paddingVertical: spacing.xs },
  rowBody: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.sm },
  rowBodyStacked: { flexDirection: 'column', alignItems: 'flex-start', gap: 2 },
  more: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', marginRight: -spacing.sm },
});
