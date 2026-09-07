import { Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { spacing, useTheme } from '../theme';

interface Props {
  onStart: () => void;
  onSkip: () => void;
  error?: string | null;
}

export function WelcomeScreen({ onStart, onSkip, error }: Props) {
  const c = useTheme();
  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>Welcome to Fynora 👋</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Take control of your finances in one place. Track spending, create budgets, monitor
        goals, and understand where your money goes with powerful insights.
      </Text>
      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
      <Button label="Start Setup" onPress={onStart} />
      <View style={{ height: spacing.sm }} />
      <Button label="Skip for Now" onPress={onSkip} variant="link" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 12, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: 24 },
  error: { fontSize: 13, textAlign: 'center', marginBottom: 16 },
});
