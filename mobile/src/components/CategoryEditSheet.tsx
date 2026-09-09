import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery } from '@tanstack/react-query';
import { Button } from './Button';
import { TextField } from './TextField';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { iconNameFor } from '../lib/categoryIcons';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';

interface Props {
  mode: 'create' | 'edit';
  initialName: string;
  /** Required for mode 'edit' -- its id is what gets PATCHed, and its icon/color seed the pickers
   *  below (an edit that doesn't touch icon/color still resends the current ones, matching
   *  CategoryService.rename's own "update this field if non-null" semantics). */
  category?: CategoryOption;
  onClose: () => void;
  onSaved: (category: CategoryOption) => void;
}

/**
 * Mobile counterpart to frontend/src/components/CategoryCreateEditPanel.tsx -- name, an icon
 * grid, and a color row, drawn from the same curated CategoryPalette vocabulary
 * (categoriesApi.options(), cached with staleTime: Infinity for the identical reason web's does:
 * this is static reference data that only needs fetching once per session).
 */
export function CategoryEditSheet({ mode, initialName, category, onClose, onSaved }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();
  const optionsQ = useQuery({
    queryKey: ['category-options'],
    queryFn: () => categoriesApi.options(),
    staleTime: Infinity,
  });
  const options = optionsQ.data ?? { icons: [], colors: [] };

  const [name, setName] = useState(initialName);
  const [icon, setIcon] = useState(category?.icon ?? 'tag');
  const [color, setColor] = useState(category?.color ?? 'gray');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    if (!name.trim()) {
      setError('Enter a name for this category.');
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        const saved = mode === 'create'
          ? await categoriesApi.create(name.trim(), icon, color)
          : await categoriesApi.update(category!.id, { name: name.trim(), icon, color });
        onSaved(saved);
      } catch (e) {
        setError(toUserMessage(e, 'Could not save this category.'));
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={saving ? undefined : onClose}
          disabled={saving}
          accessibilityLabel="Close category editor"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>
              {mode === 'create' ? 'New Category' : 'Edit Category'}
            </Text>

            <TextField
              label="Name"
              value={name}
              onChangeText={(v) => { setName(v.slice(0, 80)); setError(null); }}
              autoFocus
              autoCapitalize="words"
              testID="category-name-input"
            />

            {optionsQ.isError ? (
              <View style={[styles.notice, { backgroundColor: c.warningBg }]}>
                <Text style={[styles.noticeText, { color: c.warningInk }]}>Couldn&apos;t load icons and colors.</Text>
                <Button label="Retry" variant="link" onPress={() => void optionsQ.refetch()} />
              </View>
            ) : null}

            <Text style={[styles.sectionLabel, { color: c.muted }]}>Icon</Text>
            <View style={styles.iconGrid}>
              {options.icons.map((i) => {
                const isSelected = icon === i.token;
                return (
                  <Pressable
                    key={i.token}
                    onPress={() => setIcon(i.token)}
                    accessibilityRole="button"
                    accessibilityLabel={i.label}
                    accessibilityState={{ selected: isSelected }}
                    style={[
                      styles.iconCell,
                      {
                        borderColor: isSelected ? c.primary : c.border,
                        backgroundColor: isSelected ? c.primaryLight : 'transparent',
                      },
                    ]}
                  >
                    <Ionicons name={iconNameFor(i.token)} size={18} color={isSelected ? c.primary : c.muted} />
                  </Pressable>
                );
              })}
            </View>

            <Text style={[styles.sectionLabel, { color: c.muted }]}>Color</Text>
            <View style={styles.colorRow}>
              {options.colors.map((col) => {
                const isSelected = color === col.token;
                return (
                  <Pressable
                    key={col.token}
                    onPress={() => setColor(col.token)}
                    accessibilityRole="button"
                    accessibilityLabel={col.token}
                    accessibilityState={{ selected: isSelected }}
                    style={[
                      styles.colorDot,
                      { backgroundColor: col.label },
                      isSelected && { borderWidth: 2, borderColor: c.ink },
                    ]}
                  >
                    {isSelected ? <Ionicons name="checkmark" size={14} color="#FFFFFF" /> : null}
                  </Pressable>
                );
              })}
            </View>

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
            <View style={styles.action}>
              <Button
                label={saving ? 'Saving…' : 'Save'}
                onPress={() => void save()}
                loading={saving}
                disabled={name.trim().length === 0}
              />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '88%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  title: { fontSize: 17, fontWeight: '700', marginBottom: spacing.sm },
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginTop: spacing.md, marginBottom: spacing.xs },
  iconGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  iconCell: {
    width: 40, height: 40, borderRadius: radius.md, borderWidth: 1,
    alignItems: 'center', justifyContent: 'center',
  },
  colorRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  colorDot: {
    width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center',
  },
  notice: { borderRadius: radius.md, padding: 10, marginTop: spacing.sm, gap: 2 },
  noticeText: { fontSize: 12 },
  error: { fontSize: 13, marginTop: spacing.sm },
  action: { marginTop: spacing.md, gap: spacing.xs, marginBottom: spacing.sm },
});
