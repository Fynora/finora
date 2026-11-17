import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from './AppModal';
import { Button } from './Button';
import { GlassSurface } from './GlassSurface';
import { DISCARD_REASONS, type DiscardReason } from '../lib/discardReasons';
import { radius, spacing, useTheme } from '../theme';

interface Props {
  fileName: string;
  onDiscard: (reason: DiscardReason | null) => void;
  onClose: () => void;
}

/**
 * "Discard this import?" with an optional one-tap reason (Sid, 2026-11-17). Replaces the plain
 * alert, which cannot hold choices. Nothing has to be chosen; tapping the chosen reason again
 * clears it.
 */
export function DiscardImportSheet({ fileName, onDiscard, onClose }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [reason, setReason] = useState<DiscardReason | null>(null);

  return (
    <AppModal visible animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Keep this import" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.md }]}>
          <Text accessibilityRole="header" style={[styles.title, { color: c.ink }]}>Discard this import?</Text>
          <Text style={[styles.body, { color: c.muted }]}>
            {`"${fileName}" and everything reviewed so far will be removed. The statement itself isn't affected.`}
          </Text>
          <Text style={[styles.label, { color: c.ink }]}>
            Why? <Text style={{ color: c.muted }}>(optional)</Text>
          </Text>
          <View style={styles.chips}>
            {DISCARD_REASONS.map((r) => {
              const selected = reason === r.value;
              return (
                <Pressable
                  key={r.value}
                  testID={`discard-reason-${r.value}`}
                  accessibilityRole="button"
                  accessibilityState={{ selected }}
                  onPress={() => setReason(selected ? null : r.value)}
                  style={[
                    styles.chip,
                    { borderColor: selected ? c.primary : c.border, backgroundColor: selected ? c.primary : 'transparent' },
                  ]}
                >
                  <Text style={[styles.chipText, { color: selected ? c.onPrimary : c.ink }]}>{r.label}</Text>
                </Pressable>
              );
            })}
          </View>
          <View style={styles.actions}>
            <Button label="Discard" testID="discard-import-confirm" onPress={() => onDiscard(reason)} />
            <Button label="Cancel" variant="link" onPress={onClose} />
          </View>
        </GlassSurface>
      </View>
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
  title: { fontSize: 16, fontWeight: '600', marginBottom: spacing.xs },
  body: { fontSize: 14, marginBottom: spacing.md },
  label: { fontSize: 13, fontWeight: '500', marginBottom: spacing.xs },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  chip: { borderWidth: 1, borderRadius: 999, paddingHorizontal: 12, paddingVertical: 6 },
  chipText: { fontSize: 13 },
  actions: { marginTop: spacing.md, gap: spacing.xs },
});
