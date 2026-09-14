import { useRef, useState } from 'react';
import {
  KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, EmptyState } from '../components/Card';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';
import { fynChatApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { spacing, radius, useTheme } from '../theme';

interface ChatTurn {
  role: 'user' | 'assistant';
  content: string;
}

// One example per real chat tool (GET_BALANCE, GET_RECENT_TRANSACTIONS_SUMMARY,
// GET_SPEND_BY_CATEGORY, GET_BUDGET_STATUS -- see FynChatOrchestrationService's tool list) so a
// tap always has a genuine capability behind it, not an invented example. "Dining" is a guess at
// a category name someone might actually have, not a guarantee -- if it doesn't match, the tool's
// own no-match response now lists the user's real category names so Fyn can recover in the same
// turn (see FynGetSpendByCategoryTool's own noMatchMessage). Ported verbatim from web's identical
// FynWidget.tsx.
const SUGGESTED_QUESTIONS = [
  "What's my balance?",
  'What did I spend this month?',
  'How much did I spend on Dining?',
  'How are my budgets doing?',
];

/**
 * Mobile counterpart to frontend/src/pages/Fyn.tsx (Fyn Phase 5) -- same FYN_CHAT gate, same
 * per-visit conversation (a reload/re-open starts fresh; conversationId only threads follow-up
 * messages within one visit, same reasoning as web's own doc comment). Reached from the More menu
 * like every other FYN_CHAT-adjacent screen (AdvancedReports), since mobile has no free top-level
 * tab slot for it.
 *
 * usePreventScreenCapture, like InsightsScreen/AdvancedReportsScreen: Fyn narrates real balance,
 * spend, and budget figures, the same screenshot/recording exposure those screens already guard.
 */
export function FynScreen() {
  usePreventScreenCapture();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const navigation = useNavigation();

  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top }]}>
      <View style={styles.titleRow}>
        <Pressable onPress={() => navigation.goBack()} hitSlop={10} accessibilityRole="button" accessibilityLabel="Back">
          <Ionicons name="chevron-back" size={22} color={c.ink} />
        </Pressable>
        <View style={styles.titleTextCol}>
          <View style={styles.titleWithIcon}>
            <Ionicons name="chatbubbles" size={18} color={c.primary} />
            <Text style={[styles.title, { color: c.ink }]}>Ask Fyn</Text>
          </View>
          <Text style={[styles.subtitle, { color: c.muted }]}>
            Fyn answers questions about your own balance, spending, and budgets -- it narrates
            your real data, it doesn't give financial advice.
          </Text>
        </View>
      </View>

      <PremiumFeatureGate featureKey="FYN_CHAT" fallback={<UpgradePrompt />}>
        <FynChat />
      </PremiumFeatureGate>
    </View>
  );
}

/** More context than PremiumFeatureGate's own generic default, since this gates an entire screen
 *  rather than one widget -- same reasoning as AdvancedReportsScreen's identical UpgradePrompt. */
function UpgradePrompt() {
  const c = useTheme();
  return (
    <Card style={styles.upgradePrompt}>
      <EmptyState message="Ask Fyn is a premium feature -- a chat assistant that answers questions about your own balance, spending, and budgets, built from your real transaction history." />
      <Text style={[styles.upgradeHint, { color: c.primary }]}>Open Settings › Subscription to view plans.</Text>
    </Card>
  );
}

function FynChat() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const conversationId = useRef<string | undefined>(undefined);
  const scrollRef = useRef<ScrollView>(null);

  async function send(overrideText?: string) {
    const message = (overrideText ?? input).trim();
    if (!message || sending) return;
    setInput('');
    setError(null);
    setTurns((t) => [...t, { role: 'user', content: message }]);
    setSending(true);
    try {
      const result = await fynChatApi.send(message, conversationId.current);
      conversationId.current = result.conversationId;
      setTurns((t) => [...t, { role: 'assistant', content: result.reply }]);
    } catch (err) {
      setError(toUserMessage(err, 'Fyn could not answer that right now.'));
    } finally {
      setSending(false);
      // Content just changed height (a new turn, or the "Fyn is thinking…" line clearing) --
      // scrollToEnd needs to run after that layout pass, not this one, so it's deferred a tick.
      setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 50);
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView
        ref={scrollRef}
        style={styles.flex}
        contentContainerStyle={styles.chatContent}
        onContentSizeChange={() => scrollRef.current?.scrollToEnd({ animated: true })}
        accessibilityRole="none"
        accessibilityLabel="Conversation with Fyn"
      >
        {turns.length === 0 ? (
          <View style={styles.suggestions}>
            <Text style={[styles.suggestionsLabel, { color: c.muted }]}>Try asking:</Text>
            {SUGGESTED_QUESTIONS.map((question) => (
              <Pressable
                key={question}
                onPress={() => void send(question)}
                disabled={sending}
                style={[styles.suggestionChip, { borderColor: c.border, backgroundColor: c.bg, opacity: sending ? 0.5 : 1 }]}
                accessibilityRole="button"
                accessibilityLabel={question}
              >
                <Text style={[styles.suggestionText, { color: c.ink }]}>{question}</Text>
              </Pressable>
            ))}
          </View>
        ) : null}
        {turns.map((turn, i) => (
          <View
            key={i}
            style={[styles.turnRow, turn.role === 'user' ? styles.turnRowUser : styles.turnRowAssistant]}
          >
            <Text
              style={[
                styles.turnBubble,
                turn.role === 'user'
                  ? { backgroundColor: c.primary, color: '#fff' }
                  : { backgroundColor: c.bg, borderWidth: 1, borderColor: c.border, color: c.ink },
              ]}
            >
              {turn.content}
            </Text>
          </View>
        ))}
        {sending ? <Text style={[styles.thinking, { color: c.muted }]}>Fyn is thinking…</Text> : null}
      </ScrollView>

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      <View style={[styles.inputRow, { borderTopColor: c.border, paddingBottom: insets.bottom + spacing.sm }]}>
        <TextInput
          value={input}
          onChangeText={setInput}
          onSubmitEditing={() => void send()}
          placeholder="Ask about your balance, spending, or budgets…"
          placeholderTextColor={c.muted}
          editable={!sending}
          style={[styles.input, { borderColor: c.border, backgroundColor: c.bg, color: c.ink }]}
          returnKeyType="send"
          accessibilityLabel="Message"
        />
        <Pressable
          onPress={() => void send()}
          disabled={sending || !input.trim()}
          style={[styles.sendButton, { backgroundColor: c.primary, opacity: sending || !input.trim() ? 0.5 : 1 }]}
          accessibilityRole="button"
          accessibilityLabel="Send"
        >
          <Ionicons name="send" size={16} color="#fff" />
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  titleRow: {
    flexDirection: 'row', alignItems: 'flex-start', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingTop: spacing.md, marginBottom: spacing.md,
  },
  titleTextCol: { flex: 1 },
  titleWithIcon: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  title: { fontSize: 20, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4, lineHeight: 18 },
  upgradePrompt: { marginHorizontal: spacing.md, marginBottom: spacing.md },
  upgradeHint: { fontSize: 12, fontWeight: '600', marginTop: spacing.sm, textAlign: 'center' },
  chatContent: { padding: spacing.md, paddingBottom: spacing.sm, flexGrow: 1 },
  suggestions: { marginTop: spacing.sm, gap: spacing.xs },
  suggestionsLabel: { fontSize: 13, marginBottom: 2 },
  suggestionChip: { borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: 8 },
  suggestionText: { fontSize: 14 },
  turnRow: { marginBottom: spacing.sm, flexDirection: 'row' },
  turnRowUser: { justifyContent: 'flex-end' },
  turnRowAssistant: { justifyContent: 'flex-start' },
  turnBubble: {
    maxWidth: '80%', borderRadius: radius.lg, paddingHorizontal: spacing.sm, paddingVertical: 8,
    fontSize: 14, lineHeight: 19,
  },
  thinking: { fontSize: 13, paddingHorizontal: spacing.md, marginBottom: spacing.sm },
  error: { fontSize: 13, paddingHorizontal: spacing.md, marginBottom: spacing.sm },
  inputRow: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingVertical: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth,
  },
  input: {
    flex: 1, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: 8,
    fontSize: 14,
  },
  sendButton: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center',
  },
});
