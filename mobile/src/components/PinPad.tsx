import { useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { spacing, useTheme } from '../theme';

export const PIN_LENGTH = 4;

const ROWS = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
];

/**
 * A 4-digit PIN entry pad: dots that show how many digits are in (never which), a 3x4 keypad, and
 * an optional Face ID / fingerprint key in the bottom-left. Hands the PIN to `onComplete` the
 * moment the fourth digit lands and starts empty again, so a wrong PIN can be retyped at once.
 *
 * Purely presentational: checking, counting tries and signing out belong to the caller.
 */
export function PinPad({
  title,
  subtitle,
  error,
  busy = false,
  onComplete,
  onBiometric,
  footer,
}: {
  title: string;
  subtitle?: string;
  error?: string | null;
  busy?: boolean;
  onComplete: (pin: string) => void;
  onBiometric?: () => void;
  footer?: ReactNode;
}) {
  const c = useTheme();
  const [digits, setDigits] = useState('');

  function pressDigit(d: string) {
    if (busy) return;
    const next = digits + d;
    if (next.length < PIN_LENGTH) {
      setDigits(next);
      return;
    }
    setDigits('');
    onComplete(next);
  }

  function pressDelete() {
    if (busy) return;
    setDigits((current) => current.slice(0, -1));
  }

  const key = (label: string, onPress: () => void, content: ReactNode, accessibilityLabel = label) => (
    <Pressable
      key={accessibilityLabel}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      onPress={onPress}
      style={({ pressed }) => [styles.key, { borderColor: c.border, opacity: pressed ? 0.6 : 1 }]}
    >
      {content}
    </Pressable>
  );
  const digitKey = (d: string) =>
    key(d, () => pressDigit(d), <Text style={[styles.keyText, { color: c.ink }]}>{d}</Text>);

  return (
    <View style={styles.container}>
      <Ionicons name="lock-closed" size={40} color={c.primary} />
      <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">{title}</Text>
      {subtitle ? <Text style={[styles.subtitle, { color: c.muted }]}>{subtitle}</Text> : null}
      <View
        style={styles.dots}
        accessible
        accessibilityLabel={`${digits.length} of ${PIN_LENGTH} digits entered`}
      >
        {Array.from({ length: PIN_LENGTH }, (_, i) => (
          <View
            key={i}
            style={[
              styles.dot,
              { borderColor: c.ink, backgroundColor: i < digits.length ? c.ink : 'transparent' },
            ]}
          />
        ))}
      </View>
      <Text style={[styles.error, { color: c.dangerInk }]} accessibilityLiveRegion="polite">
        {error ?? ' '}
      </Text>
      <View style={styles.pad}>
        {ROWS.map((row) => (
          <View key={row.join('')} style={styles.row}>{row.map(digitKey)}</View>
        ))}
        <View style={styles.row}>
          {onBiometric
            ? key('biometric', onBiometric, <Ionicons name="finger-print" size={28} color={c.ink} />,
                'Unlock with Face ID or fingerprint')
            : <View style={styles.keySpacer} />}
          {digitKey('0')}
          {key('Delete', pressDelete, <Ionicons name="backspace-outline" size={26} color={c.ink} />)}
        </View>
      </View>
      {footer}
    </View>
  );
}

const KEY_SIZE = 72;

const styles = StyleSheet.create({
  container: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.xl, gap: spacing.sm },
  title: { fontSize: 20, fontWeight: '700', marginTop: spacing.md, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center' },
  dots: { flexDirection: 'row', gap: spacing.md, marginTop: spacing.lg },
  dot: { width: 14, height: 14, borderRadius: 7, borderWidth: 1.5 },
  error: { fontSize: 14, minHeight: 20, textAlign: 'center' },
  pad: { gap: spacing.md, marginTop: spacing.sm },
  row: { flexDirection: 'row', gap: spacing.lg },
  key: {
    width: KEY_SIZE,
    height: KEY_SIZE,
    borderRadius: KEY_SIZE / 2,
    borderWidth: StyleSheet.hairlineWidth,
    alignItems: 'center',
    justifyContent: 'center',
  },
  keySpacer: { width: KEY_SIZE, height: KEY_SIZE },
  keyText: { fontSize: 28, fontWeight: '500' },
});
