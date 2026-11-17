import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Button } from '../Button';
import { TextField } from '../TextField';
import { CategoryPickerModal } from '../CategoryPickerModal';
import { radius, spacing, typography, useTheme } from '../../theme';
import { hapticWarning } from '../../lib/haptics';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { parsePositiveAmount } from '../../lib/validation';
import { BudgetSheet } from './BudgetSheet';

/**
 * New budget. The category comes from CategoryPickerModal (which can also create one), never free
 * text. The picker opens over this sheet and closes on its own before anything here closes, so the
 * two never unmount together (that combination has frozen iOS touches before).
 * Mounted fresh each time it opens (BudgetsScreen renders it only while open).
 */
export function NewBudgetSheet({ onClose, onSave }: {
  onClose: () => void; onSave: (categoryName: string, limit: number) => Promise<void>;
}) {
  const c = useTheme();
  const [category, setCategory] = useState<string | null>(null);
  const [limit, setLimit] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [categoryError, setCategoryError] = useState<string | null>(null);
  const [limitError, setLimitError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const singleFlight = useSingleFlight();

  async function save() {
    await singleFlight(async () => {
      setFormError(null);
      if (!category) {
        setCategoryError('Pick a category first.');
        hapticWarning();
        return;
      }
      const amount = parsePositiveAmount(limit);
      if (amount === null) {
        setLimitError('Monthly limit must be a number greater than zero.');
        hapticWarning();
        return;
      }
      setSaving(true);
      try {
        await onSave(category, amount);
      } catch (e) {
        setFormError(toUserMessage(e, 'Could not save this budget. Try again.'));
        setSaving(false);
      }
    });
  }

  return (
    <BudgetSheet visible title="New budget" onClose={onClose} closeDisabled={saving}>
      <View>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Category</Text>
        <Pressable
          onPress={() => setPickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: categoryError ? c.danger : c.border }]}
          accessibilityRole="button"
          accessibilityLabel={category ? `Category: ${category}. Change` : 'Choose a category'}
        >
          <Text style={[styles.pickerText, { color: category ? c.ink : c.muted }]}>{category ?? 'Choose a category'}</Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        {categoryError ? <Text style={[typography.bodyS, styles.fieldError, { color: c.dangerInk }]}>{categoryError}</Text> : null}
      </View>
      <TextField
        label="Monthly limit"
        value={limit}
        onChangeText={(v) => { setLimit(v); setLimitError(null); }}
        keyboardType="decimal-pad"
        placeholder="0"
        prefix="₹"
        error={limitError ?? undefined}
      />
      {formError ? <Text style={[typography.bodyS, { color: c.dangerInk }]}>{formError}</Text> : null}
      <Button label={saving ? 'Saving…' : 'Save budget'} onPress={() => void save()} loading={saving} />

      {/* CategoryPickerModal, not OptionPickerModal: a budget for a category that doesn't exist
          yet is a real case, and the picker can create one. */}
      <CategoryPickerModal
        visible={pickerOpen}
        title="Category"
        selectedName={category}
        onSelect={(cat) => {
          setCategory(cat.name);
          setCategoryError(null);
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </BudgetSheet>
  );
}

const styles = StyleSheet.create({
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  picker: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48,
  },
  pickerText: { fontSize: 15 },
  chevron: { fontSize: 20, lineHeight: 20 },
  fieldError: { marginTop: spacing.xs },
});
