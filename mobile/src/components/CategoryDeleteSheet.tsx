import { useEffect, useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from './Button';
import { CategoryPickerModal } from './CategoryPickerModal';
import { categoriesApi, type CategoryOption, type CategoryUsage } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';

interface Props {
  category: CategoryOption;
  onClose: () => void;
  onDeleted: () => void;
}

/**
 * Mobile counterpart to frontend/src/components/CategoryDeleteDialog.tsx -- fetches what a delete
 * would touch (transactions/budget/rules/learning rows), and requires a reassignment target
 * before allowing the delete IF anything depends on this category (matching
 * CategoryService.delete's own server-side requirement -- this is a UX head start on that check,
 * not a substitute for it).
 */
export function CategoryDeleteSheet({ category, onClose, onDeleted }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [usage, setUsage] = useState<CategoryUsage | null>(null);
  const [usageFailed, setUsageFailed] = useState(false);
  const [target, setTarget] = useState<CategoryOption | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // No reset-to-false for usageFailed on entry: this sheet is always a fresh mount per delete
  // attempt (the parent renders it as `deleting ? <CategoryDeleteSheet ... /> : null`, closing
  // and reopening rather than swapping `category` on an already-mounted instance), so the
  // useState default above already covers it -- a synchronous setState at the top of an effect
  // body is exactly what react-hooks/set-state-in-effect flags as unnecessary here.
  useEffect(() => {
    let cancelled = false;
    categoriesApi.usage(category.id)
      .then((u) => { if (!cancelled) setUsage(u); })
      .catch(() => { if (!cancelled) { setUsage(null); setUsageFailed(true); } });
    return () => { cancelled = true; };
  }, [category.id]);

  const hasDependents = usage != null
    && (usage.transactionCount > 0 || usage.hasBudget || usage.ruleCount > 0 || usage.learningRowCount > 0);
  const canDelete = usage != null && (!hasDependents || target != null);

  async function confirm() {
    if (!canDelete) return;
    setError(null);
    await singleFlight(async () => {
      setDeleting(true);
      try {
        await categoriesApi.delete(category.id, target?.id);
        onDeleted();
      } catch (e) {
        setError(toUserMessage(e, 'Could not delete this category.'));
      } finally {
        setDeleting(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={deleting ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={deleting ? undefined : onClose}
          disabled={deleting}
          accessibilityLabel="Close delete category"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>
              Delete <Text style={{ fontWeight: '700' }}>{category.name}</Text>?
            </Text>

            {usage ? (
              <View style={styles.usageList}>
                <Text style={[styles.usageItem, { color: c.muted }]}>
                  {usage.transactionCount} transaction{usage.transactionCount === 1 ? '' : 's'}
                </Text>
                {usage.hasBudget ? <Text style={[styles.usageItem, { color: c.muted }]}>1 budget</Text> : null}
                {usage.ruleCount > 0 ? (
                  <Text style={[styles.usageItem, { color: c.muted }]}>
                    {usage.ruleCount} rule{usage.ruleCount === 1 ? '' : 's'}
                  </Text>
                ) : null}
                {usage.learningRowCount > 0 ? (
                  <Text style={[styles.usageItem, { color: c.muted }]}>
                    {usage.learningRowCount} learned merchant{usage.learningRowCount === 1 ? '' : 's'}
                  </Text>
                ) : null}
              </View>
            ) : null}

            {usageFailed ? (
              <Text style={[styles.warning, { color: c.warningInk }]}>
                Couldn&apos;t check what this category is used for — please try again.
              </Text>
            ) : null}

            {hasDependents ? (
              <View style={styles.reassign}>
                <Text style={[styles.sectionLabel, { color: c.muted }]}>Move everything to</Text>
                <Pressable
                  onPress={() => setPickerOpen(true)}
                  style={[styles.picker, { borderColor: c.border }]}
                  accessibilityRole="button"
                >
                  <Text style={[styles.pickerValue, { color: target ? c.ink : c.muted }]}>
                    {target?.name ?? 'Choose a category…'}
                  </Text>
                </Pressable>
              </View>
            ) : null}

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
            <View style={styles.action}>
              <Button
                label={deleting ? 'Deleting…' : 'Delete'}
                onPress={() => void confirm()}
                loading={deleting}
                disabled={!canDelete}
              />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={deleting} />
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <CategoryPickerModal
        visible={pickerOpen}
        selectedName={target?.name ?? null}
        excludeCategoryId={category.id}
        allowManage={false}
        onSelect={setTarget}
        onClose={() => setPickerOpen(false)}
      />
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
  title: { fontSize: 16, marginBottom: spacing.sm },
  usageList: { gap: 2, marginBottom: spacing.sm },
  usageItem: { fontSize: 13 },
  warning: { fontSize: 12, marginBottom: spacing.sm },
  reassign: { marginBottom: spacing.sm },
  sectionLabel: { fontSize: 11, textTransform: 'uppercase', marginBottom: 4 },
  picker: { borderWidth: 1, borderRadius: radius.md, padding: 12 },
  pickerValue: { fontSize: 14 },
  error: { fontSize: 13, marginBottom: spacing.sm },
  action: { marginTop: spacing.sm, gap: spacing.xs, marginBottom: spacing.sm },
});
