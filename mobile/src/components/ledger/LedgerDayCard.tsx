import { memo } from 'react';
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { useGlassSurfaceStyle } from '../GlassSurface';
import { MerchantLogo } from '../MerchantLogo';
import { counterpartyLabel } from '../../lib/counterpartyLabel';
import { fmtCurrency } from '../../lib/format';
import { rowSpokenLabel, rowTags, rowTitle, type DayPiece, type RowTag } from '../../lib/ledgerRows';
import { radius, shadows, spacing, typography, useTheme } from '../../theme';
import type { Transaction } from '../../types';

interface RowProps {
  t: Transaction;
  deleting: boolean;
  divider: boolean;
  largeText: boolean;
  onOpen: (t: Transaction) => void;
  onDelete: (t: Transaction) => void;
}

function tagTestId(tag: RowTag, id: string): string {
  if (tag.key === 'match') return `reconciliation-badge-${id}`;
  if (tag.key === 'international') return `international-badge-${id}`;
  if (tag.key === 'card-instalment') return `card-instalment-badge-${id}`;
  return `row-tag-${tag.key}-${id}`;
}

// Memoised so a new page arriving, or another row's delete, does not redraw rows already drawn.
const LedgerRow = memo(function LedgerRow({ t, deleting, divider, largeText, onOpen, onDelete }: RowProps) {
  const c = useTheme();
  const cp = counterpartyLabel(t.counterpartyType, t.type);
  const tags = rowTags(t);
  // Each pair is measured in theme/ledgerPairs.test.ts. Neutral is `ink`, not `mutedInk`: the dark
  // theme's mutedInk on its border falls just under 4.5:1.
  const tone = {
    warning: { bg: c.warningBg, fg: c.warningInk },
    danger: { bg: c.dangerBg, fg: c.dangerInk },
    success: { bg: c.successBg, fg: c.successInk },
    neutral: { bg: c.border, fg: c.ink },
  } as const;
  const amount = deleting ? (
    <ActivityIndicator testID={`row-deleting-${t.id}`} size="small" color={c.muted} />
  ) : (
    <Text style={[typography.numberS, { color: t.type === 'INCOME' ? c.successInk : c.dangerInk }]}>
      {t.type === 'INCOME' ? '+' : '-'}
      {fmtCurrency(Math.abs(t.amount))}
    </Text>
  );

  return (
    <Pressable
      onPress={() => onOpen(t)}
      onLongPress={() => onDelete(t)}
      android_ripple={{ color: c.border }}
      // One button for the whole row. Long press is the only gesture that deletes, so the same
      // delete is also a row of its own in the details sheet this opens, where a screen reader
      // can reach it.
      accessibilityRole="button"
      accessibilityLabel={rowSpokenLabel(t)}
      // The outcome, not the gesture: VoiceOver and TalkBack add their own "double tap to activate".
      accessibilityHint="Opens transaction details"
      style={[styles.row, divider && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
    >
      {largeText ? null : (
        <MerchantLogo merchant={t.merchant || t.description || '?'} size={40} person={t.counterpartyType === 'PERSON'} />
      )}
      <View style={styles.rowMain}>
        <Text style={[typography.labelM, { color: c.ink }]} numberOfLines={largeText ? undefined : 1}>
          {rowTitle(t)}
        </Text>
        <Text style={[typography.bodyS, { color: c.mutedInk }]} numberOfLines={largeText ? undefined : 1}>
          {cp ? `${t.categoryName} · ${cp.short}` : t.categoryName}
        </Text>
        {tags.length > 0 ? (
          <View style={styles.tags}>
            {tags.map((tag) => (
              <Text
                key={tag.key}
                testID={tagTestId(tag, t.id)}
                style={[typography.caption, styles.tag, { backgroundColor: tone[tag.tone].bg, color: tone[tag.tone].fg }]}
              >
                {tag.label}
              </Text>
            ))}
          </View>
        ) : null}
        {largeText ? <View testID={`row-amount-below-${t.id}`} style={styles.amountBelow}>{amount}</View> : null}
      </View>
      {largeText ? null : amount}
    </Pressable>
  );
});

/**
 * One piece of a day: the heading (on the day's first piece) and the card holding its rows. A day
 * of up to 25 rows is one piece and so one whole card. A longer day is several pieces that draw as
 * one card: only the first has the rounded top, only the last the rounded bottom and the shadow,
 * and the edges between them are dropped.
 *
 * The card is the tinted list-row surface, not a native glass view: a native effect view per card
 * in a long list is the costly kind (see GlassSurface).
 */
export const LedgerDayCard = memo(function LedgerDayCard({
  piece, deletingId, largeText, onOpen, onDelete,
}: {
  piece: DayPiece;
  deletingId: string | null;
  largeText: boolean;
  onOpen: (t: Transaction) => void;
  onDelete: (t: Transaction) => void;
}) {
  const c = useTheme();
  const glass = useGlassSurfaceStyle();
  return (
    <View>
      {piece.heading !== null ? (
        <View style={styles.heading}>
          <Text
            accessibilityRole="header"
            style={[typography.eyebrow, styles.headingLabel, { color: c.mutedInk }]}
            numberOfLines={1}
          >
            {piece.heading}
          </Text>
          <Text style={[typography.labelS, styles.headingTotal, { color: piece.subtotal >= 0 ? c.successInk : c.dangerInk }]}>
            {piece.subtotal >= 0 ? '+' : '-'}
            {fmtCurrency(Math.abs(piece.subtotal))}
          </Text>
        </View>
      ) : null}
      <View
        testID={`day-card-${piece.key}`}
        style={[
          styles.card,
          glass,
          piece.first ? styles.top : styles.joinAbove,
          piece.last ? styles.bottom : styles.joinBelow,
          // Read at render, like GlassSurface's own platform check, so a test can drive both.
          piece.last && (Platform.OS === 'ios' ? styles.shadowIos : styles.shadowAndroid),
          Platform.OS === 'android' && styles.clipRipple,
        ]}
      >
        {piece.rows.map((t, i) => (
          <LedgerRow
            key={t.id}
            t={t}
            deleting={deletingId === t.id}
            divider={i > 0 || !piece.first}
            largeText={largeText}
            onOpen={onOpen}
            onDelete={onDelete}
          />
        ))}
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  heading: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm,
    paddingTop: spacing.md, paddingBottom: spacing.sm, paddingHorizontal: spacing.xs,
  },
  // The total is the figure that must never be clipped, so the date gives way first.
  headingLabel: { flexShrink: 1 },
  headingTotal: { flexShrink: 0 },
  card: { borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: spacing.md, paddingVertical: 2 },
  top: { borderTopLeftRadius: radius.xxl, borderTopRightRadius: radius.xxl },
  bottom: { borderBottomLeftRadius: radius.xxl, borderBottomRightRadius: radius.xxl },
  joinAbove: { borderTopWidth: 0, paddingTop: 0 },
  joinBelow: { borderBottomWidth: 0, paddingBottom: 0 },
  shadowIos: { boxShadow: shadows.card },
  shadowAndroid: { elevation: 2 },
  // Keeps a row's ripple inside the card's corners. Not on iOS, where clipping a view is what
  // removed the dashboard cards' shadows.
  clipRipple: { overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms, paddingVertical: spacing.ms },
  rowMain: { flex: 1 },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: 5 },
  tag: { borderRadius: 999, paddingHorizontal: spacing.sm, paddingVertical: 2, overflow: 'hidden' },
  amountBelow: { marginTop: spacing.xs, alignItems: 'flex-start' },
});
