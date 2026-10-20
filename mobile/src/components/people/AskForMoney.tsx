import { useRef, useState, type ReactNode } from 'react';
import { KeyboardAvoidingView, Pressable, Share, StyleSheet, Text, TextInput } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useQueryClient } from '@tanstack/react-query';
import { peopleApi } from '../../api/endpoints';
import { AppModal } from '../AppModal';
import { Button } from '../Button';
import { GlassSurface } from '../GlassSurface';
import { fmtCurrency } from '../../lib/format';
import { hapticError } from '../../lib/haptics';
import { radius, spacing, typography, useTheme } from '../../theme';

/** name@bank: a handle, then the bank's short name. The same rule the server applies. */
export const UPI_ID = /^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$/;

/** A UPI request for what is owed, paid to the user's own UPI id. */
export function upiRequestUrl(ownUpiId: string, owed: number): string {
  return `upi://pay?pa=${ownUpiId}&am=${owed.toFixed(2)}&cu=INR`;
}

interface Ask {
  label: string;
  owed: number;
}

/**
 * "Ask <person>" (spec §6 E): a UPI request built from the user's own UPI id, which is asked for
 * once and saved. The user shares it themselves through the system share sheet; Fynora sends
 * nothing. Returns the call and the one-time prompt, which the host renders.
 */
export function useAskForMoney(): { ask: (who: Ask) => void; prompt: ReactNode } {
  const queryClient = useQueryClient();
  const [asking, setAsking] = useState<Ask | null>(null);
  const opening = useRef(false);

  async function ask(who: Ask) {
    if (opening.current) return;
    opening.current = true;
    try {
      const saved = await queryClient.fetchQuery({
        queryKey: ['people', 'preferences'], queryFn: () => peopleApi.preferences(), staleTime: 60_000,
      }).catch(() => null);
      if (saved?.ownUpiId) await Share.share({ message: upiRequestUrl(saved.ownUpiId, who.owed) });
      else setAsking(who);
    } finally {
      opening.current = false;
    }
  }

  return {
    ask: (who) => void ask(who),
    prompt: asking ? (
      <UpiIdPrompt
        who={asking}
        onClose={() => setAsking(null)}
        onSaved={async (ownUpiId) => {
          queryClient.setQueryData(['people', 'preferences'], { ownUpiId });
          const who = asking;
          setAsking(null);
          await Share.share({ message: upiRequestUrl(ownUpiId, who.owed) });
        }}
      />
    ) : null,
  };
}

function UpiIdPrompt({ who, onClose, onSaved }: { who: Ask; onClose: () => void; onSaved: (ownUpiId: string) => Promise<void> }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const upi = value.trim();
    if (!UPI_ID.test(upi)) {
      setError("That doesn't look like a UPI id, such as name@bank.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await peopleApi.setOwnUpiId(upi);
      await onSaved(upi);
    } catch {
      hapticError();
      setError("Couldn't save it. Try again.");
      setSaving(false);
    }
  }

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior="padding">
        <Pressable
          style={styles.backdrop}
          onPress={saving ? undefined : onClose}
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]} accessibilityViewIsModal>
          <Text style={[typography.cardTitle, { color: c.ink }]} accessibilityRole="header">{`Ask ${who.label} for ${fmtCurrency(who.owed)}`}</Text>
          <Text style={[typography.bodyS, styles.line, { color: c.mutedInk }]}>
            Your UPI id goes in the request, so the money comes to you. You share it yourself; Fynora sends nothing. Asked once.
          </Text>
          <TextInput
            value={value}
            onChangeText={setValue}
            placeholder="name@bank"
            placeholderTextColor={c.mutedInk}
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            accessibilityLabel="Your UPI id"
            style={[styles.input, { backgroundColor: c.inputBg, borderColor: error ? c.danger : c.border, color: c.ink }]}
            onSubmitEditing={() => void save()}
            returnKeyType="done"
          />
          {error ? <Text accessibilityRole="alert" style={[typography.bodyS, styles.line, { color: c.dangerInk }]}>{error}</Text> : null}
          <Button label="Save and share" onPress={() => void save()} loading={saving} />
          <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
        </GlassSurface>
      </KeyboardAvoidingView>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl, paddingHorizontal: spacing.md, paddingTop: spacing.md,
    gap: spacing.sm,
  },
  line: { marginTop: 2 },
  input: { minHeight: 44, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.sm },
});
