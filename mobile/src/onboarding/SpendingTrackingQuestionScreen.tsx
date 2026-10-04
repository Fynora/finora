import { useState } from 'react';
import { Pressable, ScrollView, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';

/** The answers, in the order shown -- the same as the web app's. Keys are the backend's
 *  SpendingTrackingMethod names. */
export const SPENDING_TRACKING_OPTIONS: { key: string; label: string }[] = [
  { key: 'NOT_TRACKED', label: "I don't really track it" },
  { key: 'IN_MY_HEAD', label: 'Roughly, in my head' },
  { key: 'PAPER', label: 'In a notebook or on paper' },
  { key: 'SPREADSHEET', label: 'In a spreadsheet (Excel, Google Sheets)' },
  { key: 'EXPENSE_APP', label: 'With an expense or budgeting app' },
  { key: 'BANK_APP', label: "With my bank's app or statements" },
  { key: 'OTHER', label: 'Something else' },
];

interface Props {
  onSubmit: (method: string) => Promise<void>;
  /** The way out without answering -- like VerifyPhoneScreen's, so nobody is trapped on a shared
   *  device. It never lets anyone past the question. */
  onSignOut: () => void;
}

/**
 * The required question "How do you keep track of your spending today?". One answer, and nothing
 * moves on until one is chosen and saved: there is no skip. Shown by RootNavigator while
 * useSpendingQuestion says it is unanswered.
 */
export function SpendingTrackingQuestionScreen({ onSubmit, onSignOut }: Props) {
  const c = useTheme();
  const [selected, setSelected] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!selected || saving) return;
    setSaving(true);
    setError(null);
    try {
      await onSubmit(selected);
    } catch {
      setError('Something went wrong. Check your connection and try again.');
      setSaving(false);
    }
  }

  return (
    <ScrollView contentContainerStyle={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>How do you keep track of your spending today?</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Pick the one closest to what you do now. It helps us build Fynora around how people really manage money.
      </Text>
      <View accessibilityRole="radiogroup">
        {SPENDING_TRACKING_OPTIONS.map((opt) => {
          const active = selected === opt.key;
          return (
            <Pressable
              key={opt.key}
              accessibilityRole="radio"
              accessibilityState={{ checked: active }}
              onPress={() => setSelected(opt.key)}
              style={[styles.option, { borderColor: active ? c.primary : c.border }]}
            >
              <Text style={{ color: c.ink }}>{opt.label}</Text>
            </Pressable>
          );
        })}
      </View>
      {error ? <Text accessibilityRole="alert" style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
      <View style={{ height: 16 }} />
      <Button label="Continue" onPress={() => void submit()} disabled={!selected} loading={saving} />
      <View style={{ height: 8 }} />
      <Button label="Sign out" variant="link" onPress={onSignOut} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1, alignItems: 'stretch', justifyContent: 'center', padding: 24 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 8, textAlign: 'center' },
  subtitle: { fontSize: 13, textAlign: 'center', marginBottom: 20 },
  option: { borderWidth: 1, borderRadius: 10, padding: 14, marginBottom: 10 },
  error: { fontSize: 13, textAlign: 'center', marginTop: 4 },
});
