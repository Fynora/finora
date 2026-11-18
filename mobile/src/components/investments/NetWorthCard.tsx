import { StyleSheet, Text, useWindowDimensions } from 'react-native';
import type { NetWorthData } from '../../api/endpoints';
import { Card } from '../Card';
import { TrendChart } from '../charts/TrendChart';
import { unknownCardsNote } from '../../lib/amountOwed';
import { spacing, typography, useTheme } from '../../theme';

/** Net worth in one small card: the screen is about investments, but this is the trend's only home. */
export function NetWorthCard({ netWorth, failed, money }: { netWorth: NetWorthData | null; failed: boolean; money: (n: number) => string }) {
  const c = useTheme();
  const { width } = useWindowDimensions();
  if (failed || !netWorth) {
    return (
      <Card testID="net-worth-card" style={styles.card}>
        <Text style={[typography.labelS, { color: c.mutedInk }]}>Net worth</Text>
        <Text style={[typography.bodyS, { color: c.dangerInk }]}>Could not load your net worth.</Text>
      </Card>
    );
  }
  const note = unknownCardsNote(netWorth.cardsWithUnknownAmountOwed);
  return (
    <Card testID="net-worth-card" style={styles.card}>
      <Text style={[typography.labelS, { color: c.mutedInk }]}>Net worth</Text>
      <Text style={[typography.numberM, { color: netWorth.netWorth < 0 ? c.dangerInk : c.ink }]}>{money(netWorth.netWorth)}</Text>
      <Text style={[typography.bodyS, { color: c.mutedInk }]}>{`${money(netWorth.totalAssets)} assets · ${money(netWorth.totalLiabilities)} liabilities`}</Text>
      {note ? <Text style={[typography.bodyS, { color: c.mutedInk }]}>{note}</Text> : null}
      {netWorth.history.length >= 2 ? (
        <TrendChart points={netWorth.history.map((h) => ({ date: h.date, value: h.netWorth }))} width={width - spacing.md * 4} />
      ) : (
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>Building your net worth trend. Check back in a day or two.</Text>
      )}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { gap: spacing.xs },
});
