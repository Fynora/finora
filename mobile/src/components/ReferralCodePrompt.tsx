import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from './AppModal';
import { useAuth } from '../context/AuthContext';
import { referralsApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { radius, spacing, useTheme } from '../theme';

/**
 * "Have a referral code?" -- asked once, right after a Google/Apple sign-up. Those screens never
 * had a code field (only the email sign-up form did), so a friend's referral was lost unless it
 * came through a link. AuthContext.referralPromptPending decides WHEN (a new Google/Apple account,
 * no code sent); this decides WHETHER, from the server: canApplyCode is false once the account has
 * a referral or has subscribed, and then there is nothing to ask.
 *
 * Adding a code or skipping clears the prompt for good (dismissReferralPrompt). Skipping is not a
 * loss: Refer & Earn keeps an "Enter a friend's code" box for as long as canApplyCode holds.
 */
export function ReferralCodePrompt() {
  const { referralPromptPending, dismissReferralPrompt } = useAuth();
  const queryClient = useQueryClient();
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const { data } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
    enabled: referralPromptPending,
  });

  // Already referred (e.g. a code given some other way) or already subscribed: nothing to offer,
  // so the pending flag is spent rather than left to re-check on every launch.
  useEffect(() => {
    if (referralPromptPending && data && !data.canApplyCode) dismissReferralPrompt();
  }, [referralPromptPending, data, dismissReferralPrompt]);

  const apply = useMutation({
    mutationFn: (value: string) => referralsApi.applyCode(value),
    onMutate: () => setError(null),
    onSuccess: () => {
      dismissReferralPrompt();
      void queryClient.invalidateQueries({ queryKey: ['referrals-mine'] });
    },
    onError: (err) => setError(toUserMessage(err, 'Could not add this code. Try again.')),
  });

  if (!referralPromptPending || !data?.canApplyCode) return null;

  const trimmed = code.trim();
  const submitting = apply.isPending;
  const canSubmit = trimmed.length > 0 && !submitting;

  return (
    // Every way out is closed while the request is in flight, so the sheet can't be dismissed as
    // "skipped" while the code is actually being added.
    <AppModal visible animationType="slide" transparent onRequestClose={submitting ? () => {} : dismissReferralPrompt}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable
          style={styles.backdrop}
          onPress={submitting ? undefined : dismissReferralPrompt}
          disabled={submitting}
          accessibilityLabel="Skip referral code"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <Text style={[styles.title, { color: c.ink }]}>Have a referral code?</Text>
          <Text style={[styles.subtitle, { color: c.muted }]}>
            If a friend invited you, enter their code so it counts for them. You can only use one code.
          </Text>

          <TextInput
            value={code}
            onChangeText={(v) => setCode(v.toUpperCase())}
            autoCapitalize="characters"
            autoCorrect={false}
            placeholder="Referral code"
            placeholderTextColor={c.muted}
            style={[styles.input, { color: c.ink, backgroundColor: c.inputBg, borderColor: error ? c.danger : c.border }]}
            accessibilityLabel="Referral code"
            onSubmitEditing={() => { if (canSubmit) apply.mutate(trimmed); }}
            returnKeyType="done"
          />
          <Text style={[styles.error, { color: c.danger }]} numberOfLines={2}>{error ?? ''}</Text>

          <View style={styles.actions}>
            <Pressable
              onPress={dismissReferralPrompt}
              disabled={submitting}
              style={[styles.button, styles.skip, { borderColor: c.border }, submitting && styles.disabled]}
              accessibilityRole="button"
              accessibilityState={{ disabled: submitting }}
            >
              <Text style={[styles.skipText, { color: c.muted }]}>Skip</Text>
            </Pressable>
            <Pressable
              onPress={() => { if (canSubmit) apply.mutate(trimmed); }}
              disabled={!canSubmit}
              style={[styles.button, { backgroundColor: c.primary }, !canSubmit && styles.disabled]}
              accessibilityRole="button"
              accessibilityState={{ disabled: !canSubmit, busy: submitting }}
            >
              <Text style={[styles.confirmText, { color: c.onPrimary }]}>{submitting ? 'Adding…' : 'Add code'}</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  title: { fontSize: 17, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 4, lineHeight: 18 },
  input: {
    marginTop: spacing.md,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    minHeight: 48,
    fontSize: 16,
    letterSpacing: 1,
  },
  error: { fontSize: 12, marginTop: 4, minHeight: 16 },
  actions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  button: { flex: 1, minHeight: 48, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  skip: { borderWidth: 1 },
  skipText: { fontSize: 15, fontWeight: '600' },
  confirmText: { fontSize: 15, fontWeight: '700' },
  disabled: { opacity: 0.5 },
});
