import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { activationApi } from '../../api/endpoints';
import { Card } from '../Card';
import { spacing, useTheme } from '../../theme';
import { WhatStoppedYou } from './WhatStoppedYou';
import { activationAnsweredToday } from '../../lib/activationQuestions';

/**
 * The dashboard's "What stopped you?" card (Gate 2a spec §4.1-4.2). Port of
 * frontend/src/components/activation/ActivationQuestionCard.tsx: the server decides whether there
 * is a question and which one; this renders nothing otherwise, nor while that is loading or could
 * not be fetched. Closing the card counts as Skip.
 */
export function ActivationQuestionCard() {
  const c = useTheme();
  // Read inside the query function, not at render: if the call cannot even be made, that is a failed
  // query (no card), never a broken dashboard.
  const { data: moment, isFetchedAfterMount } = useQuery({
    queryKey: ['activation', 'question'], queryFn: () => activationApi.question(),
  });
  const [closed, setClosed] = useState(false);

  // Only an answer fetched since this mount: one cached from earlier may no longer apply (a first
  // import since then ends NO_FILE). The tab staying mounted is covered by invalidateFinancialData.
  // Nor once today is answered: the question inside would not show, leaving an empty card.
  if (!isFetchedAfterMount || !moment || closed || activationAnsweredToday()) return null;

  function close() {
    setClosed(true);
    // Fire and forget, like the answers: closing must never surface an error.
    activationApi.answer(moment!, 'SKIPPED').catch(() => {});
  }

  return (
    <Card testID="activation-question-card">
      <View style={styles.row}>
        <View style={styles.body}>
          {/* A refused answer hides the question; close the card with it rather than leave it empty. */}
          <WhatStoppedYou moment={moment} onDone={(outcome) => { if (outcome === 'refused') setClosed(true); }} />
        </View>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Close"
          onPress={close}
          hitSlop={12}
          style={styles.close}
        >
          <Ionicons name="close" size={18} color={c.muted} />
        </Pressable>
      </View>
    </Card>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'flex-start' },
  body: { flex: 1 },
  close: { marginLeft: spacing.sm, padding: spacing.xs },
});
