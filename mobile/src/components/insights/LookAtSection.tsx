import { useRef, useState } from 'react';
import { AccessibilityInfo, Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, SectionHeading } from '../Card';
import { RecurringQuestion } from '../RecurringQuestion';
import { SkeletonGroup, SkeletonLines } from '../skeletons/Skeletons';
import { AiText } from './AiText';
import { PeopleLookAtRow } from './PeopleLookAtRow';
import { fmtCurrency, monthLabelLong } from '../../lib/format';
import { aiInsightsApi, type AiWhy, type PeopleSuggestion } from '../../api/endpoints';
import type { LookAtRow } from '../../lib/insightsFeed';
import { radius, spacing, typography, useTheme } from '../../theme';

interface Props {
  rows: LookAtRow[];
  /** The reporting month, e.g. "September 2026", for the empty line. */
  monthLong: string;
  onOpenRow: (row: LookAtRow) => void;
  onAck: (row: LookAtRow) => void;
  onUpload: () => void;
  /** Today, YYYY-MM-DD, and the built-in Paid back kind: what the people rows need. */
  today: string;
  paidBackKindId: string | null;
  onPickBill: (item: PeopleSuggestion) => void;
  onAsk: (item: PeopleSuggestion) => void;
  onOpenPayment: (item: PeopleSuggestion) => void;
  /** The month the rows were asked for (undefined = the newest). "Why?" names the same one, so the
   *  server finds the row on exactly the list it gave. */
  requestMonth?: string;
  /** Free's "Why?" allowance is used up: open the plans. */
  onSeePlus: () => void;
  /** An AI-written answer is now on screen. */
  onAiText?: () => void;
}

type RunningHigh = Extract<LookAtRow, { kind: 'running-high' }>;
type WhyState = { status: 'loading' } | { status: 'failed' } | { status: 'done'; answer: AiWhy };

/** The line an exhausted Free allowance shows (AI insights spec §6), the only message about limits. */
const FREE_USED_UP = "You've used this month's 5 AI explanations. Plus explains every row.";
const NO_ANSWER: AiWhy = {
  status: 'none', id: null, text: null, writtenByAi: false, basis: null, freeLeftThisMonth: null, rating: null,
  ratingReason: null,
};

function cardPhrase(row: Extract<LookAtRow, { kind: 'card-statement' }>): string {
  if (!row.cardName) return 'a credit card';
  // The account's own name, often just its bank's ("Sample Bank"): say "card" unless it already does.
  const name = /\bcard\b/i.test(row.cardName) ? row.cardName : `${row.cardName} card`;
  return row.cardLast4 ? `your ${name} ending ${row.cardLast4}` : `your ${name}`;
}

/**
 * "To look at": what changed and needs the user, each row something they can act on. Every row's
 * buttons are siblings of its tappable text, never inside it -- an accessible Pressable hides the
 * buttons nested in it from screen readers.
 */
export function LookAtSection({
  rows, monthLong, onOpenRow, onAck, onUpload, today, paidBackKindId, onPickBill, onAsk, onOpenPayment, requestMonth,
  onSeePlus, onAiText,
}: Props) {
  const c = useTheme();
  const divider = { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border };
  // "Why?" answers by row key, kept while the screen is open so reopening one asks nothing.
  const [whys, setWhys] = useState<Record<string, WhyState>>({});
  const [open, setOpen] = useState<Record<string, boolean>>({});
  // What is open right now, for an answer that lands after a tap closed it again.
  const openNow = useRef<Record<string, boolean>>({});
  // Rows with a request in flight: a second tap before the answer lands never sends another.
  const asking = useRef(new Set<string>());

  function setOpenFor(key: string, value: boolean) {
    openNow.current = { ...openNow.current, [key]: value };
    setOpen((o) => ({ ...o, [key]: value }));
  }

  /** An answer is now on screen: read it out, and tell the screen when the AI wrote it. */
  function shown(answer: AiWhy) {
    if (answer.status !== 'ready' || !answer.text) return;
    AccessibilityInfo.announceForAccessibility(answer.text);
    if (answer.writtenByAi) onAiText?.();
  }

  function ask(row: RunningHigh, key: string) {
    if (asking.current.has(key)) return;
    asking.current.add(key);
    setWhys((w) => ({ ...w, [key]: { status: 'loading' } }));
    aiInsightsApi.why(row.ackKind, row.ackItemKey, requestMonth)
      .then((answer) => {
        setWhys((w) => ({ ...w, [key]: { status: 'done', answer } }));
        if (openNow.current[key]) shown(answer);
      })
      .catch((err: unknown) => {
        // 404: the row is no longer on the server's list (an import since, or another device's
        // "Got it") -- nothing to explain, and trying again would not change that.
        const gone = (err as { response?: { status?: number } } | null)?.response?.status === 404;
        setWhys((w) => ({ ...w, [key]: gone ? { status: 'done', answer: NO_ANSWER } : { status: 'failed' } }));
      })
      .finally(() => asking.current.delete(key));
  }

  function toggleWhy(row: RunningHigh, key: string) {
    if (open[key]) {
      setOpenFor(key, false);
      return;
    }
    setOpenFor(key, true);
    const state = whys[key];
    if (!state || state.status === 'failed') ask(row, key);
    else if (state.status === 'done') shown(state.answer);
  }

  function retry(row: RunningHigh, key: string) {
    return (
      <Pressable
        onPress={() => ask(row, key)}
        accessibilityRole="button"
        accessibilityLabel="Try again"
        style={[styles.chip, styles.retry, { borderColor: c.border }]}
      >
        <Text style={[typography.labelS, { color: c.ink }]}>Try again</Text>
      </Pressable>
    );
  }

  function whyAnswer(row: RunningHigh, key: string) {
    const state = whys[key];
    if (!state || state.status === 'loading') {
      return (
        <SkeletonGroup testID="why-loading">
          <SkeletonLines count={2} size="body" />
        </SkeletonGroup>
      );
    }
    if (state.status === 'failed') {
      return (
        <View>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>Couldn't load the explanation.</Text>
          {retry(row, key)}
        </View>
      );
    }
    const { answer } = state;
    if (answer.status === 'generating') {
      return (
        <View>
          <Text style={[typography.bodyS, { color: c.mutedInk }]}>This is still being written. Try again in a moment.</Text>
          {retry(row, key)}
        </View>
      );
    }
    if (answer.status !== 'ready' || !answer.text) {
      return <Text style={[typography.bodyS, { color: c.mutedInk }]}>There is no explanation for this yet.</Text>;
    }
    const usedUp = answer.freeLeftThisMonth === 0 && !answer.writtenByAi;
    return (
      <View style={styles.answer}>
        <AiText
          key={answer.id ?? 'rules'}
          id={answer.id}
          text={answer.text}
          writtenByAi={answer.writtenByAi}
          basis={answer.basis}
          freeLeft={answer.freeLeftThisMonth}
          rating={answer.rating}
          ratingReason={answer.ratingReason}
          // Closing and reopening the answer draws it again from this copy, so it keeps the thumb too.
          onFeedback={(rating, ratingReason) => setWhys((w) => {
            const now = w[key];
            return now?.status === 'done' && now.answer.id === answer.id
              ? { ...w, [key]: { status: 'done', answer: { ...now.answer, rating, ratingReason } } }
              : w;
          })}
        />
        {usedUp ? (
          <>
            <Text style={[typography.bodyS, { color: c.mutedInk }]}>{FREE_USED_UP}</Text>
            <Pressable
              onPress={onSeePlus}
              accessibilityRole="button"
              accessibilityLabel="See Plus"
              style={[styles.chip, styles.retry, { backgroundColor: c.primary, borderColor: c.primary }]}
            >
              <Text style={[typography.labelS, { color: c.onPrimary }]}>See Plus</Text>
            </Pressable>
          </>
        ) : null}
      </View>
    );
  }

  function gotIt(row: LookAtRow, name: string) {
    return (
      <Pressable
        onPress={() => onAck(row)}
        accessibilityRole="button"
        accessibilityLabel={`Got it: ${name}`}
        style={[styles.chip, { borderColor: c.border }]}
      >
        <Text style={[typography.labelS, { color: c.ink }]}>Got it</Text>
      </Pressable>
    );
  }

  function whyKey(row: RunningHigh): string {
    return `${row.ackKind}|${row.ackItemKey}`;
  }

  function whyChip(row: RunningHigh) {
    const key = whyKey(row);
    const isOpen = open[key] === true;
    return (
      <Pressable
        onPress={() => toggleWhy(row, key)}
        accessibilityRole="button"
        accessibilityLabel={isOpen ? `Hide why ${row.category} is above your usual month` : `Why is ${row.category} above your usual month?`}
        accessibilityState={{ expanded: isOpen }}
        style={[styles.chip, { borderColor: c.border }]}
      >
        <Text style={[typography.labelS, { color: c.primary }]}>{isOpen ? 'Hide why' : 'Why?'}</Text>
      </Pressable>
    );
  }

  function renderRow(row: LookAtRow, index: number) {
    const style = [styles.row, index > 0 && divider];
    switch (row.kind) {
      case 'card-statement': {
        const month = monthLabelLong(row.month);
        return (
          <View key="card-statement" style={style}>
            <View style={styles.rowHead}>
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="document-text-outline" size={16} color={c.warningInk} />
              </View>
              <Text style={[typography.bodyM, styles.text, { color: c.ink }]}>
                {`You paid ${fmtCurrency(row.total)} toward ${cardPhrase(row)} in ${month} with no card statement uploaded for it. Upload it to see what you bought.`}
              </Text>
            </View>
            <View style={styles.actions}>
              <Pressable
                onPress={onUpload}
                accessibilityRole="button"
                accessibilityLabel="Upload statement"
                style={[styles.chip, { backgroundColor: c.primary, borderColor: c.primary }]}
              >
                <Text style={[typography.labelS, { color: c.onPrimary }]}>Upload statement</Text>
              </Pressable>
              {gotIt(row, `card statement for ${month}`)}
            </View>
          </View>
        );
      }
      case 'people':
        return (
          <View key={`people-${row.item.kind}-${row.item.ackKey}`} style={index > 0 && divider}>
            <PeopleLookAtRow
              item={row.item}
              today={today}
              paidBackKindId={paidBackKindId}
              onAck={() => onAck(row)}
              onPickBill={onPickBill}
              onAsk={onAsk}
              onOpenPayment={onOpenPayment}
            />
          </View>
        );
      case 'new-recurring':
        return (
          <View key={`new-${row.item.merchant}`} style={style}>
            <View style={styles.rowHead}>
              <View style={[styles.icon, { backgroundColor: c.primaryLight }]}>
                <Ionicons name="repeat" size={16} color={c.primary} />
              </View>
              <Text style={[typography.labelM, styles.text, { color: c.ink }]}>{`New repeating payment: ${row.item.merchant}`}</Text>
            </View>
            <RecurringQuestion
              merchant={row.item.merchant}
              state="NEEDS_ANSWER"
              answer={null}
              amount={row.item.averageAmount}
              label={row.item.label}
            />
          </View>
        );
      case 'amount-changed':
        return (
          <View key={`changed-${row.merchant}`} style={style}>
            <Pressable
              onPress={() => onOpenRow(row)}
              accessibilityRole="button"
              accessibilityLabel={`Show the payments to ${row.merchant}`}
              style={styles.rowHead}
            >
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="swap-vertical" size={16} color={c.warningInk} />
              </View>
              <Text style={[typography.labelM, styles.text, { color: c.ink }]}>{`Amount changed: ${row.merchant}`}</Text>
              <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
            </Pressable>
            <RecurringQuestion merchant={row.merchant} state="AMOUNT_CHANGED" answer={row.category || null} amount={row.latestAmount} />
            <View style={styles.actions}>
              {gotIt(row, row.merchant)}
            </View>
          </View>
        );
      case 'running-high': {
        const title = `${row.category} is ${fmtCurrency(row.current - row.priorAverage)} above your usual month`;
        const detail = `${fmtCurrency(row.current)} vs ${fmtCurrency(row.priorAverage)}`;
        return (
          <View key={`high-${row.category}`} style={style}>
            <Pressable
              onPress={() => onOpenRow(row)}
              accessibilityRole="button"
              accessibilityLabel={`${title}, ${detail}. Shows the payments`}
              style={styles.rowHead}
            >
              <View style={[styles.icon, { backgroundColor: c.warningBg }]}>
                <Ionicons name="trending-up" size={16} color={c.warningInk} />
              </View>
              <View style={styles.text}>
                <Text style={[typography.labelM, { color: c.ink }]}>{title}</Text>
                <Text style={[typography.bodyS, { color: c.mutedInk }]}>{detail}</Text>
              </View>
              <Ionicons name="chevron-forward" size={16} color={c.mutedInk} />
            </Pressable>
            <View style={styles.actions}>
              {row.whyAvailable ? whyChip(row) : null}
              {gotIt(row, row.category)}
            </View>
            {row.whyAvailable && open[whyKey(row)] ? <View style={styles.whyBody}>{whyAnswer(row, whyKey(row))}</View> : null}
          </View>
        );
      }
    }
  }

  return (
    <View>
      <SectionHeading
        title="To look at"
        action={rows.length > 0 ? <Text style={[typography.labelM, { color: c.mutedInk }]}>{rows.length}</Text> : undefined}
      />
      <Card>
        {rows.length === 0 ? (
          <Text style={[typography.bodyM, styles.empty, { color: c.mutedInk }]}>{monthLong ? `Nothing new to look at for ${monthLong}.` : 'Nothing new to look at.'}</Text>
        ) : (
          rows.map(renderRow)
        )}
      </Card>
    </View>
  );
}

const styles = StyleSheet.create({
  row: { paddingVertical: spacing.sm, gap: spacing.xs },
  rowHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, minHeight: 44 },
  icon: { width: 28, height: 28, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  text: { flex: 1 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, paddingLeft: 28 + spacing.sm },
  // 44pt minimum touch target (Apple HIG), as RecurringQuestion's chips.
  chip: {
    minHeight: 44, paddingHorizontal: spacing.md, borderWidth: 1, borderRadius: radius.xl,
    alignItems: 'center', justifyContent: 'center',
  },
  empty: { paddingVertical: spacing.sm },
  whyBody: { paddingLeft: 28 + spacing.sm, paddingTop: spacing.xs },
  answer: { gap: spacing.xs },
  retry: { alignSelf: 'flex-start', marginTop: spacing.xs },
});
