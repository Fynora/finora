import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { aiInsightsApi, type AiFeedbackRating, type AiFeedbackReason } from '../../api/endpoints';
import { hapticSelection } from '../../lib/haptics';
import { radius, spacing, typography, useTheme } from '../../theme';

/** Free's monthly "Why?" allowance (AI insights spec §6). */
const FREE_WHY_LIMIT = 5;

const REASONS: { value: AiFeedbackReason; label: string }[] = [
  { value: 'WRONG_NUMBER', label: 'Wrong number' },
  { value: 'NOT_USEFUL', label: 'Not useful' },
  { value: 'CONFUSING', label: 'Confusing' },
];

interface Props {
  /** The stored text's id; null on rules text, which has nothing to rate. */
  id: string | null;
  text: string;
  writtenByAi: boolean;
  /** What the text compares against, from the server. */
  basis: string | null;
  /** "Why?" answers a Free user has left this month; null off Free. */
  freeLeft?: number | null;
  /** The thumb saved on this text earlier, as the server sent it. */
  rating?: AiFeedbackRating | null;
  ratingReason?: AiFeedbackReason | null;
  /** A thumb the server has saved, so its owner can keep its copy of the text in step. */
  onFeedback?: (rating: AiFeedbackRating | null, reason: AiFeedbackReason | null) => void;
}

/**
 * A text the server wrote for Insights (AI insights spec §8.3): the month story or a "Why?" answer.
 * AI text carries a "Written by AI" label and thumbs; rules text is shown plainly. A thumbs-down
 * offers a fixed list of reasons -- nothing the user types is ever sent.
 */
export function AiText({
  id, text, writtenByAi, basis, freeLeft = null, rating: savedRating = null, ratingReason: savedReason = null, onFeedback,
}: Props) {
  const c = useTheme();
  // Starts from what was saved; a new text is a new block (callers key it by id).
  const [rating, setRating] = useState<AiFeedbackRating | null>(savedRating);
  const [reason, setReason] = useState<AiFeedbackReason | null>(savedRating === 'NOT_HELPFUL' ? savedReason : null);
  const rateable = writtenByAi && id !== null;

  function send(nextRating: AiFeedbackRating | null, nextReason: AiFeedbackReason | null) {
    if (!id) return;
    const before = { rating, reason };
    hapticSelection();
    setRating(nextRating);
    setReason(nextReason);
    // Not saved: show what the server still holds rather than a thumb that did not count.
    aiInsightsApi.feedback(id, nextRating, nextReason)
      .then(() => onFeedback?.(nextRating, nextReason))
      .catch(() => {
        setRating(before.rating);
        setReason(before.reason);
      });
  }

  function thumb(value: AiFeedbackRating, label: string, icon: 'thumbs-up' | 'thumbs-down') {
    const selected = rating === value;
    return (
      <Pressable
        onPress={() => send(selected ? null : value, null)}
        accessibilityRole="button"
        accessibilityLabel={label}
        accessibilityState={{ selected }}
        hitSlop={4}
        style={styles.thumb}
      >
        <Ionicons name={selected ? icon : `${icon}-outline`} size={18} color={selected ? c.primary : c.mutedInk} />
      </Pressable>
    );
  }

  const spoken = [writtenByAi ? 'Written by AI.' : null, text, basis].filter(Boolean).join(' ');
  const showFreeLeft = freeLeft !== null && freeLeft >= 1 && freeLeft < FREE_WHY_LIMIT;

  return (
    <View style={styles.root}>
      <View accessible accessibilityLabel={spoken}>
        {writtenByAi ? (
          <View style={styles.labelRow}>
            <Ionicons name="sparkles-outline" size={13} color={c.mutedInk} />
            <Text style={[typography.caption, { color: c.mutedInk }]}>Written by AI</Text>
          </View>
        ) : null}
        <Text style={[typography.bodyM, { color: c.ink }]}>{text}</Text>
        {basis ? <Text style={[typography.caption, styles.basis, { color: c.mutedInk }]}>{basis}</Text> : null}
      </View>
      {showFreeLeft ? (
        <Text style={[typography.caption, { color: c.mutedInk }]}>{`${freeLeft} of ${FREE_WHY_LIMIT} left this month`}</Text>
      ) : null}
      {rateable ? (
        <View style={styles.thumbs}>
          {thumb('HELPFUL', 'Helpful', 'thumbs-up')}
          {thumb('NOT_HELPFUL', 'Not helpful', 'thumbs-down')}
        </View>
      ) : null}
      {rateable && rating === 'NOT_HELPFUL' ? (
        reason ? (
          <Text style={[typography.caption, { color: c.mutedInk }]}>Thanks for telling us.</Text>
        ) : (
          <View style={styles.reasons}>
            {REASONS.map((r) => (
              <Pressable
                key={r.value}
                onPress={() => send('NOT_HELPFUL', r.value)}
                accessibilityRole="button"
                accessibilityLabel={r.label}
                style={[styles.chip, { borderColor: c.border }]}
              >
                <Text style={[typography.labelS, { color: c.ink }]}>{r.label}</Text>
              </Pressable>
            ))}
          </View>
        )
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { gap: spacing.xs },
  labelRow: { flexDirection: 'row', alignItems: 'center', gap: 4, marginBottom: 2 },
  basis: { marginTop: 2 },
  thumbs: { flexDirection: 'row', gap: spacing.xs },
  // 44pt minimum touch target (Apple HIG), as the row chips.
  thumb: { minWidth: 44, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  reasons: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: {
    minHeight: 44, paddingHorizontal: spacing.md, borderWidth: 1, borderRadius: radius.xl,
    alignItems: 'center', justifyContent: 'center',
  },
});
