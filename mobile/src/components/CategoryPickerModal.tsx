import { useMemo, useState } from 'react';
import {
  FlatList, KeyboardAvoidingView, Modal, Platform, Pressable, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { colorHexFor, iconNameFor } from '../lib/categoryIcons';
import { hapticSelection } from '../lib/haptics';
import { radius, spacing, useTheme } from '../theme';
import { CategoryDeleteSheet } from './CategoryDeleteSheet';
import { CategoryEditSheet } from './CategoryEditSheet';

interface Props {
  visible: boolean;
  selectedName: string | null;
  onSelect: (category: CategoryOption) => void;
  onClose: () => void;
  /** Excludes one category from the list -- CategoryDeleteSheet's reassign-to picker uses this so
   *  the category being deleted can't be offered as its own destination. */
  excludeCategoryId?: string;
  /**
   * Whether this picker also offers create/edit/delete, in addition to picking. Off for the
   * reassign-to picker nested inside CategoryDeleteSheet -- managing categories WHILE mid-delete
   * is confusing to reason about and isn't something web's own equivalent reassignment picker
   * (CategoryDeleteDialog's CategoryCombobox) offers either.
   * @default true
   */
  allowManage?: boolean;
  /**
   * Fires when the category deleted from within this picker (via its own trash icon) is the one
   * the caller currently has selected (matched by name). Without this, a caller keeps holding a
   * categoryName that no longer exists as its own row -- CategoryOption doesn't carry a
   * tombstone, so the caller has no other way to notice. For a transaction's read-model this is
   * more than cosmetic: CategoryService.delete's own reassignment already ran server-side by the
   * time this fires (usage() counts the transaction being edited too, so hasDependents -- and a
   * required reassign target -- is unavoidable in this exact scenario), so the caller's stale name
   * is not just wrong, it would name a category that no longer exists at all.
   */
  onSelectedCategoryDeleted?: () => void;
}

/**
 * Mobile counterpart to frontend/src/components/CategoryCombobox.tsx -- a searchable category
 * list with inline create/edit/delete, not just OptionPickerModal's plain flat list (which stays
 * in place for pickers that don't need any of that, e.g. the timezone picker). Deliberately a
 * simple case-insensitive substring filter, not web's fuzzy similarity-ratio "did you mean"
 * suggestions (lib/similarity.ts) -- a worthwhile follow-up, not required for capability parity:
 * every category is still reachable by typing its name, just without a fuzzy-match assist.
 *
 * Edit/Delete open as their own stacked Modals on top of this one (CategoryEditSheet /
 * CategoryDeleteSheet), rather than swapping this sheet's own content in place the way web's
 * CategoryCombobox does -- simpler, and matches the nested-Modal pattern already established by
 * DeactivateAccountSheet's own OptionPickerModal.
 */
export function CategoryPickerModal({
  visible, selectedName, onSelect, onClose, excludeCategoryId, allowManage = true,
  onSelectedCategoryDeleted,
}: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const queryClient = useQueryClient();
  const categoriesQ = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });
  // Memoized, not just `?? []` inline: that fallback array is a fresh reference every render,
  // which would make both useMemo hooks below (keyed on `categories`) recompute on every render
  // regardless of whether the actual data changed.
  const categories = useMemo(() => categoriesQ.data ?? [], [categoriesQ.data]);

  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<{ mode: 'create'; name: string } | { mode: 'edit'; category: CategoryOption } | null>(null);
  const [deleting, setDeleting] = useState<CategoryOption | null>(null);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return categories
      .filter((cat) => cat.id !== excludeCategoryId)
      .filter((cat) => !q || cat.name.toLowerCase().includes(q));
  }, [categories, excludeCategoryId, query]);

  const exactMatch = useMemo(
    () => categories.some((cat) => cat.name.toLowerCase() === query.trim().toLowerCase()),
    [categories, query]
  );
  const showCreateRow = allowManage && query.trim().length > 0 && !exactMatch;

  function close() {
    setQuery('');
    onClose();
  }

  function handleSelect(category: CategoryOption) {
    hapticSelection();
    onSelect(category);
    close();
  }

  function afterCategoriesChanged() {
    void queryClient.invalidateQueries({ queryKey: ['categories'] });
  }

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={close}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={close}
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        />
        <View
          style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}
          accessibilityViewIsModal
        >
          <View style={styles.header}>
            <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">Category</Text>
            <Pressable onPress={close} hitSlop={12} accessibilityRole="button">
              <Text style={[styles.done, { color: c.primary }]}>Done</Text>
            </Pressable>
          </View>

          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder="Search or create a category…"
            placeholderTextColor={c.muted}
            autoCapitalize="none"
            autoCorrect={false}
            accessibilityLabel="Search categories"
            style={[styles.search, { backgroundColor: c.inputBg, borderColor: c.border, color: c.ink }]}
          />

          {categoriesQ.isError ? (
            <Text style={[styles.notice, { color: c.warning }]}>Couldn&apos;t load categories.</Text>
          ) : null}

          <FlatList
            data={filtered}
            keyExtractor={(item) => item.id}
            style={styles.list}
            ListHeaderComponent={
              showCreateRow ? (
                <Pressable
                  onPress={() => setEditing({ mode: 'create', name: query.trim() })}
                  style={[styles.row, { borderBottomColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel={`Create category "${query.trim()}"`}
                >
                  <View style={[styles.iconBadge, { backgroundColor: c.primaryLight }]}>
                    <Ionicons name="add" size={16} color={c.primary} />
                  </View>
                  <Text style={[styles.rowText, { color: c.primary, flex: 1 }]} numberOfLines={1}>
                    Create &quot;{query.trim()}&quot;
                  </Text>
                </Pressable>
              ) : null
            }
            renderItem={({ item }) => {
              const isSelected = item.name === selectedName;
              return (
                <View style={[styles.row, { borderBottomColor: c.border }]}>
                  <Pressable
                    onPress={() => handleSelect(item)}
                    style={styles.rowMain}
                    accessibilityRole="button"
                    accessibilityState={{ selected: isSelected }}
                    testID={`category-${item.name}`}
                  >
                    <View style={[styles.iconBadge, { backgroundColor: colorHexFor(item.color) }]}>
                      <Ionicons name={iconNameFor(item.icon)} size={16} color="#FFFFFF" />
                    </View>
                    <Text style={[styles.rowText, { color: isSelected ? c.primary : c.ink }]} numberOfLines={1}>
                      {item.name}
                    </Text>
                    {isSelected ? (
                      <Text
                        style={[styles.check, { color: c.primary }]}
                        accessibilityElementsHidden
                        importantForAccessibility="no"
                      >
                        ✓
                      </Text>
                    ) : null}
                  </Pressable>
                  {/* System categories (isSystem) can't be renamed or removed -- CategoryService's
                      own guard on the backend rejects it, so there's nothing useful an edit/delete
                      icon here could do beyond hand the user a 400. */}
                  {allowManage && !item.isSystem ? (
                    <View style={styles.rowActions}>
                      <Pressable
                        onPress={() => setEditing({ mode: 'edit', category: item })}
                        hitSlop={8}
                        accessibilityRole="button"
                        accessibilityLabel={`Edit ${item.name}`}
                        style={styles.rowActionButton}
                      >
                        <Ionicons name="pencil-outline" size={16} color={c.muted} />
                      </Pressable>
                      <Pressable
                        onPress={() => setDeleting(item)}
                        hitSlop={8}
                        accessibilityRole="button"
                        accessibilityLabel={`Delete ${item.name}`}
                        style={styles.rowActionButton}
                      >
                        <Ionicons name="trash-outline" size={16} color={c.danger} />
                      </Pressable>
                    </View>
                  ) : null}
                </View>
              );
            }}
            ListEmptyComponent={
              categoriesQ.isLoading ? null : (
                <Text style={[styles.notice, { color: c.muted }]}>No categories match &quot;{query}&quot;.</Text>
              )
            }
          />
        </View>
      </KeyboardAvoidingView>

      {editing ? (
        <CategoryEditSheet
          mode={editing.mode}
          initialName={editing.mode === 'create' ? editing.name : editing.category.name}
          category={editing.mode === 'edit' ? editing.category : undefined}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            afterCategoriesChanged();
            // Matches web's CategoryCombobox: a brand-new category is what the user was just
            // typing into the search box, so select it. But editing an EXISTING category should
            // only carry through to the caller's own selection when the category just edited is
            // the one already selected -- otherwise renaming an unrelated row (e.g. "Travel" while
            // "Food" is selected) would silently overwrite the caller's selection with "Travel" and
            // close the picker out from under them.
            const shouldSelect = editing.mode === 'create' || editing.category.name === selectedName;
            setEditing(null);
            if (shouldSelect) handleSelect(saved);
          }}
        />
      ) : null}

      {deleting ? (
        <CategoryDeleteSheet
          category={deleting}
          onClose={() => setDeleting(null)}
          onDeleted={() => {
            afterCategoriesChanged();
            const wasSelected = deleting.name === selectedName;
            setDeleting(null);
            if (wasSelected) onSelectedCategoryDeleted?.();
          }}
        />
      ) : null}
    </Modal>
  );
}

const ROW_HEIGHT = 52;

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '80%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  title: { fontSize: 17, fontWeight: '700' },
  done: { fontSize: 15, fontWeight: '600' },
  search: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
    marginBottom: spacing.sm,
  },
  notice: { fontSize: 12, textAlign: 'center', paddingVertical: spacing.md },
  // flexShrink:1 is load-bearing -- see OptionPickerModal's identical note: without it, Yoga lays
  // this FlatList out at full content height and the sheet's maxHeight just clips the tail rather
  // than making it scroll.
  list: { flexGrow: 0, flexShrink: 1 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: ROW_HEIGHT,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingVertical: 8 },
  rowText: { fontSize: 15, flex: 1 },
  iconBadge: {
    width: 28, height: 28, borderRadius: 14, alignItems: 'center', justifyContent: 'center',
  },
  check: { fontSize: 16, fontWeight: '700', marginLeft: spacing.xs },
  rowActions: { flexDirection: 'row', gap: spacing.sm, paddingLeft: spacing.sm },
  rowActionButton: { padding: 4 },
});
