import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import { Card } from '../Card';
import { activationApi, type ActivationAnswer, type ActivationMoment } from '../../api/endpoints';
import {
  ACTIVATION_QUESTIONS, SKIP_LABEL, activationAnsweredToday, markActivationAnsweredToday,
} from '../../lib/activationQuestions';
import { radius, spacing, useTheme } from '../../theme';

/**
 * One "What stopped you?" question (Gate 2a spec §4). Port of
 * frontend/src/components/activation/WhatStoppedYou.tsx: one tap on a fixed answer, or Skip, and no
 * free text -- a typed answer could carry amounts, names or account numbers.
 *
 * After a tap it says "Thanks." and calls `onDone`. If the answer is refused (409: the question is
 * not being asked now) or fails at all, it disappears without an error and still calls `onDone`.
 */
export function WhatStoppedYou({ moment, onDone, framed = false, style, chosenByServer = false }: {
  moment: ActivationMoment;
  /** The dashboard card's question, which the server already chose. Anywhere else (the import
   *  screen) the server is asked first whether this moment may be asked now (spec §4.2). */
  chosenByServer?: boolean;
  /** 'answered': it now says "Thanks.". 'refused': it hid itself (409 or any failure). */
  onDone?: (outcome: 'answered' | 'refused') => void;
  /** Draw its own card around the question. Drawn here rather than by the caller so that when the
   *  question hides itself (answered today, or refused) no empty card is left behind. */
  framed?: boolean;
  /** Spacing for the question's own outer element, for the same reason: no gap left behind. */
  style?: StyleProp<ViewStyle>;
}) {
  const c = useTheme();
  // Not shown at all once today is answered: the server would refuse this one (spec §4.3). Nor,
  // unless the server chose it, before the server says it may be asked now: an answer to a moment
  // within its 30 quiet days, or after a reload that forgot today's answer, would be refused, and the
  // tap would go nowhere.
  const [state, setState] = useState<'checking' | 'asking' | 'thanks' | 'gone'>(() => {
    if (activationAnsweredToday()) return 'gone';
    return chosenByServer ? 'asking' : 'checking';
  });
  const sent = useRef(false);
  const { question, answers } = ACTIVATION_QUESTIONS[moment];

  useEffect(() => {
    if (state !== 'checking') return undefined;
    let live = true;
    // Any failure hides it, even one thrown before a request is made: a question about why someone
    // stopped must never be in their way.
    Promise.resolve().then(() => activationApi.question(moment)).then(
      (asked) => { if (live) setState(asked === moment ? 'asking' : 'gone'); },
      () => { if (live) setState('gone'); },
    );
    return () => { live = false; };
  }, [moment, state]);

  function send(answer: ActivationAnswer) {
    // A ref, not state: a second tap in the same frame must not send a second answer.
    if (sent.current) return;
    sent.current = true;
    activationApi.answer(moment, answer).then(
      () => {
        markActivationAnsweredToday();
        setState('thanks');
        onDone?.('answered');
      },
      (e: { response?: { status?: number } }) => {
        // 409: today (or this moment) is already answered. Any other failure says nothing about it.
        if (e?.response?.status === 409) markActivationAnsweredToday();
        setState('gone');
        onDone?.('refused');
      },
    );
  }

  if (state === 'gone' || state === 'checking') return null;
  const frame = (content: ReactNode) => (framed ? <Card testID="what-stopped-you-frame">{content}</Card> : content);
  if (state === 'thanks') {
    return frame(<Text style={[styles.thanks, { color: c.muted }, style]} accessibilityLiveRegion="polite">Thanks.</Text>);
  }
  return frame(
    <View accessibilityLabel={question} testID={`what-stopped-you-${moment}`} style={style}>
      <Text style={[styles.question, { color: c.ink }]}>{question}</Text>
      <View style={styles.answers}>
        {answers.map((a) => (
          <Pressable
            key={a.key}
            accessibilityRole="button"
            accessibilityLabel={a.label}
            onPress={() => send(a.key)}
            style={({ pressed }) => [styles.chip, { borderColor: c.border, opacity: pressed ? 0.6 : 1 }]}
          >
            <Text style={[styles.chipText, { color: c.ink }]}>{a.label}</Text>
          </Pressable>
        ))}
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={SKIP_LABEL}
          onPress={() => send('SKIPPED')}
          style={({ pressed }) => [styles.skip, { opacity: pressed ? 0.6 : 1 }]}
        >
          <Text style={[styles.chipText, { color: c.muted }]}>{SKIP_LABEL}</Text>
        </Pressable>
      </View>
    </View>,
  );
}

const styles = StyleSheet.create({
  question: { fontSize: 15, fontWeight: '600', marginBottom: spacing.sm },
  answers: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: { borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.md, paddingHorizontal: spacing.md, paddingVertical: spacing.sm, minHeight: 44, justifyContent: 'center' },
  skip: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm, minHeight: 44, justifyContent: 'center' },
  chipText: { fontSize: 14 },
  thanks: { fontSize: 14 },
});
