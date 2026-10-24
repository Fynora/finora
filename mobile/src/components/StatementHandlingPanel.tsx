import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { openWebUrl } from '../lib/webUrl';
import { spacing, useTheme } from '../theme';

/**
 * "What happens to my statement?" -- Gate 2b, spec §5.2 (approved copy, verbatim). The mobile twin of
 * frontend/src/components/StatementHandlingPanel.tsx; see that file's doc comment for the code behind
 * each line. Closed by default, beside the file picker. The policy link opens the web page, as the
 * app's other legal links do (openWebUrl).
 */
const LINES: { lead: string; rest: string }[] = [
  { lead: 'Fynora reads it with its own rules.', rest: 'It is not sent to AI services.' },
  {
    lead: 'The original file is kept in your account,',
    rest: 'so you can download it or have it read again later.',
  },
  { lead: 'You check every row before anything is saved.', rest: 'If you cancel, nothing is added.' },
  {
    lead: "If it can't be read automatically,",
    rest: 'our team may open it to fix the problem. Every opening needs a written reason, is logged, and you are told.',
  },
  {
    lead: 'You can delete a statement any time',
    rest: 'from Statement History. Its file is deleted from our storage 90 days later.',
  },
  { lead: 'A PDF password is saved only if you choose,', rest: 'and is stored encrypted.' },
];

export function StatementHandlingPanel() {
  const c = useTheme();
  const [expanded, setExpanded] = useState(false);

  return (
    <View style={styles.wrap}>
      <Pressable
        onPress={() => setExpanded((open) => !open)}
        accessibilityRole="button"
        accessibilityLabel="What happens to my statement?"
        accessibilityState={{ expanded }}
        style={styles.header}
      >
        <Text style={[styles.title, { color: c.ink }]}>What happens to my statement?</Text>
        <Ionicons name={expanded ? 'chevron-up' : 'chevron-down'} size={15} color={c.muted} />
      </Pressable>
      {expanded ? (
        <View style={styles.body}>
          {LINES.map((line) => (
            <Text key={line.lead} testID="statement-handling-item" style={[styles.item, { color: c.muted }]}>
              <Text style={{ color: c.ink, fontWeight: '600' }}>{line.lead}</Text> {line.rest}
            </Text>
          ))}
          <Text
            accessibilityRole="link"
            onPress={() => openWebUrl('/privacy#uploaded-statements')}
            style={[styles.item, { color: c.primary }]}
          >
            Read the full privacy policy
          </Text>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginTop: spacing.md },
  header: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: spacing.sm },
  title: { fontSize: 14, fontWeight: '600' },
  body: { gap: spacing.sm, paddingTop: spacing.xs },
  item: { fontSize: 13, lineHeight: 19 },
});
