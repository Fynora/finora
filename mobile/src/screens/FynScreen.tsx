import { useEffect, useRef, useState } from 'react';
import {
  KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useNavigation } from '@react-navigation/native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { usePreventScreenCapture } from 'expo-screen-capture';
import * as StoreReview from 'expo-store-review';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card, EmptyState } from '../components/Card';
import { PremiumFeatureGate } from '../components/PremiumFeatureGate';
import { fynChatApi, type FynFeedback, type RNFile } from '../api/endpoints';
import { pickFynScreenshot, ScreenshotTooLargeError } from '../lib/fynScreenshot';
import { toUserMessage } from '../lib/apiError';
import { spacing, radius, useTheme } from '../theme';

interface ChatTurn {
  // '' for a just-sent user bubble that hasn't round-tripped yet -- the backend never hands the
  // client a user message's own id (only the assistant reply's, which is the only row feedback is
  // ever recorded against), so there's nothing real to put here for that role anyway.
  id: string;
  role: 'user' | 'assistant';
  content: string;
  feedback: FynFeedback | null;
  attachmentName?: string;
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

/** Best-effort -- StoreReview.hasAction() covers both "does this platform support the native
 *  review flow" and "is it actually configured/available right now" (TestFlight has no review
 *  flow, and Expo's own docs note isAvailableAsync() alone isn't a strong enough guard). No
 *  frequency-limiting of our own on top: requestReview() itself is already throttled by iOS/
 *  Android (Apple's own SKStoreReviewController caps prompts to a few times a year regardless of
 *  how often this is called), so this only decides WHEN to ask, never how often it's allowed to
 *  actually show. Deliberately never awaited by its caller -- a rating prompt is not something a
 *  thumbs-up tap should ever visibly wait on. */
async function maybeAskToRateFynora() {
  if (await StoreReview.hasAction()) {
    await StoreReview.requestReview();
  }
}

/**
 * Mobile counterpart to frontend/src/components/FynWidget.tsx -- same FYN_CHAT gate, same
 * resume-on-mount conversation continuity (GET /fyn/chat/history), same screenshot attach and
 * thumbs-up/down feedback. Reached from the More menu like every other FYN_CHAT-adjacent screen
 * (AdvancedReports), since mobile has no free top-level tab slot for it.
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
  const [attachedImage, setAttachedImage] = useState<RNFile | null>(null);
  // Starts true so the empty state (suggested questions) doesn't flash before a real, resumed
  // conversation has had a chance to load in -- see the effect below.
  const [loadingHistory, setLoadingHistory] = useState(true);
  const conversationId = useRef<string | undefined>(undefined);
  const scrollRef = useRef<ScrollView>(null);

  useEffect(() => {
    let cancelled = false;
    fynChatApi.history()
      .then((result) => {
        if (cancelled) return;
        if (result.conversationId) conversationId.current = result.conversationId;
        if (result.turns.length > 0) setTurns(result.turns);
      })
      // Silent: a failed history fetch just means this mount starts blank, exactly the behavior
      // every mount had before this existed -- not worth an error banner over.
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoadingHistory(false); });
    return () => { cancelled = true; };
  }, []);

  async function onAttachPress() {
    try {
      const file = await pickFynScreenshot();
      if (file) {
        setError(null);
        setAttachedImage(file);
      }
    } catch (err) {
      setError(err instanceof ScreenshotTooLargeError ? err.message : toUserMessage(err, 'Could not attach that screenshot.'));
    }
  }

  // overrideText only ever comes from the suggested-question chips, which never have an
  // attachment -- a plain typed message can still carry one.
  async function send(overrideText?: string) {
    const message = (overrideText ?? input).trim();
    if (sending || (!message && !attachedImage)) return;
    const image = attachedImage;
    setInput('');
    setAttachedImage(null);
    setError(null);
    setTurns((t) => [...t, {
      id: '',
      role: 'user',
      content: message || 'What can you tell me about this screenshot?',
      feedback: null,
      attachmentName: image?.name,
    }]);
    setSending(true);
    try {
      const result = image
        ? await fynChatApi.sendScreenshot(image, message, conversationId.current)
        : await fynChatApi.send(message, conversationId.current);
      conversationId.current = result.conversationId;
      setTurns((t) => [...t, { id: result.messageId, role: 'assistant', content: result.reply, feedback: null }]);
    } catch (err) {
      setError(toUserMessage(err, 'Fyn could not answer that right now.'));
    } finally {
      setSending(false);
      // Content just changed height (a new turn, or the "Fyn is thinking…" line clearing) --
      // scrollToEnd needs to run after that layout pass, not this one, so it's deferred a tick.
      setTimeout(() => scrollRef.current?.scrollToEnd({ animated: true }), 50);
    }
  }

  // Tapping the thumb already showing toggles it off (sends null) rather than staying stuck rated
  // -- see FynChatOrchestrationService.setMessageFeedback's own doc comment for why. Optimistic:
  // the icon updates immediately, and reverts if the request fails.
  async function rate(turn: ChatTurn, value: FynFeedback) {
    if (!turn.id) return;
    const next = turn.feedback === value ? null : value;
    const previous = turn.feedback;
    setTurns((ts) => ts.map((t) => (t.id === turn.id ? { ...t, feedback: next } : t)));
    try {
      await fynChatApi.setFeedback(turn.id, next);
      if (next === 'HELPFUL') void maybeAskToRateFynora();
    } catch {
      setTurns((ts) => ts.map((t) => (t.id === turn.id ? { ...t, feedback: previous } : t)));
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
        {loadingHistory && <Text style={[styles.thinking, { color: c.muted }]}>Loading conversation…</Text>}
        {!loadingHistory && turns.length === 0 ? (
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
            <View style={{ maxWidth: '80%' }}>
              {turn.attachmentName ? (
                <View style={[styles.attachmentChip, turn.role === 'user' ? styles.attachmentChipUser : null]}>
                  <Ionicons name="attach" size={11} color={c.muted} />
                  <Text style={[styles.attachmentChipText, { color: c.muted }]} numberOfLines={1}>{turn.attachmentName}</Text>
                </View>
              ) : null}
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
              {turn.role === 'assistant' && turn.id ? (
                <View style={styles.feedbackRow}>
                  <Pressable
                    onPress={() => void rate(turn, 'HELPFUL')}
                    hitSlop={8}
                    accessibilityRole="button"
                    accessibilityLabel="Helpful"
                    accessibilityState={{ selected: turn.feedback === 'HELPFUL' }}
                  >
                    <Ionicons
                      name={turn.feedback === 'HELPFUL' ? 'thumbs-up' : 'thumbs-up-outline'}
                      size={14}
                      color={turn.feedback === 'HELPFUL' ? c.primary : c.muted}
                    />
                  </Pressable>
                  <Pressable
                    onPress={() => void rate(turn, 'NOT_HELPFUL')}
                    hitSlop={8}
                    accessibilityRole="button"
                    accessibilityLabel="Not helpful"
                    accessibilityState={{ selected: turn.feedback === 'NOT_HELPFUL' }}
                  >
                    <Ionicons
                      name={turn.feedback === 'NOT_HELPFUL' ? 'thumbs-down' : 'thumbs-down-outline'}
                      size={14}
                      color={turn.feedback === 'NOT_HELPFUL' ? c.primary : c.muted}
                    />
                  </Pressable>
                </View>
              ) : null}
            </View>
          </View>
        ))}
        {sending ? <Text style={[styles.thinking, { color: c.muted }]}>Fyn is thinking…</Text> : null}
      </ScrollView>

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      {attachedImage ? (
        <View style={[styles.attachedPreview, { borderColor: c.border, backgroundColor: c.bg, marginHorizontal: spacing.md }]}>
          <Ionicons name="attach" size={14} color={c.muted} />
          <Text style={[styles.attachedPreviewText, { color: c.ink }]} numberOfLines={1}>{attachedImage.name}</Text>
          <Pressable onPress={() => setAttachedImage(null)} hitSlop={8} accessibilityRole="button" accessibilityLabel="Remove attachment">
            <Ionicons name="close" size={16} color={c.muted} />
          </Pressable>
        </View>
      ) : null}

      <View style={[styles.inputRow, { borderTopColor: c.border, paddingBottom: insets.bottom + spacing.sm }]}>
        <Pressable
          onPress={() => void onAttachPress()}
          disabled={sending}
          style={[styles.attachButton, { borderColor: c.border, backgroundColor: c.bg, opacity: sending ? 0.5 : 1 }]}
          accessibilityRole="button"
          accessibilityLabel="Attach a screenshot"
        >
          <Ionicons name="attach" size={18} color={c.muted} />
        </Pressable>
        <TextInput
          value={input}
          onChangeText={setInput}
          onSubmitEditing={() => void send()}
          placeholder={attachedImage ? 'Add a question about this screenshot (optional)…' : 'Ask about your balance, spending, or budgets…'}
          placeholderTextColor={c.muted}
          editable={!sending}
          style={[styles.input, { borderColor: c.border, backgroundColor: c.bg, color: c.ink }]}
          returnKeyType="send"
          accessibilityLabel="Message"
        />
        <Pressable
          onPress={() => void send()}
          disabled={sending || (!input.trim() && !attachedImage)}
          style={[styles.sendButton, { backgroundColor: c.primary, opacity: sending || (!input.trim() && !attachedImage) ? 0.5 : 1 }]}
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
    borderRadius: radius.lg, paddingHorizontal: spacing.sm, paddingVertical: 8,
    fontSize: 14, lineHeight: 19,
  },
  attachmentChip: { flexDirection: 'row', alignItems: 'center', gap: 4, marginBottom: 2 },
  attachmentChipUser: { justifyContent: 'flex-end' },
  attachmentChipText: { fontSize: 11 },
  feedbackRow: { flexDirection: 'row', gap: spacing.sm, marginTop: 4 },
  thinking: { fontSize: 13, paddingHorizontal: spacing.md, marginBottom: spacing.sm },
  error: { fontSize: 13, paddingHorizontal: spacing.md, marginBottom: spacing.sm },
  attachedPreview: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.xs, borderWidth: 1, borderRadius: radius.md,
    paddingHorizontal: spacing.sm, paddingVertical: 6, marginBottom: spacing.xs,
  },
  attachedPreviewText: { flex: 1, fontSize: 12 },
  inputRow: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm,
    paddingHorizontal: spacing.md, paddingVertical: spacing.sm, borderTopWidth: StyleSheet.hairlineWidth,
  },
  attachButton: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center', borderWidth: 1,
  },
  input: {
    flex: 1, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm, paddingVertical: 8,
    fontSize: 14,
  },
  sendButton: {
    width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center',
  },
});
