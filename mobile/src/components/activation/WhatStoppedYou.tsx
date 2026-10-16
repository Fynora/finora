import { useRef, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { activationApi, type ActivationAnswer, type ActivationMoment } from '../../api/endpoints';
import { ACTIVATION_QUESTIONS, SKIP_LABEL } from '../../lib/activationQuestions';
import { radius, spacing, useTheme } from '../../theme';

/**
 * One "What stopped you?" question (Gate 2a spec §4). Port of
 * frontend/src/components/activation/WhatStoppedYou.tsx: one tap on a fixed answer, or Skip, and no
 * free text -- a typed answer could carry amounts, names or account numbers.
 *
 * After a tap it says "Thanks." and calls `onDone`. If the answer is refused (409: the question is
 * not being asked now) or fails at all, it disappears without an error and still calls `onDone`.
 */
export function WhatStoppedYou({ moment, onDone }: { moment: ActivationMoment; onDone?: () => void }) {
  const c = useTheme();
  const [state, setState] = useState<'asking' | 'thanks' | 'gone'>('asking');
  const sent = useRef(false);
  const { question, answers } = ACTIVATION_QUESTIONS[moment];

  function send(answer: ActivationAnswer) {
    // A ref, not state: a second tap in the same frame must not send a second answer.
    if (sent.current) return;
    sent.current = true;
    activationApi.answer(moment, answer).then(
      () => setState('thanks'),
      () => setState('gone'),
    ).finally(() => onDone?.());
  }

  if (state === 'gone') return null;
  if (state === 'thanks') {
    return <Text style={[styles.thanks, { color: c.muted }]} accessibilityLiveRegion="polite">Thanks.</Text>;
  }
  return (
    <View accessibilityLabel={question} testID={`what-stopped-you-${moment}`}>
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
    </View>
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
